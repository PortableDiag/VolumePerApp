package com.volumeperapp.app;

import android.app.Application;

import androidx.appcompat.app.AppCompatDelegate;

import com.volumeperapp.app.audio.Hidden;

/**
 * Application entry point. Two jobs, both done before any Activity exists.
 */
public final class VolumePerApp extends Application {

    @Override
    public void onCreate() {
        super.onCreate();

        // No-op when installed as a priv-app, where enforcement is already off.
        // On a plain side-load it is what lets Diagnostics report the truth
        // about the hidden surface instead of "class not found".
        Hidden.unseal();

        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
    }
}
