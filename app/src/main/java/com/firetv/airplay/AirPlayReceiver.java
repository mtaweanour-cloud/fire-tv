package com.firetv.airplay;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;

import com.firetv.airplay.log.LogBuffer;
import com.github.serezhka.airplay.protocol.pairing.PairingIdentity;
import com.github.serezhka.airplay.server.AirPlayConfig;
import com.github.serezhka.airplay.server.discovery.AdvertisedReceiver;
import com.github.serezhka.airplay.server.internal.ControlServer;

import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Runs the AirPlay server and announces it on the network with Android's own mDNS service,
 * so iPhones list this TV under Screen Mirroring.
 */
final class AirPlayReceiver {

    private static final String TAG = "Receiver";

    private final Context context;
    private final String name;
    private final AndroidPlayback playback;

    private ControlServer controlServer;
    private NsdManager nsd;
    private NsdManager.RegistrationListener airplayListener;
    private NsdManager.RegistrationListener raopListener;
    private WifiManager.MulticastLock multicastLock;
    private volatile String status = "Starting…";
    private int port;

    AirPlayReceiver(Context context, String name, AndroidPlayback playback) {
        this.context = context.getApplicationContext();
        this.name = name;
        this.playback = playback;
    }

    String status() {
        return status;
    }

    int port() {
        return port;
    }

    /** Blocking; call off the main thread. */
    void start() throws Exception {
        WifiManager wifi = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        if (wifi != null) {
            multicastLock = wifi.createMulticastLock("airplay");
            multicastLock.setReferenceCounted(false);
            multicastLock.acquire();
        }

        AirPlayConfig config = new AirPlayConfig();
        config.setServerName(name);
        config.setWidth(1920);
        config.setHeight(1080);
        config.setFps(60);
        config.setHlsEnabled(false);
        config.setMdnsLoopback(false);
        config.setTrayEnabled(false);

        PairingIdentity identity = PairingIdentity.generate();
        controlServer = new ControlServer(config, playback, identity);
        controlServer.start();
        port = controlServer.getPort();
        LogBuffer.i(TAG, "AirPlay server listening on port " + port);

        String deviceId = deviceId();
        nsd = (NsdManager) context.getSystemService(Context.NSD_SERVICE);
        String features = AdvertisedReceiver.featuresTxt(false);

        Map<String, String> airplay = new LinkedHashMap<>();
        airplay.put("deviceid", deviceId);
        airplay.put("features", features);
        airplay.put("srcvers", AdvertisedReceiver.SOURCE_VERSION);
        airplay.put("flags", AdvertisedReceiver.MDNS_FLAGS);
        airplay.put("vv", Integer.toString(AdvertisedReceiver.VV));
        airplay.put("model", AdvertisedReceiver.MODEL);
        airplay.put("rhd", "5.6.0.0");
        airplay.put("pw", "false");
        airplay.put("pk", identity.publicKeyHex());

        Map<String, String> raop = new LinkedHashMap<>();
        raop.put("ch", "2");
        raop.put("cn", "1,3");
        raop.put("da", "true");
        raop.put("et", "0,3,5");
        raop.put("ek", "1");
        raop.put("ft", features);
        raop.put("am", AdvertisedReceiver.MODEL);
        raop.put("md", "0,1,2");
        raop.put("sr", "44100");
        raop.put("ss", "16");
        raop.put("sv", "false");
        raop.put("sm", "false");
        raop.put("tp", "UDP");
        raop.put("txtvers", "1");
        raop.put("sf", AdvertisedReceiver.MDNS_FLAGS);
        raop.put("vs", AdvertisedReceiver.SOURCE_VERSION);
        raop.put("vn", "65537");
        raop.put("pk", identity.publicKeyHex());

        airplayListener = register(name, "_airplay._tcp", airplay);
        raopListener = register(deviceId.replace(":", "") + "@" + name, "_raop._tcp", raop);
        status = "Waiting for network announcement…";
    }

    void stop() {
        if (nsd != null) {
            for (NsdManager.RegistrationListener l : new NsdManager.RegistrationListener[]{airplayListener, raopListener}) {
                if (l != null) {
                    try {
                        nsd.unregisterService(l);
                    } catch (Exception ignored) {
                    }
                }
            }
        }
        airplayListener = null;
        raopListener = null;
        if (controlServer != null) {
            controlServer.stop();
            controlServer = null;
        }
        playback.shutdown();
        if (multicastLock != null && multicastLock.isHeld()) {
            multicastLock.release();
        }
        LogBuffer.i(TAG, "Receiver stopped");
    }

    private NsdManager.RegistrationListener register(String serviceName, String type, Map<String, String> txt) {
        NsdServiceInfo info = new NsdServiceInfo();
        info.setServiceName(serviceName);
        info.setServiceType(type);
        info.setPort(port);
        for (Map.Entry<String, String> e : txt.entrySet()) {
            info.setAttribute(e.getKey(), e.getValue());
        }
        NsdManager.RegistrationListener listener = new NsdManager.RegistrationListener() {
            @Override
            public void onServiceRegistered(NsdServiceInfo registered) {
                LogBuffer.i(TAG, "Announced " + type + " as \"" + registered.getServiceName() + "\"");
                if (type.startsWith("_airplay")) {
                    status = "Ready";
                }
            }

            @Override
            public void onRegistrationFailed(NsdServiceInfo failed, int errorCode) {
                LogBuffer.e(TAG, "Could not announce " + type + " (error " + errorCode + ")", null);
                status = "Network announcement failed (error " + errorCode + ")";
            }

            @Override
            public void onServiceUnregistered(NsdServiceInfo unregistered) {
                LogBuffer.i(TAG, "Withdrew " + type);
            }

            @Override
            public void onUnregistrationFailed(NsdServiceInfo failed, int errorCode) {
                LogBuffer.w(TAG, "Could not withdraw " + type + " (error " + errorCode + ")");
            }
        };
        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener);
        return listener;
    }

    /** A stable, random, locally administered MAC-style id: iPhones use it to recognise this TV. */
    private String deviceId() {
        SharedPreferences prefs = context.getSharedPreferences("receiver", Context.MODE_PRIVATE);
        String id = prefs.getString("device_id", null);
        if (id == null) {
            byte[] mac = new byte[6];
            new SecureRandom().nextBytes(mac);
            mac[0] = (byte) ((mac[0] & 0xFC) | 0x02);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < mac.length; i++) {
                if (i > 0) {
                    sb.append(':');
                }
                sb.append(String.format(Locale.US, "%02X", mac[i]));
            }
            id = sb.toString();
            prefs.edit().putString("device_id", id).apply();
        }
        return id;
    }
}
