import java.lang.reflect.*;
import java.util.*;

/**
 * Pins the hidden audio-routing API against whatever Android build this runs on.
 * Pure reflection: nothing here needs a stub jar, so it compiles against the
 * public android.jar and still sees the hidden surface at runtime.
 */
public class Probe {

    static final String[] CLASSES = {
        "android.media.audiopolicy.AudioPolicy",
        "android.media.audiopolicy.AudioPolicy$Builder",
        "android.media.audiopolicy.AudioPolicy$AudioPolicyStatusListener",
        "android.media.audiopolicy.AudioMix",
        "android.media.audiopolicy.AudioMix$Builder",
        "android.media.audiopolicy.AudioMixingRule",
        "android.media.audiopolicy.AudioMixingRule$Builder",
        "android.media.AudioAttributes",
        "android.media.AudioAttributes$Builder",
        "android.media.AudioFormat",
        "android.media.AudioFormat$Builder",
        "android.media.AudioManager",
        "android.media.AudioPlaybackConfiguration",
        "android.media.AudioSystem",
        "android.media.audiopolicy.AudioMixingRule$AudioMixMatchCriterion",
    };

    // Only these members are interesting on the big classes.
    static final Map<String, String[]> FILTER = new HashMap<>();
    static {
        FILTER.put("android.media.AudioManager", new String[]{
            "registerAudioPolicy", "unregisterAudioPolicy", "AUDIO_POLICY", "SUCCESS", "ERROR"});
        FILTER.put("android.media.AudioAttributes", new String[]{
            "USAGE_", "FLAG_", "CAPTURE_POLICY", "getUsage"});
        FILTER.put("android.media.AudioAttributes$Builder", new String[]{
            "setUsage", "setInternalCapturePreset", "setCapturePreset", "setFlags", "build"});
        FILTER.put("android.media.AudioFormat", new String[]{
            "ENCODING_PCM", "CHANNEL_OUT", "CHANNEL_IN", "getSampleRate", "getChannel"});
        FILTER.put("android.media.AudioFormat$Builder", new String[]{
            "setSampleRate", "setEncoding", "setChannelMask", "build"});
        FILTER.put("android.media.AudioPlaybackConfiguration", new String[]{
            "getClientUid", "getClientPid", "getPlayerState", "getAudioAttributes",
            "getAudioDeviceInfo", "getSessionId", "PLAYER_STATE"});
        FILTER.put("android.media.AudioSystem", new String[]{
            "setUidDeviceAffinity", "removeUidDeviceAffinity", "AUDIO_", "SUCCESS",
            "setStreamVolumeIndex", "MIX_"});
    }

    public static void main(String[] args) {
        out("=== VolumePerApp hidden-API probe ===");
        out("android.os.Build.VERSION.SDK_INT = " + prop("ro.build.version.sdk"));
        out("android.os.Build.VERSION.RELEASE = " + prop("ro.build.version.release"));
        out("fingerprint = " + prop("ro.build.fingerprint"));
        out("");

        for (String cn : CLASSES) {
            dump(cn);
        }

        out("");
        out("=== RECIPE CHECK — the exact call chain the engine needs ===");
        recipeCheck();
    }

    static String prop(String key) {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            Method get = sp.getMethod("get", String.class);
            return (String) get.invoke(null, key);
        } catch (Throwable t) {
            return "<" + t + ">";
        }
    }

    static void dump(String cn) {
        out("---------------------------------------------------------------");
        Class<?> c;
        try {
            c = Class.forName(cn);
        } catch (Throwable t) {
            out("MISSING  " + cn + "   (" + t.getClass().getSimpleName() + ")");
            return;
        }
        out("CLASS    " + cn);
        out("         modifiers=" + Modifier.toString(c.getModifiers())
                + "  super=" + (c.getSuperclass() == null ? "-" : c.getSuperclass().getName()));
        String[] filt = FILTER.get(cn);

        List<String> lines = new ArrayList<>();
        for (Constructor<?> k : c.getDeclaredConstructors()) {
            lines.add("  ctor   " + Modifier.toString(k.getModifiers()) + " (" + params(k.getParameterTypes()) + ")");
        }
        for (Method m : c.getDeclaredMethods()) {
            if (!keep(filt, m.getName())) continue;
            lines.add("  method " + Modifier.toString(m.getModifiers()) + " "
                    + simple(m.getReturnType()) + " " + m.getName() + "(" + params(m.getParameterTypes()) + ")");
        }
        for (Field f : c.getDeclaredFields()) {
            if (!keep(filt, f.getName())) continue;
            String val = "";
            if (Modifier.isStatic(f.getModifiers())) {
                try {
                    f.setAccessible(true);
                    Object v = f.get(null);
                    if (v != null && (v instanceof Number || v instanceof String || v instanceof Boolean)) {
                        val = " = " + v;
                    }
                } catch (Throwable ignored) { }
            }
            lines.add("  field  " + Modifier.toString(f.getModifiers()) + " "
                    + simple(f.getType()) + " " + f.getName() + val);
        }
        Collections.sort(lines);
        for (String l : lines) out(l);
    }

    static boolean keep(String[] filt, String name) {
        if (filt == null) return true;
        for (String f : filt) if (name.startsWith(f) || name.contains(f)) return true;
        return false;
    }

    static String params(Class<?>[] ps) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ps.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(simple(ps[i]));
        }
        return sb.toString();
    }

    static String simple(Class<?> c) {
        if (c.isArray()) return simple(c.getComponentType()) + "[]";
        String n = c.getName();
        int i = n.lastIndexOf('.');
        return i < 0 ? n : n.substring(i + 1);
    }

    /**
     * Walks the exact chain the routing engine will use and reports the first
     * step that does not exist, so a failure names a method instead of a stack.
     */
    static void recipeCheck() {
        step("AudioMixingRule.Builder()", new Step() { public String run() throws Throwable {
            Class<?> b = Class.forName("android.media.audiopolicy.AudioMixingRule$Builder");
            b.getDeclaredConstructor().newInstance();
            return "ok";
        }});
        step("AudioMixingRule.RULE_MATCH_UID", new Step() { public String run() throws Throwable {
            Class<?> r = Class.forName("android.media.audiopolicy.AudioMixingRule");
            Field f = r.getDeclaredField("RULE_MATCH_UID");
            f.setAccessible(true);
            return String.valueOf(f.getInt(null));
        }});
        step("AudioMixingRule.Builder.addMixRule(int, Object)", new Step() { public String run() throws Throwable {
            Class<?> b = Class.forName("android.media.audiopolicy.AudioMixingRule$Builder");
            Method m = b.getDeclaredMethod("addMixRule", int.class, Object.class);
            return m.toString();
        }});
        step("AudioMix.Builder.setRouteFlags(int)", new Step() { public String run() throws Throwable {
            Class<?> b = Class.forName("android.media.audiopolicy.AudioMix$Builder");
            Method m = b.getDeclaredMethod("setRouteFlags", int.class);
            return m.toString();
        }});
        step("AudioMix.ROUTE_FLAG_LOOP_BACK|ROUTE_FLAG_RENDER", new Step() { public String run() throws Throwable {
            Class<?> a = Class.forName("android.media.audiopolicy.AudioMix");
            Field lb = a.getDeclaredField("ROUTE_FLAG_LOOP_BACK"); lb.setAccessible(true);
            Field rn = a.getDeclaredField("ROUTE_FLAG_RENDER"); rn.setAccessible(true);
            return "LOOP_BACK=" + lb.getInt(null) + " RENDER=" + rn.getInt(null);
        }});
        step("AudioPolicy.Builder(Context)", new Step() { public String run() throws Throwable {
            Class<?> b = Class.forName("android.media.audiopolicy.AudioPolicy$Builder");
            Constructor<?> k = b.getDeclaredConstructor(Class.forName("android.content.Context"));
            return k.toString();
        }});
        step("AudioPolicy.Builder.addMix(AudioMix)", new Step() { public String run() throws Throwable {
            Class<?> b = Class.forName("android.media.audiopolicy.AudioPolicy$Builder");
            Method m = b.getDeclaredMethod("addMix", Class.forName("android.media.audiopolicy.AudioMix"));
            return m.toString();
        }});
        step("AudioPolicy.createAudioRecordSink(AudioMix)", new Step() { public String run() throws Throwable {
            Class<?> p = Class.forName("android.media.audiopolicy.AudioPolicy");
            Method m = p.getDeclaredMethod("createAudioRecordSink", Class.forName("android.media.audiopolicy.AudioMix"));
            return m.toString();
        }});
        step("AudioPolicy.createAudioTrackSource(AudioMix)", new Step() { public String run() throws Throwable {
            Class<?> p = Class.forName("android.media.audiopolicy.AudioPolicy");
            Method m = p.getDeclaredMethod("createAudioTrackSource", Class.forName("android.media.audiopolicy.AudioMix"));
            return m.toString();
        }});
        step("AudioManager.registerAudioPolicy(AudioPolicy)", new Step() { public String run() throws Throwable {
            Class<?> am = Class.forName("android.media.AudioManager");
            Method m = am.getDeclaredMethod("registerAudioPolicy", Class.forName("android.media.audiopolicy.AudioPolicy"));
            return m.toString();
        }});
        step("AudioAttributes.Builder.setInternalCapturePreset(int)", new Step() { public String run() throws Throwable {
            Class<?> b = Class.forName("android.media.AudioAttributes$Builder");
            Method m = b.getDeclaredMethod("setInternalCapturePreset", int.class);
            return m.toString();
        }});
        step("AudioMixingRule.Builder.voiceCommunicationCaptureAllowed", new Step() { public String run() throws Throwable {
            Class<?> b = Class.forName("android.media.audiopolicy.AudioMixingRule$Builder");
            Method m = b.getDeclaredMethod("voiceCommunicationCaptureAllowed", boolean.class);
            return m.toString();
        }});
    }

    interface Step { String run() throws Throwable; }

    static void step(String label, Step s) {
        try {
            out(String.format("  %-58s OK    %s", label, s.run()));
        } catch (Throwable t) {
            out(String.format("  %-58s FAIL  %s", label, t));
        }
    }

    static void out(String s) {
        System.out.println(s);
    }
}
