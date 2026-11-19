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
 * Das Update selbst macht der Updater der Helden-Software (autoUpdater.jar mit -jHeldenJar=...),
 * wir stoßen ihn nur zur eingestellten Uhrzeit an.
 */
public final class HeldenJar {
    static final String UPDATE_SERVER = "https://www.helden-software.de/update/";

    private final Db db;
    private final Path dir, jar;
    private volatile URLClassLoader loader;
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

    /** Prüfen und ggf. aktualisieren. Läuft in eigenen JVMs, damit Statics der Jar unseren Server nicht stören. */
    public synchronized String update() {
        String result;
        try {
            if (!present()) throw new IllegalStateException("helden.jar fehlt");
            String before = md5(jar);
            String neu = run(List.of(java(), "-Djava.awt.headless=true", "-cp", jar + File.pathSeparator + selfJar(), "heldenhort.UpdateCheckMain"), 120).trim();
            if (!neu.endsWith("true")) result = "Keine neue Version (" + version() + ")";
            else {
                Path upd = fetchUpdater();
                String out = run(List.of(java(), "-Djava.awt.headless=true", "-jar", upd.toString(), "-jHeldenJar=" + jar.toAbsolutePath(), "-jChannelPw="), 600);
                if (md5(jar).equals(before)) result = "Update gemeldet, aber Datei unverändert. Ausgabe: " + tail(out);
                else { reload(); result = "Aktualisiert auf " + version(); }
            }
        } catch (Exception e) { result = "Fehler: " + e.getMessage(); }
        db.put("jarLastCheck", LocalDateTime.now().withNano(0).toString().replace('T', ' '));
        db.put("jarLastResult", result);
        System.out.println("helden.jar Update: " + result);
        return result;
    }

    /** Lädt den offiziellen autoUpdater und prüft die MD5 aus der Antwort des Update-Servers. */
    private Path fetchUpdater() throws Exception {
        Map<String, String> info = new HashMap<>();
        try (var in = new Scanner(URI.create(UPDATE_SERVER + "index.php?pw=autoUpdater").toURL().openStream(), "UTF-8")) {
            while (in.hasNextLine()) { String l = in.nextLine(); int i = l.indexOf('='); if (i > 0) info.put(l.substring(0, i).trim().toLowerCase(), l.substring(i + 1).trim()); }
        }
        String url = info.get("updatepath"), md5 = info.get("md5");
        if (url == null || md5 == null || !url.startsWith(UPDATE_SERVER)) throw new IOException("Update-Server liefert keine gültige Updater-Adresse");
        Path f = dir.resolve("autoUpdater.jar");
        try (var in = URI.create(url).toURL().openStream()) { Files.copy(in, f, StandardCopyOption.REPLACE_EXISTING); }
        if (!md5(f).equalsIgnoreCase(md5)) { Files.deleteIfExists(f); throw new IOException("MD5 des autoUpdater stimmt nicht"); }
        return f;
    }

    private static String run(List<String> cmd, int timeoutSec) throws Exception {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        byte[] out = p.getInputStream().readAllBytes();
        if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) { p.destroyForcibly(); throw new IOException("Zeitüberschreitung"); }
        return new String(out);
    }

    static String java() { return Path.of(System.getProperty("java.home"), "bin", "java").toString(); }

    static String selfJar() throws Exception { return Path.of(HeldenJar.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString(); }

    static String md5(Path f) throws Exception {
        MessageDigest d = MessageDigest.getInstance("MD5");
        try (var in = Files.newInputStream(f)) { in.transferTo(new java.security.DigestOutputStream(OutputStream.nullOutputStream(), d)); }
        return HexFormat.of().formatHex(d.digest());
    }

    static String tail(String s) { return s.length() > 400 ? s.substring(s.length() - 400) : s; }
}
