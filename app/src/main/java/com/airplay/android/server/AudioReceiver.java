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
    /** AAC-ELD packet length used by AirPlay mirroring, in samples per channel. */
    private static final int SAMPLES_PER_PACKET = 480;
    /** Same thing in bytes of stereo 16 bit PCM. */
    private static final int SILENCE_BYTES = SAMPLES_PER_PACKET * 2 * 2;
    /** RTP payload type of an audio packet. */
    private static final int TYPE_AUDIO = 0x60;
    /** A retransmitted packet, which arrives with the original one wrapped behind a four byte head. */
    private static final int TYPE_RESEND = 0x56;

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
    private final ArrayBlockingQueue<PcmFrame> pcmQueue = new ArrayBlockingQueue<>(QUEUE_MAX_FRAMES);

    // Ring buffer for reorder (like reference raop_buffer)
    private final PcmFrame[] pcmBuffer = new PcmFrame[BUFFER_SIZE];
    private final int[] seqNumbers = new int[BUFFER_SIZE];
    private final boolean[] slotAvailable = new boolean[BUFFER_SIZE];
    private int firstSeq = -1;
    private int lastSeq = -1;
    private boolean bufferEmpty = true;
    /** Timestamp of the last sample handed to playback, used to stamp inserted silence. */
    private long lastPushedRtp;
    /** Packets turned away before the decoder, counted only so the log can say how many. */
    private int duplicateCount;
    private int otherTypeCount;

    /** Decoded samples together with the sender's RTP timestamp of the packet they came from. */
    private static final class PcmFrame {
        final byte[] pcm;
        final long rtpTime;

        PcmFrame(byte[] pcm, long rtpTime) {
            this.pcm = pcm;
            this.rtpTime = rtpTime;
        }
    }

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

                // Everything on this port gets decrypted and handed to the AAC decoder, so what
                // is not audio has to be turned away here: a stray packet decrypts to noise and
                // the decoder carries that noise into the frames around it.
                int payloadType = data[1] & 0x7F;
                int rtp = 0;
                if (payloadType == TYPE_RESEND) {
                    rtp = 4; // the original packet begins after the resend header
                } else if (payloadType != TYPE_AUDIO) {
                    otherTypeCount++;
                    if (otherTypeCount <= 3 || otherTypeCount % 500 == 0) {
                        Log.i(TAG, "Ignored " + otherTypeCount + " packets that are not audio,"
                                + " last type=" + payloadType + ", len=" + pktLen);
                    }
                    continue;
                }
                if (pktLen <= rtp + 12) continue;

                // Parse RTP sequence number and the 44100Hz timestamp the sender stamped the
                // packet with, which is what lines the sound up with the picture later on
                int seqnum = ((data[rtp + 2] & 0xFF) << 8) | (data[rtp + 3] & 0xFF);
                long rtpTime = ((data[rtp + 4] & 0xFFL) << 24) | ((data[rtp + 5] & 0xFFL) << 16)
                        | ((data[rtp + 6] & 0xFFL) << 8) | (data[rtp + 7] & 0xFFL);

                // Keepalive filter (reference: raop_buffer.c)
                if (pktLen == rtp + 16 && data[rtp + 12] == 0x00 && data[rtp + 13] == 0x68
                        && data[rtp + 14] == 0x34 && data[rtp + 15] == 0x00) {
                    continue;
                }

                // AAC-ELD carries state from one frame into the next, so decoding a packet twice
                // does not merely waste the work: it corrupts the frames on either side of it.
                // The ring buffer below would discard the copy, but only after the damage is done.
                if (alreadySeen(seqnum)) {
                    duplicateCount++;
                    if (duplicateCount % 500 == 0) {
                        Log.i(TAG, "Skipped " + duplicateCount + " duplicates of " + packetCount
                                + " packets, last seq=" + seqnum + ", type=" + payloadType);
                    }
                    continue;
                }

                try {
                    // Strip RTP header
                    byte[] audioPayload = Arrays.copyOfRange(data, rtp + 12, pktLen);

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
                            enqueueAndDequeue(seqnum, new PcmFrame(pcm, rtpTime));
                        }
                    } else if (!isAacEld && audioPayload.length > 4) {
                        pushToPlaybackQueue(new PcmFrame(audioPayload, rtpTime));
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
                PcmFrame frame = pcmQueue.poll(100, TimeUnit.MILLISECONDS);
                if (frame != null) {
                    callback.onAudio(frame.pcm, frame.rtpTime);
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
    private void pushToPlaybackQueue(PcmFrame frame) {
        if (!pcmQueue.offer(frame)) {
            // Queue full - drop oldest frame (like reference AUDIO_QUEUE_MAX_FRAMES limit)
            pcmQueue.poll();
            pcmQueue.offer(frame);
        }
    }

    /**
     * True if this sequence number has already been through the decoder, whether it has been
     * handed to playback or is still sitting in the ring buffer waiting its turn.
     */
    private boolean alreadySeen(int seqnum) {
        if (bufferEmpty) return false;
        if (seqCmp(seqnum, firstSeq) < 0) return true;
        int idx = seqnum % BUFFER_SIZE;
        return slotAvailable[idx] && seqNumbers[idx] == seqnum;
    }

    /**
     * Ring buffer enqueue + in-order dequeue (reference: raop_buffer.c)
     * no_resend=1: always advance, play silence for gaps
     */
    private void enqueueAndDequeue(int seqnum, PcmFrame frame) {
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
        pcmBuffer[idx] = frame;
        slotAvailable[idx] = true;

        if (seqCmp(seqnum, lastSeq) > 0) lastSeq = seqnum;

        // Dequeue consecutive available frames (no_resend=1: always advance)
        while (!bufferEmpty && seqCmp(firstSeq, lastSeq) <= 0) {
            int fidx = firstSeq % BUFFER_SIZE;
            if (slotAvailable[fidx]) {
                pushToPlaybackQueue(pcmBuffer[fidx]);
                lastPushedRtp = pcmBuffer[fidx].rtpTime;
                slotAvailable[fidx] = false;
                pcmBuffer[fidx] = null;
            } else {
                // Missing packet - push silence (like reference: memset(entry->audio_buffer, 0, ...)).
                // It still has to carry a timestamp, otherwise the gap would shift every later
                // sample against the picture; every packet is exactly SAMPLES_PER_PACKET long.
                lastPushedRtp += SAMPLES_PER_PACKET;
                pushToPlaybackQueue(new PcmFrame(new byte[SILENCE_BYTES], lastPushedRtp));
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
