package com.stegoglyph;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Hardened HTTP server and single-source web UI for StegoGlyph.
 * Uses VariationSelectorCodec as the canonical codec and enforces explicit
 * request, payload, rate, and concurrency limits.
 */
public class EmojiStegoWebServer {
    public static final int DEFAULT_PORT = 8080;
    public static final int MAX_REQUEST_BODY_BYTES = 64 * 1024; // 64 KB
    public static final int MAX_CONCURRENT_REQUESTS = 16;
    public static final int MAX_QUEUE_SIZE = 32;
    public static final int RATE_LIMIT_PER_MINUTE = 60;
    private static final long RATE_WINDOW_MS = 60_000L;

    private final HttpServer server;
    private final ThreadPoolExecutor executor;
    private final Semaphore activeRequestSlots;
    private final MessageCodec codec;
    private final ConcurrentHashMap<String, RateBucket> rateLimits = new ConcurrentHashMap<>();

    public EmojiStegoWebServer(int port) throws IOException {
        this(port, new VariationSelectorCodec());
    }

    public EmojiStegoWebServer(int port, MessageCodec codec) throws IOException {
        if (codec == null) {
            throw new IllegalArgumentException("MessageCodec cannot be null.");
        }
        this.codec = codec;
        this.activeRequestSlots = new Semaphore(MAX_CONCURRENT_REQUESTS);
        this.executor = new ThreadPoolExecutor(
                4,
                MAX_CONCURRENT_REQUESTS,
                30L,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(MAX_QUEUE_SIZE)
        );
        this.executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());

        this.server = HttpServer.create(new InetSocketAddress(port), MAX_QUEUE_SIZE);
        this.server.setExecutor(this.executor);

        this.server.createContext("/", this::handleRoot);
        this.server.createContext("/health", this::handleHealth);
        this.server.createContext("/api/encode", this::handleEncode);
        this.server.createContext("/api/decode", this::handleDecode);
    }

    public void start() {
        server.start();
    }

    public void stop(int delaySeconds) {
        server.stop(delaySeconds);
        executor.shutdownNow();
    }

    public int getPort() {
        return server.getAddress().getPort();
    }

    public static void main(String[] args) throws IOException {
        int port = resolvePort(args);
        EmojiStegoWebServer app = new EmojiStegoWebServer(port, new VariationSelectorCodec());
        app.start();
        System.out.println("StegoGlyph server started on port " + app.getPort()
                + " using " + app.codec.name());
    }

    private static int resolvePort(String[] args) {
        if (args != null && args.length > 0) {
            try {
                int parsed = Integer.parseInt(args[0].trim());
                if (parsed > 0 && parsed <= 65535) {
                    return parsed;
                }
            } catch (NumberFormatException ignored) {
                // Fall through to env/default
            }
        }
        String envPort = System.getenv("PORT");
        if (envPort != null && !envPort.isBlank()) {
            try {
                int parsed = Integer.parseInt(envPort.trim());
                if (parsed > 0 && parsed <= 65535) {
                    return parsed;
                }
            } catch (NumberFormatException ignored) {
                // Fall through to default
            }
        }
        return DEFAULT_PORT;
    }

    private void handleRoot(HttpExchange exchange) throws IOException {
        boolean slotAcquired = false;
        try {
            if (!(slotAcquired = acquireSlotOrOverload(exchange))) {
                return;
            }
            if (!checkRateLimit(exchange)) {
                return;
            }
            String method = exchange.getRequestMethod().toUpperCase(Locale.ROOT);
            if (!"GET".equals(method) && !"HEAD".equals(method)) {
                sendError(exchange, 405, "Method Not Allowed. Use GET.");
                return;
            }
            String path = exchange.getRequestURI().getPath();
            if (!"/".equals(path) && !"/index.html".equals(path)) {
                sendError(exchange, 404, "Not Found.");
                return;
            }
            byte[] htmlBytes = loadWebUi();
            Headers headers = exchange.getResponseHeaders();
            applyCommonHeaders(exchange, headers, "text/html; charset=UTF-8");
            if ("HEAD".equals(method)) {
                exchange.sendResponseHeaders(200, -1);
            } else {
                exchange.sendResponseHeaders(200, htmlBytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(htmlBytes);
                }
            }
        } finally {
            if (slotAcquired) {
                activeRequestSlots.release();
            }
            exchange.close();
        }
    }

    private void handleHealth(HttpExchange exchange) throws IOException {
        boolean slotAcquired = false;
        try {
            if (!(slotAcquired = acquireSlotOrOverload(exchange))) {
                return;
            }
            if (!checkRateLimit(exchange)) {
                return;
            }
            String method = exchange.getRequestMethod().toUpperCase(Locale.ROOT);
            if (!"GET".equals(method) && !"HEAD".equals(method)) {
                sendError(exchange, 405, "Method Not Allowed. Use GET.");
                return;
            }
            byte[] body = "{\"status\":\"UP\"}".getBytes(StandardCharsets.UTF_8);
            applyCommonHeaders(exchange, exchange.getResponseHeaders(), "application/json; charset=UTF-8");
            if ("HEAD".equals(method)) {
                exchange.sendResponseHeaders(200, -1);
            } else {
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(body);
                }
            }
        } finally {
            if (slotAcquired) {
                activeRequestSlots.release();
            }
            exchange.close();
        }
    }

    private void handleEncode(HttpExchange exchange) throws IOException {
        boolean slotAcquired = false;
        try {
            if (!(slotAcquired = acquireSlotOrOverload(exchange))) {
                return;
            }
            if (!checkRateLimit(exchange)) {
                return;
            }
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendError(exchange, 405, "Method Not Allowed. Use POST.");
                return;
            }
            if (!isJsonContentType(exchange.getRequestHeaders())) {
                sendError(exchange, 415, "Unsupported Media Type. Expected application/json.");
                return;
            }

            byte[] rawBody = readBodyWithLimit(exchange, MAX_REQUEST_BODY_BYTES);
            if (rawBody == null) {
                return; // 413 already sent
            }

            JsonObject json = parseJsonObject(rawBody);
            if (json == null) {
                sendError(exchange, 400, "Invalid JSON request body.");
                return;
            }

            String message = requireStringField(json, "message");
            String carrier = requireStringField(json, "carrier");
            if (message == null || carrier == null) {
                sendError(exchange, 400, "Fields 'message' and 'carrier' are required and must be strings.");
                return;
            }

            try {
                String encoded = codec.encode(message, carrier);
                JsonObject resp = new JsonObject();
                resp.addProperty("encoded", encoded);
                resp.addProperty("checksumType", "basic-error-detection");
                sendJson(exchange, 200, resp);
            } catch (CodecException ex) {
                sendError(exchange, 400, ex.getMessage());
            }
        } finally {
            if (slotAcquired) {
                activeRequestSlots.release();
            }
            exchange.close();
        }
    }

    private void handleDecode(HttpExchange exchange) throws IOException {
        boolean slotAcquired = false;
        try {
            if (!(slotAcquired = acquireSlotOrOverload(exchange))) {
                return;
            }
            if (!checkRateLimit(exchange)) {
                return;
            }
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendError(exchange, 405, "Method Not Allowed. Use POST.");
                return;
            }
            if (!isJsonContentType(exchange.getRequestHeaders())) {
                sendError(exchange, 415, "Unsupported Media Type. Expected application/json.");
                return;
            }

            byte[] rawBody = readBodyWithLimit(exchange, MAX_REQUEST_BODY_BYTES);
            if (rawBody == null) {
                return; // 413 already sent
            }

            JsonObject json = parseJsonObject(rawBody);
            if (json == null) {
                sendError(exchange, 400, "Invalid JSON request body.");
                return;
            }

            String encoded = requireStringField(json, "encoded");
            if (encoded == null) {
                sendError(exchange, 400, "Field 'encoded' is required and must be a string.");
                return;
            }

            try {
                String decoded = codec.decode(encoded);
                JsonObject resp = new JsonObject();
                resp.addProperty("message", decoded);
                resp.addProperty("checksumType", "basic-error-detection");
                sendJson(exchange, 200, resp);
            } catch (CodecException ex) {
                sendError(exchange, 400, ex.getMessage());
            }
        } finally {
            if (slotAcquired) {
                activeRequestSlots.release();
            }
            exchange.close();
        }
    }

    private boolean acquireSlotOrOverload(HttpExchange exchange) throws IOException {
        if (!activeRequestSlots.tryAcquire()) {
            sendError(exchange, 503, "Service Temporarily Overloaded.");
            return false;
        }
        return true;
    }

    private boolean checkRateLimit(HttpExchange exchange) throws IOException {
        String clientKey = resolveClientKey(exchange);
        long now = System.currentTimeMillis();
        RateBucket bucket = rateLimits.compute(clientKey, (k, existing) -> {
            if (existing == null || now - existing.windowStartMs >= RATE_WINDOW_MS) {
                return new RateBucket(now, new AtomicInteger(1));
            }
            existing.count.incrementAndGet();
            return existing;
        });
        if (bucket.count.get() > RATE_LIMIT_PER_MINUTE) {
            sendError(exchange, 429, "Rate limit exceeded. Try again later.");
            return false;
        }
        return true;
    }

    private String resolveClientKey(HttpExchange exchange) {
        InetSocketAddress remote = exchange.getRemoteAddress();
        if (remote != null && remote.getAddress() != null) {
            InetAddress addr = remote.getAddress();
            return addr.getHostAddress();
        }
        return "unknown";
    }

    private boolean isJsonContentType(Headers headers) {
        String ct = headers.getFirst("Content-Type");
        if (ct == null) {
            return false;
        }
        String normalized = ct.split(";")[0].trim().toLowerCase(Locale.ROOT);
        return "application/json".equals(normalized);
    }

    private byte[] readBodyWithLimit(HttpExchange exchange, int maxBytes) throws IOException {
        String contentLengthHeader = exchange.getRequestHeaders().getFirst("Content-Length");
        if (contentLengthHeader != null) {
            try {
                long declaredLength = Long.parseLong(contentLengthHeader.trim());
                if (declaredLength > maxBytes) {
                    sendError(exchange, 413, "Request body exceeds maximum allowed size (" + maxBytes + " bytes).");
                    return null;
                }
            } catch (NumberFormatException ignored) {
                // Stream check below will enforce limit safely
            }
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int total = 0;
        try (InputStream in = exchange.getRequestBody()) {
            int read;
            while ((read = in.read(buffer)) != -1) {
                total += read;
                if (total > maxBytes) {
                    sendError(exchange, 413, "Request body exceeds maximum allowed size (" + maxBytes + " bytes).");
                    return null;
                }
                out.write(buffer, 0, read);
            }
        }
        return out.toByteArray();
    }

    private JsonObject parseJsonObject(byte[] rawBody) {
        if (rawBody.length == 0) {
            return null;
        }
        try {
            String text = new String(rawBody, StandardCharsets.UTF_8);
            JsonElement element = JsonParser.parseString(text);
            if (!element.isJsonObject()) {
                return null;
            }
            return element.getAsJsonObject();
        } catch (JsonParseException ex) {
            return null;
        }
    }

    private String requireStringField(JsonObject obj, String fieldName) {
        if (!obj.has(fieldName)) {
            return null;
        }
        JsonElement el = obj.get(fieldName);
        if (el == null || el.isJsonNull() || !el.isJsonPrimitive() || !el.getAsJsonPrimitive().isString()) {
            return null;
        }
        return el.getAsString();
    }

    private void sendJson(HttpExchange exchange, int statusCode, JsonObject payload) throws IOException {
        byte[] bytes = payload.toString().getBytes(StandardCharsets.UTF_8);
        applyCommonHeaders(exchange, exchange.getResponseHeaders(), "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private void sendError(HttpExchange exchange, int statusCode, String message) throws IOException {
        JsonObject err = new JsonObject();
        err.addProperty("error", message);
        sendJson(exchange, statusCode, err);
    }

    private void applyCommonHeaders(HttpExchange exchange, Headers headers, String contentType) {
        headers.set("Content-Type", contentType);
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("X-Frame-Options", "DENY");
        headers.set("Referrer-Policy", "no-referrer");
        headers.set("Cache-Control", "no-store");
        headers.set("Content-Security-Policy",
                "default-src 'self'; img-src 'self' data:; style-src 'self' 'unsafe-inline'; "
                        + "script-src 'self' 'unsafe-inline'; connect-src 'self'; object-src 'none'; "
                        + "base-uri 'self'; form-action 'self'; frame-ancestors 'none'");

        String origin = exchange.getRequestHeaders().getFirst("Origin");
        if (origin != null && isAllowedSameOrigin(exchange, origin)) {
            headers.set("Access-Control-Allow-Origin", origin);
            headers.set("Vary", "Origin");
        }
    }

    private boolean isAllowedSameOrigin(HttpExchange exchange, String origin) {
        String host = exchange.getRequestHeaders().getFirst("Host");
        if (host == null || host.isBlank()) {
            return false;
        }
        return origin.equals("http://" + host) || origin.equals("https://" + host);
    }

    private byte[] loadWebUi() throws IOException {
        try (InputStream in = EmojiStegoWebServer.class.getResourceAsStream("/web/index.html")) {
            if (in == null) {
                throw new IOException("Web UI resource /web/index.html was not found on the classpath.");
            }
            return in.readAllBytes();
        }
    }

    private record RateBucket(long windowStartMs, AtomicInteger count) {}
}
