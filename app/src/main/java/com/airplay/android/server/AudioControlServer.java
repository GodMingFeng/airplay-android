package com.airplay.android.server;

import android.util.Log;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;

public class AudioControlServer implements Runnable {
    private static final String TAG = "AudioControlServer";

    private final Object monitor;
    private DatagramSocket socket;
    private int port;

    public AudioControlServer(Object monitor) {
        this.monitor = monitor;
    }

    @Override
    public void run() {
        try {
            socket = new DatagramSocket(new InetSocketAddress(0));
            port = socket.getLocalPort();
            Log.i(TAG, "Audio control server listening on port " + port);

            synchronized (monitor) {
                monitor.notifyAll();
            }

            byte[] buf = new byte[1024];
            while (!Thread.currentThread().isInterrupted()) {
                DatagramPacket packet = new DatagramPacket(buf, buf.length);
                socket.receive(packet);
                // Audio control packets - just receive and acknowledge
                if (packet.getLength() >= 2) {
                    int type = packet.getData()[1] & ~0x80;
                    Log.d(TAG, "Audio control packet, type: " + type + ", len: " + packet.getLength());
                }
            }
        } catch (Exception e) {
            if (!Thread.currentThread().isInterrupted()) {
                Log.e(TAG, "Audio control server error", e);
            }
        } finally {
            if (socket != null) socket.close();
        }
    }

    public int getPort() {
        return port;
    }
}
