package com.airplay.android.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * RTP sequence numbers are 16 bit and wrap, so the ordinary comparison operators say the wrong
 * thing roughly once every eleven minutes of audio. Everything the receiver does with the reorder
 * buffer, deciding what is stale, what is a duplicate and how far ahead a packet has jumped, is
 * built on {@link AudioReceiver#seqCmp}.
 */
public class SeqCmpTest {

    @Test
    public void ordinaryOrderingHolds() {
        assertTrue(AudioReceiver.seqCmp(100, 99) > 0);
        assertTrue(AudioReceiver.seqCmp(99, 100) < 0);
        assertEquals(0, AudioReceiver.seqCmp(42, 42));
    }

    @Test
    public void wrappingRoundStillCountsAsMovingForward() {
        assertTrue("0 follows 65535, it does not precede it by a whole cycle",
                AudioReceiver.seqCmp(0, 65535) > 0);
        assertTrue(AudioReceiver.seqCmp(65535, 0) < 0);
        assertTrue(AudioReceiver.seqCmp(3, 65530) > 0);
        assertTrue(AudioReceiver.seqCmp(65530, 3) < 0);
    }

    /**
     * Half the space in either direction is as far as ahead can be told from behind. At exactly
     * halfway there is nothing to go on, and the implementation calls both directions ahead rather
     * than picking one; the reorder buffer holds 64 packets, so it flushes long before a gap can
     * grow anywhere near this wide.
     */
    @Test
    public void theSplitIsHalfwayRound() {
        assertTrue(AudioReceiver.seqCmp(0x7FFF, 0) > 0);
        assertTrue(AudioReceiver.seqCmp(0x8001, 0) < 0);
        assertTrue(AudioReceiver.seqCmp(0x8000, 0) > 0);
        assertTrue(AudioReceiver.seqCmp(0, 0x8000) > 0);
    }

    /** The receiver only ever passes it values masked to 16 bits, which is what it assumes. */
    @Test
    public void countingPastTheEndComesBackToTheStart() {
        int last = 65535;
        int next = (last + 1) & 0xFFFF;
        assertEquals(0, next);
        assertTrue(AudioReceiver.seqCmp(next, last) > 0);
    }
}
