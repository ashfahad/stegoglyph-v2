# StegoGlyph — Unicode Steganography Studio

StegoGlyph is a Java-based Unicode text steganography tool with a browser interface, a Swing desktop application, and a command-line entry point. The web interface is served by the Java backend and uses the same codec through the `/api/encode` and `/api/decode` endpoints.

The web experience uses a dark navy, electric green and violet visual system with dedicated Encode, Decode and About sections. It is a presentation-layer redesign: the existing encoding format and API request/response contracts are preserved.

## What it does

- Encodes UTF-8 message bytes into Unicode variation selectors (`U+FE00..U+FE0F`).
- Places the encoded payload after a visible emoji or text carrier prefix.
- Uses invisible framing markers (`U+2062` and `U+2063`).
- Appends a basic XOR checksum for limited accidental-corruption detection.
- Decodes compatible payloads through the Java backend.

## Important security limitations

- **Steganographic concealment, not encryption:** the message is not encrypted. Anyone who knows the format can decode it.
- **Basic error detection, not authentication:** the default 8-bit XOR checksum can detect some accidental changes, but it is not cryptographically secure, collision-resistant, or proof of sender identity.
- **Carrier prefix, not visual embedding:** the visible emoji/text is a prefix before the invisible payload. Data is not embedded into the rendered shape of the emoji.
- **Platform compatibility is not guaranteed:** messaging applications, editors, browsers, and text-processing systems may strip, normalize, or alter default-ignorable Unicode characters. Test the exact sharing path before relying on it.
- Do not use StegoGlyph to protect confidential or sensitive information.

## Architecture

- `MessageCodec` — codec abstraction.
- `AbstractMessageCodec` — shared validation and strict UTF-8 decoding.
- `VariationSelectorCodec` — current variation-selector format.
- `ChecksumStrategy` / `XorChecksum` — pluggable checksum strategy and default implementation.
- `EmojiStegoWebServer` — HTTP server, JSON API, request limits, rate limiting and health endpoint.
- `src/main/resources/web/index.html` — the single source for the browser UI.

## Requirements

- Java 17 or later
- Maven 3.9 or later

## Build and test

```bash
mvn -B clean test
```

Build the executable JAR:

```bash
mvn -B clean package
```

Run the web server:

```bash
java -jar target/stegoglyph-2.0.0-SNAPSHOT.jar
```

The default port is `8080`. The server also reads the `PORT` environment variable, which is suitable for platforms such as Render.

Open `http://localhost:8080/` in a browser.

## Web API

### Health check

```http
GET /health
```

Example response:

```json
{"status":"UP"}
```

### Encode

```http
POST /api/encode
Content-Type: application/json
```

Request:

```json
{"message":"hello","carrier":"🙂"}
```

Response:

```json
{"encoded":"...","checksumType":"basic-error-detection"}
```

### Decode

```http
POST /api/decode
Content-Type: application/json
```

Request:

```json
{"encoded":"..."}
```

Response:

```json
{"message":"hello","checksumType":"basic-error-detection"}
```

API errors are returned as JSON with an `error` field. Existing endpoint paths and field names are retained by the UI.

## Project notes

- The browser does not implement a second copy of the codec. It calls the Java API for encoding and decoding.
- The browser UI is stored once at `src/main/resources/web/index.html` and loaded from the classpath by the server.
- Keep the HTML resource and Java API contract in sync when making future changes.
- Never log secret message bodies or encoded payload contents on the server.
