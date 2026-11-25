package com.volumeperapp.app.audio;

import android.media.audiofx.AudioEffect;
import android.media.audiofx.DynamicsProcessing;
import android.os.Build;
import android.util.Log;

import com.volumeperapp.app.data.VolumeStore;

import java.util.Locale;

/**
 * The overall boost: one effect on the global output mix (audio session 0),
 * after everything has been mixed and before it reaches the device.
 *
 * <p><b>Why not route everything?</b> A mix matching every app would add a
 * buffer of latency to all of them and silence every app that opts out of
 * capture. The effect diverts nothing, needs no privilege
 * ({@code MODIFY_AUDIO_SETTINGS} is a normal permission) and works in Limited
 * mode.
 *
 * <p><b>Why {@link DynamicsProcessing} and not {@code LoudnessEnhancer}</b>,
 * which is the obvious class: LoudnessEnhancer is a compressor with a target.
 * Measured on API 35 with the tone fixture (-9.0 dBFS), its +3 dB came out at
 * -9.6, quieter than no effect at all, and +6 dB at -9.0. DynamicsProcessing's
 * input gain is exact: +3 gave -6.0 and +6 gave -3.0. Its limiter then holds
 * +12 at -2.9, a full-scale sine being -3.0.
 *
 * <p>Two values that are not arbitrary: only
 * {@code VARIANT_FAVOR_FREQUENCY_RESOLUTION} exists in the platform's engine
 * (the other throws "bad parameter value"), and the limiter works on a block's
 * level rather than its peaks, so its threshold is a full-scale sine's level.
 * Music driven hard into it still distorts.
 *
 * <p>The platform attaches the effect to the output media is playing on. A
 * low-latency (fast) track bypasses it, which is why {@link StreamPump} gives
 * up its low-latency output while a boost is on.
 */
public final class Booster {

    private static final String TAG = "VPA.Boost";

    /** The global output mix. Deprecated since API 21 and still the only one. */
    private static final int OUTPUT_MIX = 0;

    private static final float LIMITER_THRESHOLD_DB = -3f;
    private static final float LIMITER_RATIO = 50f;
    private static final float LIMITER_ATTACK_MS = 1f;
    private static final float LIMITER_RELEASE_MS = 60f;

    private DynamicsProcessing effect;
    private int percent = VolumeStore.DEFAULT_PERCENT;
    private String lastError;

    /** DynamicsProcessing arrived in Android 9. */
    public static boolean supported() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.P;
    }

    /** 200 % is +6.02 dB: the same linear percent every fader uses. */
    public static float decibels(int percent) {
        return (float) (20.0 * Math.log10(percent / 100.0));
    }

    /** "+6.0 dB", as the screen and Diagnostics show it. */
    public static String decibelText(int percent) {
        return String.format(Locale.US, "+%.1f dB", decibels(percent));
    }

    /**
     * Makes the platform match {@code wanted}. 100 releases the effect
     * altogether, so an unboosted phone carries nothing of ours.
     *
     * @return whether the platform now matches
     */
    synchronized boolean apply(int wanted) {
        percent = wanted;
        if (wanted <= VolumeStore.DEFAULT_PERCENT) {
            release();
            lastError = null;
            return true;
        }
        if (!supported()) {
            lastError = "needs Android 9 or newer";
            return false;
        }
        if (effect != null && !alive()) release();
        try {
            if (effect == null) {
                effect = create();
                Log.i(TAG, "boost on, effect " + effect.getId());
            }
            effect.setInputGainAllChannelsTo(decibels(wanted));
            lastError = null;
            return true;
        } catch (Throwable t) {
            // IllegalStateException when another app holds the effect,
            // RuntimeException when the engine is absent on this build.
            lastError = t.getClass().getSimpleName() + ": " + t.getMessage();
            Log.w(TAG, "boost failed", t);
            release();
            return false;
        }
    }

    /** Puts back an effect that audioserver dropped or another app took over. */
    synchronized void check() {
        if (percent > VolumeStore.DEFAULT_PERCENT && !isActive()) apply(percent);
    }

    private static DynamicsProcessing create() {
        DynamicsProcessing.Limiter limiter = new DynamicsProcessing.Limiter(
                true, true, 0, LIMITER_ATTACK_MS, LIMITER_RELEASE_MS,
                LIMITER_RATIO, LIMITER_THRESHOLD_DB, 0f);
        DynamicsProcessing.Config config = new DynamicsProcessing.Config.Builder(
                DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                2 /* replicated to however many channels the output has */,
                false, 0, false, 0, false, 0, true)
                .setLimiterAllChannelsTo(limiter)
                .build();
        DynamicsProcessing fx = new DynamicsProcessing(0, OUTPUT_MIX, config);
        int rc = fx.setEnabled(true);
        if (rc != AudioEffect.SUCCESS) {
            fx.release();
            throw new IllegalStateException("setEnabled returned " + rc);
        }
        return fx;
    }

    private boolean alive() {
        try {
            return effect.hasControl() && effect.getEnabled();
        } catch (Throwable t) {
            return false;
        }
    }

    /** True while the platform says the effect is enabled and ours to set. */
    public synchronized boolean isActive() {
        return effect != null && alive();
    }

    public synchronized String lastError() {
        return lastError;
    }

    synchronized void release() {
        if (effect == null) return;
        try {
            effect.release();
        } catch (Throwable ignored) { }
        effect = null;
        Log.i(TAG, "boost off");
    }

    synchronized String report() {
        StringBuilder sb = new StringBuilder();
        sb.append("set: ").append(percent).append(" %");
        if (percent > VolumeStore.DEFAULT_PERCENT) {
            sb.append(" (").append(decibelText(percent)).append(')');
        }
        sb.append('\n');
        sb.append("effect: ");
        if (effect == null) {
            sb.append("none\n");
        } else {
            sb.append("id ").append(effect.getId())
              .append(isActive() ? ", enabled and in control\n" : ", NOT in effect\n");
            try {
                sb.append("gain in the platform: ").append(String.format(Locale.US, "%.2f dB",
                        effect.getInputGainByChannelIndex(0))).append('\n');
            } catch (Throwable t) {
                sb.append("gain in the platform: unreadable\n");
            }
        }
        if (lastError != null) sb.append("last error: ").append(lastError).append('\n');
        return sb.toString();
    }
}
