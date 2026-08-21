package com.airplay.android;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Arrays;

public class VideoHolder {
    private static final String TAG = "VideoHolder";

    /** Notified whenever the size of the decoded picture becomes known or changes. */
    public interface VideoSizeListener {
        void onVideoSizeChanged(int width, int height);
    }

    private static final int DEFAULT_WIDTH = 1920;
    private static final int DEFAULT_HEIGHT = 1080;
    /** Upper bound for one access unit, matching the payload limit of the mirroring receiver. */
    private static final int MAX_INPUT_SIZE = 1024 * 1024;

    /**
     * Access units held between the receiving thread and the codec. Deep enough to ride out a
     * decoder hiccup; dropping is a last resort because it breaks the H.264 reference chain.
     */
    private static final int MAX_PENDING_FRAMES = 16;
    /**
     * How far behind arrival the picture is put on screen, in nanoseconds. Zero renders each
     * frame as soon as it is decoded.
     *
     * <p>Deliberately off: a scheduled release keeps the output buffer checked out until its
     * render deadline, and with only a handful of them the decoder stalls, stops recycling input
     * buffers and the queue above starts dropping frames. Measured at 40ms on a BRAVIA AE2 that
     * cost ~20% of all frames, which looks far worse than the jitter it was meant to smooth.
     */
    private static final long PACING_DELAY_NS = 0L;
    /**
     * Mirroring only sends frames when the screen changes, so a gap of any length is normal.
     * Once the target is this far out the pacing base is simply re-anchored to now.
     */
    private static final long MAX_PACING_AHEAD_NS = 250_000_000L;

    /** Guards the decoder lifecycle and the queues below. Never held across a blocking call. */
    private static final Object LOCK = new Object();
    /**
     * Serialises decoder rebuilds, which are triggered both by the surface callbacks and by the
     * receiving thread. Without it two rebuilds can overlap and leak a codec still holding the
     * surface. Held across stop()/release(), so it must never be taken while holding {@link #LOCK}.
     */
    private static final Object LIFECYCLE_LOCK = new Object();

    private static Surface sSurface;
    private static MediaCodec sDecoder;
    private static HandlerThread sCodecThread;
    private static byte[] sSpsPps;
    private static boolean sConfigured;
    /** Set from the codec error callback; the receiving thread rebuilds on the next frame. */
    private static boolean sNeedsRestart;
    private static AudioPlayer sAudioPlayer;

    /** Access units waiting for a codec input buffer, oldest first. */
    private static final ArrayDeque<Frame> sPending = new ArrayDeque<>();
    /** Input buffers the codec has handed out and that have nothing to carry yet. */
    private static final ArrayDeque<Integer> sFreeInputs = new ArrayDeque<>();

    private static boolean sPacingAnchored;
    private static long sPacingBaseNs;
    private static long sPacingBasePtsUs;

    private static long sFrameCount;
    private static long sDroppedFrames;

    private static volatile VideoSizeListener sSizeListener;
    // Size announced by the sender in the mirroring header (hint used to configure the decoder)
    private static int sHeaderWidth;
    private static int sHeaderHeight;
    // Size actually reported by the decoder, this is what the surface must match
    private static int sVideoWidth;
    private static int sVideoHeight;

    private static final class Frame {
        final byte[] data;
        final long ptsUs;

        Frame(byte[] data, long ptsUs) {
            this.data = data;
            this.ptsUs = ptsUs;
        }
    }

    public static void setVideoSizeListener(VideoSizeListener listener) {
        int width;
        int height;
        synchronized (LOCK) {
            sSizeListener = listener;
            width = sVideoWidth;
            height = sVideoHeight;
        }
        if (listener != null && width > 0 && height > 0) {
            listener.onVideoSizeChanged(width, height);
        }
    }

    /**
     * Reports that the sender has torn the mirroring session down, so the UI can go back to its
     * idle state. The decoder is deliberately left alone: it is reconfigured from the next
     * session's SPS/PPS anyway, and tearing it down here would race with the receiving threads.
     */
    public static void notifySessionEnded() {
        synchronized (LOCK) {
            if (sVideoWidth == 0 && sVideoHeight == 0) return;
            sVideoWidth = 0;
            sVideoHeight = 0;
        }
        notifyVideoSize(0, 0);
    }

    public static void setSurface(Surface surface) {
        byte[] spsPps;
        synchronized (LOCK) {
            if (surface == sSurface) return; // Avoid re-initializing with same surface
            sSurface = surface;
            spsPps = sSpsPps;
        }
        // A brand new surface needs the stream parameters again before it can decode
        startDecoder(spsPps);
    }

    public static void release() {
        MediaCodec decoder;
        HandlerThread thread;
        AudioPlayer audioPlayer;
        synchronized (LIFECYCLE_LOCK) {
            synchronized (LOCK) {
                decoder = sDecoder;
                thread = sCodecThread;
                audioPlayer = sAudioPlayer;
                sDecoder = null;
                sCodecThread = null;
                sAudioPlayer = null;
                sSurface = null;
                sVideoWidth = 0;
                sVideoHeight = 0;
                resetDecodeStateLocked();
            }
            // Outside LOCK: release() waits for the callback thread, which needs LOCK itself
            disposeDecoder(decoder, thread);
        }
        if (audioPlayer != null) {
            audioPlayer.release();
        }
    }

    private static void resetDecodeStateLocked() {
        sConfigured = false;
        sNeedsRestart = false;
        sPending.clear();
        sFreeInputs.clear();
        sPacingAnchored = false;
    }

    private static void disposeDecoder(MediaCodec decoder, HandlerThread thread) {
        if (decoder != null) {
            try { decoder.stop(); } catch (Exception e) { /* ignore */ }
            try { decoder.release(); } catch (Exception e) { /* ignore */ }
        }
        if (thread != null) {
            thread.quitSafely();
        }
    }

    /**
     * Builds a decoder driven by callbacks instead of by polling. The receiving thread then only
     * ever appends to {@link #sPending}, so a slow codec can no longer stall the socket reads.
     */
    private static void startDecoder(byte[] spsPps) {
        synchronized (LIFECYCLE_LOCK) {
            MediaCodec oldDecoder;
            HandlerThread oldThread;
            Surface surface;
            int width;
            int height;
            synchronized (LOCK) {
                oldDecoder = sDecoder;
                oldThread = sCodecThread;
                sDecoder = null;
                sCodecThread = null;
                resetDecodeStateLocked();
                surface = sSurface;
                width = sHeaderWidth > 0 ? sHeaderWidth : DEFAULT_WIDTH;
                height = sHeaderHeight > 0 ? sHeaderHeight : DEFAULT_HEIGHT;
            }
            disposeDecoder(oldDecoder, oldThread);

            if (surface == null || spsPps == null) return;

            MediaCodec decoder = null;
            HandlerThread thread = null;
            try {
                MediaFormat format = MediaFormat.createVideoFormat("video/avc", width, height);
                format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_INPUT_SIZE);
                // Ask for a realtime, latency oriented configuration: mirroring has no seek and
                // no reordering, so the codec must not build up a queue of frames internally
                // before it hands the first one back
                format.setInteger(MediaFormat.KEY_PRIORITY, 0);
                format.setInteger(MediaFormat.KEY_OPERATING_RATE, 60);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1);
                }

                thread = new HandlerThread("VideoDecoder");
                thread.start();

                decoder = MediaCodec.createDecoderByType("video/avc");
                decoder.setCallback(CALLBACK, new Handler(thread.getLooper()));
                decoder.configure(format, surface, null, 0);
                decoder.setVideoScalingMode(MediaCodec.VIDEO_SCALING_MODE_SCALE_TO_FIT);
                decoder.start();
            } catch (Exception e) {
                Log.e(TAG, "Failed to start decoder", e);
                disposeDecoder(decoder, thread);
                return;
            }

            synchronized (LOCK) {
                sDecoder = decoder;
                sCodecThread = thread;
                sConfigured = true;
                // The parameter sets are handed over in-band as the first access unit instead of
                // through csd-0/csd-1: the sender repeats them whenever the geometry changes, and
                // hardware decoders that drive a TV video plane only pick them up from the
                // bitstream itself.
                sPending.addLast(new Frame(spsPps, 0));
                feedCodecLocked();
            }
            Log.i(TAG, "Decoder started at " + width + "x" + height);
        }
    }

    /** Size announced by the sender in the mirroring header, used as a configuration hint. */
    public static void onVideoFormat(int width, int height) {
        if (width <= 0 || height <= 0) return;
        boolean announceSize;
        synchronized (LOCK) {
            if (width == sHeaderWidth && height == sHeaderHeight) return;
            sHeaderWidth = width;
            sHeaderHeight = height;
            // Until the decoder reports its own geometry, trust the sender so the surface
            // already has the right aspect ratio for the very first frames
            announceSize = sVideoWidth <= 0;
        }
        Log.i(TAG, "Sender announced video size " + width + "x" + height);
        if (announceSize) {
            notifyVideoSize(width, height);
        }
    }

    public static void onSpsPpsData(byte[] spsPps) {
        synchronized (LOCK) {
            boolean unchanged = Arrays.equals(sSpsPps, spsPps);
            Log.i(TAG, "Got SPS/PPS, length: " + spsPps.length + ", configured=" + sConfigured
                    + ", changed=" + !unchanged);
            if (sConfigured && unchanged && !sNeedsRestart) {
                return;
            }
            sSpsPps = spsPps;
        }
        // The sender switched resolution (or the codec faulted): rebuild for the new geometry.
        // The next frame is an IDR with SPS/PPS prepended, so decoding resumes cleanly.
        startDecoder(spsPps);
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

        synchronized (LOCK) {
            if (width == sVideoWidth && height == sVideoHeight) return;
            sVideoWidth = width;
            sVideoHeight = height;
        }
        Log.i(TAG, "Decoder output size " + width + "x" + height);
        notifyVideoSize(width, height);
    }

    /** @param ptsUs presentation timestamp derived from the sender's clock, in microseconds */
    public static void onVideoData(byte[] video, long ptsUs) {
        byte[] spsPps = null;
        synchronized (LOCK) {
            if (sNeedsRestart) {
                spsPps = sSpsPps;
            } else if (!sConfigured) {
                if (sFrameCount == 0) {
                    Log.w(TAG, "onVideoData: decoder not ready, sDecoder=" + (sDecoder != null)
                            + " sConfigured=" + sConfigured);
                }
                return;
            } else {
                sFrameCount++;
                // Dropping breaks the reference chain and smears the picture until the next IDR,
                // so it only happens once the decoder is hopelessly behind
                while (sPending.size() >= MAX_PENDING_FRAMES) {
                    sPending.pollFirst();
                    sDroppedFrames++;
                }
                sPending.addLast(new Frame(video, ptsUs));
                feedCodecLocked();
                if (sFrameCount % 300 == 0) {
                    Log.i(TAG, "Frame #" + sFrameCount + ": pending=" + sPending.size()
                            + ", dropped=" + sDroppedFrames + ", size=" + video.length);
                }
                return;
            }
        }
        // Recovering from a codec fault: rebuild outside the lock, the frame itself is skipped
        // because decoding can only pick up again at the next IDR anyway
        Log.w(TAG, "Restarting decoder after codec error");
        startDecoder(spsPps);
    }

    /** Pairs the access units that arrived with the input buffers the codec handed out. */
    private static void feedCodecLocked() {
        MediaCodec decoder = sDecoder;
        if (decoder == null) return;

        while (!sFreeInputs.isEmpty() && !sPending.isEmpty()) {
            int index = sFreeInputs.pollFirst();
            Frame frame = sPending.pollFirst();
            try {
                ByteBuffer inputBuffer = decoder.getInputBuffer(index);
                if (inputBuffer == null) continue;
                if (frame.data.length > inputBuffer.capacity()) {
                    Log.w(TAG, "Access unit of " + frame.data.length + " bytes exceeds the input"
                            + " buffer (" + inputBuffer.capacity() + "), dropping it");
                    sFreeInputs.addFirst(index);
                    continue;
                }
                inputBuffer.clear();
                inputBuffer.put(frame.data);
                decoder.queueInputBuffer(index, 0, frame.data.length, frame.ptsUs, 0);
            } catch (IllegalStateException e) {
                Log.w(TAG, "Codec rejected input buffer " + index, e);
                return;
            }
        }
    }

    /**
     * Turns a sender timestamp into a moment on the local clock to put the frame on screen.
     * Handing that to the codec lets it line the frame up with a vsync instead of flushing
     * whatever arrived in the last burst all at once.
     */
    private static long renderTimeLocked(long ptsUs) {
        long now = System.nanoTime();
        if (sPacingAnchored) {
            long target = sPacingBaseNs + (ptsUs - sPacingBasePtsUs) * 1000L;
            if (target >= now && target <= now + MAX_PACING_AHEAD_NS) {
                return target;
            }
            // Either the frame is already late or the sender skipped ahead (mirroring only
            // sends on screen changes): start the smoothing window over from here
        }
        sPacingAnchored = true;
        sPacingBasePtsUs = ptsUs;
        sPacingBaseNs = now + PACING_DELAY_NS;
        return sPacingBaseNs;
    }

    private static final MediaCodec.Callback CALLBACK = new MediaCodec.Callback() {
        @Override
        public void onInputBufferAvailable(MediaCodec codec, int index) {
            synchronized (LOCK) {
                if (codec != sDecoder) return;
                sFreeInputs.addLast(index);
                feedCodecLocked();
            }
        }

        @Override
        public void onOutputBufferAvailable(MediaCodec codec, int index, MediaCodec.BufferInfo info) {
            if (PACING_DELAY_NS <= 0) {
                try {
                    codec.releaseOutputBuffer(index, true);
                } catch (IllegalStateException e) {
                    Log.w(TAG, "Codec rejected output buffer " + index, e);
                }
                return;
            }
            long renderNs;
            synchronized (LOCK) {
                if (codec != sDecoder) return;
                renderNs = renderTimeLocked(info.presentationTimeUs);
            }
            try {
                codec.releaseOutputBuffer(index, renderNs);
            } catch (IllegalStateException e) {
                Log.w(TAG, "Codec rejected output buffer " + index, e);
            }
        }

        @Override
        public void onOutputFormatChanged(MediaCodec codec, MediaFormat format) {
            synchronized (LOCK) {
                // A decoder that is on its way out may still report the geometry of the previous
                // session, which would put a stale size back on the surface
                if (codec != sDecoder) return;
            }
            Log.i(TAG, "Output format changed: " + format);
            updateVideoSizeFromOutputFormat(format);
        }

        @Override
        public void onError(MediaCodec codec, MediaCodec.CodecException e) {
            Log.e(TAG, "Decoder error, recoverable=" + e.isRecoverable(), e);
            synchronized (LOCK) {
                if (codec != sDecoder) return;
                // Rebuilding here would deadlock against this very callback thread, so only
                // flag it and let the receiving thread do the work on the next frame
                sNeedsRestart = true;
                sPending.clear();
                sFreeInputs.clear();
            }
        }
    };

    public static void onAudioData(byte[] audio) {
        AudioPlayer player;
        synchronized (LOCK) {
            if (sAudioPlayer == null) {
                sAudioPlayer = new AudioPlayer();
            }
            player = sAudioPlayer;
        }
        player.play(audio);
    }
}
