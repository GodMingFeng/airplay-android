package com.airplay.android.server;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.util.Log;

import com.airplay.android.VideoCallbackInterface;
import com.github.serezhka.jap2lib.AirPlay;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Audio receiver - follows AirPlayServer-master reference implementation:
 * - RTP header parsing (sequence number, timestamp)
 * - Keepalive packet filtering (16-byte magic {0x00,0x68,0x34,0x00})
 * - AES-128-CBC decryption (per-packet, reset IV each time)
 * - AAC-ELD decoding via MediaCodec
 * - Simple sequence-based reorder buffer (ring buffer, 64 slots)
 */
public class AudioReceiver implements Runnable {
    private static final String TAG = "AudioReceiver";
    private static final int BUFFER_SIZE = 64; // Ring buffer size (matches reference)

    private final AirPlay airPlay;
    private final VideoCallbackInterface callback;
    private final Object monitor;
    private final int sampleRate;
    private final int channels;
    private final boolean isAacEld;
    private DatagramSocket socket;
    private int port;
    private MediaCodec decoder;
    private boolean decoderStarted = false;

    // Ring buffer for jitter handling (like reference C++ raop_buffer)
    private final byte[][] pcmBuffer = new byte[BUFFER_SIZE][];
    private final int[] seqNumbers = new int[BUFFER_SIZE];
    private final boolean[] available = new boolean[BUFFER_SIZE];
    private int firstSeq = -1;
    private int lastSeq = -1;
    private boolean bufferEmpty = true;

    public AudioReceiver(AirPlay airPlay, VideoCallbackInterface callback, Object monitor,
                         int sampleRate, int channels, boolean isAacEld) {
        this.airPlay = airPlay;
        this.callback = callback;
        this.monitor = monitor;
        this.sampleRate = sampleRate;
        this.channels = channels;
        this.isAacEld = isAacEld;
    }

    @Override
    public void run() {
        try {
            socket = new DatagramSocket(new InetSocketAddress(0));
            port = socket.getLocalPort();
            Log.i(TAG, "Audio receiver listening on port " + port);

            synchronized (monitor) {
                monitor.notifyAll();
            }

            if (isAacEld) {
                initAacEldDecoder();
            }

            Log.i(TAG, "Audio using AES-128-CBC decryption (per AirplayServer reference)");

            byte[] buf = new byte[4096];
            int packetCount = 0;
            while (!Thread.currentThread().isInterrupted()) {
                DatagramPacket packet = new DatagramPacket(buf, buf.length);
                socket.receive(packet);

                int pktLen = packet.getLength();
                // Filter: packets <= 12 bytes are empty keepalive
                if (pktLen <= 12) continue;

                packetCount++;
                byte[] data = Arrays.copyOf(packet.getData(), pktLen);

                // Parse RTP header
                int seqnum = ((data[2] & 0xFF) << 8) | (data[3] & 0xFF);

                // Keepalive filter: 16-byte packet with magic {0x00, 0x68, 0x34, 0x00}
                if (pktLen == 16 && data[12] == 0x00 && data[13] == 0x68
                        && data[14] == 0x34 && data[15] == 0x00) {
                    continue;
                }

                try {
                    // Strip RTP header (12 bytes)
                    byte[] audioPayload = Arrays.copyOfRange(data, 12, pktLen);

                    // AES-128-CBC decrypt (per-packet, reset IV each time)
                    try {
                        airPlay.decryptAudio(audioPayload, audioPayload.length);
                    } catch (Exception e) {
                        if (packetCount <= 3) {
                            Log.w(TAG, "Audio decrypt error: " + e.getMessage());
                        }
                    }

                    if (audioPayload.length > 4) {
                        if (isAacEld && decoderStarted) {
                            if (packetCount <= 3) {
                                Log.i(TAG, "Packet[" + packetCount + "] seq=" + seqnum +
                                    " len=" + audioPayload.length +
                                    " hex=" + bytesToHex(audioPayload, Math.min(16, audioPayload.length)));
                            }

                            // Decode AAC-ELD to PCM
                            byte[] pcm = decodeAacEld(audioPayload);
                            if (pcm != null) {
                                // Put into ring buffer and dequeue in order
                                enqueueAndDequeue(seqnum, pcm);
                            }
                        } else if (!isAacEld) {
                            callback.onAudio(audioPayload);
                        }
                    }

                    if (packetCount <= 3 || packetCount % 200 == 0) {
                        Log.i(TAG, "Packet #" + packetCount + ": seq=" + seqnum +
                                ", len=" + pktLen);
                    }
                } catch (Exception e) {
                    if (packetCount <= 5) {
                        Log.e(TAG, "Error processing audio packet #" + packetCount, e);
                    }
                }
            }
        } catch (Exception e) {
            if (!Thread.currentThread().isInterrupted()) {
                Log.e(TAG, "Audio receiver error", e);
            }
        } finally {
            if (decoder != null) {
                decoder.stop();
                decoder.release();
            }
            if (socket != null) socket.close();
        }
    }

    /**
     * Ring buffer enqueue + dequeue (like reference raop_buffer_queue + raop_buffer_dequeue)
     */
    private void enqueueAndDequeue(int seqnum, byte[] pcm) {
        // Initialize buffer on first packet
        if (bufferEmpty) {
            firstSeq = seqnum;
            lastSeq = seqnum;
            bufferEmpty = false;
        }

        // Drop old packets (already played past)
        if (seqCmp(seqnum, firstSeq) < 0) {
            return;
        }

        // If sequence jumped too far ahead, flush and restart
        if (seqCmp(seqnum, firstSeq + BUFFER_SIZE) >= 0) {
            flushBuffer(seqnum);
        }

        // Store in ring buffer
        int idx = seqnum % BUFFER_SIZE;
        seqNumbers[idx] = seqnum;
        pcmBuffer[idx] = pcm;
        available[idx] = true;

        // Update last sequence
        if (seqCmp(seqnum, lastSeq) > 0) {
            lastSeq = seqnum;
        }

        // Dequeue all consecutive available frames starting from firstSeq
        while (!bufferEmpty && seqCmp(firstSeq, lastSeq) <= 0) {
            int fidx = firstSeq % BUFFER_SIZE;
            if (!available[fidx]) {
                // Gap detected - play silence and skip (like reference)
                byte[] silence = new byte[1920]; // 480 samples * 2ch * 16bit
                callback.onAudio(silence);
                firstSeq++;
                continue;
            }
            // Play the frame
            callback.onAudio(pcmBuffer[fidx]);
            available[fidx] = false;
            pcmBuffer[fidx] = null;
            firstSeq++;
        }
    }

    private void flushBuffer(int nextSeq) {
        for (int i = 0; i < BUFFER_SIZE; i++) {
            available[i] = false;
            pcmBuffer[i] = null;
        }
        firstSeq = nextSeq;
        lastSeq = nextSeq - 1;
        bufferEmpty = true;
    }

    /**
     * Sequence number comparison handling 16-bit wraparound
     */
    private static int seqCmp(int a, int b) {
        int diff = (a - b) & 0xFFFF;
        if (diff > 0x8000) return -1; // a is before b (wraparound)
        if (diff == 0) return 0;
        return 1; // a is after b
    }

    /**
     * Initialize AAC-ELD MediaCodec decoder
     */
    private void initAacEldDecoder() {
        try {
            decoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);

            MediaFormat format = new MediaFormat();
            format.setString(MediaFormat.KEY_MIME, MediaFormat.MIMETYPE_AUDIO_AAC);
            format.setInteger(MediaFormat.KEY_SAMPLE_RATE, sampleRate);
            format.setInteger(MediaFormat.KEY_CHANNEL_COUNT, channels);
            format.setInteger(MediaFormat.KEY_AAC_PROFILE,
                    android.media.MediaCodecInfo.CodecProfileLevel.AACObjectELD);
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 2048);

            // Reference: AirplayServer-master-1 raop_buffer.c
            // AAC-ELD CSD: {0xF8, 0xE8, 0x50, 0x00}
            byte[] csd = new byte[]{(byte)0xF8, (byte)0xE8, (byte)0x50, (byte)0x00};
            format.setByteBuffer("csd-0", ByteBuffer.wrap(csd));

            decoder.configure(format, null, null, 0);
            decoder.start();
            decoderStarted = true;
            Log.i(TAG, "AAC-ELD decoder initialized, sampleRate=" + sampleRate + ", channels=" + channels);
        } catch (Exception e) {
            Log.e(TAG, "Failed to init AAC-ELD decoder", e);
            decoderStarted = false;
        }
    }

    private int decodedFrameCount = 0;

    /**
     * Decode one AAC-ELD frame to PCM
     */
    private byte[] decodeAacEld(byte[] aacData) {
        try {
            // Queue input
            int inputIndex = decoder.dequeueInputBuffer(10000);
            if (inputIndex >= 0) {
                ByteBuffer inputBuffer = decoder.getInputBuffer(inputIndex);
                if (inputBuffer != null) {
                    inputBuffer.clear();
                    inputBuffer.put(aacData);
                    decoder.queueInputBuffer(inputIndex, 0, aacData.length, 0, 0);
                }
            } else {
                Log.w(TAG, "No audio input buffer available");
                return null;
            }

            // Drain output
            MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
            int outputIndex;
            do {
                outputIndex = decoder.dequeueOutputBuffer(bufferInfo, 1000);
                if (outputIndex >= 0) {
                    ByteBuffer outputBuffer = decoder.getOutputBuffer(outputIndex);
                    byte[] pcm = null;
                    if (outputBuffer != null && bufferInfo.size > 0) {
                        pcm = new byte[bufferInfo.size];
                        outputBuffer.get(pcm);
                        decodedFrameCount++;
                        if (decodedFrameCount <= 3 || decodedFrameCount % 200 == 0) {
                            Log.i(TAG, "Decoded audio frame #" + decodedFrameCount +
                                    ", pcm=" + pcm.length + " bytes");
                        }
                    }
                    decoder.releaseOutputBuffer(outputIndex, false);
                    return pcm;
                } else if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    Log.i(TAG, "Audio output format changed: " + decoder.getOutputFormat());
                } else if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    // No output available yet
                }
            } while (outputIndex >= 0);
        } catch (Exception e) {
            Log.e(TAG, "Error decoding AAC-ELD", e);
        }
        return null;
    }

    public int getPort() {
        return port;
    }

    private static String bytesToHex(byte[] data, int len) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len; i++) {
            sb.append(String.format("%02x", data[i] & 0xFF));
        }
        return sb.toString();
    }
}
