use regex::Regex;
use serde::{Deserialize, Serialize};
use std::fmt;
use std::sync::OnceLock;

pub mod analytics;

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum Kind {
    Sent,
    Received,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
pub enum Category {
    Send,
    Receive,
    PayBill,
    BuyGoods,
    Pochi,
    Airtime,
    Withdrawal,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Transaction {
    pub code: String,
    pub kind: Kind,
    pub category: Category,
    pub amount_cents: i64,
    pub counterparty: String,
    pub date: String,
    pub time: String,
    pub balance_cents: Option<i64>,
}

#[derive(Debug, PartialEq, Eq)]
pub enum ParseError {
    Unrecognised,
    BadAmount,
}

impl fmt::Display for ParseError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            ParseError::Unrecognised => write!(f, "message format not recognised"),
            ParseError::BadAmount => write!(f, "could not read an amount"),
        }
    }
}

impl std::error::Error for ParseError {}

const MONEY: &str = r"[\d,]+(?:\.\d{1,2})?";

fn sent_re() -> &'static Regex {
    static RE: OnceLock<Regex> = OnceLock::new();
    RE.get_or_init(|| {
        Regex::new(&format!(
            r"(?i)^(?P<code>[A-Z0-9]{{8,12}}) Confirmed\.?\s+Ksh(?P<amt>{MONEY}) sent to (?P<who>.+?) on (?P<date>\S+) at (?P<time>\d{{1,2}}:\d{{2}}\s?[AP]M)"
        ))
        .unwrap()
    })
}

fn received_re() -> &'static Regex {
    static RE: OnceLock<Regex> = OnceLock::new();
    RE.get_or_init(|| {
        Regex::new(&format!(
            r"(?i)^(?P<code>[A-Z0-9]{{8,12}}) Confirmed\.?\s+You have received Ksh(?P<amt>{MONEY}) from (?P<who>.+?) on (?P<date>\S+) at (?P<time>\d{{1,2}}:\d{{2}}\s?[AP]M)"
        ))
        .unwrap()
    })
}

fn balance_re() -> &'static Regex {
    static RE: OnceLock<Regex> = OnceLock::new();
    RE.get_or_init(|| Regex::new(&format!(r"(?i)balance is Ksh(?P<bal>{MONEY})")).unwrap())
}

/// "1,250.50" -> 125050. Returns None if it isn't a valid amount.
pub fn to_cents(s: &str) -> Option<i64> {
    let clean = s.replace(',', "");
    let (whole, frac) = match clean.split_once('.') {
        Some((w, f)) => (w, f),
        None => (clean.as_str(), ""),
    };
    let whole: i64 = whole.parse().ok()?;
    let frac: i64 = match frac.len() {
        0 => 0,
        1 => frac.parse::<i64>().ok()? * 10,
        2 => frac.parse::<i64>().ok()?,
        _ => return None,
    };
    Some(whole * 100 + frac)
}

pub fn parse_message(raw: &str) -> Result<Transaction, ParseError> {
    let text = raw.trim();

    let (kind, caps) = if let Some(c) = sent_re().captures(text) {
        (Kind::Sent, c)
    } else if let Some(c) = received_re().captures(text) {
        (Kind::Received, c)
    } else {
        return Err(ParseError::Unrecognised);
    };

    let amount_cents = to_cents(&caps["amt"]).ok_or(ParseError::BadAmount)?;
    let balance_cents = balance_re()
        .captures(text)
        .and_then(|c| to_cents(&c["bal"]));
    let category = classify_category(&kind, text);

    Ok(Transaction {
        code: caps["code"].to_uppercase(),
        kind,
        category,
        amount_cents,
        counterparty: caps["who"].trim().to_string(),
        date: caps["date"].to_string(),
        time: caps["time"].to_string(),
        balance_cents,
    })
}

fn classify_category(kind: &Kind, text: &str) -> Category {
    let text = text.to_ascii_lowercase();
    if text.contains("pochi") {
        Category::Pochi
    } else if text.contains("pay bill") || text.contains("paybill") {
        Category::PayBill
    } else if text.contains("buy goods") || text.contains("till number") {
        Category::BuyGoods
    } else if text.contains("airtime") {
        Category::Airtime
    } else if text.contains("withdraw") {
        Category::Withdrawal
    } else if *kind == Kind::Sent {
        Category::Send
    } else {
        Category::Receive
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const SENT: &str = "QGH7XYZ123 Confirmed. Ksh500.00 sent to JOHN DOE 0712345678 on 12/3/26 at 2:15 PM. New M-PESA balance is Ksh1,250.50. Transaction cost, Ksh7.00.";
    const RECEIVED: &str = "QGH7ABC456 Confirmed. You have received Ksh1,000.00 from JANE WANGARI 0722333444 on 12/3/26 at 9:05 AM. New M-PESA balance is Ksh2,250.50.";

    #[test]
    fn parses_sent() {
        let t = parse_message(SENT).unwrap();
        assert_eq!(t.kind, Kind::Sent);
        assert_eq!(t.code, "QGH7XYZ123");
        assert_eq!(t.category, Category::Send);
        assert_eq!(t.amount_cents, 50_000);
        assert_eq!(t.counterparty, "JOHN DOE 0712345678");
        assert_eq!(t.balance_cents, Some(125_050));
    }

    #[test]
    fn parses_received() {
        let t = parse_message(RECEIVED).unwrap();
        assert_eq!(t.kind, Kind::Received);
        assert_eq!(t.category, Category::Receive);
        assert_eq!(t.amount_cents, 100_000);
        assert_eq!(t.time, "9:05 AM");
    }

    #[test]
    fn classifies_payment_types_from_message_text() {
        let cases = [
            (
                "QGH7PAY123 Confirmed. Ksh500.00 sent to MARKET paybill for account 42 on 12/3/26 at 2:15 PM.",
                Category::PayBill,
            ),
            (
                "QGH7BUY123 Confirmed. Ksh500.00 sent to MARKET Buy Goods on 12/3/26 at 2:15 PM.",
                Category::BuyGoods,
            ),
            (
                "QGH7POC123 Confirmed. Ksh500.00 sent to M-PESA Pochi la Biashara on 12/3/26 at 2:15 PM.",
                Category::Pochi,
            ),
            (
                "QGH7AIR123 Confirmed. Ksh500.00 sent to Safaricom Airtime on 12/3/26 at 2:15 PM.",
                Category::Airtime,
            ),
            (
                "QGH7WDR123 Confirmed. Ksh500.00 sent to ATM withdrawal on 12/3/26 at 2:15 PM.",
                Category::Withdrawal,
            ),
        ];

        for (message, expected) in cases {
            assert_eq!(parse_message(message).unwrap().category, expected);
        }
    }

    #[test]
    fn rejects_garbage() {
        assert_eq!(parse_message("hello there"), Err(ParseError::Unrecognised));
    }

    #[test]
    fn money_conversion() {
        assert_eq!(to_cents("1,250.50"), Some(125_050));
        assert_eq!(to_cents("75"), Some(7_500));
        assert_eq!(to_cents("5.5"), Some(550));
        assert_eq!(to_cents("abc"), None);
    }
}
