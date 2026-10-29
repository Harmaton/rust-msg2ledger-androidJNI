package com.harmaton.msg2ledger;

public final class NativeBridge {
    static {
        System.loadLibrary("service");
    }

    private NativeBridge() {}

    public static native boolean nativeOpen(String path);
    public static native String nativeIngestMessage(String sender, String raw);
    public static native String nativeDailySummary(String date);
    public static native boolean nativeQueueMessage(String sender, String raw);
    public static native boolean nativeHasMessage(String sender, String raw);
    public static native String nativeDashboard(String date);
    public static native String nativeCategoryTransactions(
            String date, String category, int offset, int limit);
    public static native String nativeCounterpartyDetail(String counterparty);
    public static native boolean nativeSetCounterpartySettings(
            String counterparty, String category, long limitCents);
}
