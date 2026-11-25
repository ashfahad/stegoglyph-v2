package com.stegoglyph;

public interface MessageCodec {
    String encode(String secretMessage, String carrier);

    String decode(String encodedText);

    String name();
}
