package com.volumeperapp.app.audio;

import android.util.Log;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

/**
 * Reflection over the hidden audio-routing surface.
 *
 * <p>Nothing in this app links against {@code android.media.audiopolicy} at
 * compile time, so the APK builds against the public SDK and still drives the
 * hidden API at runtime. Every lookup is cached and every failure is reported
 * as a named missing member rather than a stack trace, because the whole point
 * of this class is that the surface drifts between Android releases and we want
 * a diagnostic that says <em>which</em> call vanished.
 *
 * <p>Hidden-API enforcement is lifted for this app by two facts working
 * together: it is installed into {@code /system/priv-app} by the Magisk module,
 * and its manifest declares {@code android:usesNonSdkApi="true"}. That is the
 * platform's own documented allowance (see
 * {@code ApplicationInfo.isAllowedToUseHiddenApis}), not a bypass. When the app
 * is side-loaded normally, {@link #unseal()} makes a best-effort attempt so the
 * diagnostics screen can still report what exists.
 */
public final class Hidden {

    private static final String TAG = "VPA.Hidden";

    private static final Map<String, Class<?>> CLASSES = new HashMap<>();
    private static final Map<String, Method> METHODS = new HashMap<>();
    private static final Map<String, Field> FIELDS = new HashMap<>();

    private Hidden() { }

    /** Thrown when the hidden surface does not look the way this build expects. */
    public static class MissingApi extends RuntimeException {
        public final String member;

        MissingApi(String member, Throwable cause) {
            super("hidden API not present on this build: " + member, cause);
            this.member = member;
        }
    }

    // ---------------------------------------------------------------- lookup

    public static Class<?> cls(String name) {
        Class<?> c = CLASSES.get(name);
        if (c != null) return c;
        try {
            c = Class.forName(name);
        } catch (Throwable t) {
            throw new MissingApi(name, t);
        }
        CLASSES.put(name, c);
        return c;
    }

    /** True when the class exists, without throwing — for capability probes. */
    public static boolean hasClass(String name) {
        try {
            cls(name);
            return true;
        } catch (MissingApi e) {
            return false;
        }
    }

    public static Method method(String className, String name, Class<?>... params) {
        String key = className + "#" + name + "/" + params.length + sig(params);
        Method m = METHODS.get(key);
        if (m != null) return m;
        try {
            m = cls(className).getDeclaredMethod(name, params);
            m.setAccessible(true);
        } catch (MissingApi e) {
            throw e;
        } catch (Throwable t) {
            throw new MissingApi(className + "." + name + "(" + sig(params) + ")", t);
        }
        METHODS.put(key, m);
        return m;
    }

    public static boolean hasMethod(String className, String name, Class<?>... params) {
        try {
            method(className, name, params);
            return true;
        } catch (MissingApi e) {
            return false;
        }
    }

    public static Constructor<?> ctor(String className, Class<?>... params) {
        try {
            Constructor<?> k = cls(className).getDeclaredConstructor(params);
            k.setAccessible(true);
            return k;
        } catch (MissingApi e) {
            throw e;
        } catch (Throwable t) {
            throw new MissingApi(className + ".<init>(" + sig(params) + ")", t);
        }
    }

    public static int intField(String className, String name) {
        String key = className + "#" + name;
        Field f = FIELDS.get(key);
        try {
            if (f == null) {
                f = cls(className).getDeclaredField(name);
                f.setAccessible(true);
                FIELDS.put(key, f);
            }
            return f.getInt(null);
        } catch (MissingApi e) {
            throw e;
        } catch (Throwable t) {
            throw new MissingApi(className + "." + name, t);
        }
    }

    // ---------------------------------------------------------------- invoke

    public static Object call(Object target, String className, String name,
                              Class<?>[] params, Object... args) {
        try {
            return method(className, name, params).invoke(target, args);
        } catch (MissingApi e) {
            throw e;
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable c = e.getCause() == null ? e : e.getCause();
            if (c instanceof RuntimeException) throw (RuntimeException) c;
            throw new RuntimeException(className + "." + name + " threw", c);
        } catch (Throwable t) {
            throw new MissingApi(className + "." + name, t);
        }
    }

    public static Object newInstance(String className, Class<?>[] params, Object... args) {
        try {
            return ctor(className, params).newInstance(args);
        } catch (MissingApi e) {
            throw e;
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable c = e.getCause() == null ? e : e.getCause();
            if (c instanceof RuntimeException) throw (RuntimeException) c;
            throw new RuntimeException(className + ".<init> threw", c);
        } catch (Throwable t) {
            throw new MissingApi(className + ".<init>", t);
        }
    }

    // ---------------------------------------------------------------- unseal

    private static Boolean unsealed;

    /**
     * Best-effort lift of hidden-API enforcement for a normally-installed build.
     *
     * <p>Irrelevant when the app is a priv-app (enforcement is already off), and
     * it is never the thing that makes the routing engine work — routing needs
     * {@code MODIFY_AUDIO_ROUTING}, which only the Magisk module grants. It
     * exists so Diagnostics can still enumerate the surface on a plain install.
     */
    public static synchronized boolean unseal() {
        if (unsealed != null) return unsealed;
        unsealed = Boolean.FALSE;
        try {
            Method forName = Class.class.getDeclaredMethod("forName", String.class);
            Method getDeclaredMethod = Class.class.getDeclaredMethod(
                    "getDeclaredMethod", String.class, Class[].class);

            Class<?> vmRuntime = (Class<?>) forName.invoke(null, "dalvik.system.VMRuntime");
            Method getRuntime = (Method) getDeclaredMethod.invoke(
                    vmRuntime, "getRuntime", new Class[0]);
            Method setExemptions = (Method) getDeclaredMethod.invoke(
                    vmRuntime, "setHiddenApiExemptions", new Class[]{String[].class});

            Object runtime = getRuntime.invoke(null);
            setExemptions.invoke(runtime, (Object) new String[]{"L"});
            unsealed = Boolean.TRUE;
        } catch (Throwable t) {
            Log.i(TAG, "hidden-API unseal not available: " + t);
        }
        return unsealed;
    }

    private static String sig(Class<?>[] params) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < params.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(params[i].getSimpleName());
        }
        return sb.toString();
    }
}
