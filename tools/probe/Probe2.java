import java.lang.reflect.*;
public class Probe2 {
    public static void main(String[] a) throws Exception {
        Class<?> c = Class.forName("android.content.pm.ApplicationInfo");
        for (Field f : c.getDeclaredFields()) {
            String n = f.getName();
            if (n.startsWith("PRIVATE_FLAG") && (n.contains("CAPTURE") || n.contains("AUDIO"))) {
                f.setAccessible(true);
                System.out.println(n + " = " + f.get(null));
            }
        }
        for (Method m : c.getDeclaredMethods()) {
            if (m.getName().toLowerCase().contains("capture") || m.getName().toLowerCase().contains("audio"))
                System.out.println("method " + m);
        }
        // AudioAttributes capture policy constants
        Class<?> aa = Class.forName("android.media.AudioAttributes");
        for (Field f : aa.getDeclaredFields()) {
            if (f.getName().startsWith("ALLOW_CAPTURE")) { f.setAccessible(true); System.out.println("AudioAttributes." + f.getName() + " = " + f.get(null)); }
        }
        Class<?> mix = Class.forName("android.media.audiopolicy.AudioMix");
        for (Field f : mix.getDeclaredFields()) {
            if (f.getName().startsWith("PRIVILEDGED") || f.getName().startsWith("PRIVILEGED")) { f.setAccessible(true); System.out.println("AudioMix." + f.getName() + " = " + f.get(null)); }
        }
    }
}
