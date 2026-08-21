package com.airplay.android;

import android.util.Log;

/**
 * Ties the audio and the video path to a single timeline so that a picture is shown at the moment
 * the sound recorded with it is heard.
 *
 * <p>The timeline is the <em>sender's</em> clock, which both streams already reference:
 *
 * <ul>
 *   <li>every mirroring frame header carries the sender's NTP time directly;
 *   <li>every audio RTP packet carries a 44100&nbsp;Hz timestamp, and the RAOP sync packets on the
 *       control channel say which NTP time a given RTP timestamp corresponds to.
 * </ul>
 *
 * <p>Only the <em>difference</em> between the two matters, so there is no need to know how the
 * sender's clock relates to ours and no NTP client is required. The link to the local clock comes
 * from the audio path alone: {@link android.media.AudioTrack#getTimestamp} reports which sample
 * reached the output and when, which is a direct reading of "at local time L the speakers were
 * playing sender time S". Video is then simply scheduled against that reading, so any drift
 * between the two devices is followed automatically instead of being compensated by a constant.
 */
public final class AvSync {
    private static final String TAG = "AvSync";

    public static final long NO_TIME = Long.MIN_VALUE;

    private static final long AUDIO_RATE = 44100;
    /**
     * Beyond this the video and audio timestamps cannot plausibly be describing the same instant,
     * which means they are not on the same clock after all. Such a reading is ignored rather than
     * acted on, because holding a frame back for minutes is far worse than not syncing at all.
     */
    private static final long MAX_PLAUSIBLE_SKEW_US = 5_000_000L;
    private static final long LOG_INTERVAL_NS = 5_000_000_000L;

    /** Immutable pair, so a reader can never see one half of an update. */
    private static final class Anchor {
        final long x;
        final long y;

        Anchor(long x, long y) {
            this.x = x;
            this.y = y;
        }
    }

    /** RTP timestamp to sender NTP microseconds, from the RAOP sync packets. */
    private static volatile Anchor sRtpToNtp;
    /** Local nanoTime to sender NTP microseconds, from the audio output timestamp. */
    private static volatile Anchor sAudioClock;

    private static long sLastLogNs;
    private static long sLastWarnNs;

    private AvSync() {
    }

    /** Forgets everything; called when a session starts or the audio path goes away. */
    public static void reset() {
        sRtpToNtp = null;
        sAudioClock = null;
    }

    /**
     * A RAOP sync packet, which is the only thing that says how the audio RTP timestamps relate to
     * the sender's NTP clock, the same clock the mirroring headers are stamped with.
     */
    public static void onAudioSync(long rtpTime, long senderNtpUs) {
        Anchor previous = sRtpToNtp;
        sRtpToNtp = new Anchor(rtpTime, senderNtpUs);
        if (previous == null) {
            Log.i(TAG, "Audio clock anchored: rtp=" + rtpTime + " -> senderNtp=" + senderNtpUs + "us");
        }
    }

    /** Sender NTP time of an audio sample, or {@link #NO_TIME} before the first sync packet. */
    public static long senderUsForRtp(long rtpTime) {
        Anchor anchor = sRtpToNtp;
        if (anchor == null) return NO_TIME;
        // The RTP timestamp is a 32 bit counter, so take the difference in that width
        int delta = (int) (rtpTime - anchor.x);
        return anchor.y + delta * 1_000_000L / AUDIO_RATE;
    }

    /**
     * Reports that at {@code localNs} the audio output was playing the sample recorded at
     * {@code senderUs}. This is the single point where the sender's timeline is pinned to ours.
     */
    public static void onAudioPresenting(long localNs, long senderUs) {
        sAudioClock = new Anchor(localNs, senderUs);
    }

    /** Called when the audio path stops, so video goes back to being shown as soon as decoded. */
    public static void onAudioStopped() {
        sAudioClock = null;
    }

    /**
     * Local time at which a picture stamped {@code senderUs} should be on screen, or
     * {@link #NO_TIME} while there is nothing to sync against.
     */
    public static long localNanosForSenderUs(long senderUs) {
        Anchor clock = sAudioClock;
        if (clock == null) return NO_TIME;

        long now = System.nanoTime();
        long skewUs = senderUs - clock.y;
        if (skewUs > MAX_PLAUSIBLE_SKEW_US || skewUs < -MAX_PLAUSIBLE_SKEW_US) {
            // Either the two streams are not on the same clock or the audio reading went stale.
            // Showing frames late is worse than showing them unsynchronised, so this reading is
            // ignored; the next one may well be sound again.
            if (now - sLastWarnNs > LOG_INTERVAL_NS) {
                sLastWarnNs = now;
                Log.e(TAG, "Video is " + (skewUs / 1000) + "ms from the audio clock, ignoring it");
            }
            return NO_TIME;
        }

        long due = clock.x + skewUs * 1000L;
        if (now - sLastLogNs > LOG_INTERVAL_NS) {
            sLastLogNs = now;
            Log.i(TAG, "Holding video " + ((due - now) / 1_000_000L) + "ms to match audio");
        }
        return due;
    }
}
