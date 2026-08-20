package com.airplay.android.server;

import android.util.Log;

import com.airplay.android.VideoCallbackInterface;
import com.github.serezhka.jap2lib.AirPlay;

import java.io.DataInputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class MirroringReceiver implements Runnable {
    private static final String TAG = "MirroringReceiver";
    private static final int HEADER_SIZE = 128;

    private final int port;
    private final AirPlay airPlay;
    private final VideoCallbackInterface callback;
    private ServerSocket serverSocket;
    private byte[] spsPps = new byte[0];

    public MirroringReceiver(int port, AirPlay airPlay, VideoCallbackInterface callback) {
        this.port = port;
        this.airPlay = airPlay;
        this.callback = callback;
    }

    @Override
    public void run() {
        try {
            serverSocket = new ServerSocket();
            serverSocket.setReuseAddress(true);
            serverSocket.bind(new InetSocketAddress(port));
            Log.i(TAG, "Mirroring receiver listening on port " + port);

            Socket client = serverSocket.accept();
            Log.i(TAG, "Mirroring client connected");
            DataInputStream in = new DataInputStream(client.getInputStream());

            byte[] headerBuf = new byte[HEADER_SIZE];

            while (!Thread.currentThread().isInterrupted()) {
                // Read header (128 bytes, little-endian)
                int headerRead = 0;
                while (headerRead < HEADER_SIZE) {
                    int n = in.read(headerBuf, headerRead, HEADER_SIZE - headerRead);
                    if (n < 0) {
                        Log.i(TAG, "Mirroring client disconnected");
                        return;
                    }
                    headerRead += n;
                }

                ByteBuffer header = ByteBuffer.wrap(headerBuf).order(ByteOrder.LITTLE_ENDIAN);
                int payloadSize = header.getInt(0);
                short payloadType = (short) (header.getShort(4) & 0xFF);
                // short payloadOption = header.getShort(6);
                Log.i(TAG, "Header: payloadSize=" + payloadSize + ", payloadType=" + payloadType);

                if (payloadSize <= 0 || payloadSize > 2 * 1024 * 1024) {
                    Log.w(TAG, "Invalid payload size: " + payloadSize);
                    continue;
                }

                // Read payload
                byte[] payload = new byte[payloadSize];
                int payloadRead = 0;
                while (payloadRead < payloadSize) {
                    int n = in.read(payload, payloadRead, payloadSize - payloadRead);
                    if (n < 0) {
                        Log.i(TAG, "Mirroring client disconnected during payload read");
                        return;
                    }
                    payloadRead += n;
                }

                if (payloadType == 0) {
                    // Video data
                    try {
                        airPlay.decryptVideo(payload);
                        processVideo(payload);
                        Log.i(TAG, "Video frame OK, size: " + payload.length);
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
        } catch (Exception e) {
            if (!Thread.currentThread().isInterrupted()) {
                Log.e(TAG, "Mirroring receiver error", e);
            }
        }
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

    private void processVideo(byte[] payload) {
        // Convert NALU length prefix from 4-byte big-endian to Annex B (0x00000001)
        int offset = 0;
        int naluCount = 0;
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
            naluCount++;
        }

        Log.i(TAG, "processVideo: " + naluCount + " NALUs, total=" + payload.length + ", first4=" +
                String.format("%02x%02x%02x%02x", payload[0], payload[1], payload[2], payload[3]) +
                ", naluType=" + (payload.length > 4 ? (payload[4] & 0x1F) : -1));

        // Check NALU type and prepend SPS/PPS for IDR frames
        int naluType = payload.length > 4 ? (payload[4] & 0x1F) : 0;
        if (naluType == 5 && spsPps.length > 0) {
            // IDR frame - prepend SPS/PPS
            byte[] combined = new byte[spsPps.length + payload.length];
            System.arraycopy(spsPps, 0, combined, 0, spsPps.length);
            System.arraycopy(payload, 0, combined, spsPps.length, payload.length);
            Log.i(TAG, "IDR frame: prepended SPS/PPS (" + spsPps.length + " bytes), total=" + combined.length);
            callback.onVideo(combined);
        } else {
            callback.onVideo(payload);
        }
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
        this.spsPps = new byte[sps.length + pps.length + 8];
        spsPps[0] = 0; spsPps[1] = 0; spsPps[2] = 0; spsPps[3] = 1;
        System.arraycopy(sps, 0, spsPps, 4, sps.length);
        spsPps[sps.length + 4] = 0; spsPps[sps.length + 5] = 0;
        spsPps[sps.length + 6] = 0; spsPps[sps.length + 7] = 1;
        System.arraycopy(pps, 0, spsPps, 8 + sps.length, pps.length);

        Log.i(TAG, "SPS/PPS: sps=" + sps.length + " pps=" + pps.length);
        callback.onSpsPps(this.spsPps);
    }


    private static String bytesToHex(byte[] data, int len) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len; i++) {
            sb.append(String.format("%02x", data[i] & 0xFF));
        }
        return sb.toString();
    }
}
