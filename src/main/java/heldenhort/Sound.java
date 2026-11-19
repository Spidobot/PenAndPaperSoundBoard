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
        for (String d : List.of("Music", "Sounds")) for (String s : SCENES) Files.createDirectories(root.resolve(d).resolve(s));
        Files.createDirectories(root.resolve("Effects"));
        Executors.newSingleThreadScheduledExecutor().scheduleAtFixedRate(this::tick, 1, 1, TimeUnit.SECONDS);
    }

    public Path root() { return root; }

    /** MP3-Liste einer Szene, wie im Original nur *.mp3. */
    public Map<String, Object> library(String scene) throws Exception {
        if (!SCENES.contains(scene)) throw new IllegalArgumentException("Szene unbekannt");
        Map<String, Object> out = new LinkedHashMap<>();
        for (String d : List.of("Music", "Sounds")) {
            try (var s = Files.list(root.resolve(d).resolve(scene))) {
                out.put(d, s.map(p -> p.getFileName().toString()).filter(n -> n.toLowerCase().endsWith(".mp3")).sorted().toList());
            }
        }
        Map<String, Boolean> fx = new LinkedHashMap<>();
        FX.forEach((k, f) -> fx.put(k, Files.isRegularFile(root.resolve("Effects").resolve(f))));
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
            broadcast(Map.of("type", "fx", "file", "Effects/" + f));
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
                case "pause" -> { k.offset = k.pos(); k.playing = false; }
                case "stop" -> { k.offset = 0; k.playing = false; }
                case "volume" -> k.volume = Math.max(0, Math.min(100, ((Number) c.get("value")).intValue()));
                case "repeat" -> k.repeat = Boolean.TRUE.equals(c.get("value"));
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
            broadcast(Map.of("type", "fx", "file", "Effects/" + FX.get("ziege")));
            broadcast(state());
        }
    }

    private void broadcast(Map<String, Object> msg) { for (SseClient c : clients) send(c, msg); }

    private void send(SseClient c, Map<String, Object> msg) {
        try { c.sendEvent("msg", json.writeValueAsString(msg)); } catch (Exception e) { clients.remove(c); }
    }
}
