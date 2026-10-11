package dev.keyrelay.helper;

parcelable HelperStatus {
    boolean configured;
    boolean connected;
    boolean pcReady;
    String pcName;
    String endpoint;
    boolean targetForeground;
    boolean enabled = true;
    boolean overriding;
    boolean grabbed;
    String[] keyboards;
    String lastError;
    int rttMs = -1;
    int rejectReason;
}
