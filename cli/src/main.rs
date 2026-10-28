use msg2ledger_core::{parse_message, Kind};
use std::{env, fs};

fn main() {
    let path = env::args().nth(1).unwrap_or_else(|| "samples/messages.txt".into());
    let text = fs::read_to_string(&path).expect("could not read messages file");

    let (mut ok, mut failed) = (0, 0);
    for line in text.lines().filter(|l| !l.trim().is_empty()) {
        match parse_message(line) {
            Ok(t) => {
                ok += 1;
                let sign = if t.kind == Kind::Sent { "-" } else { "+" };
                println!(
                    "{sign}{}.{:02}  {}  {}  [{}]",
                    t.amount_cents / 100,
                    t.amount_cents % 100,
                    t.counterparty,
                    t.date,
                    t.code
                );
            }
            Err(e) => {
                failed += 1;
                println!("needs review: {e}");
            }
        }
    }
    println!("\n{ok} parsed, {failed} need review");
}
