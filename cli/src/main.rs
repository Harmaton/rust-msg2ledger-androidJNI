use chrono::NaiveDate;
use msg2ledger_core::{
    analytics::{avg_daily_spend, format_ksh, summarize_day, summary_text},
    parse_message, Kind, Transaction,
};
use std::{env, fs, process::ExitCode};

fn main() -> ExitCode {
    let mut args = env::args().skip(1);
    match args.next().as_deref() {
        Some("--summary") => {
            let Some(date) = args.next() else {
                eprintln!("Usage: msg2ledger-cli --summary DD/MM/YY [messages-file]");
                return ExitCode::from(2);
            };
            let path = args
                .next()
                .unwrap_or_else(|| "samples/messages.txt".to_string());
            if args.next().is_some() {
                eprintln!("Usage: msg2ledger-cli --summary DD/MM/YY [messages-file]");
                return ExitCode::from(2);
            }
            match print_summary(&path, &date) {
                Ok(()) => ExitCode::SUCCESS,
                Err(error) => {
                    eprintln!("{error}");
                    ExitCode::FAILURE
                }
            }
        }
        Some("--help" | "-h") => {
            print_usage();
            ExitCode::SUCCESS
        }
        Some(path) => match print_transactions(&path) {
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
    println!("  msg2ledger-cli --summary DD/MM/YY [messages-file]");
}

fn read_transactions(path: &str) -> Result<(Vec<Transaction>, usize), String> {
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
    Ok((transactions, failed))
}

fn print_transactions(path: &str) -> Result<(), String> {
    let (transactions, failed) = read_transactions(path)?;
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

fn print_summary(path: &str, date: &str) -> Result<(), String> {
    let day = NaiveDate::parse_from_str(date, "%d/%m/%y")
        .map_err(|error| format!("invalid date {date:?}; expected DD/MM/YY: {error}"))?;
    let (transactions, failed) = read_transactions(path)?;
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
    if failed > 0 {
        println!("{failed} message(s) need review.");
    }
    Ok(())
}
