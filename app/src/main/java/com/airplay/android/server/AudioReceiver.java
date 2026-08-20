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
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Audio receiver following AirPlayServer-master reference:
 * - RTP receive + decrypt + AAC decode on receiver thread
 * - PCM pushed to queue, AudioTrack drains from queue on separate thread
 * - Ring buffer for sequence-based reorder (64 slots)
 * - no_resend=1: immediate playback, no waiting for retransmits
 * - Keepalive filtering: 16-byte magic {0x00,0x68,0x34,0x00}
 */
public class AudioReceiver implements Runnable {
    private static final String TAG = "AudioReceiver";
    private static final int BUFFER_SIZE = 64;
    private static final int QUEUE_MAX_FRAMES = 20; // Reference: AUDIO_QUEUE_MAX_FRAMES

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

    // PCM playback queue (decouples receive/decode from playback)
    private final ArrayBlockingQueue<byte[]> pcmQueue = new ArrayBlockingQueue<>(QUEUE_MAX_FRAMES);

    // Ring buffer for reorder (like reference raop_buffer)
    private final byte[][] pcmBuffer = new byte[BUFFER_SIZE][];
    private final int[] seqNumbers = new int[BUFFER_SIZE];
    private final boolean[] slotAvailable = new boolean[BUFFER_SIZE];
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

            // Start playback thread (drains pcmQueue → AudioTrack)
            Thread playbackThread = new Thread(this::playbackLoop, "AudioPlayback");
            playbackThread.setDaemon(true);
            playbackThread.start();

            Log.i(TAG, "Audio using AES-128-CBC (per AirplayServer reference)");

            byte[] buf = new byte[4096];
            int packetCount = 0;
            while (!Thread.currentThread().isInterrupted()) {
                DatagramPacket packet = new DatagramPacket(buf, buf.length);
                socket.receive(packet);

                int pktLen = packet.getLength();
                if (pktLen <= 12) continue;

                packetCount++;
                byte[] data = Arrays.copyOf(packet.getData(), pktLen);

                // Parse RTP sequence number
                int seqnum = ((data[2] & 0xFF) << 8) | (data[3] & 0xFF);

                // Keepalive filter (reference: raop_buffer.c)
                if (pktLen == 16 && data[12] == 0x00 && data[13] == 0x68
                        && data[14] == 0x34 && data[15] == 0x00) {
                    continue;
                }

                try {
                    // Strip RTP header
                    byte[] audioPayload = Arrays.copyOfRange(data, 12, pktLen);

                    // AES-128-CBC decrypt (per-packet reset)
                    try {
                        airPlay.decryptAudio(audioPayload, audioPayload.length);
                    } catch (Exception e) {
                        if (packetCount <= 3) Log.w(TAG, "Decrypt error: " + e.getMessage());
                    }

                    if (audioPayload.length > 4 && isAacEld && decoderStarted) {
                        if (packetCount <= 3) {
                            Log.i(TAG, "Pkt[" + packetCount + "] seq=" + seqnum +
                                " len=" + audioPayload.length +
                                " hex=" + bytesToHex(audioPayload, Math.min(16, audioPayload.length)));
                        }

                        // Decode AAC-ELD → PCM
                        byte[] pcm = decodeAacEld(audioPayload);
                        if (pcm != null) {
                            // Enqueue to ring buffer, dequeue in order to pcmQueue
                            enqueueAndDequeue(seqnum, pcm);
                        }
                    } else if (!isAacEld && audioPayload.length > 4) {
                        pushToPlaybackQueue(audioPayload);
                    }

                    if (packetCount <= 3 || packetCount % 500 == 0) {
                        Log.i(TAG, "Packet #" + packetCount + ": seq=" + seqnum +
                                ", len=" + pktLen + ", qSize=" + pcmQueue.size());
                    }
                } catch (Exception e) {
                    if (packetCount <= 5) Log.e(TAG, "Error packet #" + packetCount, e);
                }
            }
        } catch (Exception e) {
            if (!Thread.currentThread().isInterrupted()) Log.e(TAG, "Audio receiver error", e);
        } finally {
            if (decoder != null) { decoder.stop(); decoder.release(); }
            if (socket != null) socket.close();
        }
    }

    /**
     * Playback thread: drains pcmQueue and writes to AudioTrack via callback
     */
    private void playbackLoop() {
        Log.i(TAG, "Playback thread started");
        while (!Thread.currentThread().isInterrupted()) {
            try {
                byte[] pcm = pcmQueue.poll(100, TimeUnit.MILLISECONDS);
                if (pcm != null) {
                    callback.onAudio(pcm);
                }
            } catch (InterruptedException e) {
                break;
            }
        }
        Log.i(TAG, "Playback thread stopped");
    }

    /**
     * Push PCM to playback queue (non-blocking, drops oldest if full like reference)
     */
    private void pushToPlaybackQueue(byte[] pcm) {
        if (!pcmQueue.offer(pcm)) {
            // Queue full - drop oldest frame (like reference AUDIO_QUEUE_MAX_FRAMES limit)
            pcmQueue.poll();
            pcmQueue.offer(pcm);
        }
    }

    /**
     * Ring buffer enqueue + in-order dequeue (reference: raop_buffer.c)
     * no_resend=1: always advance, play silence for gaps
     */
    private void enqueueAndDequeue(int seqnum, byte[] pcm) {
        if (bufferEmpty) {
            firstSeq = seqnum;
            lastSeq = seqnum;
            bufferEmpty = false;
        }

        // Drop old packets
        if (seqCmp(seqnum, firstSeq) < 0) return;

        // Flush if jumped too far
        if (seqCmp(seqnum, firstSeq + BUFFER_SIZE) >= 0) {
            flushBuffer(seqnum);
        }

        // Store in ring buffer
        int idx = seqnum % BUFFER_SIZE;
        seqNumbers[idx] = seqnum;
        pcmBuffer[idx] = pcm;
        slotAvailable[idx] = true;

        if (seqCmp(seqnum, lastSeq) > 0) lastSeq = seqnum;

        // Dequeue consecutive available frames (no_resend=1: always advance)
        while (!bufferEmpty && seqCmp(firstSeq, lastSeq) <= 0) {
            int fidx = firstSeq % BUFFER_SIZE;
            if (slotAvailable[fidx]) {
                pushToPlaybackQueue(pcmBuffer[fidx]);
                slotAvailable[fidx] = false;
                pcmBuffer[fidx] = null;
            } else {
                // Missing packet - push silence (like reference: memset(entry->audio_buffer, 0, ...))
                pushToPlaybackQueue(new byte[1920]);
            }
            firstSeq++;
        }
    }

    private void flushBuffer(int nextSeq) {
        for (int i = 0; i < BUFFER_SIZE; i++) {
            slotAvailable[i] = false;
            pcmBuffer[i] = null;
        }
        firstSeq = nextSeq;
        lastSeq = nextSeq - 1;
        bufferEmpty = true;
    }

    private static int seqCmp(int a, int b) {
        int diff = (a - b) & 0xFFFF;
        if (diff > 0x8000) return -1;
        if (diff == 0) return 0;
        return 1;
    }

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

            // Reference: raop_buffer.c AAC-ELD CSD
            byte[] csd = new byte[]{(byte)0xF8, (byte)0xE8, (byte)0x50, (byte)0x00};
            format.setByteBuffer("csd-0", ByteBuffer.wrap(csd));

            decoder.configure(format, null, null, 0);
            decoder.start();
            decoderStarted = true;
            Log.i(TAG, "AAC-ELD decoder initialized, sr=" + sampleRate + ", ch=" + channels);
        } catch (Exception e) {
            Log.e(TAG, "Failed to init AAC-ELD decoder", e);
            decoderStarted = false;
        }
    }

    private int decodedFrameCount = 0;

    private byte[] decodeAacEld(byte[] aacData) {
        try {
            int inputIndex = decoder.dequeueInputBuffer(10000);
            if (inputIndex >= 0) {
                ByteBuffer inputBuffer = decoder.getInputBuffer(inputIndex);
                if (inputBuffer != null) {
                    inputBuffer.clear();
                    inputBuffer.put(aacData);
                    decoder.queueInputBuffer(inputIndex, 0, aacData.length, 0, 0);
                }
            } else {
                Log.w(TAG, "No input buffer available");
                return null;
            }

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
                        if (decodedFrameCount <= 3 || decodedFrameCount % 500 == 0) {
                            Log.i(TAG, "Decoded #" + decodedFrameCount + ", pcm=" + pcm.length + "B");
                        }
                    }
                    decoder.releaseOutputBuffer(outputIndex, false);
                    return pcm;
                } else if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    Log.i(TAG, "Output format: " + decoder.getOutputFormat());
                }
            } while (outputIndex >= 0);
        } catch (Exception e) {
            Log.e(TAG, "Decode error", e);
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
