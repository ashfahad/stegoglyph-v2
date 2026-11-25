package com.stegoglyph;

public interface ChecksumStrategy {
    int compute(byte[] data);

    int bitWidth();

    String algorithmName();
}
