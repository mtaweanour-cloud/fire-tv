package com.firetv.airplay;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.firetv.airplay.log.LogBuffer;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.Locale;

/**
 * The only screen: shows "ready" instructions while idle and the phone's screen while mirroring.
 * Press MENU (☰) on the remote to show or hide the diagnostics log.
 */
public final class MainActivity extends Activity implements AndroidPlayback.Ui, SurfaceHolder.Callback {

    private static final String TAG = "Main";

    private final Handler main = new Handler(Looper.getMainLooper());

    private FrameLayout root;
    private SurfaceView surfaceView;
    private LinearLayout idlePanel;
    private TextView title;
    private TextView statusText;
    private TextView hintText;
    private ScrollView logScroll;
    private TextView logText;

    private AndroidPlayback playback;
    private AirPlayReceiver receiver;
    private DiagnosticsServer diagnostics;
    private String receiverName;
    private int videoWidth;
    private int videoHeight;
    private String uiState = "idle";

    private final LogBuffer.Listener logListener = (line, priority) -> main.post(() -> {
        if (logText != null && logScroll.getVisibility() == View.VISIBLE) {
            logText.append(line + "\n");
            logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
        }
    });

    private final Runnable statusTicker = new Runnable() {
        @Override
        public void run() {
            refreshIdleText();
            main.postDelayed(this, 2000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        buildUi();

        receiverName = deviceName();
        playback = new AndroidPlayback();
        playback.setUi(this);
        receiver = new AirPlayReceiver(this, receiverName, playback);
        diagnostics = new DiagnosticsServer(this::diagnosticsHeader);
        diagnostics.start();
        LogBuffer.addListener(logListener);

        new Thread(() -> {
            try {
                receiver.start();
            } catch (Throwable t) {
                LogBuffer.e(TAG, "AirPlay receiver failed to start", t);
            }
        }, "receiver-start").start();

        new Thread(this::checkForUpdate, "update-check").start();
        main.post(statusTicker);
        onIdle();
    }

    @Override
    protected void onDestroy() {
        main.removeCallbacksAndMessages(null);
        LogBuffer.removeListener(logListener);
        playback.setUi(null);
        new Thread(() -> {
            receiver.stop();
            diagnostics.stop();
        }, "receiver-stop").start();
        super.onDestroy();
    }

    // ---------------------------------------------------------------- UI

    private void buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        surfaceView = new SurfaceView(this);
        surfaceView.getHolder().addCallback(this);
        root.addView(surfaceView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER));

        idlePanel = new LinearLayout(this);
        idlePanel.setOrientation(LinearLayout.VERTICAL);
        idlePanel.setGravity(Gravity.CENTER);
        idlePanel.setBackgroundColor(Color.rgb(0x10, 0x18, 0x2C));
        int pad = dp(48);
        idlePanel.setPadding(pad, pad, pad, pad);

        title = text(34, Color.WHITE, true);
        statusText = text(20, Color.rgb(0x4F, 0x8C, 0xFF), false);
        hintText = text(17, Color.rgb(0xC8, 0xD0, 0xE0), false);
        hintText.setLineSpacing(0, 1.3f);
        idlePanel.addView(title);
        idlePanel.addView(space());
        idlePanel.addView(statusText);
        idlePanel.addView(space());
        idlePanel.addView(hintText);
        root.addView(idlePanel, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        logScroll = new ScrollView(this);
        logScroll.setBackgroundColor(Color.argb(0xDD, 0, 0, 0));
        logText = new TextView(this);
        logText.setTextColor(Color.rgb(0xB8, 0xFF, 0xB8));
        logText.setTypeface(Typeface.MONOSPACE);
        logText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        logText.setPadding(dp(16), dp(16), dp(16), dp(16));
        logScroll.addView(logText);
        logScroll.setVisibility(View.GONE);
        root.addView(logScroll, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        setContentView(root);
    }

    private TextView text(int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER);
        if (bold) {
            t.setTypeface(Typeface.DEFAULT_BOLD);
        }
        return t;
    }

    private View space() {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, dp(20)));
        return v;
    }

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    private void refreshIdleText() {
        if (idlePanel.getVisibility() != View.VISIBLE) {
            return;
        }
        title.setText(receiverName);
        String ip = wifiIp();
        StringBuilder hint = new StringBuilder();
        switch (uiState) {
            case "connecting":
                statusText.setText("Connecting to your iPhone…");
                break;
            case "audio":
                statusText.setText("Playing audio from your iPhone");
                break;
            default:
                statusText.setText(receiver.status());
                hint.append("On your iPhone or iPad: open Control Center → Screen Mirroring → \"")
                        .append(receiverName).append("\".\n")
                        .append("Make sure both are on the same Wi-Fi.");
                break;
        }
        hint.append("\n\nVersion ").append(BuildConfig.VERSION_NAME);
        if (ip != null) {
            hint.append("  ·  Diagnostics: http://").append(ip).append(':').append(DiagnosticsServer.PORT);
        }
        hint.append("  ·  Press ☰ for log");
        hintText.setText(hint);
    }

    private void showIdlePanel(String state) {
        main.post(() -> {
            uiState = state;
            idlePanel.setVisibility(View.VISIBLE);
            refreshIdleText();
        });
    }

    @Override
    public void onConnecting() {
        showIdlePanel("connecting");
    }

    @Override
    public void onMirroringStarted() {
        main.post(() -> {
            uiState = "mirroring";
            idlePanel.setVisibility(View.GONE);
        });
    }

    @Override
    public void onAudioOnly() {
        showIdlePanel("audio");
    }

    @Override
    public void onIdle() {
        showIdlePanel("idle");
    }

    @Override
    public void onVideoSize(int width, int height) {
        main.post(() -> {
            videoWidth = width;
            videoHeight = height;
            fitSurface();
        });
    }

    /** Letterboxes the video so portrait and landscape phones both keep their shape. */
    private void fitSurface() {
        int w = root.getWidth();
        int h = root.getHeight();
        if (w == 0 || h == 0 || videoWidth <= 0 || videoHeight <= 0) {
            return;
        }
        double scale = Math.min((double) w / videoWidth, (double) h / videoHeight);
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) surfaceView.getLayoutParams();
        lp.width = (int) Math.round(videoWidth * scale);
        lp.height = (int) Math.round(videoHeight * scale);
        lp.gravity = Gravity.CENTER;
        surfaceView.setLayoutParams(lp);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_INFO) {
            boolean show = logScroll.getVisibility() != View.VISIBLE;
            if (show) {
                logText.setText(LogBuffer.dump());
                logText.append("\n" + playback.stats() + "\n");
                logScroll.setVisibility(View.VISIBLE);
                logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
            } else {
                logScroll.setVisibility(View.GONE);
            }
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_BACK && logScroll.getVisibility() == View.VISIBLE) {
            logScroll.setVisibility(View.GONE);
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    // ---------------------------------------------------------------- surface

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        playback.setSurface(holder.getSurface());
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        playback.setSurface(holder.getSurface());
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        playback.setSurface(null);
    }

    // ---------------------------------------------------------------- helpers

    private void checkForUpdate() {
        Updater.Release release = Updater.checkForUpdate();
        if (release == null) {
            return;
        }
        main.post(() -> statusText.setText("Installing update " + release.versionCode + "…"));
        try {
            Updater.install(this, release);
        } catch (Throwable t) {
            LogBuffer.e(TAG, "Update failed", t);
        }
    }

    private String diagnosticsHeader() {
        return "AirPlay Receiver " + BuildConfig.VERSION_NAME + " (build " + BuildConfig.VERSION_CODE + ")\n"
                + "Device: " + Build.MANUFACTURER + " " + Build.MODEL + ", Android " + Build.VERSION.RELEASE
                + " (API " + Build.VERSION.SDK_INT + ")\n"
                + "Name: " + receiverName + "  Port: " + receiver.port() + "  Status: " + receiver.status() + "\n"
                + "State: " + uiState + "\n" + playback.stats();
    }

    private String deviceName() {
        String name = null;
        try {
            name = Settings.Global.getString(getContentResolver(), "device_name");
        } catch (Exception ignored) {
        }
        if (name == null || name.trim().isEmpty()) {
            name = "Fire TV";
        }
        return name.trim();
    }

    @SuppressWarnings("deprecation")
    private String wifiIp() {
        try {
            WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wifi != null && wifi.getConnectionInfo() != null) {
                int ip = wifi.getConnectionInfo().getIpAddress();
                if (ip != 0) {
                    return String.format(Locale.US, "%d.%d.%d.%d",
                            ip & 0xFF, (ip >> 8) & 0xFF, (ip >> 16) & 0xFF, (ip >> 24) & 0xFF);
                }
            }
            for (NetworkInterface nif : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nif.isUp() || nif.isLoopback()) {
                    continue;
                }
                for (InetAddress a : Collections.list(nif.getInetAddresses())) {
                    if (a instanceof Inet4Address && !a.isLoopbackAddress()) {
                        return a.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }
}
