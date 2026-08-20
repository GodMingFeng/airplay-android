package com.airplay.android;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.net.Inet4Address;
import java.net.NetworkInterface;
import java.util.Collections;

public class MainActivity extends AppCompatActivity implements SurfaceHolder.Callback {

    private static final String TAG = "MainActivity";
    private SurfaceView surfaceView;
    private Button btnToggle;
    private TextView statusText;
    private TextView ipText;
    private boolean serverRunning = false;
    private WifiManager.MulticastLock multicastLock;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);

        surfaceView = findViewById(R.id.surface_view);
        btnToggle = findViewById(R.id.btn_toggle);
        statusText = findViewById(R.id.status_text);
        ipText = findViewById(R.id.ip_text);

        surfaceView.getHolder().addCallback(this);

        btnToggle.setOnClickListener(v -> toggleServer());

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
            }
        }

        displayIpAddress();
    }

    private void displayIpAddress() {
        try {
            String ip = getLocalIpAddress();
            if (ip != null) {
                ipText.setText("IP: " + ip);
            } else {
                ipText.setText("IP: Unknown (check WiFi)");
            }
        } catch (Exception e) {
            ipText.setText("IP: Unknown");
        }
    }

    private String getLocalIpAddress() {
        try {
            for (NetworkInterface intf : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                for (java.net.InetAddress addr : Collections.list(intf.getInetAddresses())) {
                    if (!addr.isLoopbackAddress() && addr instanceof Inet4Address) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error getting IP", e);
        }
        return null;
    }

    private void toggleServer() {
        if (!serverRunning) {
            startServer();
        } else {
            stopServer();
        }
    }

    private void startServer() {
        // Acquire multicast lock for mDNS
        WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        if (wifi != null) {
            multicastLock = wifi.createMulticastLock("airplay_mdns");
            multicastLock.setReferenceCounted(true);
            multicastLock.acquire();
        }

        Intent intent = new Intent(this, AirPlayService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }

        serverRunning = true;
        btnToggle.setText("Stop Server");
        statusText.setText("Status: Running - Waiting for connection...");
    }

    private void stopServer() {
        Intent intent = new Intent(this, AirPlayService.class);
        intent.setAction("STOP");
        startService(intent);

        if (multicastLock != null && multicastLock.isHeld()) {
            multicastLock.release();
            multicastLock = null;
        }

        serverRunning = false;
        btnToggle.setText("Start Server");
        statusText.setText("Status: Idle");
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        Log.i(TAG, "Surface created");
        VideoHolder.setSurface(holder.getSurface());
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        Log.i(TAG, "Surface changed: " + width + "x" + height);
        VideoHolder.setSurface(holder.getSurface());
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        Log.i(TAG, "Surface destroyed");
        VideoHolder.release();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (serverRunning) {
            stopServer();
        }
    }
}
