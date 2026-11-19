package heldenhort;

import io.javalin.Javalin;
import io.javalin.http.*;
import io.javalin.http.staticfiles.Location;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.jsoup.Jsoup;
import org.jsoup.safety.Safelist;

/**
 * Zwei Webserver in einem Prozess:
 *  - Meister-UI  (MEISTER_PORT, Standard 8080): Login mit ADMIN_PASSWORD aus der Docker-Konfiguration.
 *  - Spieler-UI  (SPIELER_PORT, Standard 8081): hinter den Nginx Proxy Manager, Login mit Spieler-Passwort.
 */
public final class App {
    static final int MIN_BACKGROUND = 50;

    public static void main(String[] args) throws Exception {
        Path data = Path.of(env("DATA_DIR", "/data"));
        Db db = new Db(data.resolve("db/heldenhort.sqlite"));
        Mail mail = new Mail(db);
        HeldenJar jar = new HeldenJar(db, data.resolve("jar"));
        Engine engine = new Engine(jar);
        Heroes heroes = new Heroes(db, data.resolve("helden"));
        Sound sound = new Sound(data.resolve("audio"));
        if (db.get("secret", null) == null) db.put("secret", HexFormat.of().formatHex(SecureRandom.getInstanceStrong().generateSeed(32)));

        String adminPw = env("ADMIN_PASSWORD", "");
        if (adminPw.isBlank()) System.err.println("WARNUNG: ADMIN_PASSWORD ist nicht gesetzt. Die Meister-UI bleibt gesperrt.");
        Javalin m = web("/web/meister", 600_000_000L);
        Javalin s = web("/web/spieler", 4_000_000L);
        for (Javalin a : List.of(m, s)) {
            a.exception(IllegalArgumentException.class, (e, ctx) -> ctx.status(400).json(Map.of("error", e.getMessage())));
            a.exception(IllegalStateException.class, (e, ctx) -> ctx.status(409).json(Map.of("error", e.getMessage())));
            a.get("/audio/<path>", ctx -> {
                Path p = sound.resolve(ctx.pathParam("path"));
                ctx.contentType("audio/mpeg");
                ctx.writeSeekableStream(Files.newInputStream(p), "audio/mpeg", Files.size(p));
            });
            a.get("/api/sound/library", ctx -> ctx.json(sound.library(ctx.queryParamAsClass("scene", String.class).getOrDefault(sound.scene))));
        }

        // ---------------- Meister ----------------
        m.before(ctx -> {
            String p = ctx.path();
            if (p.equals("/login.html") || p.startsWith("/common/") || p.equals("/api/admin/login") || admin(db, adminPw, ctx)) return;
            if (p.startsWith("/api/") || p.startsWith("/audio/")) throw new UnauthorizedResponse("Bitte anmelden");
            ctx.redirect("/login.html");
            ctx.skipRemainingHandlers();
        });
        m.post("/api/admin/login", ctx -> {
            String given = String.valueOf(ctx.bodyAsClass(Map.class).get("password"));
            if (adminPw.isBlank()) throw new UnauthorizedResponse("ADMIN_PASSWORD ist im Container nicht gesetzt");
            if (!MessageDigest.isEqual(adminPw.getBytes(StandardCharsets.UTF_8), given.getBytes(StandardCharsets.UTF_8))) {
                Thread.sleep(800);
                throw new UnauthorizedResponse("Passwort falsch");
            }
            ctx.cookie(cookie(ctx, "hhm", adminToken(db, adminPw)));
            ctx.json(Map.of("ok", true));
        });
        m.post("/api/admin/logout", ctx -> { ctx.removeCookie("hhm", "/"); ctx.json(Map.of("ok", true)); });
        m.post("/api/sound/upload", ctx -> {
            String target = ctx.formParam("target"), scene = ctx.formParam("scene");
            if (!List.of("Music", "Sounds", "Effects").contains(target)) throw new IllegalArgumentException("Ziel unbekannt");
            Path dir = target.equals("Effects") ? sound.root().resolve("Effects") : sound.root().resolve(target).resolve(String.valueOf(scene));
            if (!target.equals("Effects") && !Sound.SCENES.contains(scene)) throw new IllegalArgumentException("Szene unbekannt");
            List<String> saved = new ArrayList<>();
            for (var f : ctx.uploadedFiles("files")) {
                String name = Path.of(f.filename()).getFileName().toString().replaceAll("[\\\\/:*?\"<>|]", "_").trim();
                if (!name.toLowerCase().endsWith(".mp3")) throw new IllegalArgumentException("Nur MP3-Dateien: " + name);
                try (var in = f.content()) { Files.copy(in, dir.resolve(name), StandardCopyOption.REPLACE_EXISTING); }
                saved.add(name);
            }
            ctx.json(Map.of("saved", saved));
        });
        m.sse("/api/sound/events", c -> sound.subscribe(c, false));
        m.post("/api/sound/cmd", ctx -> { sound.command(ctx.bodyAsClass(Map.class)); ctx.json(sound.state()); });

        m.get("/api/config", ctx -> {
            Map<String, Object> c = new LinkedHashMap<>();
            for (String k : List.of("playerPassword", "mailTo", "smtpHost", "smtpPort", "smtpUser", "smtpPass", "smtpFrom", "updateTime"))
                c.put(k, db.get(k, k.equals("updateTime") ? "04:00" : k.equals("smtpPort") ? "587" : ""));
            ctx.json(c);
        });
        m.put("/api/config", ctx -> {
            Map<?, ?> b = ctx.bodyAsClass(Map.class);
            String t = String.valueOf(b.get("updateTime"));
            if (!t.matches("([01]\\d|2[0-3]):[0-5]\\d")) throw new IllegalArgumentException("Uhrzeit bitte als HH:MM");
            if (!String.valueOf(b.get("smtpPort")).matches("\\d{1,5}")) throw new IllegalArgumentException("SMTP-Port ist keine Zahl");
            if (!Objects.equals(db.get("playerPassword", ""), b.get("playerPassword")))
                db.put("pwVersion", String.valueOf(Long.parseLong(db.get("pwVersion", "0")) + 1)); // meldet alle Spieler ab
            if (!t.equals(db.get("updateTime", "04:00"))) db.put("jarLastDay", ""); // neue Uhrzeit gilt schon heute
            for (String k : List.of("playerPassword", "mailTo", "smtpHost", "smtpPort", "smtpUser", "smtpPass", "smtpFrom", "updateTime"))
                db.put(k, String.valueOf(b.get(k) == null ? "" : b.get(k)).trim());
            ctx.json(Map.of("ok", true));
        });
        m.post("/api/config/testmail", ctx -> {
            try { mail.send("Testmail", "Die Mail-Einstellungen von Heldenhort funktionieren."); ctx.json(Map.of("ok", true)); }
            catch (Exception e) { ctx.status(502).json(Map.of("error", e.getMessage())); }
        });
        m.get("/api/jar", ctx -> ctx.json(jar.status()));
        m.post("/api/jar/update", ctx -> ctx.json(Map.of("result", jar.update())));

        m.get("/api/heroes", ctx -> ctx.json(heroes.list()));
        m.get("/api/heroes/{file}/xml", ctx -> {
            Path p = heroes.file(ctx.pathParam("file"));
            ctx.header("Content-Disposition", "attachment; filename=\"" + p.getFileName() + "\"");
            ctx.contentType("application/xml").result(Files.newInputStream(p));
        });
        m.post("/api/heroes/{file}/accept", ctx -> {
            heroes.file(ctx.pathParam("file"));
            db.exec("UPDATE hero SET status='übernommen' WHERE file=?", ctx.pathParam("file"));
            ctx.json(Map.of("ok", true));
        });

        m.get("/api/sessions", ctx -> ctx.json(db.query("SELECT * FROM session ORDER BY id DESC")));
        m.post("/api/sessions", ctx -> {
            String name = String.valueOf(ctx.bodyAsClass(Map.class).get("name")).trim();
            if (name.isEmpty() || name.equals("null")) throw new IllegalArgumentException("Name fehlt");
            ctx.json(Map.of("id", db.exec("INSERT INTO session(name,heroes) VALUES(?,?)", name, "[]")));
        });
        m.put("/api/sessions/{id}", ctx -> {
            List<?> h = (List<?>) ctx.bodyAsClass(Map.class).get("heroes");
            db.exec("UPDATE session SET heroes=? WHERE id=?", new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(h), ctx.pathParam("id"));
            ctx.json(Map.of("ok", true));
        });

        m.get("/api/approvals", ctx -> ctx.json(approvals(db, null)));
        m.post("/api/approvals/items/{id}", ctx -> {
            Map<?, ?> b = ctx.bodyAsClass(Map.class);
            String st = String.valueOf(b.get("status")), c = b.get("comment") == null ? "" : String.valueOf(b.get("comment")).trim();
            if (!List.of("freigegeben", "geändert", "abgelehnt").contains(st)) throw new IllegalArgumentException("Status unbekannt");
            if (st.equals("geändert") && c.isEmpty()) throw new IllegalArgumentException("Bei „geändert“ bitte im Kommentar schreiben, was geändert wurde");
            db.exec("UPDATE approval_item SET status=?, comment=? WHERE id=?", st, c, ctx.pathParam("id"));
            ctx.json(Map.of("ok", true));
        });

        // ---------------- Spieler ----------------
        s.before(ctx -> {
            String p = ctx.path();
            if ((p.startsWith("/api/") || p.startsWith("/audio/")) && !p.equals("/api/login") && player(db, ctx) == null)
                throw new UnauthorizedResponse("Bitte anmelden");
        });
        s.post("/api/login", ctx -> {
            Map<?, ?> b = ctx.bodyAsClass(Map.class);
            String pw = db.get("playerPassword", ""), given = String.valueOf(b.get("password"));
            String name = String.valueOf(b.get("name")).trim();
            if (pw.isEmpty() || !MessageDigest.isEqual(pw.getBytes(StandardCharsets.UTF_8), given.getBytes(StandardCharsets.UTF_8))) {
                Thread.sleep(800); // bremst Raten
                throw new UnauthorizedResponse(pw.isEmpty() ? "Der Meister hat noch kein Passwort festgelegt" : "Passwort falsch");
            }
            if (name.isEmpty() || name.equals("null") || name.length() > 40) throw new IllegalArgumentException("Bitte deinen Namen angeben");
            ctx.cookie(cookie(ctx, "hh", token(db, name)));
            ctx.json(Map.of("name", name));
        });
        s.post("/api/logout", ctx -> { ctx.removeCookie("hh", "/"); ctx.json(Map.of("ok", true)); });
        s.get("/api/me", ctx -> ctx.json(Map.of("name", player(db, ctx))));
        s.sse("/api/sound/events", c -> {
            if (player(db, c.ctx()) == null) { c.close(); return; }
            sound.subscribe(c, true);
        });

        s.get("/api/create/catalog", ctx -> ctx.json(engine.catalog(ctx.queryParam("setting"), ctx.queryParam("geschlecht"))));

        s.post("/api/approvals", ctx -> {
            Map<?, ?> b = ctx.bodyAsClass(Map.class);
            String hero = String.valueOf(b.get("hero")).trim();
            List<?> items = (List<?>) b.get("items");
            if (hero.isEmpty() || items == null || items.isEmpty() || items.size() > 50) throw new IllegalArgumentException("Anfrage unvollständig");
            String who = player(db, ctx);
            long id = db.exec("INSERT INTO approval(hero,player,created) VALUES(?,?,?)", hero, who, java.time.LocalDateTime.now().format(Heroes.TS));
            StringBuilder txt = new StringBuilder(who + " bittet um Freigabe für " + hero + ":\n\n");
            for (Object o : items) {
                String t = String.valueOf(o).trim();
                if (t.length() > 1000) t = t.substring(0, 1000);
                db.exec("INSERT INTO approval_item(approval,kind,text,status,comment) VALUES(?,?,?,?,?)", id, "MG", t, "offen", "");
                txt.append("- ").append(t).append('\n');
            }
            mail.notifyMeister("Freigabe angefragt: " + hero, txt.append("\nFreigeben in der Meister-UI unter „Freigaben“.").toString());
            ctx.json(Map.of("id", id));
        });
        s.get("/api/approvals/{id}", ctx -> {
            var r = approvals(db, Long.parseLong(ctx.pathParam("id")));
            if (r.isEmpty() || !Objects.equals(r.get(0).get("player"), player(db, ctx))) throw new NotFoundResponse("Anfrage unbekannt");
            ctx.json(r.get(0));
        });
        s.post("/api/approvals/{id}/reopen", ctx -> {
            var r = approvals(db, Long.parseLong(ctx.pathParam("id")));
            if (r.isEmpty() || !Objects.equals(r.get(0).get("player"), player(db, ctx))) throw new NotFoundResponse("Anfrage unbekannt");
            db.exec("UPDATE approval_item SET status='offen', comment='' WHERE approval=? AND status='abgelehnt'", ctx.pathParam("id"));
            mail.notifyMeister("Freigabe erneut angefragt: " + r.get(0).get("hero"), player(db, ctx) + " hat den Helden angepasst und fragt erneut an.");
            ctx.json(Map.of("ok", true));
        });
        s.post("/api/heroes", ctx -> {
            Map<?, ?> b = ctx.bodyAsClass(Map.class);
            String who = player(db, ctx), name = String.valueOf(b.get("name")).trim();
            String bg = Jsoup.clean(String.valueOf(b.get("background")), Safelist.basic().addTags("h3"));
            if (Jsoup.parse(bg).text().trim().length() < MIN_BACKGROUND) throw new IllegalArgumentException("Hintergrundgeschichte fehlt (mindestens " + MIN_BACKGROUND + " Zeichen)");
            Object aid = b.get("approvalId");
            if (aid != null) {
                var r = approvals(db, ((Number) aid).longValue());
                if (r.isEmpty() || !Objects.equals(r.get(0).get("player"), who)) throw new IllegalArgumentException("Freigabe unbekannt");
                for (Object it : (List<?>) r.get(0).get("items"))
                    if (!List.of("freigegeben", "geändert").contains(((Map<?, ?>) it).get("status"))) throw new IllegalStateException("Noch nicht alle Punkte freigegeben");
            }
            String xml = String.valueOf(b.get("xml"));
            if (xml.length() > 2_000_000) throw new IllegalArgumentException("XML zu groß");
            String file = heroes.save(name, who, xml.getBytes(StandardCharsets.UTF_8), bg);
            mail.notifyMeister("Neuer Held: " + name, who + " hat den Helden „" + name + "“ geschickt.\nDatei: " + heroes.dir().resolve(file));
            ctx.json(Map.of("file", file));
        });

        m.start(Integer.parseInt(env("MEISTER_PORT", "8080")));
        s.start(Integer.parseInt(env("SPIELER_PORT", "8081")));
    }

    /** HttpOnly-Cookie, Secure sobald der Proxy (NPM) per HTTPS anfragt. */
    static Cookie cookie(Context ctx, String name, String value) {
        boolean https = "https".equalsIgnoreCase(ctx.header("X-Forwarded-Proto")) || "https".equals(ctx.scheme());
        return new Cookie(name, value, "/", 60 * 60 * 24 * 90, https, 0, true, null, null, SameSite.LAX);
    }

    /** Admin-Token hängt am Passwort: Passwort in der Docker-Konfig ändern meldet alle Meister-Sitzungen ab. */
    static String adminToken(Db db, String pw) throws Exception { return "admin." + hmac(db, "admin|" + pw); }

    static boolean admin(Db db, String pw, Context ctx) {
        try {
            String t = ctx.cookie("hhm");
            return !pw.isBlank() && t != null && MessageDigest.isEqual(t.getBytes(StandardCharsets.UTF_8), adminToken(db, pw).getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) { return false; }
    }

    static Javalin web(String dir, long maxRequest) {
        return Javalin.create(c -> {
            c.staticFiles.add(dir, Location.CLASSPATH);
            c.staticFiles.add(f -> { f.hostedPath = "/common"; f.directory = "/web/common"; f.location = Location.CLASSPATH; });
            c.http.maxRequestSize = maxRequest;
            c.jetty.multipartConfig.maxFileSize(maxRequest, io.javalin.config.SizeUnit.BYTES);
            c.jetty.multipartConfig.maxTotalRequestSize(maxRequest, io.javalin.config.SizeUnit.BYTES);
            c.showJavalinBanner = false;
        });
    }

    static List<Map<String, Object>> approvals(Db db, Long id) {
        var list = id == null ? db.query("SELECT * FROM approval ORDER BY id DESC") : db.query("SELECT * FROM approval WHERE id=?", id);
        for (var a : list) a.put("items", db.query("SELECT id,kind,text,status,comment FROM approval_item WHERE approval=? ORDER BY id", a.get("id")));
        return list;
    }

    // Spieler-Token: name.pwVersion.hmac — Passwortänderung erhöht pwVersion und macht alle alten Tokens ungültig.
    static String token(Db db, String name) throws Exception {
        String n = Base64.getUrlEncoder().withoutPadding().encodeToString(name.getBytes(StandardCharsets.UTF_8));
        String body = n + "." + db.get("pwVersion", "0");
        return body + "." + hmac(db, body);
    }

    static String player(Db db, Context ctx) {
        try {
            String t = ctx.cookie("hh");
            if (t == null) return null;
            int i = t.lastIndexOf('.');
            String body = t.substring(0, i);
            if (!MessageDigest.isEqual(hmac(db, body).getBytes(), t.substring(i + 1).getBytes())) return null;
            String[] p = body.split("\\.");
            if (!p[1].equals(db.get("pwVersion", "0"))) return null;
            return new String(Base64.getUrlDecoder().decode(p[0]), StandardCharsets.UTF_8);
        } catch (Exception e) { return null; }
    }

    static String hmac(Db db, String s) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(HexFormat.of().parseHex(db.get("secret", "")), "HmacSHA256"));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(s.getBytes(StandardCharsets.UTF_8)));
    }

    static String env(String k, String d) { String v = System.getenv(k); return v == null || v.isBlank() ? d : v; }
}
