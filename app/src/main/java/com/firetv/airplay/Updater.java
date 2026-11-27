package com.firetv.airplay;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Build;

import com.firetv.airplay.log.LogBuffer;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Checks the GitHub releases of this app and installs a newer build. */
final class Updater {

    static final class Release {
        final int versionCode;
        final String apkUrl;

        Release(int versionCode, String apkUrl) {
            this.versionCode = versionCode;
            this.apkUrl = apkUrl;
        }
    }

    private static final String TAG = "Updater";

    private Updater() {
    }

    /** @return a newer release, or null. Blocking. */
    static Release checkForUpdate() {
        try {
            String json = new String(get("https://api.github.com/repos/" + BuildConfig.GITHUB_REPO + "/releases/latest"),
                    StandardCharsets.UTF_8);
            JSONObject release = new JSONObject(json);
            String tag = release.getString("tag_name");
            int version = Integer.parseInt(tag.replaceAll("[^0-9]", ""));
            if (version <= BuildConfig.VERSION_CODE) {
                return null;
            }
            JSONArray assets = release.getJSONArray("assets");
            for (int i = 0; i < assets.length(); i++) {
                JSONObject asset = assets.getJSONObject(i);
                if (asset.getString("name").endsWith(".apk")) {
                    LogBuffer.i(TAG, "Update available: build " + version);
                    return new Release(version, asset.getString("browser_download_url"));
                }
            }
        } catch (Exception e) {
            LogBuffer.w(TAG, "Update check failed: " + e);
        }
        return null;
    }

    /** Downloads and hands the APK to the system installer. Blocking. */
    static void install(Context context, Release release) throws Exception {
        LogBuffer.i(TAG, "Downloading build " + release.versionCode);
        byte[] apk = get(release.apkUrl);
        PackageInstaller installer = context.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params =
                new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        int sessionId = installer.createSession(params);
        try (PackageInstaller.Session session = installer.openSession(sessionId)) {
            try (OutputStream out = session.openWrite("update.apk", 0, apk.length)) {
                out.write(apk);
                session.fsync(out);
            }
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 31) {
                flags |= PendingIntent.FLAG_MUTABLE;
            }
            PendingIntent result = PendingIntent.getBroadcast(context, sessionId,
                    new Intent(context, InstallResultReceiver.class), flags);
            session.commit(result.getIntentSender());
        }
        LogBuffer.i(TAG, "Update handed to the installer");
    }

    private static byte[] get(String url) throws Exception {
        for (int redirects = 0; redirects < 5; redirects++) {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(10_000);
            c.setReadTimeout(30_000);
            c.setInstanceFollowRedirects(false);
            c.setRequestProperty("User-Agent", "FireTV-AirPlay/" + BuildConfig.VERSION_CODE);
            c.setRequestProperty("Accept", "application/vnd.github+json, application/octet-stream");
            int code = c.getResponseCode();
            if (code >= 300 && code < 400 && c.getHeaderField("Location") != null) {
                url = new URL(new URL(url), c.getHeaderField("Location")).toString();
                c.disconnect();
                continue;
            }
            if (code != 200) {
                c.disconnect();
                throw new IllegalStateException("HTTP " + code + " for " + url);
            }
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[64 * 1024];
                for (int n; (n = in.read(buf)) != -1; ) {
                    out.write(buf, 0, n);
                }
                return out.toByteArray();
            } finally {
                c.disconnect();
            }
        }
        throw new IllegalStateException("Too many redirects");
    }
}
