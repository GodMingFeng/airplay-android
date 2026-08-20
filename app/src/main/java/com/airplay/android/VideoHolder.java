package com.airplay.android;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.util.Log;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.util.Arrays;

public class VideoHolder {
    private static final String TAG = "VideoHolder";

    /** Notified whenever the size of the decoded picture becomes known or changes. */
    public interface VideoSizeListener {
        void onVideoSizeChanged(int width, int height);
    }

    private static final int DEFAULT_WIDTH = 1920;
    private static final int DEFAULT_HEIGHT = 1080;

    private static Surface sSurface;
    private static MediaCodec sDecoder;
    private static byte[] sSpsPps;
    private static boolean sConfigured;
    private static AudioPlayer sAudioPlayer;

    private static volatile VideoSizeListener sSizeListener;
    // Size announced by the sender in the mirroring header (hint used to configure the decoder)
    private static int sHeaderWidth;
    private static int sHeaderHeight;
    // Size actually reported by the decoder, this is what the surface must match
    private static int sVideoWidth;
    private static int sVideoHeight;

    public static synchronized void setVideoSizeListener(VideoSizeListener listener) {
        sSizeListener = listener;
        if (listener != null && sVideoWidth > 0 && sVideoHeight > 0) {
            listener.onVideoSizeChanged(sVideoWidth, sVideoHeight);
        }
    }

    public static synchronized void setSurface(Surface surface) {
        if (surface == sSurface) return; // Avoid re-initializing with same surface
        sSurface = surface;
        sConfigured = false;
        initDecoder();
        // A brand new surface needs the stream parameters again before it can decode
        if (sSpsPps != null) {
            configureDecoder(sSpsPps);
        }
    }

    public static synchronized void release() {
        if (sDecoder != null) {
            try { sDecoder.stop(); } catch (Exception e) { /* ignore */ }
            try { sDecoder.release(); } catch (Exception e) { /* ignore */ }
            sDecoder = null;
        }
        sSurface = null;
        sConfigured = false;
        sVideoWidth = 0;
        sVideoHeight = 0;
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
            int width = sHeaderWidth > 0 ? sHeaderWidth : DEFAULT_WIDTH;
            int height = sHeaderHeight > 0 ? sHeaderHeight : DEFAULT_HEIGHT;
            MediaFormat format = MediaFormat.createVideoFormat("video/avc", width, height);
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
            sDecoder.setVideoScalingMode(MediaCodec.VIDEO_SCALING_MODE_SCALE_TO_FIT);
            sDecoder.start();
            sConfigured = true;
            Log.i(TAG, "Decoder configured with SPS/PPS at " + width + "x" + height);
        } catch (Exception e) {
            Log.e(TAG, "Failed to configure decoder", e);
        }
    }

    /** Size announced by the sender in the mirroring header, used as a configuration hint. */
    public static synchronized void onVideoFormat(int width, int height) {
        if (width <= 0 || height <= 0) return;
        if (width == sHeaderWidth && height == sHeaderHeight) return;
        Log.i(TAG, "Sender announced video size " + width + "x" + height);
        sHeaderWidth = width;
        sHeaderHeight = height;
        // Until the decoder reports its own geometry, trust the sender so the surface
        // already has the right aspect ratio for the very first frames
        if (sVideoWidth <= 0) {
            notifyVideoSize(width, height);
        }
    }

    public static synchronized void onSpsPpsData(byte[] spsPps) {
        boolean unchanged = Arrays.equals(sSpsPps, spsPps);
        Log.i(TAG, "Got SPS/PPS, length: " + spsPps.length + ", configured=" + sConfigured
                + ", changed=" + !unchanged);
        if (sConfigured && unchanged) {
            return;
        }
        sSpsPps = spsPps;
        if (sConfigured) {
            // The sender switched resolution: rebuild the decoder for the new geometry.
            // The next frame is an IDR with SPS/PPS prepended, so decoding resumes cleanly.
            Log.i(TAG, "SPS/PPS changed, reconfiguring decoder");
            initDecoder();
        }
        configureDecoder(spsPps);
    }

    private static void notifyVideoSize(int width, int height) {
        VideoSizeListener listener = sSizeListener;
        if (listener != null) {
            listener.onVideoSizeChanged(width, height);
        }
    }

    /**
     * Reads the real picture size from the decoder output format, honouring the crop
     * rectangle when the codec pads the frame up to a macroblock multiple.
     */
    private static void updateVideoSizeFromOutputFormat(MediaFormat format) {
        int width = format.containsKey(MediaFormat.KEY_WIDTH) ? format.getInteger(MediaFormat.KEY_WIDTH) : 0;
        int height = format.containsKey(MediaFormat.KEY_HEIGHT) ? format.getInteger(MediaFormat.KEY_HEIGHT) : 0;
        if (format.containsKey("crop-left") && format.containsKey("crop-right")) {
            width = format.getInteger("crop-right") - format.getInteger("crop-left") + 1;
        }
        if (format.containsKey("crop-top") && format.containsKey("crop-bottom")) {
            height = format.getInteger("crop-bottom") - format.getInteger("crop-top") + 1;
        }
        if (width <= 0 || height <= 0) return;
        if (width == sVideoWidth && height == sVideoHeight) return;

        sVideoWidth = width;
        sVideoHeight = height;
        Log.i(TAG, "Decoder output size " + width + "x" + height);
        notifyVideoSize(width, height);
    }

    private static int sFrameCount = 0;

    public static synchronized void onVideoData(byte[] video) {
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
                    MediaFormat outputFormat = sDecoder.getOutputFormat();
                    android.util.Log.i(TAG, "Output format changed: " + outputFormat);
                    updateVideoSizeFromOutputFormat(outputFormat);
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
