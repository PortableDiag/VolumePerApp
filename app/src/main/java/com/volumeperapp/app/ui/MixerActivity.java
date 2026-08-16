package com.volumeperapp.app.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;
import com.volumeperapp.app.R;
import com.volumeperapp.app.audio.MixerService;
import com.volumeperapp.app.audio.PlaybackWatcher;
import com.volumeperapp.app.audio.RoutingEngine;
import com.volumeperapp.app.data.AppEntry;
import com.volumeperapp.app.data.AppRepository;
import com.volumeperapp.app.data.VolumeStore;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The mixer. One screen, one fader per app, and a banner at the top that says
 * plainly whether those faders are doing anything.
 *
 * <p>The list is rebuilt from three sources on every refresh — the user's pinned
 * apps, anything currently playing, and the platform streams — rather than kept
 * incrementally, because playback state changes underneath us constantly and a
 * diffing scheme would be more code for a list that is a dozen rows long.
 */
public final class MixerActivity extends AppCompatActivity
        implements MixerAdapter.Callbacks, RoutingEngine.StateListener {

    private static final int REQ_PICK = 1;

    private VolumeStore store;
    private AppRepository apps;
    private RoutingEngine engine;
    private AudioManager audioManager;
    private MixerAdapter adapter;
    private RecyclerView list;

    private static final int[] STREAMS = {
            AudioManager.STREAM_MUSIC,
            AudioManager.STREAM_RING,
            AudioManager.STREAM_ALARM,
            AudioManager.STREAM_NOTIFICATION,
            AudioManager.STREAM_SYSTEM,
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        store = new VolumeStore(this);
        setTheme(themeFor(store.theme()));
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_mixer);

        apps = new AppRepository(this);
        engine = MixerService.engine(this);
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setOnMenuItemClickListener(this::onMenu);
        updateThemeMenuTitle(toolbar);

        adapter = new MixerAdapter(this, this);
        list = findViewById(R.id.list);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setAdapter(adapter);
        list.setItemAnimator(null);   // faders should not slide about as state ticks

        ExtendedFloatingActionButton fab = findViewById(R.id.fab_add);
        fab.setOnClickListener(v ->
                startActivityForResult(new Intent(this, AppPickerActivity.class), REQ_PICK));

        requestNotificationPermissionIfNeeded();
    }

    static int themeFor(String name) {
        return "terminal".equals(name)
                ? R.style.Theme_VolumePerApp_Terminal
                : R.style.Theme_VolumePerApp;
    }

    private void updateThemeMenuTitle(MaterialToolbar toolbar) {
        boolean terminal = "terminal".equals(store.theme());
        toolbar.getMenu().findItem(R.id.action_theme)
                .setTitle(terminal ? R.string.theme_ocean : R.string.theme_terminal);
    }

    @Override
    protected void onResume() {
        super.onResume();
        engine.addStateListener(this);
        // Playback detection first, and independently of routing: an app has to
        // be visible in "Playing now" before there is any reason to adjust it.
        engine.setUiVisible(true);
        engine.watcher().refresh();
        // Then start the engine if anything is adjusted; harmless when nothing is.
        MixerService.sync(this);
        rebuild();
    }

    @Override
    protected void onPause() {
        engine.removeStateListener(this);
        // Leaves the callback registered only if routing still needs it.
        engine.setUiVisible(false);
        super.onPause();
    }

    @Override
    public void onEngineStateChanged() {
        runOnUiThread(this::rebuild);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PICK && resultCode == RESULT_OK && data != null) {
            String pkg = data.getStringExtra(AppPickerActivity.EXTRA_PACKAGE);
            if (pkg != null) {
                store.pin(pkg);
                rebuild();
            }
        }
    }

    // ------------------------------------------------------------------ list

    private void rebuild() {
        List<Row> rows = new ArrayList<>();
        // "live" means the faders are not known to be inert — not that the
        // engine happens to be running. Before anything is adjusted the engine
        // is idle by design, and dimming every fader then would say "this does
        // nothing" about controls that work perfectly well. It dims only once a
        // registration has actually been refused.
        boolean live = !engine.isRunning() || engine.isPrivileged();
        // Separate signal, deliberately: the banner may only claim privileged
        // mode once a policy has actually been accepted. "Not known to be
        // inert" (live) and "proven to work" (routingProven) are different
        // claims, and the banner is the one place that must make the stronger.
        boolean routingProven = engine.isRunning() && engine.isPrivileged();

        PlaybackWatcher watcher = engine.watcher();
        Set<Integer> activeUids = engine.isWatching()
                ? watcher.activeUids() : java.util.Collections.emptySet();
        Set<String> activePkgs = engine.isWatching()
                ? watcher.activePackages() : java.util.Collections.emptySet();
        Set<Integer> routedUids = engine.routedUids();

        rows.add(banner(routingProven, watcher));

        // Every app that deserves a strip: pinned, adjusted, or making noise.
        Map<String, AppEntry> strips = new LinkedHashMap<>();
        Set<String> wanted = new LinkedHashSet<>();
        wanted.addAll(activePkgs);
        for (Integer uid : activeUids) {
            String pkg = apps.packageForUid(uid);
            if (pkg != null && !pkg.equals(getPackageName())) wanted.add(pkg);
        }
        wanted.addAll(store.pinned());
        wanted.addAll(store.adjustedPackages());

        for (String pkg : wanted) {
            AppEntry e = apps.entryFor(pkg);
            if (e == null) continue;
            e.gainPercent = store.gainPercent(pkg);
            e.muted = store.isMuted(pkg);
            e.playing = activePkgs.contains(pkg) || activeUids.contains(e.uid);
            e.routed = routedUids.contains(e.uid);
            e.icon = apps.iconFor(pkg);
            e.captureBlocked = !apps.captureAllowed(pkg);
            strips.put(pkg, e);
        }

        List<AppEntry> playing = new ArrayList<>();
        List<AppEntry> rest = new ArrayList<>();
        for (AppEntry e : strips.values()) {
            (e.playing ? playing : rest).add(e);
        }

        int max = store.maxGainPercent();
        if (!playing.isEmpty()) {
            rows.add(new Row.Section(getString(R.string.section_playing)));
            for (AppEntry e : playing) rows.add(new Row.Channel(e, live, max));
        }
        // A section header with nothing under it is noise; the empty-state card
        // only earns its place when the mixer is genuinely empty.
        if (!rest.isEmpty()) {
            rows.add(new Row.Section(getString(R.string.section_mixer)));
            for (AppEntry e : rest) rows.add(new Row.Channel(e, live, max));
        } else if (playing.isEmpty()) {
            rows.add(new Row.Section(getString(R.string.section_mixer)));
            rows.add(new Row.Empty());
        }

        rows.add(new Row.Section(getString(R.string.section_streams)));
        for (int type : STREAMS) {
            rows.add(new Row.Stream(type, streamLabel(type),
                    audioManager.getStreamVolume(type),
                    audioManager.getStreamMaxVolume(type)));
        }

        adapter.submit(rows);
    }

    private Row.Banner banner(boolean live, PlaybackWatcher watcher) {
        if (live) {
            String body = getString(R.string.banner_privileged_body);
            if (!watcher.sessionAccessGranted()) {
                body += "\n\nPlayback detection is off, so apps only appear here once you add them.";
            }
            return new Row.Banner(Row.Banner.Level.OK,
                    getString(R.string.banner_privileged_title), body,
                    watcher.sessionAccessGranted() ? null
                            : getString(R.string.grant_session_access));
        }
        if (store.adjustedPackages().isEmpty() && !engine.isRunning()) {
            return new Row.Banner(Row.Banner.Level.INFO,
                    getString(R.string.banner_idle_title),
                    getString(R.string.banner_idle_body), null);
        }
        return new Row.Banner(Row.Banner.Level.WARN,
                getString(R.string.banner_limited_title),
                getString(R.string.banner_limited_body),
                getString(R.string.banner_action));
    }

    private String streamLabel(int type) {
        switch (type) {
            case AudioManager.STREAM_MUSIC: return getString(R.string.stream_media);
            case AudioManager.STREAM_RING: return getString(R.string.stream_ring);
            case AudioManager.STREAM_ALARM: return getString(R.string.stream_alarm);
            case AudioManager.STREAM_NOTIFICATION: return getString(R.string.stream_notification);
            case AudioManager.STREAM_VOICE_CALL: return getString(R.string.stream_call);
            default: return getString(R.string.stream_system);
        }
    }

    // -------------------------------------------------------------- callbacks

    @Override
    public void onGainChanged(AppEntry app, int percent) {
        store.setGainPercent(app.packageName, percent);
        store.pin(app.packageName);
        pushGain(app.packageName);
    }

    @Override
    public void onMuteToggled(AppEntry app) {
        boolean muted = !store.isMuted(app.packageName);
        store.setMuted(app.packageName, muted);
        store.pin(app.packageName);
        pushGain(app.packageName);
        rebuild();
    }

    @Override
    public void onRemove(AppEntry app) {
        store.unpin(app.packageName);
        MixerService.sync(this);
        if (engine.isRunning()) engine.syncRouting();
        rebuild();
    }

    @Override
    public void onStreamChanged(int streamType, int volume) {
        try {
            audioManager.setStreamVolume(streamType, volume, 0);
        } catch (SecurityException e) {
            // Ring/notification need Do Not Disturb access on some builds.
            Toast.makeText(this, "That stream is locked by Do Not Disturb policy",
                    Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onBannerAction() {
        if (engine.isPrivileged()) {
            openSessionAccess();
        } else {
            startActivity(new Intent(this, DiagnosticsActivity.class));
        }
    }

    /**
     * A fader move must reach the engine whether or not the service is already
     * up: crossing away from 100 % is exactly what starts it.
     */
    private void pushGain(String pkg) {
        if (store.needsRouting(pkg) || !store.adjustedPackages().isEmpty()) {
            Intent i = new Intent(this, MixerService.class)
                    .setAction(MixerService.ACTION_GAIN)
                    .putExtra(MixerService.EXTRA_PACKAGE, pkg);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i);
            else startService(i);
        } else {
            MixerService.sync(this);
        }
    }

    // ------------------------------------------------------------------- menu

    private boolean onMenu(android.view.MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_theme) {
            store.setTheme("terminal".equals(store.theme()) ? "ocean" : "terminal");
            recreate();
            return true;
        } else if (id == R.id.action_diagnostics) {
            startActivity(new Intent(this, DiagnosticsActivity.class));
            return true;
        } else if (id == R.id.action_session_access) {
            openSessionAccess();
            return true;
        } else if (id == R.id.action_max_gain) {
            showMaxGainDialog();
            return true;
        } else if (id == R.id.action_reset) {
            store.resetAll();
            MixerService.sync(this);
            if (engine.isRunning()) engine.syncRouting();
            rebuild();
            Toast.makeText(this, R.string.reset_done, Toast.LENGTH_SHORT).show();
            return true;
        }
        return false;
    }

    private void openSessionAccess() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.grant_session_access)
                .setMessage(R.string.session_access_prompt)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    try {
                        startActivity(new Intent(
                                Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
                    } catch (Exception e) {
                        Toast.makeText(this, "No settings screen for that on this build",
                                Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showMaxGainDialog() {
        final int[] options = {100, 150, 200, 400};
        String[] labels = new String[options.length];
        for (int i = 0; i < options.length; i++) {
            labels[i] = options[i] + " %" + (options[i] == 100 ? "  (attenuate only)" : "");
        }
        int current = 0;
        for (int i = 0; i < options.length; i++) {
            if (options[i] == store.maxGainPercent()) current = i;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.max_gain)
                .setSingleChoiceItems(labels, current, (d, which) -> {
                    store.setMaxGainPercent(options[which]);
                    d.dismiss();
                    rebuild();
                })
                .show();
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 2);
        }
    }
}
