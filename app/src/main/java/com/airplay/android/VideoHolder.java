package com.airplay.android;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.util.Log;
import android.view.Surface;

import java.nio.ByteBuffer;

public class VideoHolder {
    private static final String TAG = "VideoHolder";

    private static Surface sSurface;
    private static MediaCodec sDecoder;
    private static byte[] sSpsPps;
    private static boolean sConfigured;
    private static AudioPlayer sAudioPlayer;

    public static synchronized void setSurface(Surface surface) {
        if (surface == sSurface) return; // Avoid re-initializing with same surface
        sSurface = surface;
        sConfigured = false;
        initDecoder();
    }

    public static synchronized void release() {
        if (sDecoder != null) {
            try { sDecoder.stop(); } catch (Exception e) { /* ignore */ }
            try { sDecoder.release(); } catch (Exception e) { /* ignore */ }
            sDecoder = null;
        }
        sSurface = null;
        sConfigured = false;
        if (sAudioPlayer != null) {
            sAudioPlayer.release();
            sAudioPlayer = null;
        }
    }

    private static void initDecoder() {
        if (sDecoder != null) {
            try { sDecoder.stop(); } catch (Exception e) { /* ignore */ }
            try { sDecoder.release(); } catch (Exception e) { /* ignore */ }
        }
        sDecoder = null;
        sConfigured = false;

        try {
            sDecoder = MediaCodec.createDecoderByType("video/avc");
            Log.i(TAG, "Decoder created");
        } catch (Exception e) {
            Log.e(TAG, "Failed to create decoder", e);
        }
    }

    private static synchronized void configureDecoder(byte[] spsPps) {
        if (sDecoder == null || sSurface == null) return;

        try {
            android.media.MediaFormat format = android.media.MediaFormat.createVideoFormat("video/avc", 1920, 1080);
            // Extract SPS and PPS from the combined array
            int spsStart = 4; // skip 0x00000001
            int spsEnd = spsStart;
            while (spsEnd < spsPps.length - 4) {
                if (spsPps[spsEnd] == 0 && spsPps[spsEnd + 1] == 0
                        && spsPps[spsEnd + 2] == 0 && spsPps[spsEnd + 3] == 1) {
                    break;
                }
                spsEnd++;
            }
            byte[] sps = new byte[spsEnd - spsStart];
            System.arraycopy(spsPps, spsStart, sps, 0, sps.length);

            int ppsStart = spsEnd + 4; // skip second 0x00000001
            byte[] pps = new byte[spsPps.length - ppsStart];
            System.arraycopy(spsPps, ppsStart, pps, 0, pps.length);

            format.setByteBuffer("csd-0", ByteBuffer.wrap(sps));
            format.setByteBuffer("csd-1", ByteBuffer.wrap(pps));

            sDecoder.configure(format, sSurface, null, 0);
            sDecoder.start();
            sConfigured = true;
            Log.i(TAG, "Decoder configured with SPS/PPS");
        } catch (Exception e) {
            Log.e(TAG, "Failed to configure decoder", e);
        }
    }

    public static void onSpsPpsData(byte[] spsPps) {
        Log.i(TAG, "Got SPS/PPS, length: " + spsPps.length + ", configured=" + sConfigured);
        sSpsPps = spsPps;
        if (!sConfigured) {
            configureDecoder(spsPps);
        } else {
            Log.i(TAG, "Decoder already configured, skipping SPS/PPS update");
        }
    }

    private static int sFrameCount = 0;

    public static void onVideoData(byte[] video) {
        if (sDecoder == null || !sConfigured) {
            if (sFrameCount == 0) {
                android.util.Log.w(TAG, "onVideoData: decoder not ready, sDecoder=" + (sDecoder != null) + " sConfigured=" + sConfigured);
            }
            return;
        }
        sFrameCount++;

        try {
            int inputIndex = sDecoder.dequeueInputBuffer(10000);
            if (inputIndex >= 0) {
                ByteBuffer inputBuffer = sDecoder.getInputBuffer(inputIndex);
                if (inputBuffer != null) {
                    inputBuffer.clear();
                    inputBuffer.put(video);
                    sDecoder.queueInputBuffer(inputIndex, 0, video.length, 0, 0);
                }
            } else {
                android.util.Log.w(TAG, "No input buffer available (frame #" + sFrameCount + ")");
            }

            // Drain output
            MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
            int outputIndex;
            int framesRendered = 0;
            do {
                outputIndex = sDecoder.dequeueOutputBuffer(bufferInfo, 1000);
                if (outputIndex >= 0) {
                    sDecoder.releaseOutputBuffer(outputIndex, true);
                    framesRendered++;
                } else if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    android.util.Log.i(TAG, "Output format changed: " + sDecoder.getOutputFormat());
                }
            } while (outputIndex >= 0);
            if (framesRendered > 0 || sFrameCount <= 5 || sFrameCount % 100 == 0) {
                android.util.Log.i(TAG, "Frame #" + sFrameCount + ": rendered=" + framesRendered + ", size=" + video.length);
            }
        } catch (Exception e) {
            android.util.Log.e(TAG, "Error decoding video frame #" + sFrameCount, e);
        }
    }

    public static void onAudioData(byte[] audio) {
        if (sAudioPlayer == null) {
            sAudioPlayer = new AudioPlayer();
        }
        sAudioPlayer.play(audio);
    }
}
