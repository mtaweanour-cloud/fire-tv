package com.firetv.airplay.log;

import org.slf4j.ILoggerFactory;
import org.slf4j.IMarkerFactory;
import org.slf4j.Logger;
import org.slf4j.helpers.BasicMarkerFactory;
import org.slf4j.helpers.NOPMDCAdapter;
import org.slf4j.spi.MDCAdapter;
import org.slf4j.spi.SLF4JServiceProvider;

import java.util.concurrent.ConcurrentHashMap;

/** Registered through META-INF/services so the AirPlay library logs into the app. */
public final class AndroidSlf4jProvider implements SLF4JServiceProvider {

    private final ConcurrentHashMap<String, Logger> loggers = new ConcurrentHashMap<>();
    private final ILoggerFactory factory = name -> loggers.computeIfAbsent(name, AndroidLogger::new);
    private final IMarkerFactory markerFactory = new BasicMarkerFactory();
    private final MDCAdapter mdcAdapter = new NOPMDCAdapter();

    @Override
    public ILoggerFactory getLoggerFactory() {
        return factory;
    }

    @Override
    public IMarkerFactory getMarkerFactory() {
        return markerFactory;
    }

    @Override
    public MDCAdapter getMDCAdapter() {
        return mdcAdapter;
    }

    @Override
    public String getRequestedApiVersion() {
        return "2.0.99";
    }

    @Override
    public void initialize() {
    }

    public static void setDebug(boolean enabled) {
        AndroidLogger.debugEnabled = enabled;
    }
}
