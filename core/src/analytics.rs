use crate::{Category, Kind, Transaction};
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

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct CategorySpending {
    pub category: Category,
    pub amount_cents: i64,
    pub transaction_count: usize,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct RegularCounterparty {
    pub counterparty: String,
    pub sent_count: usize,
    pub total_sent_cents: i64,
    pub received_count: usize,
    pub total_received_cents: i64,
}

pub fn spending_by_category(
    txs: &[Transaction],
    from: Option<NaiveDate>,
    through: Option<NaiveDate>,
) -> Vec<CategorySpending> {
    let mut totals: HashMap<Category, (i64, usize)> = HashMap::new();
    for transaction in txs.iter().filter(|transaction| {
        transaction
            .occurred_at()
            .map(|at| {
                from.is_none_or(|date| at.date() >= date)
                    && through.is_none_or(|date| at.date() <= date)
            })
            .unwrap_or(false)
            && transaction.kind == Kind::Sent
    }) {
        let total = totals.entry(transaction.category).or_default();
        total.0 += transaction.amount_cents;
        total.1 += 1;
    }

    let mut spending: Vec<_> = totals
        .into_iter()
        .map(
            |(category, (amount_cents, transaction_count))| CategorySpending {
                category,
                amount_cents,
                transaction_count,
            },
        )
        .collect();
    spending.sort_by(|a, b| {
        b.amount_cents
            .cmp(&a.amount_cents)
            .then_with(|| category_label(a.category).cmp(category_label(b.category)))
    });
    spending
}

pub fn regular_counterparties(
    txs: &[Transaction],
    from: Option<NaiveDate>,
    min_transactions: usize,
) -> Vec<RegularCounterparty> {
    let mut totals: HashMap<&str, (usize, i64, usize, i64)> = HashMap::new();
    for transaction in txs.iter().filter(|transaction| {
        transaction
            .occurred_at()
            .map(|at| from.is_none_or(|date| at.date() >= date))
            .unwrap_or(false)
    }) {
        let total = totals.entry(transaction.counterparty.trim()).or_default();
        match transaction.kind {
            Kind::Sent => {
                total.0 += 1;
                total.1 += transaction.amount_cents;
            }
            Kind::Received => {
                total.2 += 1;
                total.3 += transaction.amount_cents;
            }
        }
    }

    let mut regulars: Vec<_> = totals
        .into_iter()
        .filter(|(_, (sent_count, _, received_count, _))| {
            sent_count + received_count >= min_transactions
        })
        .map(
            |(
                counterparty,
                (sent_count, total_sent_cents, received_count, total_received_cents),
            )| {
                RegularCounterparty {
                    counterparty: counterparty.to_string(),
                    sent_count,
                    total_sent_cents,
                    received_count,
                    total_received_cents,
                }
            },
        )
        .collect();
    regulars.sort_by(|a, b| {
        (b.sent_count + b.received_count)
            .cmp(&(a.sent_count + a.received_count))
            .then_with(|| b.total_sent_cents.cmp(&a.total_sent_cents))
            .then_with(|| a.counterparty.cmp(&b.counterparty))
    });
    regulars
}

pub fn category_label(category: Category) -> &'static str {
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

    #[test]
    fn groups_spending_and_finds_regular_counterparties() {
        let mut txs = transactions();
        txs.push(parse_message(
            "QGH7XYZ124 Confirmed. Ksh100.00 sent to JOHN DOE 0712345678 on 13/3/26 at 7:15 PM.",
        )
        .unwrap());
        txs.push(parse_message(
            "QGH7XYZ125 Confirmed. Ksh200.00 sent to MARKET paybill for account 42 on 13/3/26 at 7:15 PM.",
        )
        .unwrap());

        let from = NaiveDate::from_ymd_opt(2026, 3, 12).unwrap();
        let categories = spending_by_category(&txs, Some(from), None);
        let paybill = categories
            .iter()
            .find(|category| category.category == Category::PayBill)
            .unwrap();
        assert_eq!(paybill.amount_cents, 20_000);
        assert_eq!(paybill.transaction_count, 1);

        let regulars = regular_counterparties(&txs, Some(from), 2);
        assert_eq!(regulars[0].counterparty, "JOHN DOE 0712345678");
        assert_eq!(regulars[0].sent_count, 2);
        assert_eq!(regulars[0].total_sent_cents, 60_000);
    }
}
