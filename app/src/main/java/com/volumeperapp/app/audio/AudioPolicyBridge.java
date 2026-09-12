package com.volumeperapp.app.audio;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The one place that speaks {@code android.media.audiopolicy}.
 *
 * <p>Per-app volume works like this and nowhere else in the platform: register
 * an {@code AudioPolicy} containing one {@code AudioMix} per target uid, each
 * built from an {@code AudioMixingRule} of {@code RULE_MATCH_UID} and flagged
 * {@code ROUTE_FLAG_LOOP_BACK}. Loopback means that uid's audio stops going to
 * the speaker and is handed to us instead, as an {@code AudioRecord} from
 * {@link #sinkFor(int)}. {@link StreamPump} scales the samples and writes them
 * to an ordinary {@code AudioTrack}. Divert, attenuate, re-render.
 *
 * <p>Route flags are deliberately <em>not</em> {@code ROUTE_FLAG_LOOP_BACK_RENDER}:
 * adding RENDER would also send the untouched stream straight to a device, which
 * is the opposite of attenuating it.
 *
 * <p>Values pinned against a real device rather than the docs — see
 * {@code docs/hidden-api-api35.txt}, produced by {@code tools/probe/Probe.java}.
 */
public final class AudioPolicyBridge {

    private static final String TAG = "VPA.Bridge";

    static final String C_POLICY = "android.media.audiopolicy.AudioPolicy";
    static final String C_POLICY_B = "android.media.audiopolicy.AudioPolicy$Builder";
    static final String C_MIX = "android.media.audiopolicy.AudioMix";
    static final String C_MIX_B = "android.media.audiopolicy.AudioMix$Builder";
    static final String C_RULE = "android.media.audiopolicy.AudioMixingRule";
    static final String C_RULE_B = "android.media.audiopolicy.AudioMixingRule$Builder";

    /** Verified = 4 on API 35. Read from the platform so a renumber cannot bite. */
    public static int ruleMatchUid() {
        return Hidden.intField(C_RULE, "RULE_MATCH_UID");
    }

    /** Verified = 2 on API 35. */
    public static int routeFlagLoopback() {
        return Hidden.intField(C_MIX, "ROUTE_FLAG_LOOP_BACK");
    }

    /**
     * {@code AudioManager.SUCCESS} and its {@code ERROR_*} siblings are hidden,
     * so they are pinned here from the values read off the platform by
     * {@code tools/probe/Probe.java}. They have been stable since API 21 and a
     * wrong one only mislabels a diagnostic, never misroutes audio.
     */
    public static final int RC_SUCCESS = 0;
    public static final int RC_ERROR = -1;
    public static final int RC_ERROR_BAD_VALUE = -2;
    public static final int RC_ERROR_INVALID_OPERATION = -3;
    public static final int RC_ERROR_PERMISSION_DENIED = -4;
    public static final int RC_ERROR_NO_INIT = -5;
    public static final int RC_ERROR_DEAD_OBJECT = -6;

    public static final int SAMPLE_RATE = 48000;
    public static final int CHANNELS = 2;

    private final Context context;
    private final AudioManager audioManager;

    /** uid -> AudioMix, in insertion order so a rebuild is deterministic. */
    private final Map<Integer, Object> mixes = new LinkedHashMap<>();

    private Object policy;
    private boolean registered;
    private String lastError;

    /**
     * Bumped every time a policy is registered, so a caller holding an
     * {@code AudioRecord} from {@link #sinkFor(int)} can tell whether it came
     * from <em>this</em> policy or a superseded one. A sink outlives the policy
     * it was made from only as a dead object, and the symptom is an app that is
     * routed and silent rather than an error.
     */
    private int generation;

    public AudioPolicyBridge(Context context) {
        this.context = context.getApplicationContext();
        this.audioManager = (AudioManager) this.context.getSystemService(Context.AUDIO_SERVICE);
    }

    public boolean isRegistered() {
        return registered;
    }

    public String lastError() {
        return lastError;
    }

    /** @see #generation */
    public synchronized int generation() {
        return generation;
    }

    public synchronized List<Integer> routedUids() {
        return new ArrayList<>(mixes.keySet());
    }

    // ------------------------------------------------------------ mix building

    private Object buildMix(int uid) {
        Object ruleBuilder = Hidden.newInstance(C_RULE_B, new Class[0]);

        Hidden.call(ruleBuilder, C_RULE_B, "addMixRule",
                new Class[]{int.class, Object.class}, ruleMatchUid(), Integer.valueOf(uid));

        // NOT allowPrivilegedPlaybackCapture(true), even though this app holds
        // CAPTURE_MEDIA_OUTPUT and the call would be accepted.
        //
        // Setting it puts the mix on the platform's privileged-capture path,
        // which is deliberately low-fidelity so it cannot be used to lift audio:
        // AudioMix caps it at PRIVILEDGED_CAPTURE_MAX_SAMPLE_RATE 16000,
        // MAX_CHANNEL_NUMBER 1 and MAX_BYTES_PER_SAMPLE 2. Asking for 48 kHz
        // stereo alongside it throws outright —
        //   "Privileged audio capture sample rate 48000 can not be over 16000kHz"
        // — and accepting the cap would mean re-rendering every app through
        // 16 kHz mono. Turning a slider down is not worth destroying the audio.
        //
        // The cost of leaving it off: an app that opted out of playback capture
        // (allowAudioPlaybackCapture="false", or FLAG_NO_SYSTEM_CAPTURE on its
        // attributes) yields silence instead of its audio. AppRepository detects
        // those up front so the mixer can say so rather than showing a dead fader.

        Object rule = Hidden.call(ruleBuilder, C_RULE_B, "build", new Class[0]);

        AudioFormat format = new AudioFormat.Builder()
                .setSampleRate(SAMPLE_RATE)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                .build();

        Object mixBuilder = Hidden.newInstance(C_MIX_B, new Class[]{Hidden.cls(C_RULE)}, rule);
        Hidden.call(mixBuilder, C_MIX_B, "setFormat",
                new Class[]{AudioFormat.class}, format);
        Hidden.call(mixBuilder, C_MIX_B, "setRouteFlags",
                new Class[]{int.class}, routeFlagLoopback());
        return Hidden.call(mixBuilder, C_MIX_B, "build", new Class[0]);
    }

    // ------------------------------------------------------------- lifecycle

    /**
     * Registers a policy carrying a mix for every uid in {@link #mixes}.
     * Called on the first routed app and on any rebuild.
     *
     * @return true when the platform accepted the policy
     */
    private boolean registerPolicy() {
        if (mixes.isEmpty()) {
            // A policy with no mixes has nothing to register and AudioService
            // rejects it; not an error, just nothing to do yet.
            return false;
        }
        try {
            Object builder = Hidden.newInstance(C_POLICY_B, new Class[]{Context.class}, context);
            for (Object mix : mixes.values()) {
                Hidden.call(builder, C_POLICY_B, "addMix",
                        new Class[]{Hidden.cls(C_MIX)}, mix);
            }
            Object built = Hidden.call(builder, C_POLICY_B, "build", new Class[0]);

            Object result = Hidden.call(audioManager, "android.media.AudioManager",
                    "registerAudioPolicy", new Class[]{Hidden.cls(C_POLICY)}, built);
            int rc = result instanceof Integer ? (Integer) result : RC_ERROR;
            if (rc != RC_SUCCESS) {
                lastError = "registerAudioPolicy returned " + rc + describeRc(rc);
                Log.w(TAG, lastError);
                return false;
            }
            policy = built;
            registered = true;
            generation++;
            lastError = null;
            Log.i(TAG, "policy registered with " + mixes.size() + " mix(es)"
                    + ", generation " + generation);
            return true;
        } catch (Hidden.MissingApi e) {
            lastError = e.getMessage();
            Log.w(TAG, "policy registration failed", e);
            return false;
        } catch (Throwable t) {
            lastError = t.getClass().getSimpleName() + ": " + t.getMessage();
            Log.w(TAG, "policy registration failed", t);
            return false;
        }
    }

    private static String describeRc(int rc) {
        switch (rc) {
            case RC_ERROR_PERMISSION_DENIED:
                return " (permission denied — MODIFY_AUDIO_ROUTING is not granted;"
                        + " the app is not installed as a privileged system app)";
            case RC_ERROR_INVALID_OPERATION:
                return " (invalid operation)";
            case RC_ERROR_BAD_VALUE:
                return " (bad value — a mix was rejected)";
            case RC_ERROR_DEAD_OBJECT:
                return " (audioserver died)";
            default:
                return "";
        }
    }

    private void unregisterPolicy() {
        if (policy == null) return;
        try {
            Hidden.call(audioManager, "android.media.AudioManager",
                    "unregisterAudioPolicy", new Class[]{Hidden.cls(C_POLICY)}, policy);
        } catch (Throwable t) {
            Log.w(TAG, "unregisterAudioPolicy failed (continuing)", t);
        }
        policy = null;
        registered = false;
    }

    /**
     * Adds a uid to the routed set.
     *
     * <p>Tries {@code attachMixes} first, which leaves every other app's audio
     * untouched. Older builds without it fall back to a full re-register, which
     * is audible as a brief gap on the other routed apps — hence the preference.
     *
     * @return true if the uid is now routed
     */
    public synchronized boolean addUid(int uid) {
        if (mixes.containsKey(uid)) return registered;
        Object mix;
        try {
            mix = buildMix(uid);
        } catch (Throwable t) {
            lastError = "could not build mix for uid " + uid + ": " + t;
            Log.w(TAG, lastError, t);
            return false;
        }

        if (registered && Hidden.hasMethod(C_POLICY, "attachMixes", List.class)) {
            try {
                Object rc = Hidden.call(policy, C_POLICY, "attachMixes",
                        new Class[]{List.class}, Collections.singletonList(mix));
                if (rc instanceof Integer && (Integer) rc == RC_SUCCESS) {
                    mixes.put(uid, mix);
                    Log.i(TAG, "attached mix for uid " + uid);
                    return true;
                }
                Log.i(TAG, "attachMixes returned " + rc + ", rebuilding policy");
            } catch (Throwable t) {
                Log.i(TAG, "attachMixes unavailable at runtime, rebuilding: " + t);
            }
        }

        mixes.put(uid, mix);
        unregisterPolicy();
        if (!registerPolicy()) {
            mixes.remove(uid);
            registerPolicy();  // restore whatever did work
            return false;
        }
        return true;
    }

    /** Removes a uid; its audio goes straight back to the speaker untouched. */
    public synchronized void removeUid(int uid) {
        Object mix = mixes.remove(uid);
        if (mix == null) return;

        if (registered && Hidden.hasMethod(C_POLICY, "detachMixes", List.class)) {
            try {
                Object rc = Hidden.call(policy, C_POLICY, "detachMixes",
                        new Class[]{List.class}, Collections.singletonList(mix));
                if (rc instanceof Integer && (Integer) rc == RC_SUCCESS) {
                    if (mixes.isEmpty()) unregisterPolicy();
                    Log.i(TAG, "detached mix for uid " + uid);
                    return;
                }
            } catch (Throwable t) {
                Log.i(TAG, "detachMixes unavailable at runtime, rebuilding: " + t);
            }
        }
        unregisterPolicy();
        registerPolicy();
    }

    /**
     * The capture side of one routed uid: an {@code AudioRecord} carrying only
     * that app's audio. Valid only while the mix stays attached — a rebuild
     * invalidates it, which is why {@link RoutingEngine} recreates its pumps
     * whenever the routed set changes.
     */
    public synchronized AudioRecord sinkFor(int uid) {
        Object mix = mixes.get(uid);
        if (mix == null || !registered) return null;
        try {
            Object rec = Hidden.call(policy, C_POLICY, "createAudioRecordSink",
                    new Class[]{Hidden.cls(C_MIX)}, mix);
            return (AudioRecord) rec;
        } catch (Throwable t) {
            lastError = "createAudioRecordSink failed for uid " + uid + ": " + t;
            Log.w(TAG, lastError, t);
            return null;
        }
    }

    public synchronized void shutdown() {
        mixes.clear();
        unregisterPolicy();
    }

    // ------------------------------------------------------------- capability

    /** What this device can actually do, for the Diagnostics screen. */
    public static final class Capability {
        public boolean classesPresent;
        public boolean recipeComplete;
        public boolean canAttachLive;
        public boolean policyAccepted;
        public String detail = "";

        public boolean privileged() {
            return policyAccepted;
        }
    }

    /**
     * Walks the same chain the engine walks and reports where it stops. Building
     * a throwaway policy for our own uid is the only honest test of whether
     * {@code MODIFY_AUDIO_ROUTING} is really granted — the permission check
     * happens in AudioService, not at install time.
     */
    public static Capability probe(Context context) {
        Capability cap = new Capability();
        StringBuilder sb = new StringBuilder();

        cap.classesPresent = Hidden.hasClass(C_POLICY) && Hidden.hasClass(C_MIX)
                && Hidden.hasClass(C_RULE);
        sb.append("audiopolicy classes: ").append(cap.classesPresent ? "present" : "MISSING").append('\n');
        if (!cap.classesPresent) {
            cap.detail = sb.toString();
            return cap;
        }

        try {
            sb.append("RULE_MATCH_UID = ").append(ruleMatchUid()).append('\n');
            sb.append("ROUTE_FLAG_LOOP_BACK = ").append(routeFlagLoopback()).append('\n');
            Hidden.method(C_RULE_B, "addMixRule", int.class, Object.class);
            Hidden.method(C_MIX_B, "setRouteFlags", int.class);
            Hidden.method(C_POLICY, "createAudioRecordSink", Hidden.cls(C_MIX));
            Hidden.method("android.media.AudioManager", "registerAudioPolicy", Hidden.cls(C_POLICY));
            cap.recipeComplete = true;
            sb.append("call chain: complete\n");
        } catch (Hidden.MissingApi e) {
            sb.append("call chain: BROKEN at ").append(e.member).append('\n');
            cap.detail = sb.toString();
            return cap;
        }

        cap.canAttachLive = Hidden.hasMethod(C_POLICY, "attachMixes", List.class)
                && Hidden.hasMethod(C_POLICY, "detachMixes", List.class);
        sb.append("live attach/detach: ").append(cap.canAttachLive ? "yes" : "no (policy rebuild per change)").append('\n');

        AudioPolicyBridge probe = new AudioPolicyBridge(context);
        // Route our own uid: a real registration, and harmless because nothing
        // in this process plays audio during the probe.
        boolean ok = probe.addUid(android.os.Process.myUid());
        cap.policyAccepted = ok;
        sb.append("registerAudioPolicy: ").append(ok ? "ACCEPTED — privileged mode available"
                : "refused — " + probe.lastError()).append('\n');
        probe.shutdown();

        cap.detail = sb.toString();
        return cap;
    }
}
