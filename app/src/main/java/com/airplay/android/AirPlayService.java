package com.airplay.android;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;

import com.airplay.android.server.AirPlayServer;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Hosts the AirPlay receiver for as long as the app is open.
 *
 * <p>The receiver only advertises itself while the UI is around, since mirroring needs the
 * Activity's Surface to decode into. Leaving the app therefore takes the device off the
 * AirPlay list. Running as a foreground service is still worthwhile: it keeps the multicast
 * lock (mDNS), the Wi-Fi lock (power save would otherwise stall the streams) and the network
 * threads at foreground priority while a session is live.
 */
public class AirPlayService extends Service {

    private static final String TAG = "AirPlayService";
    private static final String CHANNEL_ID = "airplay_channel";
    private static final int NOTIFICATION_ID = 1;

    /**
     * Start and stop are serialised on one thread that outlives the service instance, because
     * shutting the Netty groups down takes seconds. Leaving the app and coming straight back
     * would otherwise start a new instance while the old one is still tearing the server down,
     * and the new start would be dropped as a duplicate.
     */
    private static final ExecutorService LIFECYCLE = Executors.newSingleThreadExecutor();
    private static AirPlayServer sServer;

    private WifiManager.MulticastLock multicastLock;
    private WifiManager.WifiLock wifiLock;
    private PowerManager.WakeLock wakeLock;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String serverName = resolveServiceName(this);
        String deviceId = resolveDeviceId(this);
        startForeground(NOTIFICATION_ID, buildNotification(serverName + " · starting"));

        acquireLocks();
        LIFECYCLE.submit(() -> startServer(serverName, deviceId));
        return START_NOT_STICKY;
    }

    /**
     * Builds the name broadcast over mDNS from the device's own name, so two receivers on the
     * same network stay apart. A fixed name made jmDNS fall back to "name (2)" as soon as the
     * app ran on a second device.
     *
     * <p>{@code global device_name} is what the user typed in Settings; Android TV boxes that
     * leave it empty usually still carry the Bluetooth name, and the model is the last resort.
     */
    static String resolveServiceName(Context context) {
        String deviceName = Settings.Global.getString(
                context.getContentResolver(), Settings.Global.DEVICE_NAME);
        if (TextUtils.isEmpty(deviceName)) {
            deviceName = Settings.Secure.getString(context.getContentResolver(), "bluetooth_name");
        }
        if (TextUtils.isEmpty(deviceName)) {
            deviceName = Build.MODEL;
        }
        if (TextUtils.isEmpty(deviceName)) {
            deviceName = context.getString(R.string.airplay_fallback_name);
        }
        return deviceName.trim() + " " + context.getString(R.string.airplay_name_suffix);
    }

    /**
     * A stand-in MAC address, derived so it stays the same across restarts but differs between
     * installs. iOS pairs the {@code _airplay} and {@code _raop} records of one receiver by this
     * id, so the value that used to be hardcoded made two devices look like a single one.
     */
    private static String resolveDeviceId(Context context) {
        String androidId = Settings.Secure.getString(
                context.getContentResolver(), Settings.Secure.ANDROID_ID);
        String seed = TextUtils.isEmpty(androidId) ? Build.FINGERPRINT : androidId;
        byte[] digest;
        try {
            digest = MessageDigest.getInstance("SHA-256").digest(seed.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is guaranteed to be present", e);
        }
        byte[] mac = Arrays.copyOf(digest, 6);
        // Mark it locally administered and unicast, the shape a made-up MAC is meant to have
        mac[0] = (byte) ((mac[0] & 0xFE) | 0x02);
        StringBuilder hex = new StringBuilder(12);
        for (byte b : mac) {
            hex.append(String.format("%02X", b));
        }
        return hex.toString();
    }

    /** Runs on {@link #LIFECYCLE}. */
    private void startServer(String serverName, String deviceId) {
        if (sServer != null) {
            Log.i(TAG, "Server already running, ignoring duplicate start");
            return;
        }
        try {
            AirPlayServer server = new AirPlayServer(serverName, deviceId, 7000, 5000, new VideoCallback());
            server.start();
            sServer = server;
            Log.i(TAG, "AirPlay server started as '" + serverName + "' (id " + deviceId + ")");
            updateNotification(serverName + " · ready");
        } catch (Exception e) {
            Log.e(TAG, "Failed to start AirPlay server", e);
            updateNotification("Failed to start, will retry on next launch");
            stopSelf();
        }
    }

    /** Runs on {@link #LIFECYCLE}. */
    private static void stopServer() {
        if (sServer == null) return;
        sServer.stop();
        sServer = null;
        Log.i(TAG, "AirPlay server stopped");
    }

    private void acquireLocks() {
        WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        if (wifi != null) {
            // mDNS is multicast: without this lock the Wi-Fi stack drops the packets
            multicastLock = wifi.createMulticastLock("airplay_mdns");
            multicastLock.setReferenceCounted(false);
            multicastLock.acquire();

            wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "airplay_wifi");
            wifiLock.setReferenceCounted(false);
            wifiLock.acquire();
        }

        PowerManager power = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (power != null) {
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "airplay:receiver");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire();
        }
        Log.i(TAG, "Locks acquired (multicast/wifi/wake)");
    }

    private void releaseLocks() {
        if (multicastLock != null && multicastLock.isHeld()) multicastLock.release();
        if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        multicastLock = null;
        wifiLock = null;
        wakeLock = null;
    }

    /**
     * The user swiped the app away: stop advertising instead of lingering in the background.
     */
    @Override
    public void onTaskRemoved(Intent rootIntent) {
        Log.i(TAG, "Task removed, shutting the receiver down");
        stopSelf();
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        LIFECYCLE.submit(AirPlayService::stopServer);
        releaseLocks();
        Log.i(TAG, "AirPlay service destroyed");
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(getString(R.string.notification_channel_desc));
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.createNotificationChannel(channel);
    }

    private void updateNotification(String text) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(NOTIFICATION_ID, buildNotification(text));
    }

    private Notification buildNotification(String text) {
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return builder
                .setContentTitle(getString(R.string.app_name))
                .setContentText(text)
                .setContentIntent(contentIntent)
                .setSmallIcon(android.R.drawable.ic_menu_share)
                .setOngoing(true)
                .build();
    }

    private static class VideoCallback implements VideoCallbackInterface {
        @Override
        public void onVideo(byte[] video, long ptsUs) {
            VideoHolder.onVideoData(video, ptsUs);
        }

        @Override
        public void onVideoFormat(int width, int height) {
            Log.i("AirPlayService", "Video format: " + width + "x" + height);
            VideoHolder.onVideoFormat(width, height);
        }

        @Override
        public void onAudio(byte[] audio) {
            VideoHolder.onAudioData(audio);
        }

        @Override
        public void onAudioFormat(int sampleRate, int channels) {
            Log.i("AirPlayService", "Audio format: " + sampleRate + "Hz " + channels + "ch");
        }

        @Override
        public void onSpsPps(byte[] spsPps) {
            VideoHolder.onSpsPpsData(spsPps);
        }
    }
}
