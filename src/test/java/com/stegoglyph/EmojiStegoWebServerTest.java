package com.stegoglyph;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class EmojiStegoWebServerTest {

    private static final String TEST_CARRIER = "\uD83D\uDE42";

    private EmojiStegoWebServer server;
    private HttpClient client;
    private String baseUrl;

    @BeforeEach
    void setUp() throws IOException {
        int port = findFreePort();
        server = new EmojiStegoWebServer(port, new VariationSelectorCodec());
        server.start();
        baseUrl = "http://127.0.0.1:" + port;
        client = HttpClient.newHttpClient();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void healthEndpointReturnsUp() throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/health"))
                .GET()
                .build();
        HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, res.statusCode());
        JsonObject json = JsonParser.parseString(res.body()).getAsJsonObject();
        assertEquals("UP", json.get("status").getAsString());
        assertEquals("nosniff", res.headers().firstValue("X-Content-Type-Options").orElse(""));
    }

    @Test
    void rootServesCybersecurityStudioWithEncodeDecodeAndAboutSections() throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/"))
                .GET()
                .build();
        HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        assertEquals(200, res.statusCode());
        assertEquals("text/html; charset=UTF-8", res.headers().firstValue("Content-Type").orElse(""));
        org.junit.jupiter.api.Assertions.assertTrue(res.body().contains("StegoGlyph"));
        org.junit.jupiter.api.Assertions.assertTrue(res.body().contains("data-view=\"encode\""));
        org.junit.jupiter.api.Assertions.assertTrue(res.body().contains("data-view=\"decode\""));
        org.junit.jupiter.api.Assertions.assertTrue(res.body().contains("data-view=\"about\""));
        org.junit.jupiter.api.Assertions.assertTrue(res.body().contains("/api/encode"));
        org.junit.jupiter.api.Assertions.assertTrue(res.body().contains("/api/decode"));
    }

    @Test
    void encodeAndDecodeEndpointsRoundTrip() throws Exception {
        JsonObject encodePayload = new JsonObject();
        encodePayload.addProperty("message", "hello api");
        encodePayload.addProperty("carrier", TEST_CARRIER);

        HttpRequest encodeReq = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/encode"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(encodePayload.toString(), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> encodeRes = client.send(encodeReq, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        assertEquals(200, encodeRes.statusCode());
        JsonObject encodedJson = JsonParser.parseString(encodeRes.body()).getAsJsonObject();
        String encodedText = encodedJson.get("encoded").getAsString();
        assertNotNull(encodedText);

        JsonObject decodePayload = new JsonObject();
        decodePayload.addProperty("encoded", encodedText);
        HttpRequest decodeReq = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/decode"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(decodePayload.toString(), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> decodeRes = client.send(decodeReq, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        assertEquals(200, decodeRes.statusCode());
        JsonObject decodedJson = JsonParser.parseString(decodeRes.body()).getAsJsonObject();
        assertEquals("hello api", decodedJson.get("message").getAsString());
    }

    @Test
    void rejectsWrongMethodWith405AndWrongContentTypeWith415() throws Exception {
        HttpRequest wrongMethod = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/encode"))
                .GET()
                .build();
        HttpResponse<String> res405 = client.send(wrongMethod, HttpResponse.BodyHandlers.ofString());
        assertEquals(405, res405.statusCode());

        JsonObject bodyObj = new JsonObject();
        bodyObj.addProperty("message", "a");
        bodyObj.addProperty("carrier", TEST_CARRIER);

        HttpRequest wrongCt = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/encode"))
                .header("Content-Type", "text/plain")
                .POST(HttpRequest.BodyPublishers.ofString(bodyObj.toString()))
                .build();
        HttpResponse<String> res415 = client.send(wrongCt, HttpResponse.BodyHandlers.ofString());
        assertEquals(415, res415.statusCode());
    }

    @Test
    void rejectsInvalidJsonMissingFieldsAndWrongFieldTypesWith400() throws Exception {
        HttpRequest badJson = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/encode"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{not-valid-json"))
                .build();
        assertEquals(400, client.send(badJson, HttpResponse.BodyHandlers.ofString()).statusCode());

        HttpRequest missingField = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/encode"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"message\":\"hi\"}"))
                .build();
        assertEquals(400, client.send(missingField, HttpResponse.BodyHandlers.ofString()).statusCode());

        HttpRequest wrongType = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/encode"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"message\":123,\"carrier\":\"ok\"}"))
                .build();
        assertEquals(400, client.send(wrongType, HttpResponse.BodyHandlers.ofString()).statusCode());
    }

    @Test
    void rejectsOversizedBodyWith413() throws Exception {
        byte[] tooLarge = new byte[EmojiStegoWebServer.MAX_REQUEST_BODY_BYTES + 1024];
        for (int i = 0; i < tooLarge.length; i++) {
            tooLarge[i] = 'a';
        }
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/encode"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(tooLarge))
                .build();
        HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertEquals(413, res.statusCode());
    }

    @Test
    void enforcesRateLimitWith429() throws Exception {
        int lastStatus = 0;
        for (int i = 0; i < EmojiStegoWebServer.RATE_LIMIT_PER_MINUTE + 5; i++) {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/health"))
                    .GET()
                    .build();
            HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString());
            lastStatus = res.statusCode();
            if (lastStatus == 429) {
                break;
            }
        }
        assertEquals(429, lastStatus);
    }

    private static int findFreePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
