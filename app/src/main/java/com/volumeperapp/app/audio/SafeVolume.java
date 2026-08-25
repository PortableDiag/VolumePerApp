package com.volumeperapp.app.audio;

import android.content.Context;
import android.provider.Settings;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * The platform's hearing-safety nag, and the one switch that turns it off.
 *
 * <p>Two separate annoyances — the <em>Volume alert</em> dialog when you raise a
 * headset past the safe index, and the <em>Volume lowered</em> attenuation after
 * a long listen — are the same mechanism. Both are gated on
 * {@code SoundDoseHelper.mSafeMediaVolumeState}: the dialog is what the ACTIVE
 * state does when you raise, and the lowering is what it does when the 20-hour
 * {@code mMusicActiveMs} timer re-arms it. There is no lever that suppresses one
 * and keeps the other, so this is deliberately one setting and not two.
 *
 * <p><b>Why this cannot be a live toggle.</b> The state is decided by
 * {@code SoundDoseHelper.updateSafeMediaVolume_l}:
 *
 * <pre>
 *   bypass  = SystemProperties.getBoolean("audio.safemedia.bypass", false) || mEnableCsd;
 *   force   = SystemProperties.getBoolean("audio.safemedia.force",  false);
 *   enabled = (config_safe_media_volume_enabled || force) &amp;&amp; !bypass;
 *   if (enabled) { ...ACTIVE, enforceSafeMediaVolume()... }
 *   else         { mSafeMediaVolumeState = DISABLED; }
 * </pre>
 *
 * and that method runs only from {@code onConfigureSafeMedia}, whose two callers
 * are {@code AudioService.onSystemReady} (forced) and
 * {@code onConfigurationChanged} (not forced, so it re-runs on an MCC change
 * only). The property is therefore read once per boot, before zygote. Setting it
 * at runtime changes nothing, and writing
 * {@code Settings.Global.audio_safe_volume_state} directly is worse than
 * useless: AudioService recomputes and overwrites it on the next boot.
 *
 * <p>So the app does not apply this itself. It records the request as a file in
 * <b>device-protected storage</b> and the Magisk module's {@code post-fs-data.sh}
 * reads it and sets the property before {@code system_server} starts. Device
 * protected, not the ordinary prefs, because {@code post-fs-data} runs before the
 * user is unlocked — {@code /data/user_de/0/<pkg>} is readable there and
 * {@code /data/data/<pkg>} is not, which was measured rather than assumed.
 *
 * <p>Pinned against OxygenOS V15.0.0 on Android 15 / API 35. OnePlus skins the
 * dialog ({@code safe_media_headphone_volume_is_high_warning_dragonfly},
 * {@code oplus_safe_volume_down}) and patches this area, but hangs its UI off the
 * same AOSP state — confirmed on the device, not inferred from the strings.
 */
public final class SafeVolume {

    private static final String TAG = "VPA.SafeVolume";

    /** Read by {@code magisk/post-fs-data.sh}. The two must agree. */
    private static final String FLAG_FILE = "safemedia_bypass";

    /** The property {@code SoundDoseHelper} reads once, at {@code onSystemReady}. */
    public static final String PROPERTY = "audio.safemedia.bypass";

    /**
     * {@code Settings.Global.AUDIO_SAFE_VOLUME_STATE}. Hidden as a constant but
     * an ordinary global setting to read, so no permission is involved.
     */
    private static final String SETTING_STATE = "audio_safe_volume_state";

    public static final int NOT_CONFIGURED = 0;
    public static final int DISABLED       = 1;
    public static final int INACTIVE       = 2;
    public static final int ACTIVE         = 3;

    private SafeVolume() { }

    // ------------------------------------------------------------- the request

    private static File flagFile(Context context) {
        Context de = context.getApplicationContext().createDeviceProtectedStorageContext();
        return new File(de.getFilesDir(), FLAG_FILE);
    }

    /** True when the user has asked for the warning to be suppressed. */
    public static boolean isRequested(Context context) {
        return flagFile(context).isFile();
    }

    /**
     * Records the request. Takes effect at the next boot and nowhere else —
     * callers must say so rather than implying the switch did something now.
     *
     * @return false if the flag could not be written, in which case nothing was
     *         promised to the user.
     */
    public static boolean setRequested(Context context, boolean requested) {
        File f = flagFile(context);
        try {
            if (requested) {
                // Content, not mere presence: an empty file left behind by a
                // failed write must not read as "on" to the boot script.
                try (FileOutputStream out = new FileOutputStream(f)) {
                    out.write('1');
                }
            } else if (f.exists() && !f.delete()) {
                throw new IOException("could not delete " + f);
            }
            return true;
        } catch (IOException | SecurityException e) {
            Log.w(TAG, "cannot record safe-volume request", e);
            return false;
        }
    }

    // -------------------------------------------------------------- the effect

    /** The platform's own view of it: one of the four state constants above. */
    public static int state(Context context) {
        try {
            return Settings.Global.getInt(
                    context.getContentResolver(), SETTING_STATE, NOT_CONFIGURED);
        } catch (Throwable t) {
            return NOT_CONFIGURED;
        }
    }

    /** True when the platform has actually stopped warning and lowering. */
    public static boolean isSuppressed(Context context) {
        return state(context) == DISABLED;
    }

    /**
     * Whether the boot property is set on this boot. Diagnostic only — the
     * authoritative answer is {@link #isSuppressed}, which is what the platform
     * concluded from it.
     */
    public static String property() {
        try {
            return (String) Hidden.method("android.os.SystemProperties", "get", String.class)
                    .invoke(null, PROPERTY);
        } catch (Throwable t) {
            return null;
        }
    }

    public static String stateName(int state) {
        switch (state) {
            case DISABLED: return "DISABLED (suppressed)";
            case INACTIVE: return "INACTIVE (armed, not currently enforcing)";
            case ACTIVE:   return "ACTIVE (warning and lowering)";
            default:       return "NOT_CONFIGURED";
        }
    }

    /**
     * What the user should be told, given that the request and the effect can
     * disagree for exactly as long as it takes to restart.
     */
    public static String status(Context context) {
        boolean requested = isRequested(context);
        boolean suppressed = isSuppressed(context);
        if (requested && suppressed) {
            return "Suppressed. The system will not warn, and will not lower the volume on you.";
        }
        if (requested) {
            return "Takes effect when you restart the phone. The Magisk module sets the property"
                    + " at boot, because the platform reads it once and never again. If it is"
                    + " still active after a restart, the installed module predates this"
                    + " feature — re-flash it.";
        }
        if (suppressed) {
            return "Still suppressed until you restart the phone.";
        }
        return "Active. The system warns above the safe level, and lowers the volume after a"
                + " long listen.";
    }
}
