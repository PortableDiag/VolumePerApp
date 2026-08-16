package com.volumeperapp.tone;

import android.app.Activity;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Bundle;
import android.widget.TextView;

/**
 * Test fixture for VolumePerApp. Emits a steady 440 Hz sine at a known
 * amplitude and nothing else.
 *
 * <p>The routing engine is hard to verify by ear on a headless emulator, so it
 * is verified by arithmetic instead: this app produces a signal whose peak is
 * known, the engine reports the peak it captured and the peak it wrote, and the
 * ratio between them is the gain that was actually applied. A fixture with a
 * predictable amplitude is what makes that measurement mean anything.
 */
public final class ToneActivity extends Activity {

    /** 0.5 of full scale, so a 150 % boost still has headroom before clipping. */
    private static final double AMPLITUDE = 0.5;
    private static final int SAMPLE_RATE = 48000;
    private static final double FREQ_HZ = 440.0;

    private AudioTrack track;
    private Thread thread;
    private volatile boolean running;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        TextView tv = new TextView(this);
        tv.setPadding(48, 96, 48, 48);
        tv.setText("VolumePerApp test tone\n\n"
                + FREQ_HZ + " Hz sine at " + (int) (AMPLITUDE * 100) + " % full scale\n"
                + "uid " + android.os.Process.myUid() + "\n\n"
                + "Playing while this screen is open.");
        setContentView(tv);
    }

    // Start on create and stop on destroy, not on start/stop: the fixture has to
    // keep playing while VolumePerApp is in the foreground, which is the only
    // moment the two can be observed at once.
    @Override
    protected void onPostCreate(Bundle b) {
        super.onPostCreate(b);
        int minBytes = AudioTrack.getMinBufferSize(SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
        track = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build())
                .setBufferSizeInBytes(Math.max(minBytes, 8192))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build();

        running = true;
        thread = new Thread(() -> {
            short[] buf = new short[960 * 2];
            long n = 0;
            track.play();
            while (running) {
                for (int i = 0; i < buf.length; i += 2) {
                    short v = (short) (Math.sin(2 * Math.PI * FREQ_HZ * n / SAMPLE_RATE)
                            * AMPLITUDE * Short.MAX_VALUE);
                    buf[i] = v;
                    buf[i + 1] = v;
                    n++;
                }
                if (track.write(buf, 0, buf.length) < 0) break;
            }
        }, "tone");
        thread.start();
    }

    @Override
    protected void onDestroy() {
        running = false;
        try {
            if (thread != null) thread.join(500);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        if (track != null) {
            try { track.stop(); } catch (Throwable ignored) { }
            track.release();
            track = null;
        }
        super.onDestroy();
    }
}
