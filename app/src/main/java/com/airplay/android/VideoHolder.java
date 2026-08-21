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
    private static final int MAX_PENDING_FRAMES = 64;
    /**
     * Longest a frame may be held back waiting for the sound to catch up. Measured on this TV the
     * hold settles around 300ms, almost all of it the set's own audio output path, so the limit is
     * set well clear of that; it is only there to stop a nonsensical reading freezing the screen.
     * It also has to stay inside {@link #MAX_PENDING_FRAMES} worth of playback, otherwise the held
     * frames overflow the queue and get dropped.
     */
    private static final long MAX_HOLD_NS = 600_000_000L;

    /** Guards the decoder lifecycle and the queues below. Never held across a blocking call. */
    private static final Object LOCK = new Object();
    /**
     * Serialises decoder rebuilds, which are triggered both by the surface callbacks and by the
     * receiving thread. Without it two rebuilds can overlap and leak a codec still holding the
     * surface. Held across stop()/release(), so it must never be taken while holding {@link #LOCK}.
     */
    private static final Object LIFECYCLE_LOCK = new Object();
    /**
     * Guards {@link #sAudioPlayer} only. Kept apart from {@link #LOCK} so that building an
     * AudioTrack, which takes a few milliseconds, cannot hold up a codec callback.
     */
    private static final Object AUDIO_LOCK = new Object();

    private static Surface sSurface;
    private static MediaCodec sDecoder;
    private static HandlerThread sCodecThread;
    /** Runs the codec callbacks and the delayed hand-off of frames that are not due yet. */
    private static Handler sCodecHandler;
    private static byte[] sSpsPps;
    private static boolean sConfigured;
    /** Set from the codec error callback; the receiving thread rebuilds on the next frame. */
    private static boolean sNeedsRestart;
    private static AudioPlayer sAudioPlayer;

    /** Access units waiting for a codec input buffer, oldest first. */
    private static final ArrayDeque<Frame> sPending = new ArrayDeque<>();
    /** Input buffers the codec has handed out and that have nothing to carry yet. */
    private static final ArrayDeque<Integer> sFreeInputs = new ArrayDeque<>();

    /** True while a delayed {@link #FEED} is already queued, so only one is ever outstanding. */
    private static boolean sFeedScheduled;

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
        /** Sender NTP time this picture was captured at, or 0 for the parameter sets. */
        final long senderUs;

        Frame(byte[] data, long senderUs) {
            this.data = data;
            this.senderUs = senderUs;
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
        AvSync.reset();
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
        synchronized (AUDIO_LOCK) {
            audioPlayer = sAudioPlayer;
            sAudioPlayer = null;
        }
        synchronized (LIFECYCLE_LOCK) {
            synchronized (LOCK) {
                decoder = sDecoder;
                thread = sCodecThread;
                sDecoder = null;
                sCodecThread = null;
                sCodecHandler = null;
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
        if (sCodecHandler != null) {
            sCodecHandler.removeCallbacks(FEED);
        }
        sFeedScheduled = false;
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
            Handler handler = null;
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
                handler = new Handler(thread.getLooper());

                decoder = MediaCodec.createDecoderByType("video/avc");
                decoder.setCallback(CALLBACK, handler);
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
                sCodecHandler = handler;
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

    /** @param senderUs time on the sender's NTP clock at which this picture was captured */
    public static void onVideoData(byte[] video, long senderUs) {
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
                sPending.addLast(new Frame(video, senderUs));
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

    /**
     * Pairs the access units that arrived with the input buffers the codec handed out, holding a
     * frame back until the sound recorded with it is about to be heard.
     */
    private static void feedCodecLocked() {
        MediaCodec decoder = sDecoder;
        if (decoder == null) return;

        while (!sFreeInputs.isEmpty() && !sPending.isEmpty()) {
            Frame frame = sPending.peekFirst();
            long waitNs = holdTimeNsLocked(frame);
            if (waitNs > 0) {
                // Waiting here rather than at releaseOutputBuffer is deliberate: a timed release
                // pins an output buffer until its deadline, and there are only a handful of them,
                // so the decoder stalls and frames get dropped. Holding on the input side costs
                // nothing but a slot in a queue we already have.
                scheduleFeedLocked(waitNs);
                return;
            }
            sPending.pollFirst();
            int index = sFreeInputs.pollFirst();
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
                decoder.queueInputBuffer(index, 0, frame.data.length, frame.senderUs, 0);
            } catch (IllegalStateException e) {
                Log.w(TAG, "Codec rejected input buffer " + index, e);
                return;
            }
        }
    }

    /** How much longer this frame has to wait for the audio, 0 if it should go through now. */
    private static long holdTimeNsLocked(Frame frame) {
        // The parameter sets belong to no instant in particular and must never be delayed
        if (frame.senderUs <= 0) return 0;
        long dueNs = AvSync.localNanosForSenderUs(frame.senderUs);
        if (dueNs == AvSync.NO_TIME) return 0; // No audio to sync against yet
        long waitNs = dueNs - System.nanoTime();
        if (waitNs <= 0) return 0;
        // A wait this long is not a lip sync correction, it is a bad reading. Show the frame.
        if (waitNs > MAX_HOLD_NS) return 0;
        return waitNs;
    }

    private static void scheduleFeedLocked(long delayNs) {
        Handler handler = sCodecHandler;
        if (handler == null || sFeedScheduled) return;
        sFeedScheduled = true;
        handler.postDelayed(FEED, Math.max(1L, delayNs / 1_000_000L));
    }

    /** Retries the hand-off once the frame at the head of the queue has become due. */
    private static final Runnable FEED = new Runnable() {
        @Override
        public void run() {
            synchronized (LOCK) {
                sFeedScheduled = false;
                feedCodecLocked();
            }
        }
    };

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
            // Straight to the screen: the wait for the audio already happened before this frame
            // was handed to the decoder, so there is nothing left to time here.
            try {
                codec.releaseOutputBuffer(index, true);
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

    public static void onAudioData(byte[] audio, long rtpTime) {
        AudioPlayer player;
        synchronized (AUDIO_LOCK) {
            if (sAudioPlayer == null) {
                sAudioPlayer = new AudioPlayer();
            }
            player = sAudioPlayer;
        }
        player.play(audio, rtpTime);
    }
}
