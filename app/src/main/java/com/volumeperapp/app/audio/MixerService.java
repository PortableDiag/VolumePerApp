package com.volumeperapp.app.audio;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import com.volumeperapp.app.R;
import com.volumeperapp.app.data.VolumeStore;
import com.volumeperapp.app.ui.MixerActivity;

/**
 * Holds the routing engine for as long as any app is adjusted or the overall
 * boost is up.
 *
 * <p>Foreground, and honestly so: the process is rendering audio, which is
 * exactly what {@code mediaPlayback} means. It stops itself when the last fader
 * and the boost return to 100 %, so an unused mixer is not a permanent
 * notification.
 */
public final class MixerService extends Service {

    private static final String TAG = "VPA.Service";
    private static final String CHANNEL = "engine";
    private static final int NOTIFICATION_ID = 1;

    public static final String ACTION_SYNC = "com.volumeperapp.app.SYNC";
    public static final String ACTION_GAIN = "com.volumeperapp.app.GAIN";
    public static final String EXTRA_PACKAGE = "package";

    private static RoutingEngine sharedEngine;

    private final IBinder binder = new LocalBinder();
    private VolumeStore store;

    /** Held as a field so it can be removed again; a lambda per call could not. */
    private final RoutingEngine.StateListener notificationListener = () -> {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(NOTIFICATION_ID, buildNotification(engine(this)));
    };

    public final class LocalBinder extends Binder {
        public MixerService service() {
            return MixerService.this;
        }
    }

    /**
     * The engine outlives any single Activity but not the process, so it is held
     * here rather than in a component that Android is free to destroy.
     */
    public static synchronized RoutingEngine engine(Context context) {
        if (sharedEngine == null) {
            sharedEngine = new RoutingEngine(context.getApplicationContext());
        }
        return sharedEngine;
    }

    /**
     * Starts, stops or nudges the service to match the current settings.
     *
     * @return false only when the engine is wanted and Android refused to start
     *     the service for it — see {@link #start}.
     */
    public static boolean sync(Context context) {
        VolumeStore store = new VolumeStore(context);
        Intent i = new Intent(context, MixerService.class).setAction(ACTION_SYNC);
        if (store.engineWanted()) return start(context, i, eng -> eng.syncRouting());
        // Stopping goes through onStartCommand as well. stopService() while a
        // startForegroundService() is still undelivered kills the process, and
        // a finger jittering off the 100 % end of the boost does exactly that.
        if (engine(context).isRunning()) {
            start(context, i, eng -> context.stopService(i));
        }
        return true;
    }

    /**
     * The overall boost was moved.
     *
     * @return as {@link #pushGain}
     */
    public static boolean pushBoost(Context context) {
        RoutingEngine eng = engine(context);
        if (eng.isRunning() && new VolumeStore(context).engineWanted()) {
            eng.onBoostChanged();
            return true;
        }
        return sync(context);
    }

    /**
     * A fader move must reach the engine whether or not the service is already
     * up: crossing away from 100 % is exactly what starts it.
     *
     * @return whether the engine has the new gain; false when it was idle and
     *     Android would not let it start from where this was called.
     */
    public static boolean pushGain(Context context, String pkg) {
        VolumeStore store = new VolumeStore(context);
        if (store.needsRouting(pkg) || !store.adjustedPackages().isEmpty()) {
            Intent i = new Intent(context, MixerService.class)
                    .setAction(ACTION_GAIN)
                    .putExtra(EXTRA_PACKAGE, pkg);
            return start(context, i, eng -> eng.onGainChanged(pkg));
        }
        return sync(context);
    }

    private interface EngineCall {
        void run(RoutingEngine engine);
    }

    /**
     * Android 12 refuses a foreground-service start from the background, which
     * is where a local-API request comes from. When the engine is already
     * running its service is already foreground, so the change is handed to
     * the engine directly instead; only an idle engine has to wait for the app
     * to be opened.
     */
    private static boolean start(Context context, Intent intent, EngineCall fallback) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
            return true;
        } catch (IllegalStateException e) {
            // ForegroundServiceStartNotAllowedException is an IllegalStateException.
            RoutingEngine eng = engine(context);
            Log.w(TAG, "service start refused; engine running=" + eng.isRunning(), e);
            if (!eng.isRunning()) return false;
            fallback.run(eng);
            return true;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        store = new VolumeStore(this);
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        RoutingEngine eng = engine(this);
        goForeground(buildNotification(eng));

        if (!store.engineWanted()) {
            Log.i(TAG, "nothing to do; stopping");
            eng.stop();
            // Only if nothing newer is queued: a later start may want it again.
            stopSelf(startId);
            return START_NOT_STICKY;
        }

        eng.addStateListener(notificationListener);

        if (!eng.isRunning()) {
            eng.start();
        } else if (intent != null && ACTION_GAIN.equals(intent.getAction())) {
            eng.onGainChanged(intent.getStringExtra(EXTRA_PACKAGE));
        } else {
            eng.syncRouting();
        }
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onDestroy() {
        RoutingEngine eng = engine(this);
        eng.removeStateListener(notificationListener);
        eng.stop();
        super.onDestroy();
    }

    private void createChannel() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) return;
        NotificationChannel ch = new NotificationChannel(
                CHANNEL, getString(R.string.channel_engine), NotificationManager.IMPORTANCE_LOW);
        ch.setDescription(getString(R.string.channel_engine_desc));
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
    }

    /**
     * Android 15 throws here, not at startForegroundService(), when a
     * mediaPlayback service was started from BOOT_COMPLETED. Uncaught, that
     * crashed the process at every boot with a fader set, and took the local
     * API down with it. specialUse is allowed at boot.
     */
    private void goForeground(Notification n) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, n);
            return;
        }
        try {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } catch (IllegalStateException e) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) throw e;
            Log.i(TAG, "mediaPlayback refused, using specialUse: " + e.getMessage());
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        }
    }

    private int title(RoutingEngine eng) {
        if (eng.isPrivileged()) return R.string.engine_active;
        // Boost alone routes nothing, so "limited" would be a false alarm.
        return store.adjustedPackages().isEmpty() && store.isBoosted()
                ? R.string.engine_boost : R.string.engine_limited;
    }

    private Notification buildNotification(RoutingEngine eng) {
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MixerActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        String text = eng.status();
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_mixer)
                .setContentTitle(getString(title(eng)))
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(open)
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .build();
    }
}
