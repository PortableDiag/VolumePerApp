package com.volumeperapp.app.api;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * PhoneAPI was uninstalled, or reinstalled under another signature: drop its
 * key, so whatever next takes the package name does not inherit it.
 */
public final class PhoneApiGoneReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent.getData() == null) return;
        if (!PhoneApi.PKG.equals(intent.getData().getSchemeSpecificPart())) return;
        if (Intent.ACTION_PACKAGE_FULLY_REMOVED.equals(intent.getAction())) {
            PhoneApiAccess.revoke(context);
        } else {
            PhoneApiAccess.revokeIfGone(context);
        }
    }
}
