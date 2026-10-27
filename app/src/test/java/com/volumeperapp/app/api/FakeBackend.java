package com.volumeperapp.app.api;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** An in-memory mixer: two installed apps, the five streams, the settings. */
final class FakeBackend implements ApiBackend {

    final Map<String, Integer> gain = new HashMap<>();
    final Map<String, Boolean> muted = new HashMap<>();
    final Set<String> pinned = new LinkedHashSet<>();
    final Map<String, Integer> streams = new HashMap<>();
    int maxGain = 150;
    String palette = "Ocean";
    boolean suppress;
    boolean engineApplies = true;

    FakeBackend() {
        for (String s : ApiRouter.STREAMS) streams.put(s, 5);
    }

    @Override
    public JSONObject instance() throws Exception {
        return new JSONObject().put("app", "VolumePerApp").put("mode", "idle");
    }

    @Override
    public JSONObject mixer() throws Exception {
        JSONArray apps = new JSONArray();
        for (String p : pinned) apps.put(app(p));
        return new JSONObject().put("mode", "idle").put("apps", apps).put("streams", streams());
    }

    @Override
    public JSONObject app(String pkg) throws Exception {
        if (!isInstalled(pkg)) return null;
        return new JSONObject().put("package", pkg)
                .put("gain", gain.getOrDefault(pkg, 100))
                .put("muted", muted.getOrDefault(pkg, false))
                .put("pinned", pinned.contains(pkg));
    }

    @Override
    public JSONArray installed(String query) throws Exception {
        JSONArray out = new JSONArray();
        for (String p : Arrays.asList("com.example.music", "com.example.video")) {
            if (query == null || p.contains(query)) out.put(new JSONObject().put("package", p));
        }
        return out;
    }

    @Override
    public boolean isInstalled(String pkg) {
        return pkg.startsWith("com.example.");
    }

    @Override
    public boolean setGain(String pkg, Integer percent, Boolean m) {
        if (percent != null) gain.put(pkg, percent);
        if (m != null) muted.put(pkg, m);
        pinned.add(pkg);
        return engineApplies;
    }

    @Override
    public void pin(String pkg) {
        pinned.add(pkg);
    }

    @Override
    public void remove(String pkg) {
        pinned.remove(pkg);
        gain.remove(pkg);
        muted.remove(pkg);
    }

    @Override
    public boolean reset(String pkg) {
        gain.put(pkg, 100);
        muted.put(pkg, false);
        return true;
    }

    @Override
    public void resetAll() {
        gain.clear();
        muted.clear();
    }

    @Override
    public int maxGain() {
        return maxGain;
    }

    @Override
    public void setMaxGain(int percent) {
        maxGain = percent;
    }

    @Override
    public JSONArray streams() throws Exception {
        JSONArray out = new JSONArray();
        for (String s : ApiRouter.STREAMS) {
            out.put(new JSONObject().put("name", s).put("volume", streams.get(s)).put("max", 15));
        }
        return out;
    }

    @Override
    public void setStream(String name, int volume) throws ApiException {
        if (volume < 0 || volume > 15) throw new ApiException(400, "out of range", null);
        streams.put(name, volume);
    }

    @Override
    public String palette() {
        return palette;
    }

    @Override
    public void setPalette(String name) {
        palette = name;
    }

    @Override
    public List<String> palettes() {
        return Arrays.asList("Material You", "Ocean", "Terminal", "White Glow", "Zoro");
    }

    @Override
    public JSONObject safeVolume() throws Exception {
        return new JSONObject().put("suppress_requested", suppress);
    }

    @Override
    public boolean setSafeVolumeRequested(boolean requested) {
        suppress = requested;
        return true;
    }

    @Override
    public String diagnostics() {
        return "== VERDICT ==\n";
    }
}
