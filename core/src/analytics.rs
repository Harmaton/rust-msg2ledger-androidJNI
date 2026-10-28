use crate::{Kind, Transaction};
use chrono::{NaiveDate, NaiveDateTime, NaiveTime};
use std::collections::HashMap;

impl Transaction {
    /// Parse the M-PESA date and time as day/month/year and 12-hour time.
    pub fn occurred_at(&self) -> Option<NaiveDateTime> {
        let date = NaiveDate::parse_from_str(&self.date, "%d/%m/%y").ok()?;
        let time = NaiveTime::parse_from_str(&self.time, "%I:%M %p").ok()?;
        Some(date.and_time(time))
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DaySummary {
    pub day: NaiveDate,
    pub spent_cents: i64,
    pub received_cents: i64,
    pub sent_count: usize,
    pub biggest_cents: i64,
    pub top_counterparty: Option<String>,
}

pub fn summarize_day(txs: &[Transaction], day: NaiveDate) -> DaySummary {
    let (mut spent, mut received, mut sent_count, mut biggest) = (0, 0, 0, 0);
    let mut by_counterparty: HashMap<&str, i64> = HashMap::new();

    for transaction in txs
        .iter()
        .filter(|transaction| transaction.occurred_at().map(|at| at.date()) == Some(day))
    {
        match transaction.kind {
            Kind::Sent => {
                spent += transaction.amount_cents;
                sent_count += 1;
                biggest = biggest.max(transaction.amount_cents);
                *by_counterparty
                    .entry(transaction.counterparty.as_str())
                    .or_default() += transaction.amount_cents;
            }
            Kind::Received => received += transaction.amount_cents,
        }
    }

    let top_counterparty = by_counterparty
        .into_iter()
        .max_by(|a, b| a.1.cmp(&b.1).then_with(|| b.0.cmp(a.0)))
        .map(|(name, _)| name.to_string());

    DaySummary {
        day,
        spent_cents: spent,
        received_cents: received,
        sent_count,
        biggest_cents: biggest,
        top_counterparty,
    }
}

/// Average daily spend over the `days` days before `before` (not including it).
pub fn avg_daily_spend(txs: &[Transaction], before: NaiveDate, days: i64) -> i64 {
    if days <= 0 {
        return 0;
    }

    let total: i64 = (1..=days)
        .map(|offset| summarize_day(txs, before - chrono::Duration::days(offset)).spent_cents)
        .sum();
    total / days
}

/// Format cents as whole Kenyan shillings with thousands separators.
pub fn format_ksh(cents: i64) -> String {
    let amount = cents / 100;
    let digits = amount.unsigned_abs().to_string();
    let mut grouped = String::new();
    for (index, digit) in digits.chars().rev().enumerate() {
        if index > 0 && index % 3 == 0 {
            grouped.push(',');
        }
        grouped.push(digit);
    }
    let grouped: String = grouped.chars().rev().collect();
    let sign = if amount < 0 { "-" } else { "" };
    format!("Ksh{sign}{grouped}")
}

pub fn summary_text(summary: &DaySummary, avg_cents: i64) -> String {
    if summary.sent_count == 0 {
        return "No spending recorded today.".to_string();
    }

    let mut text = format!(
        "You spent {} across {} payment{}. Biggest: {}.",
        format_ksh(summary.spent_cents),
        summary.sent_count,
        if summary.sent_count == 1 { "" } else { "s" },
        format_ksh(summary.biggest_cents),
    );
    if avg_cents > 0 {
        let percent =
            (i128::from(summary.spent_cents) - i128::from(avg_cents)) * 100 / i128::from(avg_cents);
        if percent >= 0 {
            text.push_str(&format!(
                " That's {percent}% above your recent daily average."
            ));
        } else {
            text.push_str(&format!(
                " That's {}% below your recent daily average.",
                -percent
            ));
        }
    }
    text
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::parse_message;

    fn transactions() -> Vec<Transaction> {
        [
            "QGH7XYZ123 Confirmed. Ksh500.00 sent to JOHN DOE 0712345678 on 12/3/26 at 2:15 PM. New M-PESA balance is Ksh1,250.50.",
            "QGH7ABC456 Confirmed. You have received Ksh1,000.00 from JANE WANGARI 0722333444 on 12/3/26 at 9:05 AM. New M-PESA balance is Ksh2,250.50.",
            "QGH7DEF789 Confirmed. Ksh75.00 sent to KAMAU STORES 0733111222 on 13/3/26 at 6:40 PM. New M-PESA balance is Ksh2,175.50.",
        ]
        .iter()
        .map(|message| parse_message(message).unwrap())
        .collect()
    }

    #[test]
    fn parses_transaction_timestamp() {
        assert_eq!(
            transactions()[0].occurred_at().unwrap().to_string(),
            "2026-03-12 14:15:00"
        );
    }

    #[test]
    fn summarizes_only_transactions_for_requested_day() {
        let day = NaiveDate::from_ymd_opt(2026, 3, 12).unwrap();
        let summary = summarize_day(&transactions(), day);
        assert_eq!(summary.spent_cents, 50_000);
        assert_eq!(summary.received_cents, 100_000);
        assert_eq!(summary.sent_count, 1);
        assert_eq!(summary.biggest_cents, 50_000);
        assert_eq!(
            summary.top_counterparty.as_deref(),
            Some("JOHN DOE 0712345678")
        );
    }

    #[test]
    fn calculates_average_and_summary_text() {
        let day = NaiveDate::from_ymd_opt(2026, 3, 13).unwrap();
        let average = avg_daily_spend(&transactions(), day, 7);
        assert_eq!(average, 7_142);

        let summary = summarize_day(&transactions(), day);
        let text = summary_text(&summary, average);
        assert!(text.starts_with("You spent Ksh75 across 1 payment."));
        assert!(text.contains("above your recent daily average"));
    }

    #[test]
    fn formats_whole_shillings_with_grouping() {
        assert_eq!(format_ksh(125_050), "Ksh1,250");
        assert_eq!(format_ksh(7_500), "Ksh75");
        assert_eq!(format_ksh(123_456_700), "Ksh1,234,567");
    }

    #[test]
    fn no_spending_has_clear_notification_text() {
        let day = NaiveDate::from_ymd_opt(2026, 3, 14).unwrap();
        let summary = summarize_day(&transactions(), day);
        assert_eq!(summary_text(&summary, 7_142), "No spending recorded today.");
        assert_eq!(avg_daily_spend(&transactions(), day, 0), 0);
    }
}
