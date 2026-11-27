package com.firetv.airplay;

import com.firetv.airplay.log.LogBuffer;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.function.Supplier;

/**
 * Tiny web page on the TV (port 7777) that shows the app's log, so a phone on the same Wi-Fi can
 * open it and copy the text. Nothing leaves the home network.
 */
final class DiagnosticsServer {

    static final int PORT = 7777;
    private static final String TAG = "Diagnostics";

    private final Supplier<String> header;
    private volatile ServerSocket socket;

    DiagnosticsServer(Supplier<String> header) {
        this.header = header;
    }

    void start() {
        Thread t = new Thread(this::run, "diagnostics-http");
        t.setDaemon(true);
        t.start();
    }

    void stop() {
        ServerSocket s = socket;
        socket = null;
        if (s != null) {
            try {
                s.close();
            } catch (Exception ignored) {
            }
        }
    }

    private void run() {
        try (ServerSocket server = new ServerSocket(PORT)) {
            socket = server;
            LogBuffer.i(TAG, "Diagnostics page on port " + PORT);
            while (socket != null) {
                try (Socket client = server.accept()) {
                    client.setSoTimeout(3000);
                    BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
                    String line;
                    while ((line = in.readLine()) != null && !line.isEmpty()) {
                        // read and ignore request headers
                    }
                    byte[] body = (header.get() + "\n\n" + LogBuffer.dump()).getBytes(StandardCharsets.UTF_8);
                    OutputStream out = client.getOutputStream();
                    out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: "
                            + body.length + "\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n")
                            .getBytes(StandardCharsets.US_ASCII));
                    out.write(body);
                    out.flush();
                } catch (Exception e) {
                    if (socket == null) {
                        return;
                    }
                }
            }
        } catch (Exception e) {
            LogBuffer.w(TAG, "Diagnostics page unavailable: " + e);
        }
    }
}
