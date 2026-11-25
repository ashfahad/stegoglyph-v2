package com.stegoglyph;

import java.util.Objects;

/**
 * Basic 8-bit rotated XOR error-detection checksum (seed 0x5A).
 * Intended for accidental-corruption detection only; not cryptographic authentication.
 */
public final class XorChecksum implements ChecksumStrategy {
    private static final int INITIAL_SEED = 0x5A;

    private final int seed;

    public XorChecksum() {
        this(INITIAL_SEED);
    }

    public XorChecksum(int seed) {
        this.seed = seed & 0xFF;
    }

    @Override
    public int compute(byte[] data) {
        Objects.requireNonNull(data, "Data bytes must not be null.");
        int state = seed;
        for (byte b : data) {
            state ^= (b & 0xFF);
            state = ((state << 1) | (state >>> 7)) & 0xFF;
        }
        return state;
    }

    @Override
    public int bitWidth() {
        return 8;
    }

    @Override
    public String algorithmName() {
        return "8-bit Rotated XOR (seed 0x" + String.format("%02X", seed) + ")";
    }
}
