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
import android.util.Log;

import com.airplay.android.server.AirPlayServer;

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
        startForeground(NOTIFICATION_ID, buildNotification(
                getString(R.string.airplay_device_name) + " · starting"));

        acquireLocks();
        String serverName = getString(R.string.airplay_device_name);
        LIFECYCLE.submit(() -> startServer(serverName));
        return START_NOT_STICKY;
    }

    /** Runs on {@link #LIFECYCLE}. */
    private void startServer(String serverName) {
        if (sServer != null) {
            Log.i(TAG, "Server already running, ignoring duplicate start");
            return;
        }
        try {
            AirPlayServer server = new AirPlayServer(serverName, 7000, 5000, new VideoCallback());
            server.start();
            sServer = server;
            Log.i(TAG, "AirPlay server started as '" + serverName + "'");
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
                CHANNEL_ID, "AirPlay Service", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("AirPlay Receiver Service");
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
                .setContentTitle("AirPlay Receiver")
                .setContentText(text)
                .setContentIntent(contentIntent)
                .setSmallIcon(android.R.drawable.ic_menu_share)
                .setOngoing(true)
                .build();
    }

    private static class VideoCallback implements VideoCallbackInterface {
        @Override
        public void onVideo(byte[] video) {
            VideoHolder.onVideoData(video);
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
