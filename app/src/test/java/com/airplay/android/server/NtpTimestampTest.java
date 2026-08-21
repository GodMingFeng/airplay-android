package com.airplay.android.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The audio and the video path each turn an NTP timestamp into microseconds, and lip sync rests
 * entirely on the two agreeing about where zero is. They did not, once: the RAOP timestamps count
 * from 1900 and the mirroring headers from 1970, so the picture came out seventy years behind the
 * sound and the sync was thrown away as nonsense. Nothing in either function looks wrong on its
 * own, which is why the check has to be that they match.
 */
public class NtpTimestampTest {

    /** Seconds between the NTP epoch of 1900 and the Unix epoch of 1970. */
    private static final long EPOCH_OFFSET = 2208988800L;

    /** Builds the 64 bit NTP timestamp as it appears on the wire: seconds, then a binary fraction. */
    private static long ntp(long seconds, long fraction) {
        return (seconds << 32) | (fraction & 0xFFFFFFFFL);
    }

    @Test
    public void bothPathsPlaceTheSameInstantOnTheSameOrigin() {
        long seconds1970 = 1_700_000_000L;
        long fromMirroring = MirroringReceiver.ntpToMicros(ntp(seconds1970, 0));
        long fromRaop = AudioControlServer.ntpToMicros(ntp(seconds1970 + EPOCH_OFFSET, 0));

        assertEquals("the two streams have to share a timeline or they cannot be synchronised",
                fromMirroring, fromRaop);
    }

    /**
     * Anything past 2036 has the top bit of the seconds field set, so the value read as a signed
     * long is negative. Both functions have to treat those 32 bits as unsigned.
     */
    @Test
    public void secondsFieldIsUnsigned() {
        long seconds1900 = 3_900_000_000L; // beyond 2^31, i.e. a negative long once shifted
        long raw = ntp(seconds1900, 0);
        assertTrue("the test itself relies on this being the awkward case", raw < 0);

        assertEquals((seconds1900 - EPOCH_OFFSET) * 1_000_000L,
                AudioControlServer.ntpToMicros(raw));
        assertEquals(seconds1900 * 1_000_000L, MirroringReceiver.ntpToMicros(raw));
    }

    @Test
    public void fractionIsScaledToMicroseconds() {
        // The fraction is a binary fraction of a second, so the top bit alone is exactly half
        assertEquals(500_000L, MirroringReceiver.ntpToMicros(ntp(0, 0x80000000L)));
        assertEquals(250_000L, MirroringReceiver.ntpToMicros(ntp(0, 0x40000000L)));
        assertEquals(1_500_000L, MirroringReceiver.ntpToMicros(ntp(1, 0x80000000L)));
    }
}
