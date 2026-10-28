use chrono::NaiveDate;
use jni::{
    JNIEnv,
    objects::{JObject, JString},
    sys::{JNI_FALSE, JNI_TRUE, jboolean, jstring},
};
use msg2ledger_core::{
    Category, Kind, Transaction,
    analytics::{avg_daily_spend, format_ksh, summarize_day, summary_text},
    parse_message,
};
use rusqlite::{Connection, Row, Transaction as SqlTransaction, params};
use std::{
    path::Path,
    sync::{Mutex, OnceLock},
};

static STORE: OnceLock<Mutex<Option<MessageStore>>> = OnceLock::new();

fn store_slot() -> &'static Mutex<Option<MessageStore>> {
    STORE.get_or_init(|| Mutex::new(None))
}

fn java_string(env: &mut JNIEnv, value: JString) -> Result<String, String> {
    env.get_string(&value)
        .map(Into::into)
        .map_err(|error| error.to_string())
}

fn return_java_string(env: &mut JNIEnv, value: &str) -> jstring {
    match env.new_string(value) {
        Ok(value) => value.into_raw(),
        Err(error) => {
            throw_java_error(env, error);
            std::ptr::null_mut()
        }
    }
}

fn throw_java_error(env: &mut JNIEnv, error: impl std::fmt::Display) {
    if let Err(throw_error) = env.throw_new("java/lang/IllegalStateException", error.to_string()) {
        eprintln!("could not raise JNI exception: {throw_error}");
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_harmaton_msg2ledger_MainActivity_nativeOpen(
    mut env: JNIEnv,
    _this: JObject,
    path: JString,
) -> jboolean {
    let result = java_string(&mut env, path).and_then(|path| {
        let store = MessageStore::open(path).map_err(|error| error.to_string())?;
        let mut slot = store_slot()
            .lock()
            .map_err(|_| "database lock was poisoned".to_string())?;
        *slot = Some(store);
        Ok(())
    });

    match result {
        Ok(()) => JNI_TRUE,
        Err(error) => {
            throw_java_error(&mut env, error);
            JNI_FALSE
        }
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_harmaton_msg2ledger_MainActivity_nativeIngestMessage(
    mut env: JNIEnv,
    _this: JObject,
    sender: JString,
    raw: JString,
) -> jstring {
    let result = (|| {
        let sender = java_string(&mut env, sender)?;
        let raw = java_string(&mut env, raw)?;
        let mut slot = store_slot()
            .lock()
            .map_err(|_| "database lock was poisoned".to_string())?;
        let store = slot
            .as_mut()
            .ok_or_else(|| "database is not open; call open first".to_string())?;
        let result = store
            .ingest_message(&sender, &raw)
            .map_err(|error| error.to_string())?;
        Ok::<String, String>(match result {
            IngestResult::IgnoredNonMpesa => "ignored_non_mpesa".to_string(),
            IngestResult::Stored(transaction) => {
                format!("stored:{}", transaction.code)
            }
            IngestResult::StoredUnparsed(error) => format!("saved_for_review:{error}"),
            IngestResult::DuplicateMessage => "duplicate_message".to_string(),
            IngestResult::DuplicateTransaction(code) => {
                format!("duplicate_transaction:{code}")
            }
        })
    })();

    match result {
        Ok(value) => return_java_string(&mut env, &value),
        Err(error) => {
            throw_java_error(&mut env, error);
            std::ptr::null_mut()
        }
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_harmaton_msg2ledger_MainActivity_nativeDailySummary(
    mut env: JNIEnv,
    _this: JObject,
    date: JString,
) -> jstring {
    let result = (|| {
        let date = java_string(&mut env, date)?;
        let day = NaiveDate::parse_from_str(&date, "%d/%m/%y")
            .map_err(|error| format!("invalid date {date:?}; expected DD/MM/YY: {error}"))?;
        let slot = store_slot()
            .lock()
            .map_err(|_| "database lock was poisoned".to_string())?;
        let store = slot
            .as_ref()
            .ok_or_else(|| "database is not open; call open first".to_string())?;
        let transactions = store.transactions().map_err(|error| error.to_string())?;
        let summary = summarize_day(&transactions, day);
        let average = avg_daily_spend(&transactions, day, 7);
        Ok::<String, String>(format!(
            "{} Received: {}.",
            summary_text(&summary, average),
            format_ksh(summary.received_cents)
        ))
    })();

    match result {
        Ok(value) => return_java_string(&mut env, &value),
        Err(error) => {
            throw_java_error(&mut env, error);
            std::ptr::null_mut()
        }
    }
}

#[derive(Debug, PartialEq, Eq)]
pub enum IngestResult {
    IgnoredNonMpesa,
    Stored(Transaction),
    StoredUnparsed(String),
    DuplicateMessage,
    DuplicateTransaction(String),
}

pub struct MessageStore {
    connection: Connection,
}

impl MessageStore {
    pub fn open(path: impl AsRef<Path>) -> rusqlite::Result<Self> {
        let connection = Connection::open(path)?;
        Self::from_connection(connection)
    }

    pub fn in_memory() -> rusqlite::Result<Self> {
        Self::from_connection(Connection::open_in_memory()?)
    }

    fn from_connection(connection: Connection) -> rusqlite::Result<Self> {
        connection.pragma_update(None, "foreign_keys", "ON")?;
        connection.execute_batch(
            "
            CREATE TABLE IF NOT EXISTS messages (
                id INTEGER PRIMARY KEY,
                sender TEXT NOT NULL,
                raw TEXT NOT NULL,
                received_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                parse_error TEXT,
                UNIQUE(sender, raw)
            );
            CREATE TABLE IF NOT EXISTS transactions (
                code TEXT PRIMARY KEY,
                message_id INTEGER NOT NULL REFERENCES messages(id),
                kind TEXT NOT NULL CHECK(kind IN ('sent', 'received')),
                category TEXT NOT NULL,
                amount_cents INTEGER NOT NULL CHECK(amount_cents >= 0),
                counterparty TEXT NOT NULL,
                date TEXT NOT NULL,
                time TEXT NOT NULL,
                balance_cents INTEGER
            );
            CREATE INDEX IF NOT EXISTS transactions_date_idx
                ON transactions(date, time);
            CREATE INDEX IF NOT EXISTS transactions_counterparty_idx
                ON transactions(counterparty);
            ",
        )?;
        Ok(Self { connection })
    }

    /// Store M-PESA messages, retaining unparsed text for later parser improvements.
    pub fn ingest_message(&mut self, sender: &str, raw: &str) -> rusqlite::Result<IngestResult> {
        if !sender.trim().eq_ignore_ascii_case("MPESA") {
            return Ok(IngestResult::IgnoredNonMpesa);
        }

        let parsed = parse_message(raw);
        let error_message = parsed.as_ref().err().map(ToString::to_string);
        let transaction = self.connection.transaction()?;

        let inserted = transaction.execute(
            "INSERT INTO messages (sender, raw, parse_error) VALUES (?1, ?2, ?3)
             ON CONFLICT(sender, raw) DO NOTHING",
            params!["MPESA", raw, error_message],
        )?;
        if inserted == 0 {
            transaction.commit()?;
            return Ok(IngestResult::DuplicateMessage);
        }
        let message_id = transaction.last_insert_rowid();

        let result = match parsed {
            Ok(parsed) => {
                let inserted = insert_transaction(&transaction, message_id, &parsed)?;
                if inserted {
                    IngestResult::Stored(parsed)
                } else {
                    IngestResult::DuplicateTransaction(parsed.code)
                }
            }
            Err(error) => IngestResult::StoredUnparsed(error.to_string()),
        };
        transaction.commit()?;
        Ok(result)
    }

    pub fn transactions(&self) -> rusqlite::Result<Vec<Transaction>> {
        let mut statement = self.connection.prepare(
            "SELECT code, kind, category, amount_cents, counterparty, date, time, balance_cents
             FROM transactions ORDER BY date, time, code",
        )?;
        let rows = statement.query_map([], row_to_transaction)?;
        rows.collect()
    }

    pub fn message_count(&self) -> rusqlite::Result<usize> {
        self.connection
            .query_row("SELECT COUNT(*) FROM messages", [], |row| row.get(0))
    }

    pub fn unparsed_messages(&self) -> rusqlite::Result<Vec<(String, String)>> {
        let mut statement = self.connection.prepare(
            "SELECT raw, parse_error FROM messages WHERE parse_error IS NOT NULL ORDER BY id",
        )?;
        let rows = statement.query_map([], |row| Ok((row.get(0)?, row.get(1)?)))?;
        rows.collect()
    }
}

fn insert_transaction(
    transaction: &SqlTransaction<'_>,
    message_id: i64,
    parsed: &Transaction,
) -> rusqlite::Result<bool> {
    let inserted = transaction.execute(
        "INSERT INTO transactions
            (code, message_id, kind, category, amount_cents, counterparty, date, time, balance_cents)
         VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9)
         ON CONFLICT(code) DO NOTHING",
        params![
            parsed.code,
            message_id,
            kind_name(&parsed.kind),
            category_name(parsed.category),
            parsed.amount_cents,
            parsed.counterparty,
            parsed.date,
            parsed.time,
            parsed.balance_cents,
        ],
    )?;
    Ok(inserted == 1)
}

fn row_to_transaction(row: &Row<'_>) -> rusqlite::Result<Transaction> {
    Ok(Transaction {
        code: row.get(0)?,
        kind: parse_db_value(row.get(1)?, 1, |value| match value.as_str() {
            "sent" => Some(Kind::Sent),
            "received" => Some(Kind::Received),
            _ => None,
        })?,
        category: parse_db_value(row.get(2)?, 2, |value| parse_category(value))?,
        amount_cents: row.get(3)?,
        counterparty: row.get(4)?,
        date: row.get(5)?,
        time: row.get(6)?,
        balance_cents: row.get(7)?,
    })
}

fn parse_db_value<T>(
    value: String,
    column: usize,
    parse: impl FnOnce(&String) -> Option<T>,
) -> rusqlite::Result<T> {
    parse(&value).ok_or_else(|| {
        rusqlite::Error::FromSqlConversionFailure(
            column,
            rusqlite::types::Type::Text,
            Box::new(std::io::Error::new(
                std::io::ErrorKind::InvalidData,
                format!("unknown stored transaction value: {value}"),
            )),
        )
    })
}

fn kind_name(kind: &Kind) -> &'static str {
    match kind {
        Kind::Sent => "sent",
        Kind::Received => "received",
    }
}

fn category_name(category: Category) -> &'static str {
    match category {
        Category::Send => "Send",
        Category::Receive => "Receive",
        Category::PayBill => "PayBill",
        Category::BuyGoods => "BuyGoods",
        Category::Pochi => "Pochi",
        Category::Airtime => "Airtime",
        Category::Withdrawal => "Withdrawal",
    }
}

fn parse_category(value: &str) -> Option<Category> {
    match value {
        "Send" => Some(Category::Send),
        "Receive" => Some(Category::Receive),
        "PayBill" => Some(Category::PayBill),
        "BuyGoods" => Some(Category::BuyGoods),
        "Pochi" => Some(Category::Pochi),
        "Airtime" => Some(Category::Airtime),
        "Withdrawal" => Some(Category::Withdrawal),
        _ => None,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const SENT: &str = "QGH7XYZ123 Confirmed. Ksh500.00 sent to JOHN DOE 0712345678 on 12/3/26 at 2:15 PM. New M-PESA balance is Ksh1,250.50.";

    #[test]
    fn stores_transaction_and_raw_message_once() {
        let mut store = MessageStore::in_memory().unwrap();
        let result = store.ingest_message("MPESA", SENT).unwrap();
        assert!(matches!(result, IngestResult::Stored(_)));
        assert_eq!(
            store.ingest_message("MPESA", SENT).unwrap(),
            IngestResult::DuplicateMessage
        );
        assert_eq!(store.message_count().unwrap(), 1);
        assert_eq!(store.transactions().unwrap().len(), 1);
    }

    #[test]
    fn deduplicates_transactions_even_if_sms_text_differs() {
        let mut store = MessageStore::in_memory().unwrap();
        store.ingest_message("MPESA", SENT).unwrap();
        let second_copy = SENT.replace("New M-PESA balance is Ksh1,250.50.", "");
        assert_eq!(
            store.ingest_message("MPESA", &second_copy).unwrap(),
            IngestResult::DuplicateTransaction("QGH7XYZ123".to_string())
        );
        assert_eq!(store.message_count().unwrap(), 2);
        assert_eq!(store.transactions().unwrap().len(), 1);
    }

    #[test]
    fn retains_unparsed_mpesa_and_ignores_other_senders() {
        let mut store = MessageStore::in_memory().unwrap();
        assert_eq!(
            store.ingest_message("BANK", "private SMS").unwrap(),
            IngestResult::IgnoredNonMpesa
        );
        assert!(matches!(
            store
                .ingest_message("MPESA", "new M-PESA message format")
                .unwrap(),
            IngestResult::StoredUnparsed(_)
        ));
        assert_eq!(store.message_count().unwrap(), 1);
        assert_eq!(store.unparsed_messages().unwrap().len(), 1);
    }
}
