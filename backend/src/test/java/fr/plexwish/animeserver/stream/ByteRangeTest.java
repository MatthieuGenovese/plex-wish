package fr.plexwish.animeserver.stream;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class ByteRangeTest {

    private static final long SIZE = 1000;

    private static ByteRange partial(String header) {
        return assertInstanceOf(ByteRange.Partial.class, ByteRange.evaluate(header, SIZE)).range();
    }

    @Test
    void noHeaderMeansFullFile() {
        assertInstanceOf(ByteRange.Full.class, ByteRange.evaluate(null, SIZE));
        assertInstanceOf(ByteRange.Full.class, ByteRange.evaluate("  ", SIZE));
    }

    @Test
    void closedRange() {
        assertEquals(new ByteRange(0, 99), partial("bytes=0-99"));
        assertEquals(new ByteRange(500, 599), partial("bytes=500-599"));
        assertEquals(100, partial("bytes=500-599").length());
    }

    @Test
    void openRangeGoesToEndOfFile() {
        assertEquals(new ByteRange(0, 999), partial("bytes=0-"));
        assertEquals(new ByteRange(900, 999), partial("bytes=900-"));
    }

    @Test
    void endBeyondFileIsClamped() {
        assertEquals(new ByteRange(990, 999), partial("bytes=990-5000"));
    }

    @Test
    void suffixRange() {
        assertEquals(new ByteRange(800, 999), partial("bytes=-200"));
        assertEquals(new ByteRange(0, 999), partial("bytes=-5000"));
    }

    @Test
    void onlyFirstOfMultipleRangesIsServed() {
        assertEquals(new ByteRange(0, 9), partial("bytes=0-9, 20-29"));
    }

    @Test
    void unitIsCaseInsensitiveAndSpacesTolerated() {
        assertEquals(new ByteRange(1, 2), partial("Bytes= 1 - 2 "));
    }

    @Test
    void unsatisfiableRanges() {
        assertInstanceOf(ByteRange.Unsatisfiable.class, ByteRange.evaluate("bytes=1000-", SIZE));
        assertInstanceOf(ByteRange.Unsatisfiable.class, ByteRange.evaluate("bytes=5000-6000", SIZE));
        assertInstanceOf(ByteRange.Unsatisfiable.class, ByteRange.evaluate("bytes=-0", SIZE));
        assertInstanceOf(ByteRange.Unsatisfiable.class, ByteRange.evaluate("bytes=0-", 0));
    }

    @Test
    void malformedHeadersAreIgnored() {
        for (String h : new String[]{"bytes=abc", "bytes=10-5", "items=0-10", "bytes=-", "bytes=5",
                "bytes=99999999999999999999-", "bytes=+1-2"}) {
            assertInstanceOf(ByteRange.Full.class, ByteRange.evaluate(h, SIZE), h);
        }
    }

    @Test
    void contentRangeHeader() {
        assertEquals("bytes 0-99/1000", new ByteRange(0, 99).contentRange(SIZE));
    }
}
