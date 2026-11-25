# StegoGlyph — Unicode-Based Text Steganography and Encoding Tool

StegoGlyph hides UTF-8 text payloads inside Unicode variation selectors (`U+FE00..U+FE0F`) framed between invisible markers (`U+2062` start and `U+2063` end) following a visible carrier string, paired with a non-cryptographic error-detection checksum.

## Security & Integrity Scope

* **Concealment, not encryption:** Variation selectors conceal text visually in plain view; they do **not** encrypt data or provide confidentiality.
* **Basic error detection, not authentication:** The default `XorChecksum` provides basic accidental-corruption detection. It does **not** protect against deliberate forgery or collision attacks (authentication is out of scope for this release).

## Architecture

* **Codec & Inheritance (`com.stegoglyph`):**
  * `MessageCodec` (interface) → `AbstractMessageCodec` → `VariationSelectorCodec`
  * `ChecksumStrategy` (interface) → `XorChecksum`
  * `CodecException` → `MalformedPayloadException`, `ChecksumMismatchException`, `MalformedUtf8Exception`
* **Canonical Backend & UI (`EmojiStegoWebServer`):** Single HTML UI served by the Java backend and backed by `/api/encode` and `/api/decode` (single source of truth), plus `GET /health`.

## Requirements

* **Java 17+**
* **Maven 3.9+**

## Build & Run

```bash
mvn -B clean test
mvn -B clean package
java -jar target/stegoglyph-2.0.0-SNAPSHOT.jar
