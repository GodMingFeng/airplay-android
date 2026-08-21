package com.airplay.android.server;

import android.util.Log;

import com.airplay.android.AvSync;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;

/**
 * RAOP control channel. Its sync packets are what make lip sync possible: they carry the sender's
 * NTP time next to the audio RTP timestamp it corresponds to, which is the only bridge between the
 * audio timeline and the NTP stamps on the mirroring video frames.
 */
public class AudioControlServer implements Runnable {
    private static final String TAG = "AudioControlServer";

    /** RAOP payload type of a timing sync packet. */
    private static final int TYPE_SYNC = 84;
    /** Payload type of a retransmitted audio packet, handled by the audio receiver instead. */
    private static final int TYPE_RESEND = 86;
    /**
     * The RAOP timestamps are in NTP's 1900 epoch while the mirroring headers count from the
     * sender's own zero, so the two differ by exactly this. Measured on the wire as well: the very
     * first attempt put the video 2208988800s away from the audio, which is this constant to the
     * second.
     */
    private static final long SECONDS_1900_TO_1970 = 2208988800L;

    private final Object monitor;
    private DatagramSocket socket;
    private int port;
    private int syncCount;

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

                int len = packet.getLength();
                if (len < 2) continue;
                byte[] data = packet.getData();
                int type = data[1] & 0x7F;

                if (type == TYPE_SYNC && len >= 20) {
                    handleSync(data, len);
                } else if (type != TYPE_RESEND) {
                    Log.d(TAG, "Audio control packet, type: " + type + ", len: " + len);
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

    /**
     * Sync packet layout: the NTP time at bytes 8..15 and the RTP timestamp it refers to at
     * 16..19. Bytes 4..7 hold the same instant minus the announced latency, which is of no use
     * here because the delay of our own output is measured directly.
     */
    private void handleSync(byte[] data, int len) {
        long ntp = readLong(data, 8);
        long rtpNow = readInt(data, 16);
        long senderUs = ntpToMicros(ntp);
        AvSync.onAudioSync(rtpNow, senderUs);

        syncCount++;
        if (syncCount <= 3 || syncCount % 60 == 0) {
            Log.i(TAG, "Sync #" + syncCount + ": rtp=" + rtpNow + ", senderNtp=" + senderUs
                    + "us, len=" + len);
        }
    }

    /** Converts an NTP timestamp onto the same origin the mirroring headers use. */
    private static long ntpToMicros(long ntp) {
        long seconds = ((ntp >>> 32) & 0xFFFFFFFFL) - SECONDS_1900_TO_1970;
        long fraction = ntp & 0xFFFFFFFFL;
        return seconds * 1_000_000L + fraction * 1_000_000L / 0x1_0000_0000L;
    }

    private static long readLong(byte[] data, int offset) {
        long value = 0;
        for (int i = 0; i < 8; i++) {
            value = (value << 8) | (data[offset + i] & 0xFFL);
        }
        return value;
    }

    private static long readInt(byte[] data, int offset) {
        return ((data[offset] & 0xFFL) << 24)
                | ((data[offset + 1] & 0xFFL) << 16)
                | ((data[offset + 2] & 0xFFL) << 8)
                | (data[offset + 3] & 0xFFL);
    }

    public int getPort() {
        return port;
    }
}
