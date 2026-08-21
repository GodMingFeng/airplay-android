package com.airplay.android;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTimestamp;
import android.media.AudioTrack;
import android.os.Build;
import android.util.Log;

/**
 * Writes decoded PCM to an {@link AudioTrack}, keeping the amount of audio waiting in the track
 * small and reporting back where the sound has actually got to.
 *
 * <p>Two separate jobs. The first is to bound the queue: audio arrives faster than it plays by a
 * few dozen ppm, so without a ceiling the track drifts ever further behind and no amount of
 * synchronisation can pull the picture back to it. The second is to publish the audio clock, that
 * is which point of the sender's timeline is leaving the speakers right now, which is what
 * {@link AvSync} hands to the video path so that a frame is shown exactly when its sound is heard.
 */
public class AudioPlayer {
    private static final String TAG = "AudioPlayer";

    private static final int SAMPLE_RATE = 44100;
    private static final int BYTES_PER_FRAME = 4; // stereo, 16 bit

    /**
     * Ceiling on the audio held by the track. Anything arriving above it is dropped, which caps
     * how far behind the picture the sound can get. Also absorbs the sender/receiver clock drift:
     * a few dozen ppm means one dropped packet every couple of minutes.
     */
    private static final int MAX_QUEUED_MS = 80;
    /**
     * Slack between the ceiling and the size of the track buffer, so that the packet which tips
     * the queue over the ceiling still fits whole instead of being written short.
     */
    private static final int SLACK_FRAMES = 1024; // a little over two packets

    private AudioTrack audioTrack;
    private int maxQueuedFrames;
    private int startFrames;

    /** Total frames accepted by the track, in the same origin as the playback head. */
    private long framesWritten;
    private boolean playbackStarted;

    /**
     * Sender time of the sample sitting at {@link #anchorFrame} in the track. Together they turn
     * any playback position into a point on the sender's timeline.
     */
    private long anchorFrame;
    private long anchorSenderUs = AvSync.NO_TIME;
    /** How often the output timestamp is read back, in packets of about 11ms each. */
    private static final int CLOCK_POLL_PACKETS = 10;

    // getPlaybackHeadPosition() is a 32 bit frame counter, unwrapped here into headBase/lastHead
    private long headBase;
    private int lastHead;

    private long packetCount;
    private long droppedPackets;
    /**
     * Consecutive packets refused by the ceiling. The ceiling trusts the playback head, so were it
     * ever to stop advancing the stream would go quiet for good; after this many refusals the next
     * packet is written regardless. Too much delay beats no sound.
     */
    private int consecutiveDrops;
    private static final int MAX_CONSECUTIVE_DROPS = 50; // about half a second of audio

    /** Delay from handing a sample to the track until it leaves the speakers, -1 while unknown. */
    private static volatile int sOutputLatencyMs = -1;

    public AudioPlayer() {
        int framesPerMs = SAMPLE_RATE / 1000;
        maxQueuedFrames = MAX_QUEUED_MS * framesPerMs;
        try {
            int channelConfig = AudioFormat.CHANNEL_OUT_STEREO;
            int encoding = AudioFormat.ENCODING_PCM_16BIT;

            int minBufferSize = AudioTrack.getMinBufferSize(SAMPLE_RATE, channelConfig, encoding);
            int minBufferFrames = minBufferSize / BYTES_PER_FRAME;
            // The mixer pulls whole periods, so a ceiling under the reported minimum would hold
            // the queue below what the track needs to run at all. The margin on top keeps it from
            // sitting exactly on that edge and underrunning on every jitter.
            maxQueuedFrames = Math.max(maxQueuedFrames, minBufferFrames * 5 / 4);
            // The track does not begin playing until its buffer is full. Asking for a buffer any
            // larger than the ceiling is therefore self defeating: the ceiling refuses the very
            // packets that would fill it, and the sound stays away for as long as it takes the
            // escape hatch below to trickle the difference in. Sized like this, reaching the
            // ceiling and filling the buffer are the same event.
            int bufferSize = (maxQueuedFrames + SLACK_FRAMES) * BYTES_PER_FRAME;

            AudioAttributes attrs = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build();

            AudioFormat format = new AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(channelConfig)
                    .setEncoding(encoding)
                    .build();

            // Deliberately no PERFORMANCE_MODE_LOW_LATENCY: asking for it on this TV produced a
            // track that accepted data but never advanced its playback head, i.e. total silence.
            // The TV's own audio path dominates the delay anyway, so there was nothing to win.
            audioTrack = new AudioTrack.Builder()
                    .setAudioAttributes(attrs)
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(bufferSize)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build();

            // What was asked for is not always what was granted, and the ceiling has to stay under
            // whatever the track actually has, or writes get truncated and samples disappear
            // instead of being dropped deliberately
            int trackFrames = maxQueuedFrames + SLACK_FRAMES;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                int granted = audioTrack.getBufferSizeInFrames();
                if (granted > 0) {
                    trackFrames = granted;
                    maxQueuedFrames = Math.min(maxQueuedFrames, granted - SLACK_FRAMES);
                }
            }
            // Bank the whole buffer before starting, so the track has what it is waiting for the
            // moment play() is called instead of several seconds later
            startFrames = trackFrames;

            Log.i(TAG, "AudioTrack initialized, buffer=" + (trackFrames * 1000 / SAMPLE_RATE)
                    + "ms, minBufferSize=" + (minBufferFrames * 1000 / SAMPLE_RATE)
                    + "ms, ceiling=" + (maxQueuedFrames * 1000 / SAMPLE_RATE) + "ms");
        } catch (Exception e) {
            Log.e(TAG, "Failed to init AudioTrack", e);
        }
    }

    /**
     * Sets the playback gain, which is how the sender's volume slider reaches the speakers.
     *
     * <p>Applied to the track rather than to the samples: the track scales in the mixer, so the
     * decoded PCM is left alone and the audio clock published from it stays exactly as it was.
     * A track carries no gain over from the one before it, so the caller has to reapply this to
     * every track a session builds.
     *
     * @param gain 0 for silence, 1 for the samples as they arrive
     */
    public synchronized void setVolume(float gain) {
        AudioTrack track = audioTrack;
        if (track == null) return;
        try {
            track.setVolume(gain);
            Log.i(TAG, "Volume set to " + gain);
        } catch (Exception e) {
            Log.w(TAG, "Could not set the volume to " + gain, e);
        }
    }

    /**
     * Writes one decoded packet. Synchronised against {@link #release}, which now happens at the end
     * of every session rather than only when the app goes away: without it a teardown could pull the
     * track out from under a write. The monitor is uncontended in the ordinary case, one packet every
     * eleven milliseconds against nothing else.
     */
    public synchronized void play(byte[] audio, long rtpTime) {
        AudioTrack track = audioTrack;
        if (track == null || audio.length == 0) return;
        try {
            packetCount++;
            long queued = queuedFrames(track);

            if (!playbackStarted) {
                // Bank enough that the mixer can pull its first period straight away
                writeAll(track, audio, rtpTime);
                if (framesWritten >= startFrames) {
                    track.play();
                    playbackStarted = true;
                    Log.i(TAG, "Playback started with " + (framesWritten * 1000 / SAMPLE_RATE)
                            + "ms banked");
                }
                return;
            }

            // The ceiling applies from the first packet after play(). Left off, the backlog that
            // builds while the set's audio path spins up is pushed at a track that cannot hold it
            // and comes back as short writes, which is heard as a stutter on connecting.
            if (queued > maxQueuedFrames && consecutiveDrops < MAX_CONSECUTIVE_DROPS) {
                // Dropping the newest packet is what keeps the delay bounded; dropping the oldest
                // would shorten the queue just as much but throw away sound already due to play
                droppedPackets++;
                consecutiveDrops++;
            } else {
                if (consecutiveDrops >= MAX_CONSECUTIVE_DROPS) {
                    Log.w(TAG, "Playback head stalled at " + lastHead + " (state="
                            + track.getPlayState() + "), letting audio through anyway");
                }
                consecutiveDrops = 0;
                writeAll(track, audio, rtpTime);
            }

            if (packetCount % CLOCK_POLL_PACKETS == 0) {
                publishAudioClock(track);
            }
            if (packetCount % 500 == 0) {
                reportLatency(track, queued);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error writing audio", e);
        }
    }

    /**
     * Writes the whole packet, advancing the frame counter by what the track actually took. A
     * short write means the buffer filled up, which the ceiling above is meant to prevent.
     */
    private void writeAll(AudioTrack track, byte[] audio, long rtpTime) {
        // Pin this packet's first sample to its position in the track before writing it, so the
        // playback position can later be read back as a point on the sender's timeline
        long senderUs = AvSync.senderUsForRtp(rtpTime);
        if (senderUs != AvSync.NO_TIME) {
            anchorFrame = framesWritten;
            anchorSenderUs = senderUs;
        }

        int offset = 0;
        while (offset < audio.length) {
            int written = track.write(audio, offset, audio.length - offset,
                    AudioTrack.WRITE_NON_BLOCKING);
            if (written <= 0) {
                if (written < 0) Log.w(TAG, "AudioTrack.write failed: " + written);
                else droppedPackets++;
                break;
            }
            offset += written;
        }
        framesWritten += offset / BYTES_PER_FRAME;
    }

    /**
     * Reads back which sample has actually reached the output and when, and republishes it as a
     * point on the sender's timeline. This is the measurement the video side schedules against;
     * because it comes from the hardware it already accounts for whatever the TV adds downstream.
     */
    private void publishAudioClock(AudioTrack track) {
        if (anchorSenderUs == AvSync.NO_TIME) return;
        AudioTimestamp ts = new AudioTimestamp();
        if (!track.getTimestamp(ts)) return;
        long senderUs = anchorSenderUs
                + (ts.framePosition - anchorFrame) * 1_000_000L / SAMPLE_RATE;
        AvSync.onAudioPresenting(ts.nanoTime, senderUs);
    }

    /** Frames handed over but not yet played, i.e. the delay the track is currently adding. */
    private long queuedFrames(AudioTrack track) {
        if (!playbackStarted) return framesWritten;
        int head = track.getPlaybackHeadPosition();
        if (head < lastHead) {
            headBase += 1L << 32; // the counter is 32 bit and wraps after about 27 hours
        }
        lastHead = head;
        long played = headBase + (head & 0xFFFFFFFFL);
        long queued = framesWritten - played;
        return queued > 0 ? queued : 0;
    }

    /**
     * Logs the real end to end audio delay. {@link AudioTrack#getTimestamp} counts frames that
     * have reached the output, so unlike the queue depth this also covers whatever the TV's audio
     * path adds downstream, which is the half of the offset we cannot see from here.
     */
    private void reportLatency(AudioTrack track, long queued) {
        int totalMs = -1;
        AudioTimestamp ts = new AudioTimestamp();
        if (track.getTimestamp(ts) && ts.framePosition > 0) {
            long aheadFrames = framesWritten - ts.framePosition;
            long presentedAtNs = ts.nanoTime + aheadFrames * 1_000_000_000L / SAMPLE_RATE;
            totalMs = (int) ((presentedAtNs - System.nanoTime()) / 1_000_000L);
            sOutputLatencyMs = totalMs;
        }
        int underruns = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N ? track.getUnderrunCount() : -1;
        Log.i(TAG, "Packet #" + packetCount + ": queued=" + (queued * 1000 / SAMPLE_RATE)
                + "ms, output=" + totalMs + "ms, dropped=" + droppedPackets
                + ", underruns=" + underruns + ", head=" + lastHead);
    }

    /**
     * Last measured delay between a sample being written and it leaving the speakers, or -1 if it
     * could not be measured. This is how far the sound trails the picture, since video is shown as
     * soon as it is decoded.
     */
    public static int getOutputLatencyMs() {
        return sOutputLatencyMs;
    }

    public synchronized void release() {
        AudioTrack track = audioTrack;
        audioTrack = null;
        sOutputLatencyMs = -1;
        AvSync.onAudioStopped();
        if (track != null) {
            try {
                track.pause();
                track.flush();
                track.stop();
            } catch (Exception e) { /* ignore */ }
            track.release();
        }
    }
}
