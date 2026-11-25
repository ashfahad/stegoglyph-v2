package com.stegoglyph;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class VariationSelectorCodecTest {

    private final MessageCodec codec = new VariationSelectorCodec();

    @Test
    void roundTripAsciiAndEmoji() {
        String secret = "Meet at 9:30 PM 🚀 — bring keys";
        String carrier = "🙂";

        String encoded = codec.encode(secret, carrier);
        assertTrue(encoded.startsWith(carrier));
        assertNotEquals(carrier, encoded);

        String decoded = codec.decode(encoded);
        assertEquals(secret, decoded);
    }

    @Test
    void roundTripComplexUnicodeAndZeroWidthJoiner() {
        String secret = "Family: 👨‍👩‍👧‍👦 | Bangla: বাংলা | Arabic: مرحبا";
        String encoded = codec.encode(secret, "✨");
        assertEquals(secret, codec.decode(encoded));
    }

    @Test
    void detectsChecksumMismatchOnTamperedSelector() {
        String encoded = codec.encode("secret payload", "🔒");
        int[] cps = encoded.codePoints().toArray();

        // Flip one variation selector inside the payload
        boolean flipped = false;
        for (int i = 0; i < cps.length; i++) {
            if (cps[i] >= VariationSelectorCodec.VS_BASE && cps[i] < VariationSelectorCodec.VS_BASE + 16) {
                cps[i] = (cps[i] == VariationSelectorCodec.VS_BASE)
                        ? VariationSelectorCodec.VS_BASE + 1
                        : VariationSelectorCodec.VS_BASE;
                flipped = true;
                break;
            }
        }
        assertTrue(flipped);

        StringBuilder tampered = new StringBuilder();
        for (int cp : cps) {
            tampered.appendCodePoint(cp);
        }

        assertThrows(ChecksumMismatchException.class, () -> codec.decode(tampered.toString()));
    }

    @Test
    void rejectsMissingMarkersAndUnexpectedCodePoints() {
        assertThrows(MalformedPayloadException.class, () -> codec.decode("just plain text"));
        assertThrows(MalformedPayloadException.class, () -> codec.decode("🙂\u2062\u2063"));

        String badInner = "🙂"
                + codePointString(VariationSelectorCodec.START_MARKER)
                + codePointString(VariationSelectorCodec.VS_BASE)
                + "A"
                + codePointString(VariationSelectorCodec.END_MARKER);
        assertThrows(MalformedPayloadException.class, () -> codec.decode(badInner));
    }

    @Test
    void rejectsOddNumberOfNibbles() {
        String oddSelectors = "🙂"
                + codePointString(VariationSelectorCodec.START_MARKER)
                + codePointString(VariationSelectorCodec.VS_BASE)
                + codePointString(VariationSelectorCodec.VS_BASE + 1)
                + codePointString(VariationSelectorCodec.VS_BASE + 2)
                + codePointString(VariationSelectorCodec.END_MARKER);

        assertThrows(MalformedPayloadException.class, () -> codec.decode(oddSelectors));
    }

    @Test
    void strictUtf8RejectsInvalidByteSequenceWithMatchingXorChecksum() {
        // Invalid UTF-8 lone continuation byte 0x80 + 1-byte checksum
        byte[] badUtf8 = new byte[] { (byte) 0x80 };
        int checksum = new XorChecksum().compute(badUtf8) & 0xFF;

        StringBuilder payload = new StringBuilder();
        payload.append("🙂");
        payload.appendCodePoint(VariationSelectorCodec.START_MARKER);
        appendHexByte(payload, badUtf8[0] & 0xFF);
        appendHexByte(payload, checksum);
        payload.appendCodePoint(VariationSelectorCodec.END_MARKER);

        assertThrows(MalformedUtf8Exception.class, () -> codec.decode(payload.toString()));
    }

    @Test
    void enforcesSecretAndCarrierLimits() {
        assertThrows(MalformedPayloadException.class, () -> codec.encode("", "🙂"));
        assertThrows(MalformedPayloadException.class, () -> codec.encode("hi", "   "));

        String oversizedCarrier = "a".repeat(AbstractMessageCodec.DEFAULT_MAX_CARRIER_CODE_POINTS + 1);
        assertThrows(MalformedPayloadException.class, () -> codec.encode("hi", oversizedCarrier));

        String oversizedSecret = "x".repeat(AbstractMessageCodec.DEFAULT_MAX_SECRET_BYTES + 1);
        assertThrows(MalformedPayloadException.class, () -> codec.encode(oversizedSecret, "🙂"));
    }

    @Test
    void splitsGraphemeClusters() {
        VariationSelectorCodec vsCodec = new VariationSelectorCodec();
        List<String> clusters = vsCodec.splitGraphemeClusters("🙂🚀");
        assertEquals(2, clusters.size());
        assertEquals("🙂", clusters.get(0));
        assertEquals("🚀", clusters.get(1));
    }

    private static String codePointString(int cp) {
        return new String(Character.toChars(cp));
    }

    private static void appendHexByte(StringBuilder sb, int value) {
        sb.appendCodePoint(VariationSelectorCodec.VS_BASE + ((value >>> 4) & 0x0F));
        sb.appendCodePoint(VariationSelectorCodec.VS_BASE + (value & 0x0F));
    }
}
