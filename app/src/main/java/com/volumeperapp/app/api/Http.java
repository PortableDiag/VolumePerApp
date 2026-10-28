package com.volumeperapp.app.api;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Just enough HTTP/1.1 to serve a local API, and no more — the same core as
 * Lull's and NotifX's, in Java.
 *
 * <p>A web server would be a larger dependency than the feature it serves. What
 * is needed is: parse a request line, read the headers, read a
 * {@code Content-Length} body, write a response. Nothing here touches an
 * Android type, which is why the request/response surface is covered by JVM
 * unit tests rather than by a phone.
 */
public final class Http {

    /** Nothing this API accepts is a megabyte of JSON. */
    public static final int MAX_BODY = 1 << 20;

    /** A request line longer than this is not a request, it is someone probing. */
    private static final int MAX_LINE = 8 * 1024;

    private Http() {
    }

    /** A parsed request. {@link #path} is percent-decoded; header names are lower-cased. */
    public static final class Request {
        public final String method;
        public final String path;
        public final Map<String, String> query;
        public final Map<String, String> headers;
        public final byte[] body;

        public Request(String method, String path, Map<String, String> query,
                       Map<String, String> headers, byte[] body) {
            this.method = method;
            this.path = path;
            this.query = query;
            this.headers = headers;
            this.body = body;
        }

        public String header(String name) {
            return headers.get(name.toLowerCase(Locale.ROOT));
        }

        /** The JSON body, or an empty object: a body-less write means "with defaults". */
        public JSONObject json() throws ApiException {
            String text = new String(body, StandardCharsets.UTF_8).trim();
            if (text.isEmpty()) return new JSONObject();
            try {
                return new JSONObject(text);
            } catch (Exception e) {
                throw new ApiException(400, "body is not a JSON object: " + e.getMessage(),
                        "send e.g. {\"gain\": 50}");
            }
        }
    }

    /** One response, always fully in memory: nothing this app serves is large. */
    public static final class Response {
        private static final Map<Integer, String> REASONS = new HashMap<>();

        static {
            REASONS.put(200, "OK");
            REASONS.put(204, "No Content");
            REASONS.put(400, "Bad Request");
            REASONS.put(401, "Unauthorized");
            REASONS.put(403, "Forbidden");
            REASONS.put(404, "Not Found");
            REASONS.put(405, "Method Not Allowed");
            REASONS.put(409, "Conflict");
            REASONS.put(413, "Payload Too Large");
            REASONS.put(500, "Internal Server Error");
            REASONS.put(503, "Service Unavailable");
        }

        public final int status;
        public final String contentType;
        public final byte[] body;
        public final Map<String, String> extraHeaders;

        public Response(int status, String contentType, byte[] body,
                        Map<String, String> extraHeaders) {
            this.status = status;
            this.contentType = contentType;
            this.body = body;
            this.extraHeaders = extraHeaders;
        }

        public String reason() {
            String r = REASONS.get(status);
            return r != null ? r : "OK";
        }

        /** The same response with one more header: the proof goes on after routing. */
        public Response withHeader(String name, String value) {
            Map<String, String> headers = new LinkedHashMap<>(extraHeaders);
            headers.put(name, value);
            return new Response(status, contentType, body, headers);
        }

        public String text() {
            return new String(body, StandardCharsets.UTF_8);
        }

        public static Response json(JSONObject obj) {
            return json(obj, 200);
        }

        public static Response json(JSONObject obj, int status) {
            return new Response(status, "application/json; charset=utf-8",
                    toString(obj).getBytes(StandardCharsets.UTF_8), Collections.emptyMap());
        }

        public static Response json(JSONArray arr) {
            String text;
            try {
                text = arr.toString(2);
            } catch (Exception e) {
                text = arr.toString();
            }
            return new Response(200, "application/json; charset=utf-8",
                    text.getBytes(StandardCharsets.UTF_8), Collections.emptyMap());
        }

        public static Response text(String body, String type) {
            return new Response(200, type, body.getBytes(StandardCharsets.UTF_8),
                    Collections.emptyMap());
        }

        public static Response error(int status, String message, String hint) {
            JSONObject obj = new JSONObject();
            try {
                obj.put("error", message);
                if (hint != null) obj.put("hint", hint);
            } catch (Exception ignored) {
                // put() only throws for a null key or a non-finite number.
            }
            return json(obj, status);
        }

        public static Response noContent() {
            return new Response(204, "text/plain", new byte[0], Collections.emptyMap());
        }

        private static String toString(JSONObject obj) {
            try {
                return obj.toString(2);
            } catch (Exception e) {
                return obj.toString();
            }
        }
    }

    /**
     * Reads one request. Null at a clean end of stream (a port scanner that
     * connected and left); {@link ApiException} for something that arrived but
     * is not a request this server can answer.
     */
    public static Request parse(InputStream input) throws IOException, ApiException {
        String requestLine = readLine(input);
        if (requestLine == null || requestLine.trim().isEmpty()) return null;

        String[] parts = requestLine.split(" ");
        if (parts.length < 2) throw new ApiException(400, "malformed request line", null);
        String method = parts[0].toUpperCase(Locale.ROOT);
        String target = parts[1];

        Map<String, String> headers = new HashMap<>();
        while (true) {
            String line = readLine(input);
            if (line == null || line.isEmpty()) break;
            int colon = line.indexOf(':');
            if (colon <= 0) continue;
            headers.put(line.substring(0, colon).trim().toLowerCase(Locale.ROOT),
                    line.substring(colon + 1).trim());
        }

        int length = 0;
        try {
            String cl = headers.get("content-length");
            if (cl != null) length = Integer.parseInt(cl.trim());
        } catch (NumberFormatException ignored) {
            length = 0;
        }
        if (length > MAX_BODY) {
            throw new ApiException(413, "body larger than " + MAX_BODY + " bytes", null);
        }
        byte[] body = length > 0 ? readFully(input, length) : new byte[0];

        int q = target.indexOf('?');
        String rawPath = q >= 0 ? target.substring(0, q) : target;
        String rawQuery = q >= 0 ? target.substring(q + 1) : "";
        String path = decode(rawPath);
        while (path.length() > 1 && path.endsWith("/")) path = path.substring(0, path.length() - 1);
        if (path.isEmpty()) path = "/";
        return new Request(method, path, parseQuery(rawQuery), headers, body);
    }

    public static Request parse(String raw) throws IOException, ApiException {
        return parse(new ByteArrayInputStream(raw.getBytes(StandardCharsets.UTF_8)));
    }

    public static Map<String, String> parseQuery(String raw) {
        Map<String, String> out = new LinkedHashMap<>();
        if (raw.isEmpty()) return out;
        for (String pair : raw.split("&")) {
            if (pair.isEmpty()) continue;
            int eq = pair.indexOf('=');
            String name = eq >= 0 ? pair.substring(0, eq) : pair;
            String value = eq >= 0 ? pair.substring(eq + 1) : "";
            out.put(decodeQuery(name), decodeQuery(value));
        }
        return out;
    }

    /** A {@code +} is a literal in a path. */
    static String decode(String raw) {
        try {
            return URLDecoder.decode(raw.replace("+", "%2B"), "UTF-8");
        } catch (Exception e) {
            return raw;
        }
    }

    /** ...and a space in a query string. */
    private static String decodeQuery(String raw) {
        try {
            return URLDecoder.decode(raw, "UTF-8");
        } catch (Exception e) {
            return raw;
        }
    }

    /** CRLF or a bare LF: a shell one-liner often sends the second. */
    private static String readLine(InputStream input) throws IOException, ApiException {
        StringBuilder sb = new StringBuilder();
        while (true) {
            int b = input.read();
            if (b == -1) return sb.length() == 0 ? null : sb.toString();
            if (b == '\n') {
                int n = sb.length();
                if (n > 0 && sb.charAt(n - 1) == '\r') sb.setLength(n - 1);
                return sb.toString();
            }
            sb.append((char) b);
            if (sb.length() > MAX_LINE) throw new ApiException(413, "header line too long", null);
        }
    }

    private static byte[] readFully(InputStream input, int length) throws IOException {
        byte[] out = new byte[length];
        int read = 0;
        while (read < length) {
            int n = input.read(out, read, length - read);
            if (n < 0) break;
            read += n;
        }
        if (read == length) return out;
        byte[] cut = new byte[read];
        System.arraycopy(out, 0, cut, 0, read);
        return cut;
    }
}
