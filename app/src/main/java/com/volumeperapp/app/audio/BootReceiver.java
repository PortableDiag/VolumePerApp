package com.volumeperapp.app.audio;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Restores the mixer after a reboot.
 *
 * <p>A fader the user set to 30 % should still be at 30 % after a restart
 * without them opening the app, so the service comes back if — and only if —
 * something is actually adjusted. {@link MixerService#sync} makes that call.
 */
public final class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        MixerService.sync(context);
    }
}
