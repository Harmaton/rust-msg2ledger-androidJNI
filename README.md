# rust-msg2ledger-androidJNI

## Local spending summary

The CLI can parse a message file or summarize one day of M-PESA transactions.
Transactions are currently read from the sample file; this milestone does not
yet persist them between runs.

```sh
cargo run -p msg2ledger-cli
cargo run -p msg2ledger-cli -- --summary 13/03/26
cargo run -p msg2ledger-cli -- --summary 13/03/26 samples/messages.txt
```

The summary date uses `DD/MM/YY`, matching the date format in the supported
messages. The comparison average covers the seven calendar days before the
requested date; the requested date is excluded. Money in summary text is
displayed in whole Kenyan shillings.
