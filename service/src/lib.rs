use apalis::prelude::*;
use apalis_sqlite::{
    Config as ApalisConfig, SqliteConnectOptions, SqlitePool, SqlitePoolOptions, SqliteStorage,
};
use chrono::{Datelike, NaiveDate};
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
use rivium::{Kind as ServiceKind, Result as RiviumResult, service};
use rivium_host::{App, Context};
use rusqlite::{Connection, Row, Transaction as SqlTransaction, params};
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use std::{
    path::{Path, PathBuf},
    sync::{Mutex, OnceLock},
    time::Duration,
};

static STORE: OnceLock<Mutex<Option<MessageStore>>> = OnceLock::new();
static DATABASE_PATH: OnceLock<Mutex<Option<PathBuf>>> = OnceLock::new();

fn store_slot() -> &'static Mutex<Option<MessageStore>> {
    STORE.get_or_init(|| Mutex::new(None))
}

fn database_path_slot() -> &'static Mutex<Option<PathBuf>> {
    DATABASE_PATH.get_or_init(|| Mutex::new(None))
}

#[derive(Debug, Deserialize, Serialize)]
pub struct ServiceConfig {
    #[serde(default = "default_database_name")]
    database_name: String,
}

impl Default for ServiceConfig {
    fn default() -> Self {
        Self {
            database_name: default_database_name(),
        }
    }
}

fn default_database_name() -> String {
    "ledger.db".to_string()
}

#[derive(Clone, Debug, Deserialize, Serialize)]
struct IncomingMessage {
    sender: String,
    raw: String,
    database_path: String,
}

#[derive(Clone, Debug, Deserialize)]
pub struct StatementRow {
    pub date: String,
    pub time: String,
    pub kind: String,
    pub amount_cents: i64,
    pub description: String,
    pub reference: String,
}

pub struct LedgerApplication;

impl App for LedgerApplication {
    const NAME: &'static str = "msg2ledger";
    const VERSION: &'static str = env!("CARGO_PKG_VERSION");
    type Config = ServiceConfig;

    fn services(
        config: &Self::Config,
        context: &Context<Self::Config>,
    ) -> RiviumResult<Vec<Box<dyn rivium::Service>>> {
        let database_path = context.paths().root().join(&config.database_name);
        Ok(vec![service(
            "mpesa-message-worker",
            ServiceKind::Frontline,
            move |ctx| async move {
                let pool = sqlite_pool(&database_path)
                    .await
                    .map_err(|error| rivium::Error::internal(error.to_string()))?;
                SqliteStorage::<()>::setup(&pool)
                    .await
                    .map_err(|error| rivium::Error::internal(error.to_string()))?;
                let backend = SqliteStorage::<IncomingMessage>::new(&pool).with_config(
                    ApalisConfig::default().heartbeat_interval(Duration::from_secs(5)),
                );
                let worker = WorkerBuilder::new("mpesa-message-worker")
                    .backend(backend)
                    .retry(RetryPolicy::retries(3))
                    .build(process_incoming_message);
                ctx.ready();
                tokio::select! {
                    _ = ctx.stopped() => Ok(()),
                    result = worker.run() => match result {
                        Ok(()) => Ok(()),
                        Err(error) => Err(rivium::Error::internal(error.to_string())),
                    },
                }
            },
        )])
    }
}

rivium_jni::export!(
    class = "com/harmaton/msg2ledger/NativeHost",
    app = LedgerApplication
);

async fn sqlite_pool(path: &Path) -> Result<SqlitePool, apalis_sqlite::SqlxError> {
    SqlitePoolOptions::new()
        .max_connections(1)
        .connect_with(
            SqliteConnectOptions::new()
                .filename(path)
                .create_if_missing(true),
        )
        .await
}

async fn process_incoming_message(
    message: IncomingMessage,
    _: WorkerContext,
) -> Result<(), BoxDynError> {
    let result = tokio::task::spawn_blocking(move || {
        let mut store = MessageStore::open(message.database_path)?;
        store
            .ingest_message(&message.sender, &message.raw)
            .map(|_| ())
    })
    .await
    .map_err(|error| -> BoxDynError { Box::new(error) })?;
    result.map_err(|error| -> BoxDynError { Box::new(error) })?;
    Ok(())
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
pub extern "system" fn Java_com_harmaton_msg2ledger_NativeBridge_nativeOpen(
    mut env: JNIEnv,
    _class: JObject,
    path: JString,
) -> jboolean {
    let result = java_string(&mut env, path).and_then(|path| {
        let path = PathBuf::from(path);
        let store = MessageStore::open(&path).map_err(|error| error.to_string())?;
        let mut slot = store_slot()
            .lock()
            .map_err(|_| "database lock was poisoned".to_string())?;
        *slot = Some(store);
        *database_path_slot()
            .lock()
            .map_err(|_| "database path lock was poisoned".to_string())? = Some(path);
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
pub extern "system" fn Java_com_harmaton_msg2ledger_NativeBridge_nativeIngestMessage(
    mut env: JNIEnv,
    _class: JObject,
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
pub extern "system" fn Java_com_harmaton_msg2ledger_NativeBridge_nativeDailySummary(
    mut env: JNIEnv,
    _class: JObject,
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

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_harmaton_msg2ledger_NativeBridge_nativeQueueMessage(
    mut env: JNIEnv,
    _class: JObject,
    sender: JString,
    raw: JString,
) -> jboolean {
    let result = (|| {
        let sender = java_string(&mut env, sender)?;
        let raw = java_string(&mut env, raw)?;
        if !sender.trim().eq_ignore_ascii_case("MPESA") {
            return Ok::<bool, String>(false);
        }
        let path = database_path_slot()
            .lock()
            .map_err(|_| "database path lock was poisoned".to_string())?
            .clone()
            .ok_or_else(|| "database is not open; call open first".to_string())?;
        let runtime = tokio::runtime::Builder::new_multi_thread()
            .enable_all()
            .build()
            .map_err(|error| error.to_string())?;
        runtime
            .block_on(async {
                let pool = sqlite_pool(&path)
                    .await
                    .map_err(|error| error.to_string())?;
                SqliteStorage::<()>::setup(&pool)
                    .await
                    .map_err(|error| error.to_string())?;
                let mut queue = SqliteStorage::<IncomingMessage>::new(&pool);
                queue
                    .push(IncomingMessage {
                        sender,
                        raw,
                        database_path: path.to_string_lossy().into_owned(),
                    })
                    .await
                    .map_err(|error| error.to_string())
            })
            .map(|()| true)
    })();

    match result {
        Ok(true) => JNI_TRUE,
        Ok(false) => JNI_FALSE,
        Err(error) => {
            throw_java_error(&mut env, error);
            JNI_FALSE
        }
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_harmaton_msg2ledger_NativeBridge_nativeHasMessage(
    mut env: JNIEnv,
    _class: JObject,
    sender: JString,
    raw: JString,
) -> jboolean {
    let result = (|| {
        let sender = java_string(&mut env, sender)?;
        let raw = java_string(&mut env, raw)?;
        let slot = store_slot()
            .lock()
            .map_err(|_| "database lock was poisoned".to_string())?;
        let store = slot
            .as_ref()
            .ok_or_else(|| "database is not open; call open first".to_string())?;
        store
            .has_message(&sender, &raw)
            .map_err(|error| error.to_string())
    })();

    match result {
        Ok(true) => JNI_TRUE,
        Ok(false) => JNI_FALSE,
        Err(error) => {
            throw_java_error(&mut env, error);
            JNI_FALSE
        }
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_harmaton_msg2ledger_NativeBridge_nativeDashboard(
    mut env: JNIEnv,
    _class: JObject,
    date: JString,
) -> jstring {
    let result = (|| {
        let date = java_string(&mut env, date)?;
        let day = NaiveDate::parse_from_str(&date, "%Y-%m-%d")
            .map_err(|error| format!("invalid date {date:?}; expected YYYY-MM-DD: {error}"))?;
        let slot = store_slot()
            .lock()
            .map_err(|_| "database lock was poisoned".to_string())?;
        let store = slot
            .as_ref()
            .ok_or_else(|| "database is not open; call open first".to_string())?;
        store
            .dashboard(day)
            .map(|value| value.to_string())
            .map_err(|error| error.to_string())
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
pub extern "system" fn Java_com_harmaton_msg2ledger_NativeBridge_nativeCategoryTransactions(
    mut env: JNIEnv,
    _class: JObject,
    date: JString,
    category: JString,
    offset: jni::sys::jint,
    limit: jni::sys::jint,
) -> jstring {
    let result = (|| {
        let date = java_string(&mut env, date)?;
        let day = NaiveDate::parse_from_str(&date, "%Y-%m-%d")
            .map_err(|error| format!("invalid date {date:?}; expected YYYY-MM-DD: {error}"))?;
        let category = java_string(&mut env, category)?;
        let offset = usize::try_from(offset)
            .map_err(|_| "transaction offset must not be negative".to_string())?;
        let limit = usize::try_from(limit)
            .map_err(|_| "transaction page size must not be negative".to_string())?;
        if limit == 0 || limit > 100 {
            return Err("transaction page size must be between 1 and 100".to_string());
        }
        let slot = store_slot()
            .lock()
            .map_err(|_| "database lock was poisoned".to_string())?;
        let store = slot
            .as_ref()
            .ok_or_else(|| "database is not open; call open first".to_string())?;
        store
            .category_transactions(
                day,
                (category != "All").then_some(category.as_str()),
                offset,
                limit,
            )
            .map(|value| value.to_string())
            .map_err(|error| error.to_string())
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
pub extern "system" fn Java_com_harmaton_msg2ledger_NativeBridge_nativeCounterpartyDetail(
    mut env: JNIEnv,
    _class: JObject,
    counterparty: JString,
) -> jstring {
    let result = (|| {
        let counterparty = java_string(&mut env, counterparty)?;
        let slot = store_slot()
            .lock()
            .map_err(|_| "database lock was poisoned".to_string())?;
        let store = slot
            .as_ref()
            .ok_or_else(|| "database is not open; call open first".to_string())?;
        store
            .counterparty_detail(&counterparty)
            .map(|value| value.to_string())
            .map_err(|error| error.to_string())
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
pub extern "system" fn Java_com_harmaton_msg2ledger_NativeBridge_nativeAttachTransactionToPaybill(
    mut env: JNIEnv,
    _class: JObject,
    code: JString,
    paybill: JString,
) -> jboolean {
    let result = (|| {
        let code = java_string(&mut env, code)?;
        let paybill = java_string(&mut env, paybill)?;
        let slot = store_slot()
            .lock()
            .map_err(|_| "database lock was poisoned".to_string())?;
        let store = slot
            .as_ref()
            .ok_or_else(|| "database is not open; call open first".to_string())?;
        store
            .attach_transaction_to_paybill(&code, &paybill)
            .map_err(|error| error.to_string())
    })();

    match result {
        Ok(()) => JNI_TRUE,
        Err(error) => {
            throw_java_error(&mut env, error);
            JNI_FALSE
        }
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_harmaton_msg2ledger_NativeBridge_nativeTrackedPaybills(
    mut env: JNIEnv,
    _class: JObject,
) -> jstring {
    let result = (|| {
        let slot = store_slot()
            .lock()
            .map_err(|_| "database lock was poisoned".to_string())?;
        let store = slot
            .as_ref()
            .ok_or_else(|| "database is not open; call open first".to_string())?;
        store
            .tracked_paybills()
            .map(|value| value.to_string())
            .map_err(|error| error.to_string())
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
pub extern "system" fn Java_com_harmaton_msg2ledger_NativeBridge_nativeImportStatement(
    mut env: JNIEnv,
    _class: JObject,
    institution: JString,
    rows_json: JString,
) -> jstring {
    let result = (|| {
        let institution = java_string(&mut env, institution)?;
        let rows_json = java_string(&mut env, rows_json)?;
        let rows: Vec<StatementRow> = serde_json::from_str(&rows_json)
            .map_err(|error| format!("invalid statement data: {error}"))?;
        let slot = store_slot()
            .lock()
            .map_err(|_| "database lock was poisoned".to_string())?;
        let store = slot
            .as_ref()
            .ok_or_else(|| "database is not open; call open first".to_string())?;
        store
            .import_statement(&institution, rows)
            .map(|count| json!({"imported_count": count}).to_string())
            .map_err(|error| error.to_string())
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
pub extern "system" fn Java_com_harmaton_msg2ledger_NativeBridge_nativeInstitutionReport(
    mut env: JNIEnv,
    _class: JObject,
) -> jstring {
    let result = (|| {
        let slot = store_slot()
            .lock()
            .map_err(|_| "database lock was poisoned".to_string())?;
        let store = slot
            .as_ref()
            .ok_or_else(|| "database is not open; call open first".to_string())?;
        store
            .institution_report()
            .map(|value| value.to_string())
            .map_err(|error| error.to_string())
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
pub extern "system" fn Java_com_harmaton_msg2ledger_NativeBridge_nativeSetCounterpartySettings(
    mut env: JNIEnv,
    _class: JObject,
    counterparty: JString,
    category: JString,
    limit_cents: jni::sys::jlong,
) -> jboolean {
    let result = (|| {
        let counterparty = java_string(&mut env, counterparty)?;
        let category = java_string(&mut env, category)?;
        let slot = store_slot()
            .lock()
            .map_err(|_| "database lock was poisoned".to_string())?;
        let store = slot
            .as_ref()
            .ok_or_else(|| "database is not open; call open first".to_string())?;
        store
            .set_counterparty_settings(&counterparty, &category, limit_cents)
            .map_err(|error| error.to_string())
    })();

    match result {
        Ok(()) => JNI_TRUE,
        Err(error) => {
            throw_java_error(&mut env, error);
            JNI_FALSE
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
        connection.busy_timeout(Duration::from_secs(5))?;
        connection.pragma_update(None, "foreign_keys", "ON")?;
        connection.execute_batch(
            "
            PRAGMA journal_mode = WAL;
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
            CREATE TABLE IF NOT EXISTS counterparty_settings (
                counterparty TEXT PRIMARY KEY,
                category TEXT NOT NULL,
                limit_cents INTEGER CHECK(limit_cents IS NULL OR limit_cents > 0)
            );
            CREATE TABLE IF NOT EXISTS tracked_paybills (
                id INTEGER PRIMARY KEY,
                name TEXT NOT NULL UNIQUE COLLATE NOCASE
            );
            CREATE TABLE IF NOT EXISTS transaction_paybills (
                transaction_code TEXT PRIMARY KEY REFERENCES transactions(code) ON DELETE CASCADE,
                tracked_paybill_id INTEGER NOT NULL REFERENCES tracked_paybills(id) ON DELETE CASCADE
            );
            CREATE TABLE IF NOT EXISTS institution_transactions (
                id INTEGER PRIMARY KEY,
                institution TEXT NOT NULL,
                dedupe_key TEXT NOT NULL,
                external_reference TEXT,
                kind TEXT NOT NULL CHECK(kind IN ('sent', 'received')),
                amount_cents INTEGER NOT NULL CHECK(amount_cents >= 0),
                description TEXT NOT NULL,
                date TEXT NOT NULL,
                time TEXT NOT NULL,
                UNIQUE(institution, dedupe_key)
            );
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
        let mut transactions: Vec<Transaction> = rows.collect::<rusqlite::Result<_>>()?;
        transactions.sort_by(|left, right| {
            left.occurred_at()
                .cmp(&right.occurred_at())
                .then_with(|| left.code.cmp(&right.code))
        });
        Ok(transactions)
    }

    pub fn message_count(&self) -> rusqlite::Result<usize> {
        self.connection
            .query_row("SELECT COUNT(*) FROM messages", [], |row| row.get(0))
    }

    pub fn has_message(&self, sender: &str, raw: &str) -> rusqlite::Result<bool> {
        self.connection.query_row(
            "SELECT EXISTS(SELECT 1 FROM messages WHERE sender = ?1 AND raw = ?2)",
            params![sender.trim().to_ascii_uppercase(), raw],
            |row| row.get(0),
        )
    }

    pub fn unparsed_messages(&self) -> rusqlite::Result<Vec<(String, String)>> {
        let mut statement = self.connection.prepare(
            "SELECT raw, parse_error FROM messages WHERE parse_error IS NOT NULL ORDER BY id",
        )?;
        let rows = statement.query_map([], |row| Ok((row.get(0)?, row.get(1)?)))?;
        rows.collect()
    }

    pub fn set_counterparty_settings(
        &self,
        counterparty: &str,
        category: &str,
        limit_cents: i64,
    ) -> rusqlite::Result<()> {
        const CATEGORIES: &[&str] = &[
            "Shopping",
            "Food",
            "Transport",
            "Bills",
            "Airtime",
            "Miscellaneous",
            "Gifts",
            "Other",
        ];
        if counterparty.trim().is_empty() {
            return Err(rusqlite::Error::InvalidParameterName(
                "counterparty must not be empty".to_string(),
            ));
        }
        if !CATEGORIES.contains(&category) {
            return Err(rusqlite::Error::InvalidParameterName(format!(
                "unsupported category: {category}"
            )));
        }
        if limit_cents < 0 {
            return Err(rusqlite::Error::InvalidParameterName(
                "limit must not be negative".to_string(),
            ));
        }
        self.connection.execute(
            "INSERT INTO counterparty_settings(counterparty, category, limit_cents)
             VALUES (?1, ?2, ?3)
             ON CONFLICT(counterparty) DO UPDATE SET
                 category = excluded.category,
                 limit_cents = excluded.limit_cents",
            params![
                counterparty,
                category,
                (limit_cents > 0).then_some(limit_cents)
            ],
        )?;
        Ok(())
    }

    pub fn attach_transaction_to_paybill(&self, code: &str, paybill: &str) -> rusqlite::Result<()> {
        let paybill = paybill.trim();
        if paybill.is_empty() {
            return Err(rusqlite::Error::InvalidParameterName(
                "paybill name must not be empty".to_string(),
            ));
        }
        let is_outgoing: bool = self.connection.query_row(
            "SELECT EXISTS(SELECT 1 FROM transactions WHERE code = ?1 AND kind = 'sent')",
            [code],
            |row| row.get(0),
        )?;
        if !is_outgoing {
            return Err(rusqlite::Error::InvalidParameterName(
                "only outgoing payments can be linked as contributions".to_string(),
            ));
        }
        let transaction = self.connection.unchecked_transaction()?;
        transaction.execute(
            "INSERT INTO tracked_paybills(name) VALUES (?1)
             ON CONFLICT(name) DO NOTHING",
            [paybill],
        )?;
        let paybill_id = transaction.query_row(
            "SELECT id FROM tracked_paybills WHERE name = ?1 COLLATE NOCASE",
            [paybill],
            |row| row.get::<_, i64>(0),
        )?;
        transaction.execute(
            "INSERT INTO transaction_paybills(transaction_code, tracked_paybill_id)
             VALUES (?1, ?2)
             ON CONFLICT(transaction_code) DO UPDATE SET
                 tracked_paybill_id = excluded.tracked_paybill_id",
            params![code, paybill_id],
        )?;
        transaction.commit()
    }

    pub fn tracked_paybills(&self) -> rusqlite::Result<Value> {
        let mut statement = self.connection.prepare(
            "SELECT p.name, t.code, t.kind, t.category, t.amount_cents, t.counterparty, t.date, t.time
             FROM tracked_paybills p
             JOIN transaction_paybills link ON link.tracked_paybill_id = p.id
             JOIN transactions t ON t.code = link.transaction_code
             ORDER BY p.name COLLATE NOCASE, t.date DESC, t.time DESC, t.code DESC",
        )?;
        let rows = statement.query_map([], |row| {
            Ok((
                row.get::<_, String>(0)?,
                json!({
                    "code": row.get::<_, String>(1)?,
                    "kind": row.get::<_, String>(2)?,
                    "category": row.get::<_, String>(3)?,
                    "amount_cents": row.get::<_, i64>(4)?,
                    "counterparty": row.get::<_, String>(5)?,
                    "date": row.get::<_, String>(6)?,
                    "time": row.get::<_, String>(7)?,
                }),
            ))
        })?;
        let mut paybills = std::collections::BTreeMap::<String, Vec<Value>>::new();
        for row in rows {
            let (name, transaction) = row?;
            paybills.entry(name).or_default().push(transaction);
        }
        let entities: Vec<Value> = paybills
            .into_iter()
            .map(|(name, transactions)| {
                let amount_cents = transactions
                    .iter()
                    .filter_map(|transaction| transaction["amount_cents"].as_i64())
                    .sum::<i64>();
                json!({
                    "name": name,
                    "transaction_count": transactions.len(),
                    "amount_cents": amount_cents,
                    "transactions": transactions,
                })
            })
            .collect();
        Ok(json!({ "paybills": entities }))
    }

    pub fn import_statement(
        &self,
        institution: &str,
        rows: Vec<StatementRow>,
    ) -> rusqlite::Result<usize> {
        const INSTITUTIONS: &[&str] = &["NCBA", "I&M Bank", "Co-op Bank", "KCB"];
        if !INSTITUTIONS.contains(&institution) {
            return Err(rusqlite::Error::InvalidParameterName(format!(
                "unsupported institution: {institution}"
            )));
        }
        if rows.len() > 50_000 {
            return Err(rusqlite::Error::InvalidParameterName(
                "statement import is limited to 50,000 rows".to_string(),
            ));
        }
        let transaction = self.connection.unchecked_transaction()?;
        let mut inserted = 0;
        for row in rows {
            if row.date.trim().is_empty()
                || row.kind != "sent" && row.kind != "received"
                || row.amount_cents < 0
                || row.description.trim().is_empty()
            {
                return Err(rusqlite::Error::InvalidParameterName(
                    "statement rows need a date, description, non-negative amount and sent/received direction"
                        .to_string(),
                ));
            }
            let reference = row.reference.trim();
            let dedupe_key = if reference.is_empty() {
                format!(
                    "{}|{}|{}|{}|{}",
                    row.date.trim(),
                    row.time.trim(),
                    row.kind,
                    row.amount_cents,
                    row.description.trim().to_ascii_lowercase(),
                )
            } else {
                format!("ref:{}", reference.to_ascii_uppercase())
            };
            inserted += transaction.execute(
                "INSERT INTO institution_transactions
                    (institution, dedupe_key, external_reference, kind, amount_cents, description, date, time)
                 VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8)
                 ON CONFLICT(institution, dedupe_key) DO NOTHING",
                params![
                    institution,
                    dedupe_key,
                    (!reference.is_empty()).then_some(reference),
                    row.kind,
                    row.amount_cents,
                    row.description.trim(),
                    row.date.trim(),
                    row.time.trim(),
                ],
            )?;
        }
        transaction.commit()?;
        Ok(inserted)
    }

    pub fn institution_report(&self) -> rusqlite::Result<Value> {
        let mut totals = std::collections::BTreeMap::<String, (i64, i64, Vec<Value>)>::new();
        for transaction in self.transactions()? {
            let entry = totals.entry("M-PESA".to_string()).or_default();
            match transaction.kind {
                Kind::Sent => entry.0 += transaction.amount_cents,
                Kind::Received => entry.1 += transaction.amount_cents,
            }
            entry.2.push(json!({
                "code": transaction.code,
                "kind": kind_name(&transaction.kind),
                "category": category_name(transaction.category),
                "amount_cents": transaction.amount_cents,
                "counterparty": transaction.counterparty,
                "date": transaction.date,
                "time": transaction.time,
            }));
        }

        let mut statement = self.connection.prepare(
            "SELECT institution, dedupe_key, kind, amount_cents, description, date, time
             FROM institution_transactions
             ORDER BY institution COLLATE NOCASE, date DESC, time DESC, id DESC",
        )?;
        let rows = statement.query_map([], |row| {
            Ok((
                row.get::<_, String>(0)?,
                row.get::<_, String>(1)?,
                row.get::<_, String>(2)?,
                row.get::<_, i64>(3)?,
                row.get::<_, String>(4)?,
                row.get::<_, String>(5)?,
                row.get::<_, String>(6)?,
            ))
        })?;
        for row in rows {
            let (institution, code, kind, amount_cents, description, date, time) = row?;
            let entry = totals.entry(institution).or_default();
            if kind == "sent" {
                entry.0 += amount_cents;
            } else {
                entry.1 += amount_cents;
            }
            entry.2.push(json!({
                "code": code,
                "kind": kind,
                "category": "Bank transfer",
                "amount_cents": amount_cents,
                "counterparty": description,
                "date": date,
                "time": time,
            }));
        }

        let mut institutions: Vec<Value> = totals
            .into_iter()
            .map(|(name, (sent_cents, received_cents, transactions))| {
                json!({
                    "name": name,
                    "sent_cents": sent_cents,
                    "received_cents": received_cents,
                    "total_cents": sent_cents + received_cents,
                    "transaction_count": transactions.len(),
                    "transactions": transactions,
                })
            })
            .collect();
        institutions.sort_by(|left, right| {
            right["total_cents"]
                .as_i64()
                .cmp(&left["total_cents"].as_i64())
                .then_with(|| left["name"].as_str().cmp(&right["name"].as_str()))
        });
        Ok(json!({ "institutions": institutions }))
    }

    pub fn dashboard(&self, day: NaiveDate) -> rusqlite::Result<Value> {
        let transactions = self.transactions()?;
        let summary = summarize_day(&transactions, day);
        let average = avg_daily_spend(&transactions, day, 7);
        let settings = self.counterparty_settings()?;
        let mut categories = std::collections::BTreeMap::<String, (i64, usize, i64)>::new();
        let mut counterparties =
            std::collections::BTreeMap::<String, (i64, usize, String, Option<i64>, i64, i64)>::new(
            );
        let week_start = day - chrono::Duration::days(6);
        let mut weekly_spent_cents = 0i64;
        let mut weekly_received_cents = 0i64;
        let todays_count = transactions
            .iter()
            .filter(|transaction| {
                transaction
                    .occurred_at()
                    .is_some_and(|occurred_at| occurred_at.date() == day)
            })
            .count();

        for transaction in &transactions {
            let occurred_date = transaction
                .occurred_at()
                .map(|occurred_at| occurred_at.date());
            if occurred_date.is_some_and(|date| (week_start..=day).contains(&date)) {
                match transaction.kind {
                    Kind::Sent => weekly_spent_cents += transaction.amount_cents,
                    Kind::Received => weekly_received_cents += transaction.amount_cents,
                }
            }
            let occurred_today = transaction
                .occurred_at()
                .is_some_and(|occurred_at| occurred_at.date() == day);
            if occurred_today {
                let (category, _) = if transaction.kind == Kind::Sent {
                    settings
                        .get(&transaction.counterparty)
                        .cloned()
                        .unwrap_or_else(|| (category_name(transaction.category).to_string(), None))
                } else {
                    (category_name(transaction.category).to_string(), None)
                };
                let category_total = categories.entry(category).or_default();
                category_total.0 += transaction.amount_cents;
                category_total.1 += 1;
                category_total.2 = category_total.2.max(transaction.amount_cents);
            }
            if transaction.kind != Kind::Sent {
                continue;
            }
            let (category, limit) = settings
                .get(&transaction.counterparty)
                .cloned()
                .unwrap_or_else(|| (category_name(transaction.category).to_string(), None));
            let party = counterparties
                .entry(transaction.counterparty.clone())
                .or_insert_with(|| (0, 0, category, limit, 0, 0));
            party.0 += transaction.amount_cents;
            party.1 += 1;
            if occurred_today {
                party.4 += transaction.amount_cents;
            }
            if occurred_date.is_some_and(|date| (week_start..=day).contains(&date)) {
                party.5 += transaction.amount_cents;
            }
        }

        let mut categories: Vec<Value> = categories
            .into_iter()
            .map(|(name, (spent_cents, count, largest_spend_cents))| {
                json!({
                    "name": name,
                    "spent_cents": spent_cents,
                    "count": count,
                    "largest_spend_cents": largest_spend_cents,
                })
            })
            .collect();
        categories.sort_by(|left, right| {
            right["spent_cents"]
                .as_i64()
                .cmp(&left["spent_cents"].as_i64())
                .then_with(|| left["name"].as_str().cmp(&right["name"].as_str()))
        });

        let mut counterparties: Vec<Value> = counterparties
            .into_iter()
            .map(
                |(
                    name,
                    (
                        spent_cents,
                        count,
                        category,
                        limit_cents,
                        today_spent_cents,
                        weekly_spent_cents,
                    ),
                )| {
                    json!({
                        "name": name,
                        "spent_cents": spent_cents,
                        "count": count,
                        "category": category,
                        "limit_cents": limit_cents,
                        "today_spent_cents": today_spent_cents,
                        "weekly_spent_cents": weekly_spent_cents,
                    })
                },
            )
            .collect();
        counterparties.sort_by(|left, right| {
            right["spent_cents"]
                .as_i64()
                .cmp(&left["spent_cents"].as_i64())
                .then_with(|| left["name"].as_str().cmp(&right["name"].as_str()))
        });

        let top_category = categories
            .first()
            .and_then(|category| category["name"].as_str());
        let top_counterparty = counterparties
            .first()
            .and_then(|party| party["name"].as_str());
        let mut insights = Vec::new();
        if let Some(category) = top_category {
            insights.push(format!("You spent the most on {category} today."));
        }
        if let Some(counterparty) = top_counterparty {
            insights.push(format!("Your highest-spend regular is {counterparty}."));
        }
        if summary.spent_cents > 0 && average > 0 {
            let percentage = (summary.spent_cents - average) * 100 / average;
            insights.push(if percentage >= 0 {
                format!("Today's spending is {percentage}% above your recent daily average.")
            } else {
                format!(
                    "Today's spending is {}% below your recent daily average.",
                    -percentage
                )
            });
        }

        Ok(json!({
            "date": day.to_string(),
            "spent_cents": summary.spent_cents,
            "received_cents": summary.received_cents,
            "sent_count": summary.sent_count,
            "transaction_count": todays_count,
            "average_daily_spend_cents": average,
            "weekly_spent_cents": weekly_spent_cents,
            "weekly_received_cents": weekly_received_cents,
            "categories": categories,
            "counterparties": counterparties,
            "insights": insights,
        }))
    }

    pub fn category_transactions(
        &self,
        day: NaiveDate,
        category: Option<&str>,
        offset: usize,
        limit: usize,
    ) -> rusqlite::Result<Value> {
        let settings = self.counterparty_settings()?;
        let mut transactions: Vec<_> = self
            .transactions()?
            .into_iter()
            .filter_map(|transaction| {
                let occurred_at = transaction.occurred_at()?;
                if occurred_at.date() != day {
                    return None;
                }
                let transaction_category = if transaction.kind == Kind::Sent {
                    settings.get(&transaction.counterparty).map_or_else(
                        || category_name(transaction.category).to_string(),
                        |(value, _)| value.clone(),
                    )
                } else {
                    category_name(transaction.category).to_string()
                };
                if category.is_some_and(|filter| filter != transaction_category) {
                    return None;
                }
                Some((
                    occurred_at,
                    transaction.code.clone(),
                    json!({
                        "code": transaction.code,
                        "kind": kind_name(&transaction.kind),
                        "category": transaction_category,
                        "amount_cents": transaction.amount_cents,
                        "counterparty": transaction.counterparty,
                        "date": transaction.date,
                        "time": transaction.time,
                    }),
                ))
            })
            .collect();
        transactions.sort_by(|left, right| right.0.cmp(&left.0).then_with(|| right.1.cmp(&left.1)));
        let has_more = transactions.len() > offset.saturating_add(limit);
        let page: Vec<Value> = transactions
            .into_iter()
            .skip(offset)
            .take(limit)
            .map(|(_, _, transaction)| transaction)
            .collect();
        let next_offset = offset.saturating_add(page.len());
        Ok(json!({
            "transactions": page,
            "next_offset": next_offset,
            "has_more": has_more,
        }))
    }

    pub fn counterparty_detail(&self, counterparty: &str) -> rusqlite::Result<Value> {
        let transactions: Vec<Transaction> = self
            .transactions()?
            .into_iter()
            .filter(|transaction| transaction.counterparty == counterparty)
            .collect();
        let settings = self.counterparty_settings()?;
        let custom_category = settings
            .get(counterparty)
            .map(|(category, _)| category.as_str());
        let (category, limit_cents) = settings.get(counterparty).cloned().unwrap_or_else(|| {
            (
                transactions.first().map_or_else(
                    || "Other".to_string(),
                    |t| category_name(t.category).to_string(),
                ),
                None,
            )
        });
        let sent: Vec<&Transaction> = transactions
            .iter()
            .filter(|transaction| transaction.kind == Kind::Sent)
            .collect();
        let total_spent_cents: i64 = sent
            .iter()
            .map(|transaction| transaction.amount_cents)
            .sum();
        let first_day = sent
            .iter()
            .filter_map(|transaction| transaction.occurred_at().map(|value| value.date()))
            .min();
        let last_day = sent
            .iter()
            .filter_map(|transaction| transaction.occurred_at().map(|value| value.date()))
            .max();
        let elapsed_days = match (first_day, last_day) {
            (Some(first), Some(last)) => (last - first).num_days() + 1,
            _ => 1,
        }
        .max(1);
        let elapsed_weeks = (elapsed_days + 6) / 7;
        let weekly_average_cents = total_spent_cents / elapsed_weeks;
        let mut weekdays = [0i64; 7];
        for transaction in &sent {
            if let Some(occurred_at) = transaction.occurred_at() {
                weekdays[occurred_at.weekday().num_days_from_monday() as usize] +=
                    transaction.amount_cents;
            }
        }
        const WEEKDAYS: [&str; 7] = [
            "Monday",
            "Tuesday",
            "Wednesday",
            "Thursday",
            "Friday",
            "Saturday",
            "Sunday",
        ];
        let busiest_day = weekdays
            .iter()
            .enumerate()
            .max_by_key(|(_, amount)| *amount)
            .filter(|(_, amount)| **amount > 0)
            .map(|(index, _)| WEEKDAYS[index]);
        let weekday_spending: Vec<Value> = weekdays
            .iter()
            .enumerate()
            .map(|(index, amount)| {
                json!({
                    "day": WEEKDAYS[index],
                    "spent_cents": amount,
                })
            })
            .collect();
        let history: Vec<Value> = transactions
            .iter()
            .rev()
            .map(|transaction| {
                json!({
                    "code": transaction.code,
                    "kind": kind_name(&transaction.kind),
                    "category": custom_category
                        .unwrap_or_else(|| category_name(transaction.category)),
                    "amount_cents": transaction.amount_cents,
                    "counterparty": transaction.counterparty,
                    "date": transaction.date,
                    "time": transaction.time,
                    "balance_cents": transaction.balance_cents,
                })
            })
            .collect();

        Ok(json!({
            "counterparty": counterparty,
            "category": category,
            "limit_cents": limit_cents,
            "total_spent_cents": total_spent_cents,
            "transaction_count": transactions.len(),
            "weekly_average_cents": weekly_average_cents,
            "busiest_day": busiest_day,
            "weekday_spending": weekday_spending,
            "transactions": history,
        }))
    }

    fn counterparty_settings(
        &self,
    ) -> rusqlite::Result<std::collections::HashMap<String, (String, Option<i64>)>> {
        let mut statement = self
            .connection
            .prepare("SELECT counterparty, category, limit_cents FROM counterparty_settings")?;
        let rows = statement.query_map([], |row| {
            Ok((
                row.get::<_, String>(0)?,
                (row.get::<_, String>(1)?, row.get::<_, Option<i64>>(2)?),
            ))
        })?;
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
    use std::time::{SystemTime, UNIX_EPOCH};

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

    #[test]
    fn dashboard_and_counterparty_detail_are_backed_by_ledger_data() {
        let mut store = MessageStore::in_memory().unwrap();
        store.ingest_message("MPESA", SENT).unwrap();
        store
            .ingest_message(
                "MPESA",
                "QGH7ABC456 Confirmed. You have received Ksh1,000.00 from JANE WANGARI 0722333444 on 12/3/26 at 9:05 AM. New M-PESA balance is Ksh2,250.50.",
            )
            .unwrap();
        let day = NaiveDate::from_ymd_opt(2026, 3, 12).unwrap();
        let report = store.dashboard(day).unwrap();
        assert_eq!(report["spent_cents"], 50_000);
        assert_eq!(report["received_cents"], 100_000);
        assert_eq!(report["transaction_count"], 2);
        assert_eq!(report["weekly_spent_cents"], 50_000);
        assert_eq!(report["counterparties"][0]["name"], "JOHN DOE 0712345678");
        assert_eq!(report["categories"][0]["name"], "Receive");
        assert_eq!(report["categories"][0]["largest_spend_cents"], 100_000);
        assert_eq!(report["categories"][1]["name"], "Send");

        store
            .set_counterparty_settings("JOHN DOE 0712345678", "Shopping", 30_000)
            .unwrap();
        let details = store.counterparty_detail("JOHN DOE 0712345678").unwrap();
        assert_eq!(details["category"], "Shopping");
        assert_eq!(details["limit_cents"], 30_000);
        assert_eq!(details["total_spent_cents"], 50_000);
        assert_eq!(details["weekly_average_cents"], 50_000);
        assert_eq!(details["busiest_day"], "Thursday");
        assert_eq!(details["transactions"].as_array().unwrap().len(), 1);
        assert_eq!(details["transactions"][0]["category"], "Shopping");
    }

    #[test]
    fn category_transactions_are_filtered_and_paginated() {
        let mut store = MessageStore::in_memory().unwrap();
        store.ingest_message("MPESA", SENT).unwrap();
        store
            .ingest_message(
                "MPESA",
                "QGH7PAY456 Confirmed. Ksh250.00 sent to MARKET paybill for account 42 on 12/3/26 at 3:15 PM.",
            )
            .unwrap();
        store
            .ingest_message(
                "MPESA",
                "QGH7SML456 Confirmed. Ksh100.00 sent to SMALL SHOP on 12/3/26 at 1:15 PM.",
            )
            .unwrap();
        store
            .ingest_message(
                "MPESA",
                "QGH7INC456 Confirmed. You have received Ksh80.00 from ALICE on 12/3/26 at 12:15 PM.",
            )
            .unwrap();
        let day = NaiveDate::from_ymd_opt(2026, 3, 12).unwrap();
        let dashboard = store.dashboard(day).unwrap();
        assert_eq!(dashboard["categories"][0]["name"], "Send");
        assert_eq!(dashboard["categories"][0]["largest_spend_cents"], 50_000);

        let first_page = store
            .category_transactions(day, Some("Send"), 0, 1)
            .unwrap();
        assert_eq!(first_page["transactions"].as_array().unwrap().len(), 1);
        assert_eq!(first_page["transactions"][0]["category"], "Send");
        assert_eq!(first_page["next_offset"], 1);
        assert_eq!(first_page["has_more"], true);

        let second_page = store
            .category_transactions(day, Some("Send"), 1, 1)
            .unwrap();
        assert_eq!(second_page["transactions"][0]["amount_cents"], 10_000);
        assert_eq!(second_page["has_more"], false);

        let received = store
            .category_transactions(day, Some("Receive"), 0, 1)
            .unwrap();
        assert_eq!(received["transactions"][0]["kind"], "received");

        let all_categories = store.category_transactions(day, None, 0, 1).unwrap();
        assert_eq!(all_categories["transactions"].as_array().unwrap().len(), 1);
        assert_eq!(all_categories["has_more"], true);
    }

    #[test]
    fn transactions_can_be_grouped_under_a_named_paybill() {
        let mut store = MessageStore::in_memory().unwrap();
        store.ingest_message("MPESA", SENT).unwrap();
        store
            .attach_transaction_to_paybill("QGH7XYZ123", " Ndechu ")
            .unwrap();
        let report = store.tracked_paybills().unwrap();
        assert_eq!(report["paybills"][0]["name"], "Ndechu");
        assert_eq!(report["paybills"][0]["transaction_count"], 1);
        assert_eq!(report["paybills"][0]["amount_cents"], 50_000);

        assert!(
            store
                .attach_transaction_to_paybill("UNKNOWN", "Ndechu")
                .is_err()
        );
        assert!(
            store
                .attach_transaction_to_paybill("QGH7XYZ123", "   ")
                .is_err()
        );
        assert!(
            store
                .attach_transaction_to_paybill("QGH7ABC456", "Ndechu")
                .is_err()
        );
    }

    #[test]
    fn bank_statement_import_is_idempotent_and_ranked_separately_from_mpesa() {
        let mut store = MessageStore::in_memory().unwrap();
        store.ingest_message("MPESA", SENT).unwrap();
        let rows = vec![
            StatementRow {
                date: "29/10/26".to_string(),
                time: "09:30".to_string(),
                kind: "sent".to_string(),
                amount_cents: 25_000,
                description: "Ndechu PayBill".to_string(),
                reference: "NCBA-001".to_string(),
            },
            StatementRow {
                date: "29/10/26".to_string(),
                time: "10:00".to_string(),
                kind: "received".to_string(),
                amount_cents: 10_000,
                description: "Salary".to_string(),
                reference: String::new(),
            },
        ];
        assert_eq!(store.import_statement("NCBA", rows.clone()).unwrap(), 2);
        assert_eq!(store.import_statement("NCBA", rows).unwrap(), 0);
        let report = store.institution_report().unwrap();
        assert_eq!(report["institutions"][0]["name"], "M-PESA");
        assert_eq!(report["institutions"][1]["name"], "NCBA");
        assert_eq!(report["institutions"][1]["total_cents"], 35_000);
        assert_eq!(report["institutions"][1]["transaction_count"], 2);
    }

    #[test]
    fn counterparty_settings_reject_invalid_values() {
        let store = MessageStore::in_memory().unwrap();
        assert!(
            store
                .set_counterparty_settings("M-PESA shop", "Unknown", 100)
                .is_err()
        );
        assert!(
            store
                .set_counterparty_settings("M-PESA shop", "Shopping", -1)
                .is_err()
        );
    }

    #[test]
    fn message_completion_can_be_observed_by_the_android_worker() {
        let mut store = MessageStore::in_memory().unwrap();
        assert!(!store.has_message("MPESA", SENT).unwrap());
        store.ingest_message("MPESA", SENT).unwrap();
        assert!(store.has_message("mpesa", SENT).unwrap());
    }

    #[tokio::test]
    async fn apalis_queue_persists_and_processes_a_message_into_the_ledger() {
        let unique = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap()
            .as_nanos();
        let path = std::env::temp_dir().join(format!(
            "msg2ledger-apalis-{}-{unique}.db",
            std::process::id()
        ));
        let pool = sqlite_pool(&path).await.unwrap();
        SqliteStorage::<()>::setup(&pool).await.unwrap();
        let mut queue = SqliteStorage::<IncomingMessage>::new(&pool);
        queue
            .push(IncomingMessage {
                sender: "MPESA".to_string(),
                raw: SENT.to_string(),
                database_path: path.to_string_lossy().into_owned(),
            })
            .await
            .unwrap();

        let backend = SqliteStorage::<IncomingMessage>::new(&pool);
        let worker = WorkerBuilder::new("msg2ledger-test-worker")
            .backend(backend)
            .build(
                |message: IncomingMessage, worker_context: WorkerContext| async move {
                    process_incoming_message(message, worker_context.clone()).await?;
                    worker_context.stop()?;
                    Ok::<(), BoxDynError>(())
                },
            );
        worker.run().await.unwrap();

        let store = MessageStore::open(&path).unwrap();
        let transactions = store.transactions().unwrap();
        assert_eq!(transactions.len(), 1);
        assert_eq!(transactions[0].code, "QGH7XYZ123");
        drop(store);
        drop(pool);
        let _ = std::fs::remove_file(&path);
        let _ = std::fs::remove_file(path.with_extension("db-shm"));
        let _ = std::fs::remove_file(path.with_extension("db-wal"));
    }
}
