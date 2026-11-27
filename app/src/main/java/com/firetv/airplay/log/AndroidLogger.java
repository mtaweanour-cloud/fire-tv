package com.firetv.airplay.log;

import android.util.Log;

import org.slf4j.Marker;
import org.slf4j.event.Level;
import org.slf4j.helpers.LegacyAbstractLogger;
import org.slf4j.helpers.MessageFormatter;

/** SLF4J logger that writes to logcat and {@link LogBuffer}. Debug output is off by default. */
final class AndroidLogger extends LegacyAbstractLogger {

    static volatile boolean debugEnabled = false;

    AndroidLogger(String name) {
        this.name = name;
    }

    @Override
    public boolean isTraceEnabled() {
        return false;
    }

    @Override
    public boolean isDebugEnabled() {
        return debugEnabled;
    }

    @Override
    public boolean isInfoEnabled() {
        return true;
    }

    @Override
    public boolean isWarnEnabled() {
        return true;
    }

    @Override
    public boolean isErrorEnabled() {
        return true;
    }

    @Override
    protected String getFullyQualifiedCallerName() {
        return null;
    }

    @Override
    protected void handleNormalizedLoggingCall(Level level, Marker marker, String messagePattern,
                                               Object[] arguments, Throwable throwable) {
        String message = MessageFormatter.basicArrayFormat(messagePattern, arguments);
        int priority;
        switch (level) {
            case ERROR: priority = Log.ERROR; break;
            case WARN: priority = Log.WARN; break;
            case INFO: priority = Log.INFO; break;
            default: priority = Log.DEBUG; break;
        }
        LogBuffer.add(priority, name, message, throwable);
    }
}
