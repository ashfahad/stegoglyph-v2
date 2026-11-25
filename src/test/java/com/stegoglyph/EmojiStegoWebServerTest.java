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

import static org.junit.jupiter.api.Assertions.;

class EmojiStegoWebServerTest {

    private EmojiStegoWebServer server;
    private HttpClient client;
    private String baseUrl;

    @BeforeEach
    void setUp() throws IOException {
        int port = findFreePort();
        server = new EmojiStegoWebServer(port, new VariationSelectorCodec());
        server.start();
        baseUrl = http127.0.0.1 + port;
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
                .uri(URI.create(baseUrl + health))
                .GET()
                .build();
        HttpResponseString res = client.send(req, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, res.statusCode());
        JsonObject json = JsonParser.parseString(res.body()).getAsJsonObject();
        assertEquals(UP, json.get(status).getAsString());
        assertEquals(nosniff, res.headers().firstValue(X-Content-Type-Options).orElse());
    }

    @Test
    void encodeAndDecodeEndpointsRoundTrip() throws Exception {
        String encodeBody = {messagehello api,carrier🙂};
        HttpRequest encodeReq = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + apiencode))
                .header(Content-Type, applicationjson)
                .POST(HttpRequest.BodyPublishers.ofString(encodeBody))
                .build();
        HttpResponseString encodeRes = client.send(encodeReq, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, encodeRes.statusCode());
        JsonObject encodedJson = JsonParser.parseString(encodeRes.body()).getAsJsonObject();
        String encodedText = encodedJson.get(encoded).getAsString();
        assertNotNull(encodedText);

        JsonObject decodePayload = new JsonObject();
        decodePayload.addProperty(encoded, encodedText);
        HttpRequest decodeReq = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + apidecode))
                .header(Content-Type, applicationjson)
                .POST(HttpRequest.BodyPublishers.ofString(decodePayload.toString()))
                .build();
        HttpResponseString decodeRes = client.send(decodeReq, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, decodeRes.statusCode());
        JsonObject decodedJson = JsonParser.parseString(decodeRes.body()).getAsJsonObject();
        assertEquals(hello api, decodedJson.get(message).getAsString());
    }

    @Test
    void rejectsWrongMethodWith405AndWrongContentTypeWith415() throws Exception {
        HttpRequest wrongMethod = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + apiencode))
                .GET()
                .build();
        HttpResponseString res405 = client.send(wrongMethod, HttpResponse.BodyHandlers.ofString());
        assertEquals(405, res405.statusCode());

        HttpRequest wrongCt = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + apiencode))
                .header(Content-Type, textplain)
                .POST(HttpRequest.BodyPublishers.ofString({messagea,carrier🙂}))
                .build();
        HttpResponseString res415 = client.send(wrongCt, HttpResponse.BodyHandlers.ofString());
        assertEquals(415, res415.statusCode());
    }

    @Test
    void rejectsInvalidJsonMissingFieldsAndWrongFieldTypesWith400() throws Exception {
        HttpRequest badJson = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + apiencode))
                .header(Content-Type, applicationjson)
                .POST(HttpRequest.BodyPublishers.ofString({not-valid-json))
                .build();
        assertEquals(400, client.send(badJson, HttpResponse.BodyHandlers.ofString()).statusCode());

        HttpRequest missingField = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + apiencode))
                .header(Content-Type, applicationjson)
                .POST(HttpRequest.BodyPublishers.ofString({messagehi}))
                .build();
        assertEquals(400, client.send(missingField, HttpResponse.BodyHandlers.ofString()).statusCode());

        HttpRequest wrongType = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + apiencode))
                .header(Content-Type, applicationjson)
                .POST(HttpRequest.BodyPublishers.ofString({message123,carrier🙂}))
                .build();
        assertEquals(400, client.send(wrongType, HttpResponse.BodyHandlers.ofString()).statusCode());
    }

    @Test
    void rejectsOversizedBodyWith413() throws Exception {
        byte[] tooLarge = new byte[EmojiStegoWebServer.MAX_REQUEST_BODY_BYTES + 1024];
        for (int i = 0; i  tooLarge.length; i++) {
            tooLarge[i] = 'a';
        }
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + apiencode))
                .header(Content-Type, applicationjson)
                .POST(HttpRequest.BodyPublishers.ofByteArray(tooLarge))
                .build();
        HttpResponseString res = client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertEquals(413, res.statusCode());
    }

    @Test
    void enforcesRateLimitWith429() throws Exception {
        int lastStatus = 0;
        for (int i = 0; i  EmojiStegoWebServer.RATE_LIMIT_PER_MINUTE + 5; i++) {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + health))
                    .GET()
                    .build();
            HttpResponseString res = client.send(req, HttpResponse.BodyHandlers.ofString());
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
