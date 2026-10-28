package com.volumeperapp.app.api;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import com.volumeperapp.app.R;
import com.volumeperapp.app.ui.MixerActivity;

/**
 * Hosts the local API for as long as it is switched on.
 *
 * <p>Not part of {@code MixerService}: that one exists only while some app is
 * adjusted, and stops itself when the last fader returns to 100 %. The API has
 * to answer precisely when nothing is adjusted, because "turn this app down" is
 * the request an agent most obviously makes. So it has its own foreground
 * service, with a notification saying plainly that a port is open.
 */
public final class ApiService extends Service {

    private static final String TAG = "VPA.Api";
    private static final String CHANNEL = "api";
    private static final int NOTIFICATION_ID = 2;

    static final String ACTION_STOP = "com.volumeperapp.app.api.STOP";

    private ApiServer server;

    /** Starts the API if it is enabled; a no-op otherwise. */
    public static void startIfEnabled(Context context) {
        if (!ApiPrefs.enabled(context)) return;
        try {
            context.startForegroundService(new Intent(context, ApiService.class));
        } catch (Exception e) {
            Log.w(TAG, "could not start the API service", e);
        }
    }

    public static void stop(Context context) {
        context.stopService(new Intent(context, ApiService.class));
    }

    /** For a port change, which needs a fresh socket. */
    public static void restart(Context context) {
        stop(context);
        startIfEnabled(context);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            // The notification's own Stop turns the setting off too, or the
            // next launch would reopen it.
            ApiPrefs.setEnabled(this, false);
            ApiStatus.update(false, "", null);
            stopSelf();
            return START_NOT_STICKY;
        }
        int port = ApiPrefs.port(this);
        String address = ApiPrefs.BIND_ADDRESS + ":" + port;
        // Foreground first: a service that binds a socket before posting its
        // notification can be killed with the socket open.
        startForegroundWith(address);

        if (!ApiPrefs.enabled(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (server != null && server.isRunning()) return START_STICKY;

        Context app = getApplicationContext();
        // PhoneAPI may have been uninstalled or re-signed while nothing was listening.
        PhoneApiAccess.revokeIfGone(app);
        ApiRouter router = new ApiRouter(new VolumePerAppBackend(app), () -> ApiPrefs.key(app),
                () -> PhoneApiAccess.grant(app));
        ApiServer api = new ApiServer(router, ApiPrefs.BIND_ADDRESS, port);
        try {
            api.start();
            server = api;
            ApiStatus.update(true, api.address(), null);
            return START_STICKY;
        } catch (Exception e) {
            Log.w(TAG, "could not listen on " + address, e);
            ApiStatus.update(false, "", e.getMessage() != null ? e.getMessage()
                    : e.getClass().getSimpleName());
            stopSelf();
            return START_NOT_STICKY;
        }
    }

    @Override
    public void onDestroy() {
        if (server != null) server.stop();
        server = null;
        ApiStatus.update(false, "", ApiStatus.error());
        super.onDestroy();
    }

    private void startForegroundWith(String address) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null && nm.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL,
                    getString(R.string.api_channel), NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MixerActivity.class), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, ApiService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_mixer)
                .setContentTitle(getString(R.string.api_notification_title))
                .setContentText(getString(R.string.api_notification_text, address))
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(null,
                        getString(R.string.api_stop), stop).build())
                .setOngoing(true)
                .setShowWhen(false)
                .setCategory(Notification.CATEGORY_SERVICE)
                .build();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, n);
        }
    }
}
