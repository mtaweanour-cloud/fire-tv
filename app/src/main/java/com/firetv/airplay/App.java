package com.firetv.airplay;

import android.app.Application;
import android.os.Build;

import com.firetv.airplay.log.LogBuffer;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

public final class App extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        System.setProperty("slf4j.provider", "com.firetv.airplay.log.AndroidSlf4jProvider");
        LogBuffer.i("App", "AirPlay Receiver build " + BuildConfig.VERSION_CODE + " on " + Build.MANUFACTURER
                + " " + Build.MODEL + " (Android " + Build.VERSION.RELEASE + ", API " + Build.VERSION.SDK_INT + ")");

        File crashFile = new File(getFilesDir(), "last-crash.txt");
        if (crashFile.exists()) {
            try {
                LogBuffer.w("App", "Previous run crashed:\n" + new String(readAll(crashFile), StandardCharsets.UTF_8));
            } catch (Exception ignored) {
            }
            //noinspection ResultOfMethodCallIgnored
            crashFile.delete();
        }

        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            try (FileOutputStream out = new FileOutputStream(crashFile)) {
                String text = "Thread " + thread.getName() + "\n" + android.util.Log.getStackTraceString(error)
                        + "\n--- last log lines ---\n" + LogBuffer.tail(80);
                out.write(text.getBytes(StandardCharsets.UTF_8));
            } catch (Exception ignored) {
            }
            if (previous != null) {
                previous.uncaughtException(thread, error);
            }
        });
    }

    private static byte[] readAll(File f) throws Exception {
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) != -1; ) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }
}
