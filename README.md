# rust-msg2ledger-androidJNI

## Local SQLite ledger

The CLI can import M-PESA message text into a local SQLite database. Parsed
transactions are deduplicated by transaction code, and unparsed M-PESA messages
are retained for review as the parser learns more message formats. Other sender
IDs are ignored by the storage service. The default database is
`msg2ledger.db` in the current directory; database files are git-ignored.

```sh
# Import each non-empty line as an M-PESA message
cargo run -p msg2ledger-cli -- --import samples/messages.txt

# Summarize a day from the local database
cargo run -p msg2ledger-cli -- --summary 13/03/26

# Spending totals grouped by category, plus repeat counterparties
cargo run -p msg2ledger-cli -- --analytics
cargo run -p msg2ledger-cli -- --regulars

# Inspect retained messages the parser did not recognize
cargo run -p msg2ledger-cli -- --review

# Choose a database file
cargo run -p msg2ledger-cli -- --import samples/messages.txt ledger-backup.db
cargo run -p msg2ledger-cli -- --analytics ledger-backup.db

# The original parser-only mode remains available
cargo run -p msg2ledger-cli
```

Summary dates use `DD/MM/YY`; the comparison average uses the prior seven
calendar days, excluding the requested day. Spending analytics group sent
transactions into Send, PayBill, BuyGoods, Pochi, Airtime, and Withdrawal
categories, and list counterparties appearing in at least two transactions.
Category detection currently relies on wording in the message body, so
representative, anonymized SMS examples are needed to improve coverage and
avoid misclassification.

The SQLite service stores raw M-PESA message text locally, including unparsed
messages, so those messages may contain financial and personal information.
Keep the database private and do not commit it or share it unredacted.
