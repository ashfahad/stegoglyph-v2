package com.stegoglyph;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

public abstract class AbstractMessageCodec implements MessageCodec {
    public static final int DEFAULT_MAX_SECRET_BYTES = 4096;
    public static final int DEFAULT_MAX_CARRIER_CODE_POINTS = 16;
    public static final int DEFAULT_MAX_DECODED_BYTES = 4096;
    public static final int DEFAULT_MAX_ENCODED_CODE_POINTS = 20000;

    private final ChecksumStrategy checksumStrategy;
    private final int maxSecretBytes;
    private final int maxCarrierCodePoints;
    private final int maxDecodedBytes;
    private final int maxEncodedCodePoints;

    protected AbstractMessageCodec(ChecksumStrategy checksumStrategy) {
        this(
                checksumStrategy,
                DEFAULT_MAX_SECRET_BYTES,
                DEFAULT_MAX_CARRIER_CODE_POINTS,
                DEFAULT_MAX_DECODED_BYTES,
                DEFAULT_MAX_ENCODED_CODE_POINTS
        );
    }

    protected AbstractMessageCodec(
            ChecksumStrategy checksumStrategy,
            int maxSecretBytes,
            int maxCarrierCodePoints,
            int maxDecodedBytes,
            int maxEncodedCodePoints
    ) {
        if (checksumStrategy == null) {
            throw new IllegalArgumentException("ChecksumStrategy cannot be null.");
        }
        if (maxSecretBytes <= 0 || maxCarrierCodePoints <= 0 || maxDecodedBytes <= 0 || maxEncodedCodePoints <= 0) {
            throw new IllegalArgumentException("Size limits must be positive.");
        }
        this.checksumStrategy = checksumStrategy;
        this.maxSecretBytes = maxSecretBytes;
        this.maxCarrierCodePoints = maxCarrierCodePoints;
        this.maxDecodedBytes = maxDecodedBytes;
        this.maxEncodedCodePoints = maxEncodedCodePoints;
    }

    protected ChecksumStrategy getChecksumStrategy() {
        return checksumStrategy;
    }

    protected int getMaxSecretBytes() {
        return maxSecretBytes;
    }

    protected int getMaxCarrierCodePoints() {
        return maxCarrierCodePoints;
    }

    protected int getMaxDecodedBytes() {
        return maxDecodedBytes;
    }

    protected int getMaxEncodedCodePoints() {
        return maxEncodedCodePoints;
    }

    protected void validateEncodeInputs(String secretMessage, String carrier) {
        if (secretMessage == null || secretMessage.isEmpty()) {
            throw new MalformedPayloadException("Secret message cannot be empty.");
        }
        if (carrier == null || carrier.trim().isEmpty()) {
            throw new MalformedPayloadException("Carrier cannot be empty.");
        }
        int carrierCpCount = carrier.codePointCount(0, carrier.length());
        if (carrierCpCount > maxCarrierCodePoints) {
            throw new MalformedPayloadException(
                    "Carrier exceeds maximum allowed code points (" + maxCarrierCodePoints + ")."
            );
        }
        byte[] utf8 = secretMessage.getBytes(StandardCharsets.UTF_8);
        if (utf8.length > maxSecretBytes) {
            throw new MalformedPayloadException(
                    "Secret message exceeds maximum allowed UTF-8 bytes (" + maxSecretBytes + ")."
            );
        }
    }

    protected String decodeStrictUtf8(byte[] bytes) {
        if (bytes.length > maxDecodedBytes) {
            throw new MalformedPayloadException(
                    "Decoded payload exceeds maximum allowed bytes (" + maxDecodedBytes + ")."
            );
        }
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            return decoder.decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException ex) {
            throw new MalformedUtf8Exception("Invalid UTF-8 byte sequence in payload.", ex);
        }
    }
}
