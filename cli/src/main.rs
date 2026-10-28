use chrono::NaiveDate;
use msg2ledger_core::{
    analytics::{
        avg_daily_spend, category_label, format_ksh, regular_counterparties, spending_by_category,
        summarize_day, summary_text,
    },
    parse_message, Kind, Transaction,
};
use service::{IngestResult, MessageStore};
use std::{env, fs, process::ExitCode};

fn main() -> ExitCode {
    let mut args = env::args().skip(1);
    match args.next().as_deref() {
        Some("--import") => {
            let path = args
                .next()
                .unwrap_or_else(|| "samples/messages.txt".to_string());
            let database = args.next().unwrap_or_else(|| "msg2ledger.db".to_string());
            if args.next().is_some() {
                print_usage();
                return ExitCode::from(2);
            }
            run_import(&path, &database)
        }
        Some("--summary") => {
            let Some(date) = args.next() else {
                eprintln!("Usage: msg2ledger-cli --summary DD/MM/YY [database]");
                return ExitCode::from(2);
            };
            let database = args.next().unwrap_or_else(|| "msg2ledger.db".to_string());
            if args.next().is_some() {
                eprintln!("Usage: msg2ledger-cli --summary DD/MM/YY [database]");
                return ExitCode::from(2);
            }
            run_command(|| print_summary(&database, &date))
        }
        Some("--analytics") => {
            let database = args.next().unwrap_or_else(|| "msg2ledger.db".to_string());
            if args.next().is_some() {
                print_usage();
                return ExitCode::from(2);
            }
            run_command(|| print_analytics(&database))
        }
        Some("--regulars") => {
            let database = args.next().unwrap_or_else(|| "msg2ledger.db".to_string());
            if args.next().is_some() {
                print_usage();
                return ExitCode::from(2);
            }
            run_command(|| print_regulars(&database))
        }
        Some("--review") => {
            let database = args.next().unwrap_or_else(|| "msg2ledger.db".to_string());
            if args.next().is_some() {
                print_usage();
                return ExitCode::from(2);
            }
            run_command(|| print_review(&database))
        }
        Some("--help" | "-h") => {
            print_usage();
            ExitCode::SUCCESS
        }
        Some(path) => match print_transactions(path) {
            Ok(()) => ExitCode::SUCCESS,
            Err(error) => {
                eprintln!("{error}");
                ExitCode::FAILURE
            }
        },
        None => match print_transactions("samples/messages.txt") {
            Ok(()) => ExitCode::SUCCESS,
            Err(error) => {
                eprintln!("{error}");
                ExitCode::FAILURE
            }
        },
    }
}

fn print_usage() {
    println!("Usage:");
    println!("  msg2ledger-cli [messages-file]");
    println!("  msg2ledger-cli --import [messages-file] [database]");
    println!("  msg2ledger-cli --summary DD/MM/YY [database]");
    println!("  msg2ledger-cli --analytics [database]");
    println!("  msg2ledger-cli --regulars [database]");
    println!("  msg2ledger-cli --review [database]");
}

fn run_command(command: impl FnOnce() -> Result<(), String>) -> ExitCode {
    match command() {
        Ok(()) => ExitCode::SUCCESS,
        Err(error) => {
            eprintln!("{error}");
            ExitCode::FAILURE
        }
    }
}

fn run_import(path: &str, database: &str) -> ExitCode {
    run_command(|| {
        let text =
            fs::read_to_string(path).map_err(|error| format!("could not read {path}: {error}"))?;
        let mut store = MessageStore::open(database)
            .map_err(|error| format!("could not open {database}: {error}"))?;
        let mut parsed = 0;
        let mut unparsed = 0;
        let mut duplicates = 0;

        for line in text.lines().filter(|line| !line.trim().is_empty()) {
            match store
                .ingest_message("MPESA", line)
                .map_err(|error| format!("could not store message: {error}"))?
            {
                IngestResult::Stored(_) => parsed += 1,
                IngestResult::StoredUnparsed(error) => {
                    unparsed += 1;
                    println!("saved for review: {error}");
                }
                IngestResult::DuplicateMessage | IngestResult::DuplicateTransaction(_) => {
                    duplicates += 1
                }
                IngestResult::IgnoredNonMpesa => unreachable!("import uses MPESA sender"),
            }
        }

        println!("Imported {parsed} transaction(s), saved {unparsed} unparsed message(s), ignored {duplicates} duplicate(s).");
        println!("Local database: {database}");
        Ok(())
    })
}

fn read_transactions(database: &str) -> Result<Vec<Transaction>, String> {
    let store = MessageStore::open(database)
        .map_err(|error| format!("could not open {database}: {error}"))?;
    store
        .transactions()
        .map_err(|error| format!("could not read transactions: {error}"))
}

fn print_transactions(path: &str) -> Result<(), String> {
    let text =
        fs::read_to_string(path).map_err(|error| format!("could not read {path}: {error}"))?;
    let mut transactions = Vec::new();
    let mut failed = 0;
    for line in text.lines().filter(|line| !line.trim().is_empty()) {
        match parse_message(line) {
            Ok(transaction) => transactions.push(transaction),
            Err(error) => {
                failed += 1;
                println!("needs review: {error}");
            }
        }
    }
    for transaction in &transactions {
        let sign = if transaction.kind == Kind::Sent {
            "-"
        } else {
            "+"
        };
        println!(
            "{sign}{}.{:02}  {}  {}  [{}]",
            transaction.amount_cents / 100,
            transaction.amount_cents % 100,
            transaction.counterparty,
            transaction.date,
            transaction.code
        );
    }
    println!("\n{} parsed, {failed} need review", transactions.len());
    Ok(())
}

fn print_summary(database: &str, date: &str) -> Result<(), String> {
    let day = NaiveDate::parse_from_str(date, "%d/%m/%y")
        .map_err(|error| format!("invalid date {date:?}; expected DD/MM/YY: {error}"))?;
    let transactions = read_transactions(database)?;
    let summary = summarize_day(&transactions, day);
    let average = avg_daily_spend(&transactions, day, 7);

    println!("{}", summary_text(&summary, average));
    println!("Received: {}", format_ksh(summary.received_cents));
    if let Some(counterparty) = summary.top_counterparty {
        println!("Top payee: {counterparty}");
    }
    println!(
        "Recent average: {} per day (previous 7 days).",
        format_ksh(average)
    );
    Ok(())
}

fn print_analytics(database: &str) -> Result<(), String> {
    let store = MessageStore::open(database)
        .map_err(|error| format!("could not open {database}: {error}"))?;
    let transactions = store
        .transactions()
        .map_err(|error| format!("could not read transactions: {error}"))?;
    let categories = spending_by_category(&transactions, None, None);

    println!(
        "Stored {} M-PESA message(s), including {} awaiting review.",
        store
            .message_count()
            .map_err(|error| format!("could not count messages: {error}"))?,
        store
            .unparsed_messages()
            .map_err(|error| format!("could not read review messages: {error}"))?
            .len()
    );
    if categories.is_empty() {
        println!("No outgoing transactions stored yet.");
    } else {
        println!("Spending by category:");
        for category in categories {
            println!(
                "  {}: {} across {} transaction(s)",
                category_label(category.category),
                format_ksh(category.amount_cents),
                category.transaction_count
            );
        }
    }
    print_regulars_from(&transactions);
    Ok(())
}

fn print_regulars(database: &str) -> Result<(), String> {
    let transactions = read_transactions(database)?;
    print_regulars_from(&transactions);
    Ok(())
}

fn print_regulars_from(transactions: &[Transaction]) {
    let regulars = regular_counterparties(transactions, None, 2);
    println!("Regular counterparties (2+ transactions):");
    if regulars.is_empty() {
        println!("  Not enough repeated activity yet.");
    }
    for regular in regulars {
        println!(
            "  {}: {} transaction(s), sent {}, received {}",
            regular.counterparty,
            regular.sent_count + regular.received_count,
            format_ksh(regular.total_sent_cents),
            format_ksh(regular.total_received_cents)
        );
    }
}

fn print_review(database: &str) -> Result<(), String> {
    let store = MessageStore::open(database)
        .map_err(|error| format!("could not open {database}: {error}"))?;
    let messages = store
        .unparsed_messages()
        .map_err(|error| format!("could not read review messages: {error}"))?;
    if messages.is_empty() {
        println!("No unparsed M-PESA messages.");
    } else {
        for (raw, error) in messages {
            println!("{error}: {raw}");
        }
    }
    Ok(())
}
