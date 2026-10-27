package com.volumeperapp.app.api;

import java.util.ArrayList;
import java.util.List;

/**
 * What the API is doing, for the settings dialog: the service publishes here
 * and the dialog redraws if it is up.
 */
public final class ApiStatus {

    public interface Listener {
        void onApiStatusChanged();
    }

    private static boolean running;
    private static String address = "";
    /** Why it is not running, when the user can fix it (a port already in use). */
    private static String error;
    private static final List<Listener> LISTENERS = new ArrayList<>();

    private ApiStatus() {
    }

    public static synchronized boolean running() {
        return running;
    }

    public static synchronized String address() {
        return address;
    }

    public static synchronized String error() {
        return error;
    }

    public static synchronized void addListener(Listener l) {
        LISTENERS.add(l);
    }

    public static synchronized void removeListener(Listener l) {
        LISTENERS.remove(l);
    }

    static void update(boolean isRunning, String at, String why) {
        List<Listener> copy;
        synchronized (ApiStatus.class) {
            running = isRunning;
            address = at;
            error = why;
            copy = new ArrayList<>(LISTENERS);
        }
        for (Listener l : copy) l.onApiStatusChanged();
    }
}
