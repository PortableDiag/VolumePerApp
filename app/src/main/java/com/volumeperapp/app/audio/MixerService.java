package com.volumeperapp.app.audio;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import com.volumeperapp.app.R;
import com.volumeperapp.app.data.VolumeStore;
import com.volumeperapp.app.ui.MixerActivity;

/**
 * Holds the routing engine for as long as any app is adjusted.
 *
 * <p>Foreground, and honestly so: the process is rendering audio, which is
 * exactly what {@code mediaPlayback} means. It stops itself when the last fader
 * returns to 100 %, so an unused mixer is not a permanent notification.
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

    /** Starts, stops or nudges the service to match the current settings. */
    public static void sync(Context context) {
        VolumeStore store = new VolumeStore(context);
        boolean wanted = store.engineEnabled() && !store.adjustedPackages().isEmpty();
        Intent i = new Intent(context, MixerService.class).setAction(ACTION_SYNC);
        if (wanted) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(i);
            } else {
                context.startService(i);
            }
        } else {
            context.stopService(new Intent(context, MixerService.class));
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
        startForeground(NOTIFICATION_ID, buildNotification(eng));

        if (!store.engineEnabled() || store.adjustedPackages().isEmpty()) {
            Log.i(TAG, "nothing to do; stopping");
            eng.stop();
            stopSelf();
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

    private Notification buildNotification(RoutingEngine eng) {
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MixerActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        String text = eng.status();
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_mixer)
                .setContentTitle(getString(eng.isPrivileged()
                        ? R.string.engine_active : R.string.engine_limited))
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(open)
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .build();
    }
}
