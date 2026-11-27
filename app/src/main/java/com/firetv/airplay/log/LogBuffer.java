package com.firetv.airplay.log;

import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

/** In-memory log kept for the diagnostics overlay and the diagnostics web page. */
public final class LogBuffer {

    public interface Listener {
        void onLine(String line, int priority);
    }

    private static final int MAX_LINES = 1500;
    private static final ArrayDeque<String> LINES = new ArrayDeque<>();
    private static final CopyOnWriteArrayList<Listener> LISTENERS = new CopyOnWriteArrayList<>();
    private static final ThreadLocal<SimpleDateFormat> TIME =
            ThreadLocal.withInitial(() -> new SimpleDateFormat("HH:mm:ss.SSS", Locale.US));

    private LogBuffer() {
    }

    public static void add(int priority, String tag, String message, Throwable t) {
        String shortTag = tag;
        int dot = tag.lastIndexOf('.');
        if (dot >= 0 && dot < tag.length() - 1) {
            shortTag = tag.substring(dot + 1);
        }
        String level;
        switch (priority) {
            case Log.ERROR: level = "E"; break;
            case Log.WARN: level = "W"; break;
            case Log.INFO: level = "I"; break;
            default: level = "D"; break;
        }
        StringBuilder sb = new StringBuilder();
        sb.append(TIME.get().format(new Date())).append(' ').append(level).append(' ')
                .append(shortTag).append(": ").append(message);
        if (t != null) {
            sb.append('\n').append(Log.getStackTraceString(t));
        }
        String line = sb.toString();
        String logTag = shortTag.length() > 23 ? shortTag.substring(0, 23) : shortTag;
        Log.println(priority, logTag, t == null ? message : message + '\n' + Log.getStackTraceString(t));
        synchronized (LINES) {
            LINES.addLast(line);
            while (LINES.size() > MAX_LINES) {
                LINES.removeFirst();
            }
        }
        for (Listener l : LISTENERS) {
            try {
                l.onLine(line, priority);
            } catch (Throwable ignored) {
            }
        }
    }

    public static void i(String tag, String message) {
        add(Log.INFO, tag, message, null);
    }

    public static void w(String tag, String message) {
        add(Log.WARN, tag, message, null);
    }

    public static void e(String tag, String message, Throwable t) {
        add(Log.ERROR, tag, message, t);
    }

    public static String dump() {
        StringBuilder sb = new StringBuilder();
        synchronized (LINES) {
            for (String line : LINES) {
                sb.append(line).append('\n');
            }
        }
        return sb.toString();
    }

    public static String tail(int count) {
        StringBuilder sb = new StringBuilder();
        synchronized (LINES) {
            int skip = Math.max(0, LINES.size() - count);
            int i = 0;
            for (String line : LINES) {
                if (i++ >= skip) {
                    sb.append(line).append('\n');
                }
            }
        }
        return sb.toString();
    }

    public static void addListener(Listener l) {
        LISTENERS.add(l);
    }

    public static void removeListener(Listener l) {
        LISTENERS.remove(l);
    }
}
