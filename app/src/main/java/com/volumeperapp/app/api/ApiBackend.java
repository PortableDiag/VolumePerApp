package com.volumeperapp.app.api;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;

/**
 * Everything the API can do, with no Android type in sight — so the router
 * runs against a fake in JVM unit tests. Each method is something the mixer
 * screen already does; {@link VolumePerAppBackend} does it the same way.
 *
 * <p>Read methods hand back JSON directly: the shapes are the API's, and a
 * second set of value classes would only be copied into it.
 */
public interface ApiBackend {

    JSONObject instance() throws Exception;

    /** The mixer as the screen shows it: the mode banner, then the strips. */
    JSONObject mixer() throws Exception;

    /** One strip, or null if no such package is installed. */
    JSONObject app(String pkg) throws Exception;

    /** The app picker's list, filtered by label or package when {@code query} is set. */
    JSONArray installed(String query) throws Exception;

    boolean isInstalled(String pkg);

    /**
     * A fader move, a mute, or both; null leaves that half alone. Pins the app,
     * as moving its fader does.
     *
     * @return whether the engine has it now (false: it starts when the app is opened)
     */
    boolean setGain(String pkg, Integer percent, Boolean muted);

    void pin(String pkg);

    /** The strip's X: off the mixer, and its setting forgotten. */
    void remove(String pkg);

    /** Back to 100 % and unmuted, as the strip's reset button. */
    boolean reset(String pkg);

    void resetAll();

    int maxGain();

    void setMaxGain(int percent);

    JSONArray streams() throws Exception;

    void setStream(String name, int volume) throws ApiException;

    String palette();

    void setPalette(String name);

    List<String> palettes();

    JSONObject safeVolume() throws Exception;

    /** @return false when the flag could not be recorded */
    boolean setSafeVolumeRequested(boolean requested);

    /** The Diagnostics screen's report, re-probed live. */
    String diagnostics();
}
