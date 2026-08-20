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

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public class AudioReceiver implements Runnable {
    private static final String TAG = "AudioReceiver";

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

            // Initialize AAC-ELD decoder if needed
            if (isAacEld) {
                initAacEldDecoder();
            }

            byte[] shk = airPlay.getShk();
            // Reference: AirplayServer-master-1 uses AES-128-CBC for audio (AirPlay 1 style)
            // Key = SHA-512(aeskey + ecdh_secret)[0:16], IV = aesiv
            // This is what airPlay.decryptAudio() does internally
            Log.i(TAG, "Audio using AES-128-CBC decryption (per AirplayServer reference)");

            byte[] buf = new byte[4096];
            int packetCount = 0;
            while (!Thread.currentThread().isInterrupted()) {
                DatagramPacket packet = new DatagramPacket(buf, buf.length);
                socket.receive(packet);

                int pktLen = packet.getLength();
                if (pktLen <= 12) continue;

                packetCount++;
                byte[] data = Arrays.copyOf(packet.getData(), pktLen);

                try {
                    // Reference: AirplayServer-master-1 raop_buffer.c
                    // Strip RTP header (12 bytes), then AES-128-CBC decrypt
                    if (pktLen <= 12) continue;
                    byte[] audioPayload = Arrays.copyOfRange(data, 12, pktLen);

                    // AES-128-CBC decrypt (only 16-byte aligned portion)
                    try {
                        airPlay.decryptAudio(audioPayload, audioPayload.length);
                    } catch (Exception e) {
                        if (packetCount <= 3) {
                            Log.w(TAG, "Audio decrypt error: " + e.getMessage());
                        }
                    }

                    if (audioPayload != null && audioPayload.length > 4) {
                        if (isAacEld && decoderStarted) {
                            if (packetCount <= 3) {
                                Log.i(TAG, "Raw audio[" + packetCount + "] len=" + audioPayload.length +
                                    " hex=" + bytesToHex(audioPayload, Math.min(16, audioPayload.length)));
                            }
                            decodeAndPlayAacEld(audioPayload);
                        } else if (!isAacEld) {
                            // PCM - send directly to AudioTrack
                            callback.onAudio(audioPayload);
                        }
                    }

                    if (packetCount <= 3 || packetCount % 100 == 0) {
                        Log.i(TAG, "Packet #" + packetCount + ": len=" + pktLen +
                                ", decrypted=" + (audioPayload != null ? audioPayload.length : "null"));
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
     * ChaCha20-Poly1305 AEAD decryption
     * RTP structure:
     * - bytes[0:12] = RTP header (version, payload type, seq, timestamp, ssrc)
     * - bytes[12:-24] = encrypted payload
     * - bytes[-24:-8] = 16-byte GCM/Poly1305 tag
     * - bytes[-8:] = 8-byte nonce
     * AAD = bytes[4:12] (timestamp + ssrc)
     */
    private byte[] decryptChaCha20(byte[] data, int len, byte[] shk) throws Exception {
        if (len < 36) return null; // minimum: 12 header + 0 payload + 16 tag + 8 nonce

        // Extract components
        byte[] nonce = Arrays.copyOfRange(data, len - 8, len);
        byte[] tag = Arrays.copyOfRange(data, len - 24, len - 8);
        byte[] payload = Arrays.copyOfRange(data, 12, len - 24);
        byte[] aad = Arrays.copyOfRange(data, 4, 12); // timestamp + ssrc

        if (payload.length == 0) return new byte[0];

        // ChaCha20-Poly1305 needs 12-byte nonce: pad 8-byte nonce with 4 zero bytes at front
        byte[] fullNonce = new byte[12];
        System.arraycopy(nonce, 0, fullNonce, 4, 8);

        // Combine ciphertext + tag for JCE
        byte[] ctWithTag = new byte[payload.length + 16];
        System.arraycopy(payload, 0, ctWithTag, 0, payload.length);
        System.arraycopy(tag, 0, ctWithTag, payload.length, 16);

        Cipher cipher = Cipher.getInstance("ChaCha20-Poly1305");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(shk, "ChaCha20"),
                new IvParameterSpec(fullNonce));
        cipher.updateAAD(aad);

        return cipher.doFinal(ctWithTag);
    }

    /**
     * Fallback AES-CBC decryption (old RSA/ANNOUNCE mode)
     */
    private byte[] decryptAesCbc(byte[] data, int len) throws Exception {
        // Skip RTP header (12 bytes)
        byte[] audioData = Arrays.copyOfRange(data, 12, len);
        airPlay.decryptAudio(audioData, audioData.length);
        return audioData;
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

    /**
     * Build AAC-ELD AudioSpecificConfig (ISO 14496-3)
     */
    private byte[] buildAacEldConfig(int sampleRate, int channels) {
        // AAC AudioSpecificConfig for ELD:
        // audioObjectType (5 bits): 39 (ELD) = 31 + 8 extension
        // samplingFrequencyIndex (4 bits)
        // channelConfiguration (4 bits)
        // ...ELD specific config...
        int srIdx;
        switch (sampleRate) {
            case 8000: srIdx = 11; break;
            case 16000: srIdx = 8; break;
            case 22050: srIdx = 7; break;
            case 24000: srIdx = 6; break;
            case 32000: srIdx = 5; break;
            case 44100: srIdx = 4; break;
            case 48000: srIdx = 3; break;
            default: srIdx = 4;
        }

        // For ELD, use a simpler 2-byte config that MediaCodec often accepts
        // audioObjectType=39 (ELD): 5 bits = 11111 (31) + extension 01000 (8) = effectively AOT 39
        // But many Android decoders accept AOT=2 (AAC-LC) for ELD data
        // Use AAC-LC profile with the correct sample rate as a workaround
        int aot = 2; // AAC-LC
        int byte0 = (aot << 3) | (srIdx >> 1);
        int byte1 = ((srIdx & 1) << 7) | (channels << 3);

        return new byte[]{(byte) byte0, (byte) byte1};
    }

    private int decodedFrameCount = 0;

    /**
     * Decode AAC-ELD frame and send PCM to AudioTrack
     */
    private void decodeAndPlayAacEld(byte[] aacData) {
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
            }

            // Drain output
            MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
            int outputIndex;
            do {
                outputIndex = decoder.dequeueOutputBuffer(bufferInfo, 0);
                if (outputIndex >= 0) {
                    ByteBuffer outputBuffer = decoder.getOutputBuffer(outputIndex);
                    if (outputBuffer != null && bufferInfo.size > 0) {
                        byte[] pcm = new byte[bufferInfo.size];
                        outputBuffer.get(pcm);
                        decodedFrameCount++;
                        if (decodedFrameCount <= 3 || decodedFrameCount % 100 == 0) {
                            Log.i(TAG, "Decoded audio frame #" + decodedFrameCount + ", pcm=" + pcm.length + " bytes");
                        }
                        callback.onAudio(pcm);
                    }
                    decoder.releaseOutputBuffer(outputIndex, false);
                } else if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    Log.i(TAG, "Audio output format changed: " + decoder.getOutputFormat());
                } else if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    // No output available yet - normal for first few frames
                }
            } while (outputIndex >= 0);
        } catch (Exception e) {
            Log.e(TAG, "Error decoding AAC-ELD", e);
        }
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
