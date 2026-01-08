package com.arthenica.smartexception.java;

import android.util.Log;

/**
 * Minimal in-project fallback for ffmpeg-kit SmartException dependency.
 * ffmpeg-kit expects this class at runtime (com.arthenica.smartexception.java.Exceptions).
 *
 * This implementation keeps the app stable even if the external smart-exception-java
 * artifact is missing from the APK for any reason.
 */
public final class Exceptions {

    private Exceptions() {}

    public static void registerRootPackage(String rootPackage) {
        // no-op (fallback)
    }

    public static String getStackTraceString(Throwable e) {
        return Log.getStackTraceString(e);
    }

    public static String getStackTraceString(Throwable e, String rootPackage) {
        return Log.getStackTraceString(e);
    }

    public static String getStackTraceString(Throwable e, String rootPackage, int maxElements) {
        return Log.getStackTraceString(e);
    }

    public static Throwable getCause(Throwable e) {
        return (e != null) ? e.getCause() : null;
    }
}
