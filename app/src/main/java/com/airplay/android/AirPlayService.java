package com.airplay.android;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import com.airplay.android.server.AirPlayServer;

public class AirPlayService extends Service {

    private static final String TAG = "AirPlayService";
    private static final String CHANNEL_ID = "airplay_channel";
    private static final int NOTIFICATION_ID = 1;

    private AirPlayServer airPlayServer;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Notification notification = buildNotification("AirPlay Receiver is running");
        startForeground(NOTIFICATION_ID, notification);

        if (intent != null && "STOP".equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }

        startServer();
        return START_STICKY;
    }

    private void startServer() {
        new Thread(() -> {
            try {
                String serverName = "Android AirPlay";
                int airPlayPort = 7000;
                int airTunesPort = 5000;

                airPlayServer = new AirPlayServer(serverName, airPlayPort, airTunesPort,
                        new VideoCallback());
                airPlayServer.start();
                Log.i(TAG, "AirPlay server started");
            } catch (Exception e) {
                Log.e(TAG, "Failed to start AirPlay server", e);
                stopSelf();
            }
        }).start();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (airPlayServer != null) {
            airPlayServer.stop();
        }
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

    private Notification buildNotification(String text) {
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }
        return builder
                .setContentTitle("AirPlay Receiver")
                .setContentText(text)
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
