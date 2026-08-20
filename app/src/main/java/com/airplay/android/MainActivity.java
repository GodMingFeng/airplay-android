package com.airplay.android;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.net.Inet4Address;
import java.net.NetworkInterface;
import java.util.Collections;

public class MainActivity extends AppCompatActivity implements SurfaceHolder.Callback {

    private static final String TAG = "MainActivity";
    /** How long the overlay stays visible after the remote is used, in ms. */
    private static final long CONTROLS_TIMEOUT_MS = 6000;

    private FrameLayout root;
    private SurfaceView surfaceView;
    private LinearLayout controls;
    private Button btnToggle;
    private TextView statusText;
    private TextView ipText;
    private boolean serverRunning = false;
    /** Set when the user stops the server by hand, so returning to the app does not restart it. */
    private boolean userStopped = false;
    private int videoWidth;
    private int videoHeight;

    private final Runnable hideControls = new Runnable() {
        @Override
        public void run() {
            // Only get out of the way once there is actually a picture to look at
            if (videoWidth > 0) {
                controls.setVisibility(View.GONE);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);

        root = findViewById(R.id.root);
        surfaceView = findViewById(R.id.surface_view);
        controls = findViewById(R.id.controls);
        btnToggle = findViewById(R.id.btn_toggle);
        statusText = findViewById(R.id.status_text);
        ipText = findViewById(R.id.ip_text);

        surfaceView.getHolder().addCallback(this);

        // Keep the letterbox up to date when the window itself changes size
        root.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> applyVideoAspectRatio());

        VideoHolder.setVideoSizeListener((width, height) -> runOnUiThread(() -> {
            boolean firstFrame = videoWidth == 0;
            videoWidth = width;
            videoHeight = height;
            applyVideoAspectRatio();
            statusText.setText(getString(R.string.status_connected) + " · " + width + "x" + height);
            if (firstFrame) {
                // A mirroring session just began: give the whole screen to the video
                scheduleHideControls(0);
            }
        }));

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

    /**
     * The receiver is tied to the UI being on screen: mirroring can only be decoded into this
     * Activity's Surface, so the device advertises itself exactly while the app is visible.
     */
    @Override
    protected void onStart() {
        super.onStart();
        if (!userStopped) {
            startServer();
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        Log.i(TAG, "Activity no longer visible, stopping the receiver");
        stopServer();
        userStopped = false;
    }

    private void displayIpAddress() {
        String name = getString(R.string.airplay_device_name);
        try {
            String ip = getLocalIpAddress();
            ipText.setText(name + "  ·  " + (ip != null ? ip : "no network"));
        } catch (Exception e) {
            ipText.setText(name);
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

    /**
     * Sizes the surface so the decoded picture keeps its own aspect ratio inside the
     * window (letterbox). Without this the codec stretches every frame to the full
     * screen, so any resolution change on the sender shows up as a distorted image.
     */
    private void applyVideoAspectRatio() {
        if (videoWidth <= 0 || videoHeight <= 0) return;

        int containerWidth = root.getWidth();
        int containerHeight = root.getHeight();
        if (containerWidth <= 0 || containerHeight <= 0) return;

        float videoAspect = (float) videoWidth / videoHeight;
        float containerAspect = (float) containerWidth / containerHeight;

        int width;
        int height;
        if (videoAspect > containerAspect) {
            width = containerWidth;
            height = Math.round(containerWidth / videoAspect);
        } else {
            height = containerHeight;
            width = Math.round(containerHeight * videoAspect);
        }

        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) surfaceView.getLayoutParams();
        if (params.width == width && params.height == height) return;

        params.width = width;
        params.height = height;
        surfaceView.setLayoutParams(params);
        Log.i(TAG, "Video " + videoWidth + "x" + videoHeight + " letterboxed to " + width + "x" + height);
    }

    /** Brings the overlay back, then hides it again after a while. */
    private void revealControls() {
        controls.setVisibility(View.VISIBLE);
        scheduleHideControls(CONTROLS_TIMEOUT_MS);
    }

    private void scheduleHideControls(long delayMs) {
        controls.removeCallbacks(hideControls);
        controls.postDelayed(hideControls, delayMs);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        // Any remote key press brings the status/stop overlay back
        if (event.getAction() == KeyEvent.ACTION_DOWN && controls.getVisibility() != View.VISIBLE) {
            revealControls();
            return true;
        }
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            scheduleHideControls(CONTROLS_TIMEOUT_MS);
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            revealControls();
        }
        return super.onTouchEvent(event);
    }

    private void toggleServer() {
        if (!serverRunning) {
            userStopped = false;
            startServer();
        } else {
            userStopped = true;
            stopServer();
        }
    }

    private void startServer() {
        Intent intent = new Intent(this, AirPlayService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }

        serverRunning = true;
        btnToggle.setText(R.string.btn_stop);
        statusText.setText(R.string.status_running);
        displayIpAddress();
    }

    private void stopServer() {
        // stopService (rather than a command Intent) also works while the Activity is being
        // torn down, when starting a service would be treated as a background start
        stopService(new Intent(this, AirPlayService.class));

        serverRunning = false;
        videoWidth = 0;
        videoHeight = 0;
        btnToggle.setText(R.string.btn_start);
        statusText.setText(R.string.status_idle);
        controls.setVisibility(View.VISIBLE);
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
        VideoHolder.setVideoSizeListener(null);
        controls.removeCallbacks(hideControls);
    }
}
