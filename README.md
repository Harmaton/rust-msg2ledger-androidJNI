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

## Android app

The `android/` project is a Jetpack Compose app backed by the Rust parser and a
private SQLite database. Its background path is:

1. Android broadcasts an incoming SMS to the manifest-declared receiver.
2. The receiver accepts only sender ID `MPESA` and schedules a WorkManager job.
3. That job starts the embedded Rust service through Rivium's JNI host and adds
   the message to the durable Apalis SQLite queue.
4. The Apalis worker parses the message and stores a transaction (or keeps an
   unparsed M-PESA message for review). SQLite deduplicates messages and
   transaction codes. The SMS work waits for that local write before it
   completes.
5. A budget-alert check runs after ingestion, and periodic WorkManager jobs
   query the ledger and post local spending or budget notifications.

The home screen reads real ledger data: today's spend and receipts, spending
categories with each category's largest payment, regular counterparties and
rule-based on-device insights. Horizontally scrollable category filters show
matching transactions in pages of 30, rendered by a lazy list. A
counterparty opens its transaction history, busiest spending weekday, weekly
average, editable category and optional daily spending cap. Daily and weekly
budgets, notification frequency and Android permissions are configurable in
Settings. The “spending coach” is deterministic rule-based logic; no message or
financial data is sent to an AI service.

The Android launcher uses the adaptive launcher icon resources under
`android/app/src/main/res`; reinstalling is not normally required after
updating the installed app with a newly built APK.

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
run it on an ARM64 phone or x86_64 emulator. Build from the command line with:

```sh
./scripts/build-android.sh
cd android
./gradlew assembleDebug
```

For emulator testing, install and launch the app, grant SMS and notification
permissions in Settings, then send a simulated MPESA SMS:

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.harmaton.msg2ledger/.MainActivity
adb emu sms send MPESA "QGH7XYZ123 Confirmed. Ksh500.00 sent to JOHN DOE 0712345678 on 12/3/26 at 2:15 PM. New M-PESA balance is Ksh1,250.50."
```

Use the current date in the test message to see it in today's totals. The
background queue and notification checks may take time to run: Android
WorkManager schedules periodic work inexactly and may defer it for battery
optimization. Choose hourly, 2-hour, 3-hour, daily or weekly checks in Settings.
For SMS on a physical phone, grant SMS access and test with a real M-PESA
message; some Android versions require enabling restricted permissions from
the app's system settings. SMS and notification access are runtime permissions.
SMS permissions are restricted for Play Store distribution, so this is intended
for local installation and challenge testing.

The app database and Apalis queue share the app-private `ledger.db`; no message
database is bundled in the APK. The SMS receiver does only a sender check and
hands work to WorkManager. Parsed transaction formats are still limited to
the patterns supported by the Rust parser; unrecognized M-PESA formats remain
local for later parser improvements.
