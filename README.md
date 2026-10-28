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

## Android JNI smoke-test app

The `android/` project is a minimal Jetpack Compose app that calls the Rust
service via JNI. It has a splash screen, a home screen with the sample
transaction and daily-summary JNI actions, and a settings screen in bottom
navigation. It opens `ledger.db` in the app's private database directory. It
does not request SMS permissions or listen for SMS yet; this first app verifies
that the Android Kotlin-to-Rust-to-SQLite path works.

The native build targets `arm64-v8a` phones and `x86_64` emulators. Install
Android Studio with the Android SDK and NDK, install both Rust Android targets
and `cargo-ndk`, then build the native library from the repository root:

```sh
rustup target add aarch64-linux-android
rustup target add x86_64-linux-android
cargo install cargo-ndk
./scripts/build-android.sh
```

Open the `android/` directory in Android Studio, sync the Gradle project, and
run the app on an ARM64 device or an x86_64 emulator. The app opens on a splash screen,
then shows the home screen and settings in bottom navigation. On the home
screen, tap **Add sample transaction**, then **Refresh today's summary** to
exercise the JNI and SQLite path. The sample date is today's date; repeated
taps generate distinct test transaction codes. The settings page shows local
storage and preview limitations. Rust/JNI errors are shown in the home status.

The native library owns one SQLite connection per app process. The app supplies
the private database path when it calls `open`; no database file is copied into
the APK. The receiver, permission flow, and background notification work remain
separate follow-up work.
