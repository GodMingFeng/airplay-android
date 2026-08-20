package com.airplay.android.server;

import android.util.Log;

import com.airplay.android.VideoCallbackInterface;
import com.github.serezhka.jap2lib.AirPlayBonjour;

public class AirPlayServer {
    private static final String TAG = "AirPlayServer";

    private final AirPlayBonjour airPlayBonjour;
    private final RtspControlServer controlServer;
    private final String serverName;
    private final int airPlayPort;
    private final int airTunesPort;

    public AirPlayServer(String serverName, int airPlayPort, int airTunesPort,
                         VideoCallbackInterface callback) {
        this.serverName = serverName;
        this.airPlayPort = airPlayPort;
        this.airTunesPort = airTunesPort;
        this.airPlayBonjour = new AirPlayBonjour(serverName);
        this.controlServer = new RtspControlServer(airPlayPort, airTunesPort, callback);
    }

    public void start() throws Exception {
        Log.i(TAG, "Starting AirPlay Bonjour...");
        airPlayBonjour.start(airPlayPort, airTunesPort);
        Log.i(TAG, "Starting RTSP control server on port " + airTunesPort + "...");
        controlServer.start();
    }

    public void stop() {
        Log.i(TAG, "Stopping AirPlay server...");
        try { airPlayBonjour.stop(); } catch (Exception e) { Log.w(TAG, "Error stopping bonjour", e); }
        controlServer.stop();
    }
}
