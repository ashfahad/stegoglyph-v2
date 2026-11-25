package com.stegoglyph;

import java.nio.charset.StandardCharsets;
import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Encodes UTF-8 message bytes + 1-byte checksum as Unicode Variation Selectors
 * (U+FE00..U+FE0F) delimited by U+2062 (start) and U+2063 (end) after a carrier string.
 */
public final class VariationSelectorCodec extends AbstractMessageCodec {
    public static final int START_MARKER = 0x2062; // INVISIBLE TIMES
    public static final int END_MARKER = 0x2063;   // INVISIBLE SEPARATOR
    public static final int VS_BASE = 0xFE00;      // VARIATION SELECTOR-1..16

    public VariationSelectorCodec() {
        super(new XorChecksum());
    }

    public VariationSelectorCodec(ChecksumStrategy checksumStrategy) {
        super(checksumStrategy);
    }

    public VariationSelectorCodec(
            ChecksumStrategy checksumStrategy,
            int maxSecretBytes,
            int maxCarrierCodePoints,
            int maxDecodedBytes,
            int maxEncodedCodePoints
    ) {
        super(checksumStrategy, maxSecretBytes, maxCarrierCodePoints, maxDecodedBytes, maxEncodedCodePoints);
    }

    @Override
    public String encode(String secretMessage, String carrier) {
        validateEncodeInputs(secretMessage, carrier);

        byte[] utf8Bytes = secretMessage.getBytes(StandardCharsets.UTF_8);
        int checksumByte = getChecksumStrategy().compute(utf8Bytes) & 0xFF;

        StringBuilder out = new StringBuilder();
        out.append(carrier);
        out.appendCodePoint(START_MARKER);

        for (byte b : utf8Bytes) {
            appendByteAsVariationSelectors(out, b & 0xFF);
        }
        appendByteAsVariationSelectors(out, checksumByte);

        out.appendCodePoint(END_MARKER);

        String encoded = out.toString();
        int encodedCpCount = encoded.codePointCount(0, encoded.length());
        if (encodedCpCount > getMaxEncodedCodePoints()) {
            throw new MalformedPayloadException(
                    "Encoded output exceeds maximum allowed code points (" + getMaxEncodedCodePoints() + ")."
            );
        }
        return encoded;
    }

    @Override
    public String decode(String encodedText) {
        if (encodedText == null || encodedText.isEmpty()) {
            throw new MalformedPayloadException("Encoded text cannot be empty.");
        }

        int encodedCpCount = encodedText.codePointCount(0, encodedText.length());
        if (encodedCpCount > getMaxEncodedCodePoints()) {
            throw new MalformedPayloadException(
                    "Encoded input exceeds maximum allowed code points (" + getMaxEncodedCodePoints() + ")."
            );
        }

        List<Integer> codePoints = new ArrayList<>();
        for (int i = 0; i < encodedText.length(); ) {
            int cp = encodedText.codePointAt(i);
            i += Character.charCount(cp);
            codePoints.add(cp);
        }

        int startIdx = -1;
        int endIdx = -1;
        for (int i = 0; i < codePoints.size(); i++) {
            int cp = codePoints.get(i);
            if (cp == START_MARKER && startIdx == -1) {
                startIdx = i;
            } else if (cp == END_MARKER && startIdx != -1) {
                endIdx = i;
                break;
            }
        }

        if (startIdx == -1 || endIdx == -1 || endIdx <= startIdx + 1) {
            throw new MalformedPayloadException("Missing or invalid StegoGlyph framing markers.");
        }

        List<Integer> nibbles = new ArrayList<>(endIdx - startIdx - 1);
        for (int i = startIdx + 1; i < endIdx; i++) {
            int cp = codePoints.get(i);
            if (cp >= VS_BASE && cp < VS_BASE + 16) {
                nibbles.add(cp - VS_BASE);
            } else {
                throw new MalformedPayloadException(
                        "Unexpected non-variation-selector code point U+"
                                + String.format("%04X", cp) + " inside payload."
                );
            }
        }

        if (nibbles.size() < 4 || nibbles.size() % 2 != 0) {
            throw new MalformedPayloadException("Truncated or odd-length variation-selector payload.");
        }

        int totalBytes = nibbles.size() / 2;
        byte[] decodedAll = new byte[totalBytes];
        for (int i = 0; i < totalBytes; i++) {
            int high = nibbles.get(i * 2);
            int low = nibbles.get(i * 2 + 1);
            decodedAll[i] = (byte) ((high << 4) | low);
        }

        int payloadLen = totalBytes - 1;
        byte[] messageBytes = new byte[payloadLen];
        System.arraycopy(decodedAll, 0, messageBytes, 0, payloadLen);
        int expectedChecksum = decodedAll[payloadLen] & 0xFF;

        int actualChecksum = getChecksumStrategy().compute(messageBytes) & 0xFF;
        if (actualChecksum != expectedChecksum) {
            throw new ChecksumMismatchException(
                    "Checksum mismatch: expected 0x" + String.format("%02X", expectedChecksum)
                            + " but computed 0x" + String.format("%02X", actualChecksum) + "."
            );
        }

        return decodeStrictUtf8(messageBytes);
    }

    public List<String> splitGraphemeClusters(String text) {
        List<String> clusters = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return clusters;
        }
        BreakIterator iterator = BreakIterator.getCharacterInstance(Locale.ROOT);
        iterator.setText(text);
        int start = iterator.first();
        for (int end = iterator.next(); end != BreakIterator.DONE; start = end, end = iterator.next()) {
            clusters.add(text.substring(start, end));
        }
        return clusters;
    }

    @Override
    public String name() {
        return "VariationSelectorCodec (" + getChecksumStrategy().algorithmName() + ")";
    }

    private static void appendByteAsVariationSelectors(StringBuilder out, int byteValue) {
        int highNibble = (byteValue >>> 4) & 0x0F;
        int lowNibble = byteValue & 0x0F;
        out.appendCodePoint(VS_BASE + highNibble);
        out.appendCodePoint(VS_BASE + lowNibble);
    }
}
