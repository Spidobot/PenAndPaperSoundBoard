package heldenhort;

/** Läuft in eigener JVM mit helden.jar im Classpath. Gibt "true" aus, wenn der Update-Server eine neue Version meldet. */
public final class UpdateCheckMain {
    public static void main(String[] a) throws Exception {
        Class<?> uc = Class.forName("helden.comm.updater.UpdateCheck");
        Object u = uc.getMethod("getInstance", Class.forName("helden.gui.A"), boolean.class).invoke(null, null, false);
        System.out.println(uc.getMethod("istNeueVersionVorhanden").invoke(u));
        System.exit(0);
    }
}
