package com.airplay.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

/**
 * {@link AvSync} is where the sender's timeline, the audio RTP counter and our own clock are tied
 * together, and it is arithmetic all the way down: nothing here needs a device, only a clear
 * statement of what each number means.
 */
public class AvSyncTest {

    private static final long AUDIO_RATE = 44100;

    @Before
    public void forgetPreviousSession() {
        AvSync.reset();
    }

    @Test
    public void nothingIsClaimedBeforeTheFirstSyncPacket() {
        assertEquals(AvSync.NO_TIME, AvSync.senderUsForRtp(12345));
        assertEquals(AvSync.NO_TIME, AvSync.localNanosForSenderUs(12345));
    }

    @Test
    public void rtpCountsForwardOnTheSenderTimeline() {
        AvSync.onAudioSync(1000, 5_000_000);

        assertEquals(5_000_000, AvSync.senderUsForRtp(1000));
        assertEquals("one second of samples is one second later",
                6_000_000, AvSync.senderUsForRtp(1000 + AUDIO_RATE));
        assertEquals(4_000_000, AvSync.senderUsForRtp(1000 - AUDIO_RATE));
    }

    /**
     * The RTP timestamp is a 32 bit counter and rolls over about every 27 hours at 44.1kHz. Read
     * as a plain difference the roll-over would look like a jump of 27 hours, which is well past
     * the plausibility check and would silently drop the sync for good.
     */
    @Test
    public void rtpRollingOverIsJustTheNextSample() {
        long justBeforeWrap = 0xFFFFFFF0L;
        AvSync.onAudioSync(justBeforeWrap, 5_000_000);

        // 32 samples past the wrap, i.e. 16 before it and 16 after
        long expected = 5_000_000 + 32 * 1_000_000L / AUDIO_RATE;
        assertEquals(expected, AvSync.senderUsForRtp(0x10L));
    }

    @Test
    public void videoIsDueRelativeToWhatTheSpeakersArePlaying() {
        long localNs = 1_000_000_000L;
        long senderUs = 8_000_000L;
        AvSync.onAudioPresenting(localNs, senderUs);

        assertEquals("a frame from the instant now being heard is due now",
                localNs, AvSync.localNanosForSenderUs(senderUs));
        assertEquals("a frame 100ms later than the sound is due 100ms later",
                localNs + 100_000_000L, AvSync.localNanosForSenderUs(senderUs + 100_000));
        assertEquals(localNs - 30_000_000L, AvSync.localNanosForSenderUs(senderUs - 30_000));
    }

    /**
     * A reading this far out means the two are not on the same clock, which is what a mismatched
     * epoch looked like. Holding a frame for that long would freeze the screen, so it is refused
     * and the picture goes out unsynchronised instead.
     */
    @Test
    public void aRidiculousSkewIsRefusedRatherThanActedOn() {
        long senderUs = 8_000_000L;
        AvSync.onAudioPresenting(1_000_000_000L, senderUs);

        assertEquals(AvSync.NO_TIME, AvSync.localNanosForSenderUs(senderUs + 60_000_000L));
        assertEquals(AvSync.NO_TIME, AvSync.localNanosForSenderUs(senderUs - 60_000_000L));
        // A whole epoch out, which is what a raw RAOP timestamp against a mirroring one gives
        assertEquals(AvSync.NO_TIME,
                AvSync.localNanosForSenderUs(senderUs + 2_208_988_800_000_000L));
    }

    /** One bad reading must not put the sync away for the rest of the session. */
    @Test
    public void aGoodReadingAfterABadOneStillWorks() {
        long localNs = 1_000_000_000L;
        long senderUs = 8_000_000L;
        AvSync.onAudioPresenting(localNs, senderUs);

        assertEquals(AvSync.NO_TIME, AvSync.localNanosForSenderUs(senderUs + 60_000_000L));
        assertEquals(localNs + 50_000_000L, AvSync.localNanosForSenderUs(senderUs + 50_000));
    }

    @Test
    public void videoRunsFreeOnceTheAudioPathIsGone() {
        AvSync.onAudioPresenting(1_000_000_000L, 8_000_000L);
        AvSync.onAudioStopped();

        assertEquals(AvSync.NO_TIME, AvSync.localNanosForSenderUs(8_000_000L));
    }

    /**
     * The two halves are set from different threads and are read independently, so having one is
     * not having the other.
     */
    @Test
    public void theTwoAnchorsAreIndependent() {
        AvSync.onAudioSync(1000, 5_000_000);
        assertTrue(AvSync.senderUsForRtp(1000) != AvSync.NO_TIME);
        assertEquals("a sync packet alone says nothing about our own clock",
                AvSync.NO_TIME, AvSync.localNanosForSenderUs(5_000_000));
    }
}
