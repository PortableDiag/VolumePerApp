package com.volumeperapp.app.audio;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.util.Log;

/**
 * One diverted app, carried across.
 *
 * <p>Reads the loopback {@code AudioRecord} that {@link AudioPolicyBridge} hands
 * out for a uid, scales every sample by the current gain, and writes it to an
 * ordinary {@code AudioTrack}. That write is the app's audio finally reaching
 * the speaker — at whatever level the user chose.
 *
 * <p>Gain is volatile and read once per buffer, so moving a slider takes effect
 * within one buffer (~10 ms) without locking the audio thread.
 *
 * <p><b>A pump lasts as long as the routing, not as long as the sound.</b> Both
 * endpoints are opened once and kept: creating or releasing a
 * {@code REMOTE_SUBMIX} capture makes the policy manager re-evaluate routing,
 * and that invalidates the output tracks of <em>other</em> apps, which is
 * visible to their users as a video restarting or audio looping. Doing it on
 * every track change — which is what tying the pump's life to playback meant —
 * did that several times a minute. When the input falls silent the output track
 * is <em>parked</em> ({@code pause} + {@code flush}) rather than released, so an
 * idle routed app costs a blocked reader and a stopped track and disturbs
 * nothing.
 *
 * <p>Above 1.0 this is a real amplifier and it will clip. Samples are summed in
 * int and clamped to short range, so overdrive distorts rather than wrapping
 * around into noise, which is the difference between "too loud" and "broken".
 */
final class StreamPump implements Runnable {

    private static final String TAG = "VPA.Pump";

    /** ~10 ms at 48 kHz stereo. Small enough not to add audible delay. */
    private static final int FRAMES_PER_BUFFER = 480;

    /**
     * How much unbroken silence on the way in before the output track is parked.
     *
     * <p>Two seconds, because the gap between two tracks in a music app is a
     * fraction of that and must not be seen as the end of playback — parking
     * and unparking between tracks is exactly the churn this pump exists to
     * avoid. A genuine stop costs two seconds of an idle track and nothing else.
     */
    private static final long SILENCE_FRAMES_BEFORE_PARK = AudioPolicyBridge.SAMPLE_RATE * 2L;

    private final int uid;
    private final AudioRecord in;
    private final AudioTrack out;
    private final Thread thread;

    private volatile float gain;
    private volatile boolean running = true;
    private volatile long framesCarried;

    /**
     * Peak absolute sample seen on the way in and on the way out, over the last
     * measurement window.
     *
     * <p>These are the only objective evidence that the engine did what it
     * claims: peakOut/peakIn is the gain that was actually applied to real
     * audio, which is checkable on a headless device where "does it sound
     * quieter" is not. Diagnostics reports both.
     */
    private volatile int peakIn;
    private volatile int peakOut;
    private volatile int windowPeakIn;
    private volatile int windowPeakOut;
    private long windowFrames;

    private StreamPump(int uid, AudioRecord in, AudioTrack out, float gain) {
        this.uid = uid;
        this.in = in;
        this.out = out;
        this.gain = gain;
        this.thread = new Thread(this, "vpa-pump-" + uid);
        this.thread.setPriority(Thread.MAX_PRIORITY);
    }

    /**
     * Attributes for the re-rendered output.
     *
     * <p>{@code ALLOW_CAPTURE_BY_NONE} is what actually stops our own output
     * being taken by a playback-capture mix — including one of ours, which
     * would be a feedback loop. An earlier version set {@code FLAG_LOW_LATENCY}
     * here with a comment claiming it did that; it does not, and never did. The
     * low-latency request lives on the track itself, as
     * {@code PERFORMANCE_MODE_LOW_LATENCY}.
     */
    private static AudioAttributes attributes() {
        AudioAttributes.Builder b = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            b.setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_NONE);
        }
        return b.build();
    }

    /**
     * @return a started pump, or null if either endpoint refused to initialise
     *         (which happens when the mix was detached underneath us)
     */
    static StreamPump start(int uid, AudioRecord in, float gain) {
        if (in == null) return null;
        if (in.getState() != AudioRecord.STATE_INITIALIZED) {
            Log.w(TAG, "record sink for uid " + uid + " not initialised");
            in.release();
            return null;
        }

        int channelMask = AudioFormat.CHANNEL_OUT_STEREO;
        int bytesPerFrame = 2 /* PCM16 */ * AudioPolicyBridge.CHANNELS;
        int minOut = AudioTrack.getMinBufferSize(
                AudioPolicyBridge.SAMPLE_RATE, channelMask, AudioFormat.ENCODING_PCM_16BIT);
        int wanted = FRAMES_PER_BUFFER * bytesPerFrame * 4;
        int bufferBytes = Math.max(minOut, wanted);

        AudioTrack out;
        try {
            out = new AudioTrack.Builder()
                    .setAudioAttributes(attributes())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setSampleRate(AudioPolicyBridge.SAMPLE_RATE)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(channelMask)
                            .build())
                    .setBufferSizeInBytes(bufferBytes)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                    .build();
        } catch (Throwable t) {
            Log.w(TAG, "could not open output track for uid " + uid, t);
            in.release();
            return null;
        }

        if (out.getState() != AudioTrack.STATE_INITIALIZED) {
            Log.w(TAG, "output track for uid " + uid + " not initialised");
            out.release();
            in.release();
            return null;
        }

        StreamPump pump = new StreamPump(uid, in, out, gain);
        pump.thread.start();
        return pump;
    }

    void setGain(float g) {
        this.gain = g;
    }

    long framesCarried() {
        return framesCarried;
    }

    int peakIn() {
        return peakIn;
    }

    int peakOut() {
        return peakOut;
    }

    /** Applied gain as measured, or -1 when the input was silent. */
    float measuredGain() {
        int in = peakIn;
        if (in <= 0) return -1f;
        return peakOut / (float) in;
    }

    int uid() {
        return uid;
    }

    void stop() {
        running = false;
        thread.interrupt();
        try {
            thread.join(500);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        release();
    }

    private void release() {
        try {
            in.stop();
        } catch (Throwable ignored) { }
        try {
            in.release();
        } catch (Throwable ignored) { }
        try {
            out.stop();
        } catch (Throwable ignored) { }
        try {
            out.release();
        } catch (Throwable ignored) { }
    }

    @Override
    public void run() {
        short[] buf = new short[FRAMES_PER_BUFFER * AudioPolicyBridge.CHANNELS];
        try {
            in.startRecording();
        } catch (Throwable t) {
            Log.w(TAG, "pump for uid " + uid + " could not start", t);
            return;
        }
        Log.i(TAG, "pump running for uid " + uid);

        // The output track is created once and then parked and unparked, never
        // released and rebuilt. Starting parked means an app that is routed but
        // silent costs an idle track rather than an active output.
        boolean parked = true;
        long silentFrames = 0;

        while (running) {
            int n;
            try {
                n = in.read(buf, 0, buf.length);
            } catch (Throwable t) {
                Log.w(TAG, "read failed on uid " + uid, t);
                break;
            }
            if (n <= 0) {
                if (n == AudioRecord.ERROR_INVALID_OPERATION || n == AudioRecord.ERROR_DEAD_OBJECT) {
                    Log.w(TAG, "record sink for uid " + uid + " went away (" + n + ")");
                    break;
                }
                // A blocking read should never come back empty, but this thread
                // now lives as long as the routing rather than as long as the
                // sound, so a sink that returned 0 forever would spin a core for
                // as long as the app stayed adjusted. Yield instead of trusting.
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                continue;
            }

            float g = gain;
            int inPeak = 0;
            int outPeak = 0;
            for (int i = 0; i < n; i++) {
                int s0 = buf[i];
                int a = s0 < 0 ? -s0 : s0;
                if (a > inPeak) inPeak = a;

                if (g != 1f) {
                    int v = g == 0f ? 0 : Math.round(s0 * g);
                    if (v > Short.MAX_VALUE) v = Short.MAX_VALUE;
                    else if (v < Short.MIN_VALUE) v = Short.MIN_VALUE;
                    buf[i] = (short) v;
                    s0 = v;
                }
                int b = s0 < 0 ? -s0 : s0;
                if (b > outPeak) outPeak = b;
            }
            if (inPeak > windowPeakIn) windowPeakIn = inPeak;
            if (outPeak > windowPeakOut) windowPeakOut = outPeak;
            windowFrames += n / AudioPolicyBridge.CHANNELS;
            // Publish and reset about twice a second, so a reading reflects what
            // is happening now rather than the loudest moment since start-up.
            if (windowFrames >= AudioPolicyBridge.SAMPLE_RATE / 2) {
                peakIn = windowPeakIn;
                peakOut = windowPeakOut;
                windowPeakIn = 0;
                windowPeakOut = 0;
                windowFrames = 0;
            }

            int frames = n / AudioPolicyBridge.CHANNELS;
            if (inPeak > 0) {
                silentFrames = 0;
                if (parked) {
                    try {
                        out.play();
                        parked = false;
                    } catch (Throwable t) {
                        Log.w(TAG, "could not unpark output for uid " + uid, t);
                    }
                }
            } else {
                silentFrames += frames;
                if (!parked && silentFrames >= SILENCE_FRAMES_BEFORE_PARK) {
                    try {
                        out.pause();
                        out.flush();     // drop what is queued; it is silence
                        parked = true;
                    } catch (Throwable t) {
                        Log.w(TAG, "could not park output for uid " + uid, t);
                    }
                }
            }

            if (!parked) {
                int written = 0;
                while (written < n && running) {
                    int w = out.write(buf, written, n - written);
                    if (w < 0) {
                        Log.w(TAG, "write failed on uid " + uid + " (" + w + ")");
                        running = false;
                        break;
                    }
                    written += w;
                }
                // Counted on the way out, not the way in: a parked pump reads
                // silence continuously and has carried nothing.
                framesCarried += frames;
            }
        }
        Log.i(TAG, "pump stopped for uid " + uid + " after " + framesCarried + " frames");
    }
}
