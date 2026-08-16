package com.volumeperapp.app.audio;

import android.content.ComponentName;
import android.content.Context;
import android.media.AudioManager;
import android.media.AudioPlaybackConfiguration;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Who is making noise right now.
 *
 * <p>Two sources, because neither is sufficient alone:
 *
 * <ul>
 *   <li>{@code AudioManager.getActivePlaybackConfigurations()} sees every
 *       player, including ones with no media session — but the uid it holds,
 *       {@code getClientUid()}, is hidden. As a priv-app we can read it; as an
 *       ordinary install we cannot, and this source degrades to a count.</li>
 *   <li>{@code MediaSessionManager.getActiveSessions()} gives package names
 *       outright, but needs notification-listener access and only sees apps
 *       that publish a session — so it misses games and notification sounds.</li>
 * </ul>
 *
 * <p>{@code AudioPlaybackConfiguration} is the trap recorded in the design
 * notes: it looks like the class that names what is playing and it is not.
 * Hence the pairing.
 */
public final class PlaybackWatcher {

    private static final String TAG = "VPA.Watch";

    public interface Listener {
        void onActiveUidsChanged(Set<Integer> uids, Set<String> packages);
    }

    private final Context context;
    private final AudioManager audioManager;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final int selfUid = android.os.Process.myUid();

    private Listener listener;
    private Set<Integer> activeUids = Collections.emptySet();
    private Set<String> activePackages = Collections.emptySet();
    private boolean uidReadable = true;

    private final AudioManager.AudioPlaybackCallback callback =
            new AudioManager.AudioPlaybackCallback() {
                @Override
                public void onPlaybackConfigChanged(List<AudioPlaybackConfiguration> configs) {
                    recompute(configs);
                }
            };

    public PlaybackWatcher(Context context) {
        this.context = context.getApplicationContext();
        this.audioManager = (AudioManager) this.context.getSystemService(Context.AUDIO_SERVICE);
    }

    public void start(Listener l) {
        this.listener = l;
        audioManager.registerAudioPlaybackCallback(callback, main);
        refresh();
    }

    public void stop() {
        audioManager.unregisterAudioPlaybackCallback(callback);
        listener = null;
    }

    public void refresh() {
        recompute(audioManager.getActivePlaybackConfigurations());
    }

    /** False once we have learned that {@code getClientUid} is not reachable. */
    public boolean uidReadable() {
        return uidReadable;
    }

    public Set<Integer> activeUids() {
        return activeUids;
    }

    public Set<String> activePackages() {
        return activePackages;
    }

    private void recompute(List<AudioPlaybackConfiguration> configs) {
        Set<Integer> uids = new LinkedHashSet<>();
        if (configs != null) {
            for (AudioPlaybackConfiguration c : configs) {
                if (playerState(c) != PLAYER_STATE_STARTED) continue;
                Integer uid = clientUid(c);
                if (uid == null) continue;
                // Our own re-rendered output is not somebody else's playback.
                if (uid == selfUid) continue;
                uids.add(uid);
            }
        }

        Set<String> pkgs = new LinkedHashSet<>(sessionPackages());

        boolean changed = !uids.equals(activeUids) || !pkgs.equals(activePackages);
        activeUids = uids;
        activePackages = pkgs;
        if (changed && listener != null) {
            listener.onActiveUidsChanged(activeUids, activePackages);
        }
    }

    /** {@code AudioPlaybackConfiguration.PLAYER_STATE_STARTED}, pinned = 2. */
    private static final int PLAYER_STATE_STARTED = 2;
    private static final int PLAYER_STATE_UNKNOWN = -1;

    /**
     * {@code getPlayerState()} is hidden alongside {@code getClientUid()}. When
     * it is unreachable we return UNKNOWN, which excludes the entry — the media
     * session list is then the only source, which is the documented fallback.
     */
    private int playerState(AudioPlaybackConfiguration c) {
        try {
            Object v = Hidden.call(c, "android.media.AudioPlaybackConfiguration",
                    "getPlayerState", new Class[0]);
            return v instanceof Integer ? (Integer) v : PLAYER_STATE_UNKNOWN;
        } catch (Throwable t) {
            return PLAYER_STATE_UNKNOWN;
        }
    }

    private Integer clientUid(AudioPlaybackConfiguration c) {
        try {
            Object v = Hidden.call(c, "android.media.AudioPlaybackConfiguration",
                    "getClientUid", new Class[0]);
            return v instanceof Integer ? (Integer) v : null;
        } catch (Throwable t) {
            if (uidReadable) {
                Log.i(TAG, "getClientUid unreachable — falling back to media sessions only");
                uidReadable = false;
            }
            return null;
        }
    }

    // ------------------------------------------------------- media sessions

    /** True when the user has switched our notification listener on. */
    public boolean sessionAccessGranted() {
        String enabled = Settings.Secure.getString(
                context.getContentResolver(), "enabled_notification_listeners");
        if (enabled == null) return false;
        String self = context.getPackageName();
        for (String part : enabled.split(":")) {
            ComponentName cn = ComponentName.unflattenFromString(part);
            if (cn != null && self.equals(cn.getPackageName())) return true;
        }
        return false;
    }

    public Set<String> sessionPackages() {
        if (!sessionAccessGranted()) return Collections.emptySet();
        Set<String> out = new LinkedHashSet<>();
        try {
            MediaSessionManager msm = (MediaSessionManager)
                    context.getSystemService(Context.MEDIA_SESSION_SERVICE);
            if (msm == null) return out;
            ComponentName me = new ComponentName(context, SessionListenerService.class);
            List<MediaController> controllers = msm.getActiveSessions(me);
            for (MediaController mc : controllers) {
                android.media.session.PlaybackState st = mc.getPlaybackState();
                if (st != null && st.getState() == android.media.session.PlaybackState.STATE_PLAYING) {
                    out.add(mc.getPackageName());
                }
            }
        } catch (SecurityException e) {
            Log.i(TAG, "media session access refused: " + e.getMessage());
        } catch (Throwable t) {
            Log.w(TAG, "getActiveSessions failed", t);
        }
        return out;
    }
}
