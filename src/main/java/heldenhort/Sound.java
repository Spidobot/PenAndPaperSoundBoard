package heldenhort;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.http.sse.SseClient;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Soundboard-Zustand liegt auf dem Server. Alle Meister-Tabs und alle Spieler bekommen ihn per SSE
 * und spielen die MP3s selbst ab, synchron über Startzeitpunkt + Position.
 * ponytail: kein serverseitiges Mischen/Streamen. Reicht für eine Runde; Icecast erst, wenn Sync nicht genügt.
 */
public final class Sound {
    public static final List<String> SCENES = List.of("Village", "Travel", "Dungeon", "Castle", "Fight");
    /** Feste Effekt-Knöpfe aus dem alten Soundboard. Datei liegt unter /data/audio/Effects/. */
    public static final Map<String, String> FX = new LinkedHashMap<>();
    static {
        FX.put("truhe", "Schatztruhe.mp3"); FX.put("kuh", "Kuh.mp3"); FX.put("avengers", "Avengers.mp3");
        FX.put("ziege", "Bertha.mp3"); FX.put("qadd", "QuestHinzugefuegt.mp3"); FX.put("qdone", "QuestAbgeschlossen.mp3");
        FX.put("pizza", "Pizza.mp3");
    }

    public static final class Channel {
        public String file;          // relativer Pfad unter /data/audio, z. B. Music/Village/x.mp3
        public boolean playing;
        public boolean repeat = true;
        public int volume;
        public double offset;        // Sekunden im Titel zum Zeitpunkt 'since'
        public long since;           // Server-Zeit ms
        Channel(int v) { volume = v; }
        double pos() { return playing ? offset + (System.currentTimeMillis() - since) / 1000.0 : offset; }
    }

    private final Path root;
    private final ObjectMapper json = new ObjectMapper();
    private final Set<SseClient> clients = ConcurrentHashMap.newKeySet();
    public final Map<String, Channel> ch = new LinkedHashMap<>(Map.of("music", new Channel(70), "bg", new Channel(50)));
    public String scene = "Village";
    public long berthaAt;            // 0 = aus
    public int fxVolume = 80;        // eigene Lautstärke für die Effekt-Knöpfe

    public Sound(Path root) throws Exception {
        this.root = root;
        // Fehlende Ordner anlegen, wenn möglich. Fehlende Schreibrechte (z. B. Nextcloud-Ordner) dürfen den Start nicht verhindern.
        List<Path> dirs = new ArrayList<>();
        for (String d : List.of("Music", "Sounds")) for (String s : SCENES) dirs.add(root.resolve(d).resolve(s));
        dirs.add(root.resolve("Effects"));
        for (Path d : dirs) {
            if (Files.isDirectory(d)) continue;
            try { Files.createDirectories(d); }
            catch (Exception e) { System.err.println("WARNUNG: Ordner " + d + " fehlt und kann nicht angelegt werden (" + e + "). Schreibrechte des Containers prüfen."); }
        }
        if (!Files.isWritable(root)) System.err.println("WARNUNG: " + root + " ist nicht beschreibbar. Abspielen geht, Hochladen nicht.");
        Executors.newSingleThreadScheduledExecutor().scheduleAtFixedRate(this::tick, 1, 1, TimeUnit.SECONDS);
    }

    public Path root() { return root; }

    /** MP3-Liste einer Szene, wie im Original nur *.mp3. */
    public Map<String, Object> library(String scene) throws Exception {
        if (!SCENES.contains(scene)) throw new IllegalArgumentException("Szene unbekannt");
        Map<String, Object> out = new LinkedHashMap<>();
        for (String d : List.of("Music", "Sounds")) {
            Path dir = root.resolve(d).resolve(scene);
            if (!Files.isDirectory(dir)) { out.put(d, List.of()); continue; }
            try (var s = Files.list(dir)) {
                out.put(d, s.map(p -> p.getFileName().toString()).filter(n -> n.toLowerCase().endsWith(".mp3")).sorted().toList());
            }
        }
        // je Effekt: "ok", "fehlt" oder eine Beschreibung, warum der Browser die Datei nicht abspielen kann
        Map<String, String> fx = new LinkedHashMap<>();
        FX.forEach((k, f) -> fx.put(k, check(root.resolve("Effects").resolve(f))));
        out.put("fx", fx);
        return out;
    }

    /** Befehl vom Meister. Wirft bei unbekannten Werten, damit Fehler im UI sichtbar werden. */
    public synchronized void command(Map<String, Object> c) {
        String action = String.valueOf(c.get("action"));
        if (action.equals("scene")) {
            String s = String.valueOf(c.get("scene"));
            if (!SCENES.contains(s)) throw new IllegalArgumentException("Szene unbekannt");
            scene = s;
        } else if (action.equals("fxvolume")) {
            fxVolume = Math.max(0, Math.min(100, ((Number) c.get("value")).intValue()));
        } else if (action.equals("bertha")) {
            berthaAt = Boolean.TRUE.equals(c.get("on")) ? System.currentTimeMillis() + 3_600_000 : 0;
        } else if (action.equals("fx")) {
            String f = FX.get(String.valueOf(c.get("key")));
            if (f == null) throw new IllegalArgumentException("Effekt unbekannt");
            broadcast(Map.of("type", "fx", "file", fxUrl(f)));
            return;
        } else {
            Channel k = ch.get(String.valueOf(c.get("ch")));
            if (k == null) throw new IllegalArgumentException("Kanal unbekannt");
            long now = System.currentTimeMillis();
            switch (action) {
                case "pick" -> {
                    String file = String.valueOf(c.get("file"));
                    resolve(file); // prüft Pfad
                    k.file = file; k.offset = 0; k.since = now; k.playing = true;
                }
                case "play" -> { if (k.file == null) throw new IllegalArgumentException("Kein Titel gewählt"); if (!k.playing) { k.since = now; k.playing = true; } }
                case "pause" -> { k.offset = c.get("pos") instanceof Number n ? n.doubleValue() : k.pos(); k.playing = false; }
                case "seek" -> {
                    if (!(c.get("pos") instanceof Number n) || n.doubleValue() < 0) throw new IllegalArgumentException("Position fehlt");
                    k.offset = n.doubleValue(); k.since = now;
                }
                case "stop" -> { k.offset = 0; k.playing = false; }
                case "volume" -> k.volume = Math.max(0, Math.min(100, ((Number) c.get("value")).intValue()));
                case "repeat" -> {
                    k.repeat = Boolean.TRUE.equals(c.get("value"));
                    // aktuelle Position übernehmen, sonst springt ein schon mehrfach wiederholter Titel ans Ende
                    if (c.get("pos") instanceof Number n) { k.offset = n.doubleValue(); k.since = now; }
                }
                case "ended" -> { if (!k.repeat && k.playing) { k.playing = false; k.offset = 0; } } // meldet ein Meister-Tab am Titelende
                default -> throw new IllegalArgumentException("Aktion unbekannt: " + action);
            }
        }
        broadcast(state());
    }

    public synchronized Map<String, Object> state() {
        Map<String, Object> chs = new LinkedHashMap<>();
        ch.forEach((n, k) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("file", k.file); m.put("playing", k.playing); m.put("repeat", k.repeat); m.put("volume", k.volume); m.put("pos", k.pos());
            chs.put(n, m);
        });
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("type", "state"); s.put("scene", scene); s.put("ch", chs); s.put("berthaAt", berthaAt); s.put("fxVolume", fxVolume);
        s.put("serverNow", System.currentTimeMillis()); s.put("listeners", listeners());
        return s;
    }

    public void subscribe(SseClient c, boolean player) {
        c.keepAlive();
        c.onClose(() -> { clients.remove(c); broadcast(state()); });
        c.ctx().attribute("player", player);
        clients.add(c);
        send(c, state());
        broadcast(state());
    }

    long listeners() { return clients.stream().filter(c -> Boolean.TRUE.equals(c.ctx().attribute("player"))).count(); }

    /** Effekt-Adresse mit Änderungszeit: eine ersetzte Datei bekommt eine neue Adresse, der Browser lädt sie neu. */
    String fxUrl(String f) {
        Path p = root.resolve("Effects").resolve(f);
        long v = 0;
        try { v = Files.getLastModifiedTime(p).toMillis(); } catch (Exception ignored) { }
        return "Effects/" + f + "?v=" + v;
    }

    /** Diagnose der Effekt-Dateien: Pfad, Format, Größe, Prüfsumme. */
    public List<Map<String, Object>> fxInfo() {
        List<Map<String, Object>> out = new ArrayList<>();
        FX.forEach((k, f) -> {
            Path p = root.resolve("Effects").resolve(f);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", k); m.put("file", "Effects/" + f); m.put("path", p.toString()); m.put("status", check(p));
            try {
                if (Files.isRegularFile(p)) {
                    m.put("size", Files.size(p));
                    m.put("md5", HeldenJar.md5(p).substring(0, 8));
                    m.put("modified", Files.getLastModifiedTime(p).toString());
                }
            } catch (Exception e) { m.put("error", e.getMessage()); }
            // ähnlich benannte Dateien im Ordner (z. B. falsche Groß-/Kleinschreibung)
            try (var s = Files.list(root.resolve("Effects"))) {
                m.put("similar", s.map(x -> x.getFileName().toString()).filter(n -> !n.equals(f) && n.equalsIgnoreCase(f)).toList());
            } catch (Exception e) { m.put("similar", List.of()); }
            out.add(m);
        });
        return out;
    }

    /** Prüft, ob die Datei eine echte MP3 (MPEG Layer III) ist. Browser können z. B. kein MP2. */
    static String check(Path p) {
        if (!Files.isRegularFile(p)) return "fehlt";
        try (var in = Files.newInputStream(p)) {
            byte[] b = in.readNBytes(64 * 1024);
            int i = 0;
            if (b.length > 10 && b[0] == 'I' && b[1] == 'D' && b[2] == '3') // ID3v2-Tag überspringen
                i = 10 + ((b[6] & 0x7f) << 21 | (b[7] & 0x7f) << 14 | (b[8] & 0x7f) << 7 | (b[9] & 0x7f));
            for (; i + 1 < b.length; i++) {
                if ((b[i] & 0xff) != 0xff || (b[i + 1] & 0xe0) != 0xe0) continue;
                int layer = (b[i + 1] >> 1) & 3;
                return layer == 1 ? "ok" : "Datei ist MPEG Layer " + (layer == 2 ? "II (MP2)" : layer == 3 ? "I" : "?") + ", keine echte MP3. Bitte als MP3 neu speichern.";
            }
            return i == 0 ? "ok" : "Kein MPEG-Audio gefunden";
        } catch (Exception e) { return "nicht lesbar: " + e.getMessage(); }
    }

    /** Pfad unter /data/audio, ohne Ausbruch nach oben. */
    public Path resolve(String rel) {
        Path p = root.resolve(rel).normalize();
        if (!p.startsWith(root.normalize()) || !p.toString().toLowerCase().endsWith(".mp3") || !Files.isRegularFile(p))
            throw new IllegalArgumentException("Datei unbekannt");
        return p;
    }

    private void tick() {
        if (berthaAt > 0 && System.currentTimeMillis() >= berthaAt) {
            berthaAt = System.currentTimeMillis() + 3_600_000;
            broadcast(Map.of("type", "fx", "file", fxUrl(FX.get("ziege"))));
            broadcast(state());
        }
    }

    private void broadcast(Map<String, Object> msg) { for (SseClient c : clients) send(c, msg); }

    private void send(SseClient c, Map<String, Object> msg) {
        try { c.sendEvent("msg", json.writeValueAsString(msg)); } catch (Exception e) { clients.remove(c); }
    }
}
