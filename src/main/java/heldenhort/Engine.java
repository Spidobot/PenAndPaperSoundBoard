package heldenhort;

import java.lang.reflect.Method;
import java.util.*;

/**
 * Brücke zur Erschaffungslogik der helden.jar, nur per Reflection über die lesbaren Klassen-/Methodennamen
 * (RassenFabrik, KulturFabrik, Settings, HEW2 ...). Die Jar ist teilweise verschleiert; verschleierte Namen
 * werden bewusst nicht benutzt, damit ein Update der Jar uns nicht sofort bricht.
 * Stand: Regelbasis, Geschlecht, Rassen, Kulturen. Weitere Schritte folgen.
 */
public final class Engine {
    private final HeldenJar jar;
    private boolean ready;

    public Engine(HeldenJar jar) {
        this.jar = jar;
        jar.onReload(() -> { synchronized (this) { ready = false; } });
    }

    private synchronized ClassLoader init() throws Exception {
        ClassLoader l = jar.loader();
        if (!ready) {
            l.loadClass("helden.framework.settings.Setting").getMethod("initAll").invoke(null);
            ready = true;
        }
        return l;
    }

    public synchronized Map<String, Object> catalog(String setting, String geschlecht) throws Exception {
        ClassLoader l = init();
        Class<?> setC = l.loadClass("helden.framework.settings.Setting");
        Class<?> setsC = l.loadClass("helden.framework.settings.Settings");
        Class<?> gC = l.loadClass("helden.framework.Geschlecht");

        List<String> settings = new ArrayList<>();
        for (Object s : (List<?>) setC.getMethod("getHauptSettings").invoke(null)) settings.add((String) setC.getMethod("getName").invoke(s));
        if (setting == null || !settings.contains(setting)) setting = settings.isEmpty() ? null : settings.get(0);

        Object g = null;
        for (Object x : gC.getEnumConstants()) if (x.toString().equalsIgnoreCase(geschlecht == null ? "männlich" : geschlecht)) g = x;
        if (g == null) g = gC.getEnumConstants()[0];

        Object sets = setsC.getMethod("getSettingsBySetting", setC).invoke(null, setC.getMethod("getByName", String.class).invoke(null, setting));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("version", jar.version());
        out.put("settings", settings);
        out.put("setting", setting);
        List<String> gs = new ArrayList<>();
        for (Object x : gC.getEnumConstants()) gs.add(x.toString());
        out.put("geschlechter", gs);
        out.put("geschlecht", g.toString());
        out.put("rassen", names(call(l, "helden.model.RassenFabrik", "getRassen", sets, g, false)));
        out.put("kulturen", names(call(l, "helden.model.KulturFabrik", "getKulturen", sets, g)));
        return out;
    }

    /** Ruft eine statische Methode per Name und passender Parameteranzahl/-typen auf. */
    private static Object call(ClassLoader l, String cls, String name, Object... args) throws Exception {
        for (Method m : l.loadClass(cls).getMethods()) {
            if (!m.getName().equals(name) || m.getParameterCount() != args.length) continue;
            Class<?>[] p = m.getParameterTypes();
            boolean ok = true;
            for (int i = 0; i < p.length; i++) {
                Class<?> t = p[i].isPrimitive() ? (p[i] == boolean.class ? Boolean.class : p[i] == int.class ? Integer.class : p[i]) : p[i];
                if (args[i] != null && !t.isInstance(args[i])) { ok = false; break; }
            }
            if (ok) return m.invoke(null, args);
        }
        throw new NoSuchMethodException(cls + "." + name);
    }

    private static List<String> names(Object coll) {
        List<String> out = new ArrayList<>();
        for (Object o : (Collection<?>) coll) out.add(o.toString());
        return out;
    }
}
