package heldenhort;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Verwaltet /data/jar/helden.jar: eigener ClassLoader (austauschbar nach Update) und tägliches Update.
 * Das Update läuft über den Update-Server der Helden-Software (gleiches Protokoll wie deren autoUpdater),
 * einmal täglich zur eingestellten Uhrzeit.
 */
public final class HeldenJar {
    static final String UPDATE_SERVER = "https://www.helden-software.de/update/";

    private final Db db;
    private final Path dir, jar;
    private volatile URLClassLoader loader;
    /** Prüft eine neue Jar, bevor sie die laufende ersetzt (wird von App gesetzt). */
    public volatile java.util.function.Function<URLClassLoader, String> selfTest;
    /** Meldet ein abgelehntes Update (Mail an den Meister). */
    public volatile java.util.function.Consumer<String> onRejected;
    private final List<Runnable> onReload = new CopyOnWriteArrayList<>();

    public HeldenJar(Db db, Path dir) throws Exception {
        this.db = db;
        this.dir = dir;
        this.jar = dir.resolve("helden.jar");
        Files.createDirectories(dir);
        Executors.newSingleThreadScheduledExecutor().scheduleAtFixedRate(this::maybeDaily, 1, 1, TimeUnit.MINUTES);
    }

    public boolean present() { return Files.isRegularFile(jar); }

    public synchronized ClassLoader loader() throws Exception {
        if (!present()) throw new IllegalStateException("helden.jar fehlt in " + dir + ". Bitte per FTP ablegen.");
        if (loader == null) loader = new URLClassLoader(new URL[]{jar.toUri().toURL()}, HeldenJar.class.getClassLoader());
        return loader;
    }

    public void onReload(Runnable r) { onReload.add(r); }

    synchronized void reload() {
        try { if (loader != null) loader.close(); } catch (IOException ignored) { }
        loader = null;
        onReload.forEach(Runnable::run);
    }

    public String version() {
        try { return (String) loader().loadClass("helden.Version").getMethod("getVersion").invoke(null); }
        catch (Exception e) { return null; }
    }

    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("present", present());
        m.put("path", jar.toString());
        m.put("version", version());
        m.put("lastCheck", db.get("jarLastCheck", ""));
        m.put("lastResult", db.get("jarLastResult", ""));
        m.put("updateTime", db.get("updateTime", "04:00"));
        m.put("serverTime", LocalDateTime.now().withNano(0).toString().replace('T', ' '));
        m.put("timezone", ZoneId.systemDefault().getId());
        return m;
    }

    private void maybeDaily() {
        try {
            LocalTime at = LocalTime.parse(db.get("updateTime", "04:00"));
            LocalDate today = LocalDate.now();
            if (LocalTime.now().isBefore(at) || today.toString().equals(db.get("jarLastDay", ""))) return;
            db.put("jarLastDay", today.toString());
            update();
        } catch (Exception e) { System.err.println("Update-Job: " + e.getMessage()); }
    }

    /**
     * Prüfen und ggf. aktualisieren, mit demselben Protokoll wie der autoUpdater der Helden-Software:
     * index.php?pw=<Kanal>&md5=<MD5 der lokalen Jar> liefert UpdatePath, MD5 und UpdateText der aktuellen Version.
     * Der autoUpdater selbst braucht ein Fenster und läuft im Container nicht, daher hier nachgebaut.
     */
    public synchronized String update() {
        String result;
        try {
            if (!present()) throw new IllegalStateException("helden.jar fehlt");
            String local = md5(jar);
            Map<String, String> info = query("index.php?pw=&md5=" + local);
            String url = info.get("updatepath"), md5 = info.get("md5"), text = info.getOrDefault("updatetext", "");
            if (md5 == null || url == null) throw new IOException("Update-Server liefert keine Versionsdaten");
            if (md5.equalsIgnoreCase(local)) result = "Aktuell (" + version() + ")";
            else {
                if (!url.startsWith(UPDATE_SERVER)) throw new IOException("Unerwartete Download-Adresse");
                Path tmp = dir.resolve("helden.jar.temp");
                try (var in = URI.create(url).toURL().openStream()) { Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING); }
                if (!md5(tmp).equalsIgnoreCase(md5)) { Files.deleteIfExists(tmp); throw new IOException("MD5 der neuen helden.jar stimmt nicht, nichts ersetzt"); }
                // Erst testen, dann tauschen: passt die Anbindung nicht mehr, bleibt die alte Jar aktiv
                if (selfTest != null) {
                    String test;
                    try (URLClassLoader probe = new URLClassLoader(new URL[]{tmp.toUri().toURL()}, HeldenJar.class.getClassLoader())) { test = selfTest.apply(probe); }
                    catch (Throwable t) { test = "FEHLER " + t; }
                    if (!test.startsWith("Selbsttest ok")) {
                        Files.deleteIfExists(tmp);
                        String msg = "Neue helden.jar (" + text + ") nicht übernommen, Selbsttest gescheitert: " + test + ". Die bisherige Version " + version() + " läuft weiter.";
                        if (onRejected != null) onRejected.accept(msg);
                        throw new IOException(msg);
                    }
                }
                Files.move(tmp, jar, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                reload();
                result = "Aktualisiert auf " + version() + (text.isBlank() ? "" : " (" + text + ")");
            }
        } catch (Exception e) { result = "Fehler: " + e.getMessage(); }
        db.put("jarLastCheck", LocalDateTime.now().withNano(0).toString().replace('T', ' '));
        db.put("jarLastResult", result);
        System.out.println("helden.jar Update: " + result);
        return result;
    }

    private static Map<String, String> query(String path) throws IOException {
        Map<String, String> info = new HashMap<>();
        try (var in = new Scanner(URI.create(UPDATE_SERVER + path).toURL().openStream(), "UTF-8")) {
            while (in.hasNextLine()) { String l = in.nextLine(); int i = l.indexOf('='); if (i > 0) info.put(l.substring(0, i).trim().toLowerCase(), l.substring(i + 1).trim()); }
        }
        return info;
    }

    static String md5(Path f) throws Exception {
        MessageDigest d = MessageDigest.getInstance("MD5");
        try (var in = Files.newInputStream(f)) { in.transferTo(new java.security.DigestOutputStream(OutputStream.nullOutputStream(), d)); }
        return HexFormat.of().formatHex(d.digest());
    }
}
