package com.volumeperapp.app.audio;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.volumeperapp.app.api.ApiService;

/**
 * Restores the mixer after a reboot or an update of this app.
 *
 * <p>A fader the user set to 30 % should still be at 30 % after a restart
 * without them opening the app, so the service comes back if — and only if —
 * something is actually adjusted. {@link MixerService#sync} makes that call.
 * The local API comes back too, if — and only if — it was switched on.
 */
public final class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        // An update kills the app the same way a reboot does: without this the
        // API stays off until someone opens the app, and PhoneAPI loses it.
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) return;
        MixerService.sync(context);
        ApiService.startIfEnabled(context);
    }
}
