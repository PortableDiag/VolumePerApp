package com.volumeperapp.app.audio;

import android.content.Context;
import android.media.AudioRecord;
import android.util.Log;

import com.volumeperapp.app.data.AppRepository;
import com.volumeperapp.app.data.VolumeStore;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The part that decides <em>when</em> to divert an app, as opposed to
 * {@link AudioPolicyBridge}, which knows <em>how</em>.
 *
 * <p>Two sets, kept apart on purpose:
 *
 * <ul>
 *   <li><b>Routed</b> — apps whose fader is not at 100 %. A mix is attached for
 *       each; their audio no longer reaches the speaker on its own.</li>
 *   <li><b>Pumping</b> — the subset of routed apps that are actually playing.
 *       A pump is a thread and an {@code AudioTrack}, so it exists only while
 *       there is sound to carry.</li>
 * </ul>
 *
 * <p>An app at 100 % is never routed at all. That matters: routing adds a buffer
 * of latency and a thread, and "leave it alone" should cost neither.
 */
public final class RoutingEngine implements PlaybackWatcher.Listener {

    private static final String TAG = "VPA.Engine";

    public interface StateListener {
        void onEngineStateChanged();
    }

    private final Context context;
    private final VolumeStore store;
    private final AppRepository apps;
    private final AudioPolicyBridge bridge;
    private final PlaybackWatcher watcher;

    /** uid -> package, for every routed app. */
    private final Map<Integer, String> routed = new LinkedHashMap<>();
    private final Map<Integer, StreamPump> pumps = new HashMap<>();

    /**
     * More than one thing needs to know when the engine changes state — the
     * foreground notification and the mixer screen, at least. A single slot
     * looked sufficient and was not: whichever registered last silently
     * displaced the other, and the symptom was a mixer that routed audio
     * correctly while still displaying "nothing is adjusted".
     */
    private final java.util.concurrent.CopyOnWriteArrayList<StateListener> stateListeners =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    private boolean running;
    private boolean watching;
    private boolean uiVisible;
    private boolean privileged;
    private String status = "stopped";

    public RoutingEngine(Context context) {
        this.context = context.getApplicationContext();
        this.store = new VolumeStore(this.context);
        this.apps = new AppRepository(this.context);
        this.bridge = new AudioPolicyBridge(this.context);
        this.watcher = new PlaybackWatcher(this.context);
    }

    public void addStateListener(StateListener l) {
        if (l != null && !stateListeners.contains(l)) stateListeners.add(l);
    }

    public void removeStateListener(StateListener l) {
        stateListeners.remove(l);
    }

    public boolean isPrivileged() {
        return privileged;
    }

    public boolean isRunning() {
        return running;
    }

    public String status() {
        return status;
    }

    public PlaybackWatcher watcher() {
        return watcher;
    }

    public synchronized Set<Integer> routedUids() {
        return new HashSet<>(routed.keySet());
    }

    public synchronized boolean isPumping(int uid) {
        return pumps.containsKey(uid);
    }

    // ---------------------------------------------------------------- lifecycle

    /**
     * Playback detection runs whenever <em>either</em> the mixer is on screen or
     * the engine is routing — and stops when neither is true.
     *
     * <p>It needs no privilege and no policy; it is a callback registration. But
     * gating it on the engine alone would be precisely backwards: the app you
     * want to turn down is the one making noise right now, and you have not
     * adjusted it yet, so the engine is not running. Gating it on the UI alone
     * would blind the engine the moment the mixer is backgrounded.
     */
    private void reconcileWatcher() {
        boolean want = running || uiVisible;
        if (want && !watching) {
            watching = true;
            watcher.start(this);
        } else if (!want && watching) {
            watching = false;
            watcher.stop();
        }
    }

    /** Called by the mixer screen as it comes and goes. */
    public synchronized void setUiVisible(boolean visible) {
        uiVisible = visible;
        reconcileWatcher();
    }

    public synchronized boolean isWatching() {
        return watching;
    }

    public synchronized void start() {
        if (running) return;
        running = true;
        reconcileWatcher();
        syncRouting();
        Log.i(TAG, "engine started; privileged=" + privileged);
        notifyState();
    }

    public synchronized void stop() {
        if (!running) return;
        running = false;
        reconcileWatcher();      // keeps watching if the mixer is still on screen
        for (StreamPump p : pumps.values()) p.stop();
        pumps.clear();
        routed.clear();
        bridge.shutdown();
        privileged = false;
        status = "stopped";
        Log.i(TAG, "engine stopped");
        notifyState();
    }

    /**
     * Re-reads the stored faders and makes the routed set match. Called on start
     * and whenever the user moves or clears a fader.
     */
    public synchronized void syncRouting() {
        if (!running) return;

        Map<Integer, String> wanted = new LinkedHashMap<>();
        for (String pkg : store.adjustedPackages()) {
            int uid = apps.uidFor(pkg);
            if (uid < 0) continue;              // uninstalled since we stored it
            wanted.put(uid, pkg);
        }

        for (Integer uid : new ArrayList<>(routed.keySet())) {
            if (!wanted.containsKey(uid)) {
                stopPump(uid);
                bridge.removeUid(uid);
                routed.remove(uid);
                Log.i(TAG, "unrouted uid " + uid);
            }
        }

        for (Map.Entry<Integer, String> e : wanted.entrySet()) {
            if (routed.containsKey(e.getKey())) continue;
            boolean ok = bridge.addUid(e.getKey());
            if (ok) {
                routed.put(e.getKey(), e.getValue());
                Log.i(TAG, "routed uid " + e.getKey() + " (" + e.getValue() + ")");
            } else {
                Log.w(TAG, "could not route " + e.getValue() + ": " + bridge.lastError());
            }
        }

        privileged = bridge.isRegistered();
        if (wanted.isEmpty()) {
            status = "idle — no app is adjusted";
        } else if (privileged) {
            status = "routing " + routed.size() + " of " + wanted.size() + " adjusted app(s)";
        } else {
            status = "limited — " + friendlyError(bridge.lastError());
        }

        // Attaching or detaching a mix can invalidate existing record sinks, so
        // every pump is rebuilt against the new policy rather than trusted.
        restartPumps();
        notifyState();
    }

    private String friendlyError(String raw) {
        if (raw == null) return "the routing policy was refused";
        if (raw.contains("permission denied") || raw.contains("PERMISSION")) {
            return "MODIFY_AUDIO_ROUTING is not granted — install the Magisk module";
        }
        return raw;
    }

    /** The user moved one fader; no policy change is needed if it stays routed. */
    public synchronized void onGainChanged(String pkg) {
        if (!running) return;
        int uid = apps.uidFor(pkg);
        boolean shouldRoute = store.needsRouting(pkg);
        boolean isRouted = routed.containsKey(uid);

        if (shouldRoute != isRouted) {
            syncRouting();                       // crossing 100 % adds/removes a mix
            return;
        }
        StreamPump p = pumps.get(uid);
        if (p != null) p.setGain(store.effectiveGain(pkg));
    }

    // ------------------------------------------------------------------- pumps

    @Override
    public void onActiveUidsChanged(Set<Integer> uids, Set<String> packages) {
        synchronized (this) {
            if (!running) return;
            restartPumps();
        }
        notifyState();
    }

    /**
     * Brings the pump set in line with "routed AND playing".
     *
     * <p>A pump is cheap to create and free to not have, so this is idempotent
     * and safe to call on every playback change.
     */
    private void restartPumps() {
        Set<Integer> active = new HashSet<>(watcher.activeUids());

        // A media session tells us a package is playing even when the uid was
        // unreadable, so fold those in.
        for (String pkg : watcher.activePackages()) {
            int uid = apps.uidFor(pkg);
            if (uid >= 0) active.add(uid);
        }

        // When we cannot see uids at all, run a pump for everything routed;
        // an idle loopback mix yields silence, which costs a thread and no audio.
        boolean blind = !watcher.uidReadable() && watcher.activePackages().isEmpty();

        for (Integer uid : new ArrayList<>(pumps.keySet())) {
            if (!routed.containsKey(uid) || (!blind && !active.contains(uid))) {
                stopPump(uid);
            }
        }

        for (Integer uid : routed.keySet()) {
            if (pumps.containsKey(uid)) continue;
            if (!blind && !active.contains(uid)) continue;
            String pkg = routed.get(uid);
            AudioRecord sink = bridge.sinkFor(uid);
            if (sink == null) continue;
            StreamPump pump = StreamPump.start(uid, sink, store.effectiveGain(pkg));
            if (pump != null) {
                pumps.put(uid, pump);
                Log.i(TAG, "pumping " + pkg + " (uid " + uid + ")");
            }
        }
    }

    private void stopPump(int uid) {
        StreamPump p = pumps.remove(uid);
        if (p != null) {
            p.stop();
            Log.i(TAG, "stopped pump for uid " + uid);
        }
    }

    private void notifyState() {
        for (StateListener l : stateListeners) {
            l.onEngineStateChanged();
        }
    }

    // -------------------------------------------------------------- reporting

    /** Human-readable engine state for the Diagnostics screen. */
    public synchronized String report() {
        StringBuilder sb = new StringBuilder();
        sb.append("running: ").append(running).append('\n');
        sb.append("privileged: ").append(privileged).append('\n');
        sb.append("status: ").append(status).append('\n');
        sb.append("uid visibility: ")
                .append(watcher.uidReadable() ? "AudioPlaybackConfiguration.getClientUid readable"
                        : "hidden — media sessions only").append('\n');
        sb.append("notification listener: ")
                .append(watcher.sessionAccessGranted() ? "enabled" : "not enabled").append('\n');
        sb.append("routed uids: ").append(routed.isEmpty() ? "(none)" : routed.toString()).append('\n');
        sb.append("live pumps: ").append(pumps.isEmpty() ? "(none)" : "").append('\n');
        for (Map.Entry<Integer, StreamPump> e : pumps.entrySet()) {
            StreamPump p = e.getValue();
            String pkg = routed.get(e.getKey());
            float measured = p.measuredGain();
            sb.append("  uid ").append(e.getKey())
              .append(pkg == null ? "" : " (" + pkg + ")")
              .append("\n    frames carried : ").append(p.framesCarried())
              .append("\n    peak in/out    : ").append(p.peakIn()).append(" -> ").append(p.peakOut())
              .append("\n    measured gain  : ")
              .append(measured < 0 ? "(input silent)"
                      : String.format(java.util.Locale.US, "%.3f", measured))
              .append("  [set: ")
              .append(String.format(java.util.Locale.US, "%.3f",
                      store.effectiveGain(pkg == null ? "" : pkg)))
              .append("]\n");
        }
        if (bridge.lastError() != null) {
            sb.append("last policy error: ").append(bridge.lastError()).append('\n');
        }
        return sb.toString();
    }

    public List<Integer> bridgeRoutedUids() {
        return bridge.routedUids();
    }

    public static List<String> emptyList() {
        return Collections.emptyList();
    }
}
