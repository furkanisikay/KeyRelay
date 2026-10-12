package dev.keyrelay.helper;

parcelable RelayConfig {
    String[] endpoints;
    byte[] spkiSha256;
    byte[] deviceId;
    byte[] token;
    int[] targetUids;
    int toggleKey;
    long pingIntervalMs;
    long pongTimeoutMs;
    long sendTimeoutMs;
    boolean enabled;
    boolean showKeys;
    boolean invertHorizontalScroll;
    boolean invertVerticalScroll;
    // The system key repeat (ViewConfiguration), read in the app: the helper's app_process has no ActivityThread.
    int keyRepeatTimeoutMs = 500;
    int keyRepeatDelayMs = 50;
}
