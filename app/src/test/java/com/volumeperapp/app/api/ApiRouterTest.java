package com.volumeperapp.app.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

public final class ApiRouterTest {

    private static final String KEY = "k3y";

    private final FakeBackend backend = new FakeBackend();
    private final ApiRouter router = new ApiRouter(backend, () -> KEY);

    private Http.Response call(String method, String path, String body, boolean withKey)
            throws Exception {
        StringBuilder raw = new StringBuilder(method + " " + path + " HTTP/1.1\r\n");
        if (withKey) raw.append("X-API-Key: ").append(KEY).append("\r\n");
        byte[] bytes = body == null ? new byte[0] : body.getBytes("UTF-8");
        raw.append("Content-Length: ").append(bytes.length).append("\r\n\r\n");
        if (body != null) raw.append(body);
        return router.handle(Http.parse(raw.toString()));
    }

    private Http.Response call(String method, String path, String body) throws Exception {
        return call(method, path, body, true);
    }

    private static JSONObject json(Http.Response r) throws Exception {
        return new JSONObject(r.text());
    }

    @Test
    public void healthNeedsNoKeyButEverythingElseDoes() throws Exception {
        assertEquals(200, call("GET", "/api/health", null, false).status);
        assertEquals(401, call("GET", "/api/mixer", null, false).status);
        assertEquals(401, call("GET", "/api/docs", null, false).status);
        assertEquals(200, call("GET", "/api/mixer", null).status);
    }

    @Test
    public void wrongKeyAndBearerForm() throws Exception {
        Http.Request wrong = Http.parse("GET /api/mixer HTTP/1.1\r\nX-API-Key: nope\r\n\r\n");
        assertEquals(401, router.handle(wrong).status);
        Http.Request bearer = Http.parse(
                "GET /api/mixer HTTP/1.1\r\nAuthorization: Bearer " + KEY + "\r\n\r\n");
        assertEquals(200, router.handle(bearer).status);
    }

    @Test
    public void noKeyConfiguredMeansNothingIsOpen() throws Exception {
        ApiRouter closed = new ApiRouter(backend, () -> "");
        Http.Request r = Http.parse("GET /api/mixer HTTP/1.1\r\nX-API-Key: \r\n\r\n");
        assertEquals(401, closed.handle(r).status);
    }

    @Test
    public void moveAFaderPinsItAndSaysWhetherTheEngineHasIt() throws Exception {
        Http.Response r = call("PATCH", "/api/apps/com.example.music", "{\"gain\": 40}");
        assertEquals(200, r.status);
        assertEquals("applied", json(r).getString("engine"));
        assertEquals(40, (int) backend.gain.get("com.example.music"));
        assertTrue(backend.pinned.contains("com.example.music"));

        backend.engineApplies = false;
        r = call("PATCH", "/api/apps/com.example.music", "{\"muted\": true}");
        assertEquals("deferred", json(r).getString("engine"));
        assertTrue(backend.muted.get("com.example.music"));
    }

    @Test
    public void gainIsCheckedAgainstTheCeiling() throws Exception {
        assertEquals(400, call("PATCH", "/api/apps/com.example.music", "{\"gain\": 151}").status);
        assertEquals(400, call("PATCH", "/api/apps/com.example.music", "{\"gain\": -1}").status);
        assertEquals(400, call("PATCH", "/api/apps/com.example.music", "{\"gain\": 50.5}").status);
        assertEquals(400, call("PATCH", "/api/apps/com.example.music", "{\"gain\": \"50\"}").status);
        assertEquals(400, call("PATCH", "/api/apps/com.example.music", "{}").status);
        assertFalse(backend.gain.containsKey("com.example.music"));
        backend.maxGain = 400;
        assertEquals(200, call("PATCH", "/api/apps/com.example.music", "{\"gain\": 400}").status);
    }

    @Test
    public void unknownAndMalformedPackages() throws Exception {
        assertEquals(404, call("GET", "/api/apps/org.nowhere.app", null).status);
        assertEquals(404, call("PATCH", "/api/apps/org.nowhere.app", "{\"gain\": 50}").status);
        assertEquals(400, call("GET", "/api/apps/notapackage", null).status);
        assertEquals(400, call("GET", "/api/apps/..%2Fetc", null).status);
    }

    @Test
    public void addResetRemove() throws Exception {
        assertEquals(200, call("POST", "/api/apps/com.example.video", null).status);
        assertTrue(backend.pinned.contains("com.example.video"));
        call("PATCH", "/api/apps/com.example.video", "{\"gain\": 10, \"muted\": true}");
        assertEquals(200, call("POST", "/api/apps/com.example.video/reset", null).status);
        assertEquals(100, (int) backend.gain.get("com.example.video"));
        assertFalse(backend.muted.get("com.example.video"));
        assertEquals(200, call("DELETE", "/api/apps/com.example.video", null).status);
        assertFalse(backend.pinned.contains("com.example.video"));
        assertEquals(405, call("GET", "/api/reset", null).status);
        assertEquals(200, call("POST", "/api/reset", null).status);
    }

    @Test
    public void streams() throws Exception {
        assertEquals(200, call("PATCH", "/api/streams/media", "{\"volume\": 9}").status);
        assertEquals(9, (int) backend.streams.get("media"));
        assertEquals(404, call("PATCH", "/api/streams/voice", "{\"volume\": 9}").status);
        assertEquals(400, call("PATCH", "/api/streams/media", "{\"volume\": 99}").status);
        assertEquals(400, call("PATCH", "/api/streams/media", "{}").status);
    }

    @Test
    public void settingsValidateEverythingBeforeChangingAnything() throws Exception {
        Http.Response bad = call("PATCH", "/api/settings",
                "{\"palette\": \"Zoro\", \"max_gain\": 300}");
        assertEquals(400, bad.status);
        assertEquals("Ocean", backend.palette);

        Http.Response ok = call("PATCH", "/api/settings",
                "{\"palette\": \"white glow\", \"max_gain\": 200, \"suppress_volume_warning\": true}");
        assertEquals(200, ok.status);
        assertEquals("White Glow", backend.palette);
        assertEquals(200, backend.maxGain);
        assertTrue(backend.suppress);

        assertEquals(400, call("PATCH", "/api/settings", "{\"palette\": \"Beige\"}").status);
        assertEquals(400, call("PATCH", "/api/settings", "{}").status);
        assertEquals(400, call("PATCH", "/api/settings", "not json").status);
    }

    @Test
    public void errorsAreSentencesNotSilence() throws Exception {
        Http.Response r = call("GET", "/api/nowhere", null);
        assertEquals(404, r.status);
        assertTrue(json(r).has("hint"));
        Http.Response m = call("DELETE", "/api/mixer", null);
        assertEquals(405, m.status);
        assertEquals("GET", m.extraHeaders.get("Allow"));
        assertEquals(404, call("GET", "/elsewhere", null).status);
    }

    /** Every line of the advertised index must reach a route, not "no route". */
    @Test
    public void everyAdvertisedEndpointIsRouted() throws Exception {
        for (String line : ApiDocs.SUMMARY) {
            String[] parts = line.trim().split("\\s+");
            String path = parts[1]
                    .replace("{package}", "com.example.music")
                    .replace("{media|ring|alarm|notification|system}", "alarm")
                    .replaceAll("\\?.*", "");
            String body = parts[0].equals("PATCH")
                    ? (path.contains("streams") ? "{\"volume\": 3}"
                    : path.contains("settings") ? "{\"max_gain\": 100}" : "{\"gain\": 50}")
                    : null;
            Http.Response r = call(parts[0], path, body);
            assertNotEquals(line + " -> " + r.text(), 404, r.status);
            assertNotEquals(line + " -> " + r.text(), 405, r.status);
            assertTrue(line + " -> " + r.status, r.status < 400);
        }
    }

    @Test
    public void docsAreMarkdownAndMentionEveryPath() throws Exception {
        Http.Response r = call("GET", "/api/docs", null);
        assertTrue(r.contentType.startsWith("text/markdown"));
        for (String line : ApiDocs.SUMMARY) {
            String path = line.trim().split("\\s+")[1].replaceAll("\\?.*", "");
            String stem = path.split("/\\{")[0];
            assertTrue("docs never mention " + stem, ApiDocs.MARKDOWN.contains(stem));
        }
    }

    @Test
    public void parserHandlesQueryBareLfAndTrailingSlash() throws Exception {
        Http.Request r = Http.parse("GET /api/installed/?q=a+b HTTP/1.1\nX-API-Key: x\n\n");
        assertEquals("/api/installed", r.path);
        assertEquals("a b", r.query.get("q"));
        assertEquals("x", r.header("X-API-Key"));
    }

    // ------------------------------------------------------------- PhoneAPI

    private static final byte[] PROOF = new byte[32];

    private static Http.Response asPhoneApi(ApiRouter r, String method, String path, String key,
                                            String challenge) throws Exception {
        StringBuilder raw = new StringBuilder(method + " " + path + " HTTP/1.1\r\n");
        if (key != null) raw.append("X-API-Key: ").append(key).append("\r\n");
        if (challenge != null) raw.append("X-PhoneAPI-Challenge: ").append(challenge).append("\r\n");
        raw.append("Content-Length: 2\r\n\r\n{}");
        return r.handle(Http.parse(raw.toString()));
    }

    @Test
    public void phoneApiKeyIsHeldToWhatTheOwnerSwitchedOn() throws Exception {
        ApiRouter seeOnly = new ApiRouter(backend, () -> KEY,
                () -> new PhoneApi.Grant("pk", PROOF, true, false));
        assertEquals(200, asPhoneApi(seeOnly, "GET", "/api/mixer", "pk", null).status);
        assertEquals(403, asPhoneApi(seeOnly, "POST", "/api/reset", "pk", null).status);

        ApiRouter changeOnly = new ApiRouter(backend, () -> KEY,
                () -> new PhoneApi.Grant("pk", PROOF, false, true));
        assertEquals(403, asPhoneApi(changeOnly, "GET", "/api/mixer", "pk", null).status);
        assertEquals(200, asPhoneApi(changeOnly, "POST", "/api/reset", "pk", null).status);
        // The owner's key is never limited by PhoneAPI's switches.
        assertEquals(200, asPhoneApi(changeOnly, "GET", "/api/mixer", KEY, null).status);
    }

    @Test
    public void everyAnswerCarriesTheProofOnceThereIsAProofKey() throws Exception {
        ApiRouter granted = new ApiRouter(backend, () -> KEY,
                () -> new PhoneApi.Grant("pk", PROOF, true, true));
        String want = PhoneApi.proof(PROOF, "abc123");
        for (Http.Response r : new Http.Response[]{
                asPhoneApi(granted, "GET", "/api/health", null, "abc123"),
                asPhoneApi(granted, "GET", "/api/mixer", null, "abc123"),
                asPhoneApi(granted, "GET", "/nope", null, "abc123"),
                asPhoneApi(granted, "GET", "/api/nope", "pk", "abc123")}) {
            assertEquals(want, r.extraHeaders.get("X-PhoneAPI-Proof"));
        }
        assertFalse(asPhoneApi(granted, "GET", "/api/health", null, null)
                .extraHeaders.containsKey("X-PhoneAPI-Proof"));
        assertFalse(asPhoneApi(router, "GET", "/api/health", null, "abc123")
                .extraHeaders.containsKey("X-PhoneAPI-Proof"));
    }

    @Test
    public void proofIsBase64UrlHmacAndThePinIsPhoneApis() throws Exception {
        // RFC 4231 test case 2: key "Jefe", data "what do ya want for nothing?".
        assertEquals("W9zBRr9gdU5qBCQmCJV1x1oAPwidJzmDnexYuWTsOEM",
                PhoneApi.proof("Jefe".getBytes("US-ASCII"), "what do ya want for nothing?"));
        assertTrue(PhoneApi.callerTrusted("com.phoneapi.app", java.util.Collections.singletonList(
                "ad86a704863986f3a4ed106600f1ecc219ee318268d484bb54e9079ce3b8e002")));
        assertFalse(PhoneApi.callerTrusted("com.evil.app", java.util.Collections.singletonList(
                "ad86a704863986f3a4ed106600f1ecc219ee318268d484bb54e9079ce3b8e002")));
        assertFalse(PhoneApi.callerTrusted("com.phoneapi.app",
                java.util.Collections.singletonList("00")));
    }
}
