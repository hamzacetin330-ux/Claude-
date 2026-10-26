package org.umineko.installer;

/** Shared progress state, written by InstallService and polled by MainActivity. */
public final class Progress {
    public static volatile boolean running;
    public static volatile boolean stopRequested;
    public static volatile String status = "";
    public static volatile int itemIndex;
    public static volatile int itemCount;
    /** 0..1000 for the current step, or -1 when unknown. */
    public static volatile int stepPermille = -1;
    public static volatile String error;
    /** Path of a downloaded engine APK, if any, so the user can install it. */
    public static volatile String apkPath;

    private Progress() {
    }
}
