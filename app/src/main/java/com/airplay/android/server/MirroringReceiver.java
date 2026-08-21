package com.airplay.android.server;

import android.os.Process;
import android.util.Log;

import com.airplay.android.VideoCallbackInterface;
import com.github.serezhka.jap2lib.AirPlay;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class MirroringReceiver implements Runnable {
    private static final String TAG = "MirroringReceiver";
    private static final int HEADER_SIZE = 128;
    private static final int MAX_PAYLOAD_SIZE = 2 * 1024 * 1024;
    /**
     * Mirroring bursts several hundred kilobytes at a time on a key frame. A receive window this
     * large keeps the sender from stalling while a frame is being decrypted.
     */
    private static final int SOCKET_RECEIVE_BUFFER = 1024 * 1024;

    private final int port;
    private final AirPlay airPlay;
    private final VideoCallbackInterface callback;
    private ServerSocket serverSocket;
    /** Sender clock of the first frame, so presentation timestamps start at zero. */
    private long ptsBaseUs;

    public MirroringReceiver(int port, AirPlay airPlay, VideoCallbackInterface callback) {
        this.port = port;
        this.airPlay = airPlay;
        this.callback = callback;
    }

    @Override
    public void run() {
        // This thread does nothing but drain the socket and decrypt; if it loses the CPU the
        // sender's window fills up and the picture stutters, so it runs above the default
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY);
        try {
            serverSocket = new ServerSocket();
            serverSocket.setReuseAddress(true);
            // Has to be set before bind so that accepted sockets inherit the scaled window
            serverSocket.setReceiveBufferSize(SOCKET_RECEIVE_BUFFER);
            serverSocket.bind(new InetSocketAddress(port));
            Log.i(TAG, "Mirroring receiver listening on port " + port);

            Socket client = serverSocket.accept();
            client.setTcpNoDelay(true);
            Log.i(TAG, "Mirroring client connected");
            DataInputStream in = new DataInputStream(
                    new BufferedInputStream(client.getInputStream(), 64 * 1024));

            byte[] headerBuf = new byte[HEADER_SIZE];

            while (!Thread.currentThread().isInterrupted()) {
                // Read header (128 bytes, little-endian)
                in.readFully(headerBuf, 0, HEADER_SIZE);

                ByteBuffer header = ByteBuffer.wrap(headerBuf).order(ByteOrder.LITTLE_ENDIAN);
                int payloadSize = header.getInt(0);
                short payloadType = (short) (header.getShort(4) & 0xFF);
                // short payloadOption = header.getShort(6);

                if (payloadSize <= 0 || payloadSize > MAX_PAYLOAD_SIZE) {
                    Log.w(TAG, "Invalid payload size: " + payloadSize);
                    continue;
                }

                // Read payload
                byte[] payload = new byte[payloadSize];
                in.readFully(payload, 0, payloadSize);

                if (payloadType == 0) {
                    // Video data. The header carries the sender's NTP clock at offset 8, which is
                    // the only timestamp source for the decoder.
                    long frameUs = ntpToMicros(header.getLong(8));
                    long ptsUs = 0;
                    if (ptsBaseUs == 0) {
                        ptsBaseUs = frameUs;
                    } else {
                        ptsUs = frameUs - ptsBaseUs;
                    }
                    try {
                        airPlay.decryptVideo(payload);
                        processVideo(payload, ptsUs);
                    } catch (Exception e) {
                        Log.e(TAG, "Error video, size: " + payload.length, e);
                    }
                } else if (payloadType == 1) {
                    // SPS/PPS packet: its 128 byte header also carries the source screen size
                    processVideoSize(header);
                    processSPSPPS(payload);
                } else {
                    // Ignore other payload types (including type=5 which is plist metadata, not audio)
                    if (payloadType != 5) {
                        Log.i(TAG, "Unknown payload type: " + payloadType + ", size: " + payloadSize);
                    }
                }
            }
        } catch (EOFException e) {
            Log.i(TAG, "Mirroring client disconnected");
        } catch (Exception e) {
            if (!Thread.currentThread().isInterrupted()) {
                Log.e(TAG, "Mirroring receiver error", e);
            }
        } finally {
            // Without this the listening socket outlives the thread and the next SETUP
            // cannot bind the mirroring port again
            if (serverSocket != null) {
                try { serverSocket.close(); } catch (Exception e) { /* ignore */ }
            }
        }
    }

    /** Converts an NTP timestamp as carried in the mirroring header into microseconds. */
    private static long ntpToMicros(long ntp) {
        long seconds = (ntp >>> 32) & 0xFFFFFFFFL;
        long fraction = ntp & 0xFFFFFFFFL;
        return seconds * 1_000_000L + fraction * 1_000_000L / 0x1_0000_0000L;
    }

    /**
     * The header of a type=1 (SPS/PPS) packet carries the sender's video geometry as
     * little-endian floats: source size at offset 40/44 and encoded size at offset 56/60.
     */
    private void processVideoSize(ByteBuffer header) {
        int width = (int) header.getFloat(40);
        int height = (int) header.getFloat(44);
        int encodedWidth = (int) header.getFloat(56);
        int encodedHeight = (int) header.getFloat(60);

        if (!isPlausibleSize(width, height)) {
            width = encodedWidth;
            height = encodedHeight;
        }

        Log.i(TAG, "Video size from header: source=" + (int) header.getFloat(40) + "x" + (int) header.getFloat(44)
                + ", encoded=" + encodedWidth + "x" + encodedHeight);

        if (isPlausibleSize(width, height)) {
            callback.onVideoFormat(width, height);
        }
    }

    private static boolean isPlausibleSize(int width, int height) {
        return width >= 16 && height >= 16 && width <= 8192 && height <= 8192;
    }

    private void processVideo(byte[] payload, long ptsUs) {
        // Convert NALU length prefix from 4-byte big-endian to Annex B (0x00000001)
        int offset = 0;
        while (offset < payload.length) {
            if (offset + 4 > payload.length) break;
            int naluLen = ((payload[offset] & 0xFF) << 24)
                    | ((payload[offset + 1] & 0xFF) << 16)
                    | ((payload[offset + 2] & 0xFF) << 8)
                    | (payload[offset + 3] & 0xFF);

            if (naluLen <= 0 || offset + 4 + naluLen > payload.length) {
                Log.w(TAG, "NALU break: naluLen=" + naluLen + ", offset=" + offset + ", total=" + payload.length);
                if (naluLen > 0 && payload.length - offset - 4 > 4) {
                    Log.w(TAG, "NALU length error");
                    return;
                }
                break;
            }

            // Replace length prefix with Annex B start code
            payload[offset] = 0;
            payload[offset + 1] = 0;
            payload[offset + 2] = 0;
            payload[offset + 3] = 1;
            offset += 4 + naluLen;
        }

        callback.onVideo(payload, ptsUs);
    }

    private void processSPSPPS(byte[] payload) {
        if (payload.length < 10) return;

        ByteBuffer buf = ByteBuffer.wrap(payload);
        buf.position(6); // Skip 6 bytes

        short spsLen = buf.getShort();
        byte[] sps = new byte[spsLen & 0xFFFF];
        buf.get(sps);

        buf.get(); // skip 1 byte (pps count)

        short ppsLen = buf.getShort();
        byte[] pps = new byte[ppsLen & 0xFFFF];
        buf.get(pps);

        // Build Annex B format: [00 00 00 01] [SPS] [00 00 00 01] [PPS]
        byte[] spsPps = new byte[sps.length + pps.length + 8];
        spsPps[0] = 0; spsPps[1] = 0; spsPps[2] = 0; spsPps[3] = 1;
        System.arraycopy(sps, 0, spsPps, 4, sps.length);
        spsPps[sps.length + 4] = 0; spsPps[sps.length + 5] = 0;
        spsPps[sps.length + 6] = 0; spsPps[sps.length + 7] = 1;
        System.arraycopy(pps, 0, spsPps, 8 + sps.length, pps.length);

        Log.i(TAG, "SPS/PPS: sps=" + sps.length + " pps=" + pps.length);
        callback.onSpsPps(spsPps);
    }


    private static String bytesToHex(byte[] data, int len) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len; i++) {
            sb.append(String.format("%02x", data[i] & 0xFF));
        }
        return sb.toString();
    }
}
