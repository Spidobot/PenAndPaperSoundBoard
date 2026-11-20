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

    public static void main(String[] args) throws Exception {
        Path data = Path.of(env("DATA_DIR", "/data"));
        Db db = new Db(data.resolve("db/heldenhort.sqlite"));
        Mail mail = new Mail(db);
        HeldenJar jar = new HeldenJar(db, data.resolve("jar"));
        Creation creation = new Creation(jar);
        Accounts accounts = new Accounts(db);
        jar.selfTest = probe -> { try { return creation.selfTest(probe); } catch (Exception e) { return "FEHLER " + e.getMessage(); } };
        jar.onRejected = msg -> mail.notifyMeister("helden.jar-Update zurückgehalten", msg);
        Heroes heroes = new Heroes(db, data.resolve("helden"));
        Regeln regeln = new Regeln(data.resolve("regeln")); // Regeltexte aus /data/regeln/*.json (per FTP, nie im Repo)
        // per FTP abgelegte Helden: vermerkte Meistergenehmigungen landen ebenfalls unter „Freigaben“
        heroes.onFtp = (file, h) -> {
            int n = approvalFromXml(db, mail, file, str(h.get("name")), "FTP", null, h);
            if (n == 0 && autoUebernehmen(db)) db.exec("UPDATE hero SET status='übernommen' WHERE file=?", file);
            if (n > 0) mail.notifyMeister("Freigabe nötig: " + h.get("name"), "Im per FTP abgelegten Helden „" + h.get("name") + "“ (" + file + ") sind " + n + " Punkt(e) mit Meistergenehmigung vermerkt. Siehe „Freigaben“.");
        };
        Advance advance = new Advance(db, heroes, jar, accounts, mail);
        Sound sound = new Sound(data.resolve("audio"));
        sound.db = db;
        sound.warmUp();
        if (db.get("secret", null) == null) db.put("secret", HexFormat.of().formatHex(SecureRandom.getInstanceStrong().generateSeed(32)));

        String adminPw = env("ADMIN_PASSWORD", "");
        if (adminPw.isBlank()) System.err.println("WARNUNG: ADMIN_PASSWORD ist nicht gesetzt. Die Meister-UI bleibt gesperrt.");
        Javalin m = web("/web/meister", 600_000_000L);
        // gleiche Grenze wie beim Meister: Javalins Multipart-Grenze gilt prozessweit, die zuletzt gesetzte gewinnt (sonst max. 4 MB beim Meister-Upload).
        // Spieler-Uploads (Helden-XML) prüfen ihre Größe selbst (5 MB).
        Javalin s = web("/web/spieler", 600_000_000L);
        for (Javalin a : List.of(m, s)) {
            a.get("/api/public", ctx -> ctx.json(Map.of("impressum", db.get("impressum", "").isBlank() ? IMPRESSUM : db.get("impressum", ""))));
            a.exception(IllegalArgumentException.class, (e, ctx) -> ctx.status(400).json(Map.of("error", e.getMessage())));
            a.exception(IllegalStateException.class, (e, ctx) -> ctx.status(409).json(Map.of("error", e.getMessage())));
            // Alles Unerwartete: verständliche Meldung an den Browser (4xx, sonst ersetzt Cloudflare sie), Details ins Docker-Log
            a.exception(Exception.class, (e, ctx) -> {
                System.err.println("FEHLER bei " + ctx.method() + " " + ctx.path() + ": " + e);
                e.printStackTrace();
                ctx.status(422).json(Map.of("error", "Da ist auf dem Server etwas schiefgegangen (" + e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage()) + "). Bitte nochmal versuchen. Wenn es bleibt, dem Meister Bescheid geben, die Details stehen im Server-Log."));
            });
            a.exception(io.javalin.http.ContentTooLargeResponse.class, (e, ctx) -> ctx.status(413).json(Map.of("error", "Die Datei ist zu groß für eine einzelne Anfrage. Bitte Seite neu laden, große Dateien werden dann in Teilen hochgeladen.")));
            a.error(404, ctx -> { if (ctx.path().startsWith("/api/") && ctx.result() == null) ctx.json(Map.of("error", "Diese Funktion gibt es auf dem Server nicht (mehr). Bitte die Seite neu laden (Strg+F5).")); });
            a.get("/audio/<path>", ctx -> {
                Path p = sound.resolve(ctx.pathParam("path"));
                Sound.serve(ctx, p);
            });
            boolean playerSide = a == s;
            a.get("/api/sound/state", ctx -> {
                // Zuhörer = Spieler mit eingeschaltetem Ton und Lautstärke > 0 (meldet die Spielerseite bei jeder Abfrage)
                if (playerSide && "1".equals(ctx.queryParam("hoert"))) { var acc = accounts.current(ctx); if (acc != null) sound.polled(acc.get("id"), str(acc.get("name"))); }
                ctx.header("Cache-Control", "no-store"); ctx.json(sound.snapshot());
            });
            a.get("/api/sound/library", ctx -> ctx.json(sound.library(ctx.queryParamAsClass("scene", String.class).getOrDefault(sound.scene), ctx.queryParam("sid"))));
        }

        // ---------------- Meister ----------------
        m.before(ctx -> {
            String p = ctx.path();
            if (p.equals("/login.html") || p.startsWith("/common/") || p.equals("/api/admin/login") || p.equals("/api/public") || admin(db, adminPw, ctx)) return;
            if (p.startsWith("/api/") || p.startsWith("/audio/")) throw new UnauthorizedResponse("Du bist nicht (mehr) angemeldet. Bitte neu anmelden.");
            ctx.redirect("/login.html");
            ctx.skipRemainingHandlers();
        });
        m.post("/api/admin/login", ctx -> {
            String given = String.valueOf(ctx.bodyAsClass(Map.class).get("password"));
            if (adminPw.isBlank()) throw new UnauthorizedResponse("ADMIN_PASSWORD ist im Container nicht gesetzt");
            if (!MessageDigest.isEqual(adminPw.getBytes(StandardCharsets.UTF_8), given.getBytes(StandardCharsets.UTF_8))) {
                Thread.sleep(800);
                throw new UnauthorizedResponse("Das Meister-Passwort stimmt nicht.");
            }
            ctx.cookie(cookie(ctx, "hhm", adminToken(db, adminPw)));
            ctx.json(Map.of("ok", true));
        });
        m.post("/api/admin/logout", ctx -> { ctx.removeCookie("hhm", "/"); ctx.json(Map.of("ok", true)); });
        m.post("/api/sound/upload", ctx -> {
            String target = ctx.formParam("target"), scene = ctx.formParam("scene");
            if (!List.of("Music", "Sounds").contains(target)) throw new IllegalArgumentException("Ziel unbekannt (Effekte werden nicht über die Oberfläche hochgeladen)");
            Path dir = target.equals("Effects") ? sound.root().resolve("Effects") : sound.root().resolve(target).resolve(String.valueOf(scene));
            boolean ses = Sound.SESSION.equals(scene); // Rubrik Session: Datei nach Music/Session bzw. Sounds/Session, Eintrag in die gewählte Session
            String sid = ctx.formParam("sid");
            if (ses && (sid == null || !sid.matches("\\d{1,9}"))) throw new IllegalArgumentException("Erst oben eine Session auswählen");
            if (!target.equals("Effects") && !ses && !Sound.SCENES.contains(scene)) throw new IllegalArgumentException("Diese Soundboard-Rubrik gibt es nicht. Bitte Seite neu laden.");
            try { Files.createDirectories(dir); } catch (Exception e) { throw new IllegalStateException("Ordner " + dir + " kann nicht angelegt werden: keine Schreibrechte für den Container"); }
            if (!Files.isWritable(dir)) throw new IllegalStateException("Keine Schreibrechte auf " + dir + ". In Unraid den Container ohne --user starten oder die Rechte des Ordners anpassen.");
            List<String> saved = new ArrayList<>();
            // Große Dateien kommen in Teilen (Cloudflare lässt nur 100 MB pro Anfrage durch): part=0..parts-1,
            // die Teile werden an eine .teil-Datei angehängt und erst nach dem letzten Teil umbenannt.
            int part = ctx.formParam("part") == null ? 0 : Integer.parseInt(ctx.formParam("part"));
            int parts = ctx.formParam("parts") == null ? 1 : Integer.parseInt(ctx.formParam("parts"));
            if (part < 0 || parts < 1 || part >= parts || parts > 10_000) throw new IllegalArgumentException("Teilnummer ungültig");
            for (var f : ctx.uploadedFiles("files")) {
                String name = Path.of(ctx.formParam("name") != null ? ctx.formParam("name") : f.filename()).getFileName().toString().replaceAll("[\\\\/:*?\"<>|]", "_").trim();
                if (!name.toLowerCase().endsWith(".mp3")) throw new IllegalArgumentException("Nur MP3-Dateien: " + name);
                Path tmp = dir.resolve("." + name + ".teil");
                try (var in = f.content()) {
                    if (part == 0) Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
                    else {
                        if (!Files.exists(tmp)) throw new IllegalStateException("Teil " + part + " ohne Anfang: bitte neu hochladen");
                        try (var out = Files.newOutputStream(tmp, StandardOpenOption.APPEND)) { in.transferTo(out); }
                    }
                }
                if (part < parts - 1) { ctx.json(Map.of("partial", true)); return; }
                Files.move(tmp, dir.resolve(name), StandardCopyOption.REPLACE_EXISTING);
                saved.add(name);
                if (ses) sound.sessionEdit(sid, target + "/" + Sound.SESSION + "/" + name, true);
            }
            if (!ses) sound.rescan(scene);
            ctx.json(Map.of("saved", saved));
        });
        m.post("/api/sound/session", ctx -> {
            Map<?, ?> b = ctx.bodyAsClass(Map.class);
            sound.sessionEdit(str(b.get("sid")), str(b.get("file")), !Boolean.FALSE.equals(b.get("add")));
            ctx.json(Map.of("ok", true));
        });
        m.delete("/api/sound/session", ctx -> { sound.sessionClear(ctx.queryParam("sid")); ctx.json(Map.of("ok", true)); });
        m.get("/api/sound/events", ctx -> ctx.status(410).result("abgeschaltet")); // alte Seiten: Browser hört auf, neu zu verbinden
        m.get("/api/sound/fxinfo", ctx -> ctx.json(sound.fxInfo()));
        // Diagnose: was machen die Threads gerade? Bei Hängern im Browser öffnen und den Text schicken.
        m.get("/api/debug/threads", ctx -> {
            StringBuilder sb = new StringBuilder("Zeit " + java.time.LocalDateTime.now() + "\n");
            Map<String, Integer> states = new TreeMap<>();
            var all = Thread.getAllStackTraces();
            all.forEach((t, st) -> states.merge(t.getState().toString(), 1, Integer::sum));
            sb.append("Threads ").append(all.size()).append(" ").append(states).append("\n\n");
            all.forEach((t, st) -> {
                if (t.getState() == Thread.State.RUNNABLE || t.getState() == Thread.State.BLOCKED || t.getName().contains("Javalin") || t.getName().contains("Jetty") || t.getName().contains("pool")) {
                    boolean idle = java.util.Arrays.stream(st).anyMatch(e -> e.getMethodName().equals("idleJobPoll") || e.getClassName().contains("ReservedThreadExecutor") || e.getMethodName().contains("select") || e.getMethodName().equals("getTask"));
                    if (idle) return;
                    sb.append(t.getName()).append(" ").append(t.getState()).append("\n");
                    for (int i = 0; i < Math.min(12, st.length); i++) sb.append("    ").append(st[i]).append("\n");
                }
            });
            ctx.contentType("text/plain; charset=utf-8").result(sb.toString());
        });
        m.post("/api/sound/cmd", ctx -> { sound.command(ctx.bodyAsClass(Map.class)); ctx.json(sound.state()); });

        m.get("/api/config", ctx -> {
            Map<String, Object> c = new LinkedHashMap<>();
            for (String k : List.of("mailTo", "smtpHost", "smtpPort", "smtpUser", "smtpPass", "smtpFrom", "updateTime", "steigerLimit", "greeting", "impressum", "autoUebernehmen"))
            {
                String def = k.equals("autoUebernehmen") ? "1" : k.equals("updateTime") ? "04:00" : k.equals("smtpPort") ? "587" : k.equals("steigerLimit") ? "0" : k.equals("greeting") ? GREETING : k.equals("impressum") ? IMPRESSUM : "";
                String v = db.get(k, def);
                c.put(k, v.isBlank() && (k.equals("greeting") || k.equals("impressum")) ? def : v); // leer gespeichert = Vorlage zeigen
            }
            ctx.json(c);
        });
        m.put("/api/config", ctx -> {
            Map<?, ?> b = ctx.bodyAsClass(Map.class);
            String t = String.valueOf(b.get("updateTime"));
            if (!t.matches("([01]\\d|2[0-3]):[0-5]\\d")) throw new IllegalArgumentException("Uhrzeit bitte als HH:MM");
            if (!String.valueOf(b.get("smtpPort")).matches("\\d{1,5}")) throw new IllegalArgumentException("SMTP-Port ist keine Zahl");
            if (!String.valueOf(b.get("steigerLimit")).matches("\\d{1,3}")) throw new IllegalArgumentException("Steigerungsgrenze ist keine Zahl (0 = keine Grenze)");
            if (!t.equals(db.get("updateTime", "04:00"))) db.put("jarLastDay", ""); // neue Uhrzeit gilt schon heute
            for (String k : List.of("mailTo", "smtpHost", "smtpPort", "smtpUser", "smtpPass", "smtpFrom", "updateTime", "steigerLimit", "greeting", "impressum", "autoUebernehmen"))
                db.put(k, String.valueOf(b.get(k) == null ? "" : b.get(k)).trim());
            ctx.json(Map.of("ok", true));
        });
        m.post("/api/config/testmail", ctx -> {
            try { mail.send("Testmail", "Die Mail-Einstellungen von Heldenhort funktionieren."); ctx.json(Map.of("ok", true)); }
            catch (Exception e) { ctx.status(422).json(Map.of("error", cause(e))); }
        });
        m.get("/api/jar", ctx -> ctx.json(jar.status()));
        m.post("/api/jar/update", ctx -> ctx.json(Map.of("result", jar.update())));
        m.post("/api/jar/selftest", ctx -> { // prüft die laufende Jar mit demselben Test wie vor einem Update
            try (var probe = new java.net.URLClassLoader(new java.net.URL[]{data.resolve("jar/helden.jar").toUri().toURL()}, App.class.getClassLoader())) {
                ctx.json(Map.of("result", creation.selfTest(probe)));
            }
        });

        m.get("/api/heroes", ctx -> ctx.json(heroes.list()));
        m.get("/api/heroes/{file}/xml", ctx -> {
            Path p = heroes.file(ctx.pathParam("file"));
            ctx.header("Content-Disposition", "attachment; filename=\"" + p.getFileName() + "\"");
            ctx.contentType("application/xml").result(Files.newInputStream(p));
        });
        m.get("/api/heroes/{file}/pdf", ctx -> pdf(ctx, jar, heroes, db, regeln, ctx.pathParam("file")));
        m.get("/api/heroes/{file}/zip", ctx -> { // XML + Backgroundstory, z. B. zum Weitergeben fürs Balancing
            Path p = heroes.file(ctx.pathParam("file"));
            String base = p.getFileName().toString().replaceAll("\\.xml$", "");
            var bos = new java.io.ByteArrayOutputStream();
            try (var z = new java.util.zip.ZipOutputStream(bos)) {
                z.putNextEntry(new java.util.zip.ZipEntry(p.getFileName().toString())); z.write(Files.readAllBytes(p)); z.closeEntry();
                String bg = background(db, p.getFileName().toString());
                if (!bg.isBlank()) {
                    z.putNextEntry(new java.util.zip.ZipEntry(base + "_Backgroundstory.html"));
                    z.write(("<!doctype html><meta charset=\"utf-8\"><title>" + base + "</title>\n" + bg).getBytes(StandardCharsets.UTF_8)); z.closeEntry();
                    z.putNextEntry(new java.util.zip.ZipEntry(base + "_Backgroundstory.txt"));
                    z.write(Jsoup.parse(bg).wholeText().trim().getBytes(StandardCharsets.UTF_8)); z.closeEntry();
                }
                String ex = extra(db, p.getFileName().toString());
                if (!ex.isBlank()) {
                    z.putNextEntry(new java.util.zip.ZipEntry(base + "_Zusatzinfos.html"));
                    z.write(("<!doctype html><meta charset=\"utf-8\"><title>" + base + "</title>\n" + ex).getBytes(StandardCharsets.UTF_8)); z.closeEntry();
                    z.putNextEntry(new java.util.zip.ZipEntry(base + "_Zusatzinfos.txt"));
                    z.write(Jsoup.parse(ex).wholeText().trim().getBytes(StandardCharsets.UTF_8)); z.closeEntry();
                }
            }
            ctx.header("Content-Disposition", "attachment; filename=\"" + base + ".zip\"");
            ctx.contentType("application/zip").result(bos.toByteArray());
        });
        m.put("/api/heroes/{file}/extra", ctx -> {
            heroes.file(ctx.pathParam("file"));
            db.exec("UPDATE hero SET extra=? WHERE file=?", cleanBg(ctx.bodyAsClass(Map.class).get("extra")), ctx.pathParam("file"));
            ctx.json(Map.of("ok", true));
        });
        m.put("/api/heroes/{file}/bg", ctx -> {
            heroes.file(ctx.pathParam("file"));
            db.exec("UPDATE hero SET background=? WHERE file=?", cleanBg(ctx.bodyAsClass(Map.class).get("bg")), ctx.pathParam("file"));
            ctx.json(Map.of("ok", true));
        });
        m.post("/api/accounts/{id}/upload", ctx -> {
            db.exec("UPDATE account SET can_upload=? WHERE id=?", Boolean.TRUE.equals(ctx.bodyAsClass(Map.class).get("on")) ? 1 : 0, Long.parseLong(ctx.pathParam("id")));
            ctx.json(Map.of("ok", true));
        });
        m.delete("/api/heroes/{file}", ctx -> {
            String file = ctx.pathParam("file");
            var r = db.query("SELECT account FROM hero WHERE file=?", file);
            String name = str(Heroes.parse(Files.readAllBytes(heroes.file(file))).get("name"));
            heroes.trash(file);
            if (!r.isEmpty() && r.get(0).get("account") != null)
                mail.notify(accounts.email(r.get(0).get("account")), "Held gelöscht: " + name, "Der Meister hat den Helden „" + name + "“ gelöscht.");
            ctx.json(Map.of("ok", true));
        });
        m.post("/api/heroes/{file}/accept", ctx -> {
            heroes.file(ctx.pathParam("file"));
            db.exec("UPDATE hero SET status='übernommen' WHERE file=?", ctx.pathParam("file"));
            ctx.json(Map.of("ok", true));
        });

        m.get("/api/sessions", ctx -> ctx.json(db.query("SELECT * FROM session ORDER BY id DESC")));
        m.post("/api/sessions", ctx -> {
            String name = String.valueOf(ctx.bodyAsClass(Map.class).get("name")).trim();
            if (name.isEmpty() || name.equals("null")) throw new IllegalArgumentException("Bitte einen Namen eintragen.");
            ctx.json(Map.of("id", db.exec("INSERT INTO session(name,heroes,created) VALUES(?,?,?)", name, "[]", java.time.LocalDateTime.now().format(Heroes.TS))));
        });
        m.put("/api/sessions/{id}", ctx -> {
            Map<?, ?> b = ctx.bodyAsClass(Map.class);
            if (b.get("heroes") instanceof List<?> h)
                db.exec("UPDATE session SET heroes=? WHERE id=?", new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(h), ctx.pathParam("id"));
            if (b.get("name") != null) {
                String name = String.valueOf(b.get("name")).trim();
                if (name.isEmpty()) throw new IllegalArgumentException("Bitte einen Namen eintragen.");
                db.exec("UPDATE session SET name=? WHERE id=?", name, ctx.pathParam("id"));
            }
            ctx.json(Map.of("ok", true));
        });
        // Regelzusammenfassung aller Helden der Session: jede Regel einmal, mit den Helden, die sie haben
        m.get("/api/sessions/{id}/regeln", ctx -> {
            var r = sessionRegeln(db, heroes, regeln, ctx.pathParam("id"));
            if ("pdf".equals(ctx.queryParam("format"))) {
                ctx.header("Content-Disposition", "attachment; filename=\"Regeln " + r.getKey().replaceAll("[\\\\/:*?\"<>|]|[^\\x20-\\x7E]", "_") + ".pdf\"");
                ctx.contentType("application/pdf").result(Pdf.html("Regelzusammenfassung: " + r.getKey(), r.getValue()));
            } else ctx.json(Map.of("name", r.getKey(), "html", r.getValue(), "leer", regeln.leer()));
        });
        m.delete("/api/sessions/{id}", ctx -> {
            if (db.exec("DELETE FROM session WHERE id=?", ctx.pathParam("id")) == 0) throw new NotFoundResponse("Diese Session gibt es nicht mehr, vermutlich wurde sie gelöscht.");
            ctx.json(Map.of("ok", true));
        });

        m.get("/api/accounts", ctx -> ctx.json(accounts.list()));
        m.post("/api/accounts", ctx -> {
            Map<?, ?> b = ctx.bodyAsClass(Map.class);
            ctx.json(Map.of("id", accounts.create(str(b.get("name")), str(b.get("password")))));
        });
        m.post("/api/accounts/{id}/password", ctx -> {
            accounts.setStartPassword(Long.parseLong(ctx.pathParam("id")), str(ctx.bodyAsClass(Map.class).get("password")));
            ctx.json(Map.of("ok", true));
        });
        m.delete("/api/accounts/{id}", ctx -> { accounts.delete(Long.parseLong(ctx.pathParam("id"))); ctx.json(Map.of("ok", true)); });
        m.post("/api/heroes/{file}/owner", ctx -> {
            heroes.file(ctx.pathParam("file"));
            Object acc = ctx.bodyAsClass(Map.class).get("account");
            Long id = acc == null ? null : ((Number) acc).longValue();
            String name = id == null ? "FTP" : String.valueOf(accounts.get(id).get("name"));
            db.exec("UPDATE hero SET account=?, player=? WHERE file=?", id, name, ctx.pathParam("file"));
            ctx.json(Map.of("ok", true));
        });

        m.get("/api/approvals", ctx -> ctx.json(approvals(db, null)));
        m.post("/api/approvals/items/{id}", ctx -> {
            Map<?, ?> b = ctx.bodyAsClass(Map.class);
            String st = String.valueOf(b.get("status")), c = b.get("comment") == null ? "" : String.valueOf(b.get("comment")).trim();
            if (!List.of("freigegeben", "geändert", "abgelehnt").contains(st)) throw new IllegalArgumentException("Unbekannte Entscheidung. Bitte Seite neu laden.");
            if (st.equals("geändert") && c.isEmpty()) throw new IllegalArgumentException("Bei „geändert“ bitte im Kommentar schreiben, was geändert wurde");
            var kp = db.query("SELECT kind, payload, status, comment FROM approval_item WHERE id=?", ctx.pathParam("id"));
            if (!kp.isEmpty() && st.equals(kp.get(0).get("status")) && c.equals(str(kp.get(0).get("comment") == null ? "" : kp.get(0).get("comment"))))
                throw new IllegalStateException("Ist schon „" + st + "“"); // kein zweites Mal, keine doppelte Mail
            if (!kp.isEmpty() && "DEL".equals(kp.get(0).get("kind"))) {
                if (!"offen".equals(kp.get(0).get("status"))) throw new IllegalStateException("Über diese Löschung ist schon entschieden");
                if (st.equals("geändert")) throw new IllegalArgumentException("Löschen bitte freigeben oder ablehnen");
                if (st.equals("freigegeben")) {
                    String f = str(kp.get(0).get("payload"));
                    try { heroes.trash(f); c = (c.isEmpty() ? "" : c + " · ") + "gelöscht (Papierkorb)"; }
                    catch (IllegalArgumentException gone) { c = (c.isEmpty() ? "" : c + " · ") + "war schon weg"; }
                }
            }
            if (!kp.isEmpty() && "STEIG".equals(kp.get(0).get("kind"))) {
                if (!"offen".equals(kp.get(0).get("status"))) throw new IllegalStateException("Über diese Steigerung ist schon entschieden");
                if (st.equals("geändert")) throw new IllegalArgumentException("Steigerungen bitte freigeben oder ablehnen");
                if (st.equals("freigegeben")) c = (c.isEmpty() ? "" : c + " · ") + "Steigerung " + advance.approved(str(kp.get(0).get("payload")), str(db.query("SELECT text FROM approval_item WHERE id=?", ctx.pathParam("id")).get(0).get("text")));
            }
            db.exec("UPDATE approval_item SET status=?, comment=? WHERE id=?", st, c, ctx.pathParam("id"));
            var ap = db.query("SELECT a.id, a.file FROM approval a JOIN approval_item i ON i.approval=a.id WHERE i.id=?", ctx.pathParam("id"));
            if (!ap.isEmpty() && ap.get(0).get("file") != null && autoUebernehmen(db) && !"DEL".equals(kp.get(0).get("kind"))
                    && db.query("SELECT 1 FROM approval_item WHERE approval=? AND status='offen' AND kind='MG'", ap.get(0).get("id")).isEmpty())
                db.exec("UPDATE hero SET status='übernommen' WHERE file=? AND status='neu'", ap.get(0).get("file"));
            var it = db.query("SELECT a.hero, a.account, i.text FROM approval_item i JOIN approval a ON a.id=i.approval WHERE i.id=?", ctx.pathParam("id"));
            if (!it.isEmpty() && it.get(0).get("account") != null)
                mail.notify(accounts.email(it.get(0).get("account")), "Freigabe: " + it.get(0).get("hero"),
                        "Der Meister hat entschieden: " + st + "\n\n" + it.get(0).get("text") + (c.isEmpty() ? "" : "\n\nKommentar: " + c));
            ctx.json(Map.of("ok", true));
        });

        // ---------------- Spieler ----------------
        s.before(ctx -> {
            String p = ctx.path();
            if ((p.startsWith("/api/") || p.startsWith("/audio/")) && !p.equals("/api/login") && !p.equals("/api/account/confirm") && !p.equals("/api/public") && accounts.current(ctx) == null)
                throw new UnauthorizedResponse("Du bist nicht (mehr) angemeldet. Bitte neu anmelden.");
        });
        s.post("/api/login", ctx -> {
            Map<?, ?> b = ctx.bodyAsClass(Map.class);
            Long id = accounts.login(str(b.get("name")), str(b.get("password")));
            if (id == null) { Thread.sleep(800); throw new UnauthorizedResponse("Name oder Passwort falsch"); }
            ctx.cookie(cookie(ctx, "hh", accounts.token(id)));
            ctx.json(Map.of("name", accounts.get(id).get("name")));
        });
        s.post("/api/logout", ctx -> { ctx.removeCookie("hh", "/"); ctx.json(Map.of("ok", true)); });
        s.get("/api/me", ctx -> {
            var a = accounts.current(ctx);
            Map<String, Object> me = new LinkedHashMap<>();
            me.put("id", a.get("id")); me.put("name", a.get("name"));
            String gr = db.get("greeting", "");
            me.put("greeting", (gr.isBlank() ? GREETING : gr).replace("{name}", String.valueOf(a.get("name")))); me.put("email", a.get("email")); me.put("emailPending", a.get("email_pending"));
            me.put("canUpload", Objects.equals(String.valueOf(a.get("can_upload")), "1"));
            ctx.json(me);
        });
        s.post("/api/account/password", ctx -> {
            var a = accounts.current(ctx);
            Map<?, ?> b = ctx.bodyAsClass(Map.class);
            long id = ((Number) a.get("id")).longValue();
            if (!accounts.checkOld(id, str(b.get("old")))) { Thread.sleep(800); throw new IllegalArgumentException("Bisheriges Passwort stimmt nicht"); }
            accounts.setPassword(id, str(b.get("new")));
            ctx.cookie(cookie(ctx, "hh", accounts.token(id)));
            ctx.json(Map.of("ok", true));
        });
        s.post("/api/account/email", ctx -> {
            var a = accounts.current(ctx);
            String to = str(ctx.bodyAsClass(Map.class).get("email"));
            String t = accounts.requestEmail(((Number) a.get("id")).longValue(), to);
            String proto = Optional.ofNullable(ctx.header("X-Forwarded-Proto")).orElse(ctx.scheme());
            String host = Optional.ofNullable(ctx.header("X-Forwarded-Host")).orElse(ctx.host());
            mail.notify(to, "Mail-Adresse bestätigen", "Hallo " + a.get("name") + ",\n\nbitte bestätige deine Mail-Adresse für Heldenhort:\n"
                    + proto + "://" + host + "/api/account/confirm?t=" + t + "\n\nWenn du das nicht warst, ignoriere diese Mail.");
            ctx.json(Map.of("ok", true));
        });
        s.get("/api/account/confirm", ctx -> {
            boolean ok = accounts.confirmEmail(ctx.queryParam("t"));
            ctx.html("<!doctype html><meta charset=utf-8><meta name=viewport content='width=device-width'><link rel=stylesheet href='/common/app.css'>"
                    + "<div class='pl'><div class='inner'><div class='card'><h2>" + (ok ? "Mail-Adresse bestätigt" : "Link ungültig oder schon benutzt")
                    + "</h2><a class='btn' href='/'>Zu Heldenhort</a></div></div></div>");
        });
        s.get("/api/sound/events", ctx -> ctx.status(410).result("abgeschaltet"));

        // --- Entwürfe (Heldenerstellung), gehören dem Konto
        s.get("/api/drafts", ctx -> ctx.json(db.query("SELECT id,name,status,updated, xml IS NOT NULL AS haupt FROM draft WHERE account=? AND status<>'gesendet' ORDER BY updated DESC", me(accounts, ctx))));
        s.post("/api/drafts", ctx -> ctx.json(Map.of("id", db.exec("INSERT INTO draft(account,name,input,updated) VALUES(?,?,?,?)", me(accounts, ctx), "Neuer Held", "{}", now()))));
        s.get("/api/drafts/{id}", ctx -> {
            var d = draft(db, accounts, ctx);
            Map<String, Object> out = new LinkedHashMap<>(Map.of("id", d.get("id"), "name", str(d.get("name")), "status", str(d.get("status")), "bg", str(d.get("bg"))));
            out.put("extra", str(d.get("extra")));
            out.put("input", JSON.readValue(str(d.get("input")).isEmpty() ? "{}" : str(d.get("input")), Map.class));
            out.put("approval", d.get("approval"));
            out.put("haupt", d.get("xml") != null);
            ctx.json(out);
        });
        s.delete("/api/drafts/{id}", ctx -> { var d = draft(db, accounts, ctx); db.exec("DELETE FROM draft WHERE id=?", d.get("id")); ctx.json(Map.of("ok", true)); });
        s.post("/api/drafts/{id}/rkp", ctx -> {
            var d = draft(db, accounts, ctx);
            if (d.get("xml") != null) throw new IllegalStateException("Rasse, Kultur und Profession stehen schon fest");
            Map<String, Object> in = ctx.bodyAsClass(Map.class);
            var r = creation.rkp(in);
            String name = str(in.get("name")).isEmpty() ? "Neuer Held" : str(in.get("name"));
            db.exec("UPDATE draft SET input=?, name=?, xml=?, updated=? WHERE id=?", JSON.writeValueAsString(in), name, r.get("draft"), now(), d.get("id"));
            r.remove("draft");
            ctx.json(r);
        });
        s.post("/api/drafts/{id}/reset", ctx -> { // zurück zu Rasse/Kultur/Profession, Werte gehen verloren
            var d = draft(db, accounts, ctx);
            db.exec("UPDATE draft SET xml=NULL, approval=NULL, updated=? WHERE id=?", now(), d.get("id"));
            ctx.json(Map.of("ok", true));
        });
        s.post("/api/drafts/{id}/edit", ctx -> {
            var d = draft(db, accounts, ctx);
            if (d.get("xml") == null) throw new IllegalStateException("Erst Rasse, Kultur und Profession wählen");
            if ("gesendet".equals(d.get("status"))) throw new IllegalStateException("Der Held ist schon beim Meister");
            List<?> ops = (List<?>) ctx.bodyAsClass(Map.class).getOrDefault("ops", List.of());
            var r = creation.edit((String) d.get("xml"), ops);
            for (Object o : ops) if (o instanceof Map<?, ?> op && "name".equals(op.get("op")) && !str(op.get("value")).isEmpty())
                db.exec("UPDATE draft SET name=? WHERE id=?", str(op.get("value")), d.get("id"));
            db.exec("UPDATE draft SET xml=?, updated=? WHERE id=?", r.remove("draft"), now(), d.get("id"));
            ctx.json(r);
        });
        s.put("/api/drafts/{id}/bg", ctx -> {
            var d = draft(db, accounts, ctx);
            String bg = cleanBg(ctx.bodyAsClass(Map.class).get("bg"));
            if (bg.length() > 200_000) throw new IllegalArgumentException("Backgroundstory zu lang");
            db.exec("UPDATE draft SET bg=?, updated=? WHERE id=?", bg, now(), d.get("id"));
            ctx.json(Map.of("ok", true));
        });
        s.get("/api/drafts/{id}/talentkosten", ctx -> {
            var d = draft(db, accounts, ctx);
            if (d.get("xml") == null) throw new IllegalStateException("Erst Herkunft und Profession festlegen, dann lassen sich Kosten berechnen.");
            String only = ctx.queryParam("nur");
            ctx.json(creation.talentCosts((String) d.get("xml"), only == null || only.isBlank() ? null : new HashSet<>(Arrays.asList(only.split("\\|")))));
        });
        s.put("/api/drafts/{id}/extra", ctx -> {
            var d = draft(db, accounts, ctx);
            String ex = cleanBg(ctx.bodyAsClass(Map.class).get("extra"));
            if (ex.length() > 200_000) throw new IllegalArgumentException("Zusatzinfos zu lang");
            db.exec("UPDATE draft SET extra=?, updated=? WHERE id=?", ex, now(), d.get("id"));
            ctx.json(Map.of("ok", true));
        });
        s.get("/api/drafts/{id}/check", ctx -> { // Prüfung wie beim Senden (inkl. Punkte, die erst beim Abschließen entstehen)
            var d = draft(db, accounts, ctx);
            if (d.get("xml") == null) throw new IllegalStateException("Der Held ist noch nicht so weit: erst Herkunft und Profession festlegen.");
            ctx.json(Map.of("hinweise", creation.finish((String) d.get("xml"), str(d.get("name"))).get("hinweise")));
        });
        s.post("/api/drafts/{id}/approval", ctx -> {
            var d = draft(db, accounts, ctx);
            var a = accounts.current(ctx);
            List<String> mg = mgHints(creation, d);
            if (mg.isEmpty()) throw new IllegalArgumentException("Die Regelprüfung meldet nichts, was der Meister genehmigen müsste. Du kannst direkt senden.");
            long id = db.exec("INSERT INTO approval(hero,player,created,account) VALUES(?,?,?,?)", d.get("name"), a.get("name"), now(), a.get("id"));
            for (String t : mg) db.exec("INSERT INTO approval_item(approval,kind,text,status,comment) VALUES(?,?,?,?,?)", id, "MG", t, "offen", "");
            db.exec("UPDATE draft SET approval=? WHERE id=?", id, d.get("id"));
            mail.notifyMeister("Freigabe angefragt: " + d.get("name"), a.get("name") + " bittet um Freigabe für " + d.get("name") + ":\n\n- "
                    + String.join("\n- ", mg) + "\n\nFreigeben in der Meister-UI unter „Freigaben“.");
            ctx.json(Map.of("id", id));
        });
        s.get("/api/drafts/{id}/approval", ctx -> {
            var d = draft(db, accounts, ctx);
            if (d.get("approval") == null) { ctx.json(Map.of()); return; }
            var r = approvals(db, ((Number) d.get("approval")).longValue());
            ctx.json(r.isEmpty() ? Map.of() : r.get(0));
        });
        s.post("/api/drafts/{id}/send", ctx -> {
            var d = draft(db, accounts, ctx);
            var a = accounts.current(ctx);
            if (d.get("xml") == null) throw new IllegalStateException("Der Held ist noch nicht fertig: erst Herkunft, Profession und Werte festlegen.");
            if ("gesendet".equals(d.get("status"))) throw new IllegalStateException("Dieser Held ist schon beim Meister.");
            String bg = str(d.get("bg"));
            var f = creation.finish((String) d.get("xml"), str(d.get("name")));
            List<String> fehler = new ArrayList<>(), mg = new ArrayList<>();
            for (Object o : (List<?>) f.get("hinweise")) { Map<?, ?> h = (Map<?, ?>) o; if ("F".equals(h.get("art"))) fehler.add(str(h.get("text"))); if ("MG".equals(h.get("art"))) mg.add(str(h.get("text"))); }
            if (!fehler.isEmpty()) throw new IllegalStateException("Die Regelprüfung meldet Fehler: " + String.join("; ", fehler));
            if (!mg.isEmpty()) { // jeder aktuelle Punkt muss freigegeben oder geändert sein
                Set<String> ok = new HashSet<>();
                if (d.get("approval") != null)
                    for (var it : db.query("SELECT text FROM approval_item WHERE approval=? AND status IN ('freigegeben','geändert')", d.get("approval"))) ok.add(str(it.get("text")));
                for (String t : mg) if (!ok.contains(t)) throw new IllegalStateException("Noch nicht freigegeben: " + t);
            }
            String file = heroes.save(str(d.get("name")), str(a.get("name")), ((Number) a.get("id")).longValue(), ((String) f.get("xml")).getBytes(StandardCharsets.UTF_8), bg);
            db.exec("UPDATE hero SET extra=? WHERE file=?", str(d.get("extra")), file);
            if (d.get("approval") != null) db.exec("UPDATE approval SET file=? WHERE id=?", file, d.get("approval"));
            if (autoUebernehmen(db)) db.exec("UPDATE hero SET status='übernommen' WHERE file=?", file); // alle Punkte sind schon genehmigt
            db.exec("UPDATE draft SET status='gesendet', updated=? WHERE id=?", now(), d.get("id"));
            mail.notifyMeister("Neuer Held: " + d.get("name"), a.get("name") + " hat den Helden „" + d.get("name") + "“ geschickt.\nDatei: " + heroes.dir().resolve(file));
            ctx.json(Map.of("file", file));
        });

        // --- Meine Helden (fertige, beim Meister liegende)
        // kleiner Stand für die Live-Aktualisierung: Status der Helden und Entscheidungen des Meisters
        s.get("/api/mystand", ctx -> {
            Object id = accounts.current(ctx).get("id");
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("heroes", db.query("SELECT file,status FROM hero WHERE account=? ORDER BY file", id));
            o.put("items", db.query("SELECT i.id,i.text,i.status,i.comment,a.hero FROM approval_item i JOIN approval a ON a.id=i.approval WHERE a.account=? ORDER BY i.id", id));
            o.put("awards", db.query("SELECT COUNT(*) AS n FROM award WHERE sent IS NOT NULL AND file IN (SELECT file FROM hero WHERE account=?)", id).get(0).get("n"));
            ctx.json(o);
        });
        s.get("/api/myheroes", ctx -> {
            Object id = accounts.current(ctx).get("id");
            List<Map<String, Object>> out = new ArrayList<>();
            for (var h : heroes.list()) if (Objects.equals(String.valueOf(h.get("account")), String.valueOf(id))) {
                var del = db.query("SELECT id FROM approval_item WHERE kind='DEL' AND payload=? AND status='offen'", h.get("file"));
                if (!del.isEmpty()) h.put("delAnfrage", del.get(0).get("id"));
                out.add(h);
            }
            ctx.json(out);
        });

        s.get("/api/myheroes/{file}/xml", ctx -> {
            Path p = heroes.file(advance.own(ctx.pathParam("file"), ctx));
            ctx.header("Content-Disposition", "attachment; filename=\"" + p.getFileName() + "\"");
            ctx.contentType("application/xml").result(Files.readAllBytes(p));
        });
        s.get("/api/myheroes/{file}/pdf", ctx -> pdf(ctx, jar, heroes, db, regeln, advance.own(ctx.pathParam("file"), ctx)));
        s.put("/api/myheroes/{file}/bg", ctx -> {
            String file = advance.own(ctx.pathParam("file"), ctx);
            String bg = cleanBg(ctx.bodyAsClass(Map.class).get("bg"));
            boolean first = background(db, file).isBlank();
            db.exec("UPDATE hero SET background=? WHERE file=?", bg, file);
            if (first && !bg.isBlank())
                mail.notifyMeister("Backgroundstory nachgetragen: " + file, accounts.current(ctx).get("name") + " hat die Backgroundstory zu " + file + " nachgetragen.");
            ctx.json(Map.of("ok", true));
        });
        s.put("/api/myheroes/{file}/extra", ctx -> {
            String file = advance.own(ctx.pathParam("file"), ctx);
            String ex = cleanBg(ctx.bodyAsClass(Map.class).get("extra"));
            boolean first = extra(db, file).isBlank();
            db.exec("UPDATE hero SET extra=? WHERE file=?", ex, file);
            if (first && !ex.isBlank())
                mail.notifyMeister("Zusatzinfos nachgetragen: " + file, accounts.current(ctx).get("name") + " hat Zusatzinfos zu " + file + " nachgetragen.");
            ctx.json(Map.of("ok", true));
        });
        // Spieler löscht: noch nicht übernommen -> sofort zurückziehen; übernommen -> Löschanfrage an den Meister
        s.post("/api/myheroes/{file}/delete", ctx -> {
            String file = advance.own(ctx.pathParam("file"), ctx);
            var a = accounts.current(ctx);
            var h = db.query("SELECT status FROM hero WHERE file=?", file).get(0);
            String name = str(Heroes.parse(Files.readAllBytes(heroes.file(file))).get("name"));
            if (!"übernommen".equals(h.get("status"))) {
                heroes.trash(file);
                mail.notifyMeister("Held zurückgezogen: " + name, a.get("name") + " hat den Helden „" + name + "“ zurückgezogen (liegt im Papierkorb).");
                ctx.json(Map.of("done", "zurückgezogen"));
                return;
            }
            if (!db.query("SELECT 1 FROM approval_item WHERE kind='DEL' AND payload=? AND status='offen'", file).isEmpty())
                throw new IllegalStateException("Löschen ist schon beim Meister angefragt");
            long id = db.exec("INSERT INTO approval(hero,player,created,account,file) VALUES(?,?,?,?,?)", name, a.get("name"), now(), a.get("id"), file);
            db.exec("INSERT INTO approval_item(approval,kind,text,status,comment,payload) VALUES(?,?,?,?,?,?)", id, "DEL", "Held „" + name + "“ löschen", "offen", "", file);
            mail.notifyMeister("Löschanfrage: " + name, a.get("name") + " möchte den Helden „" + name + "“ löschen. Freigeben oder ablehnen unter „Freigaben“.");
            ctx.json(Map.of("done", "angefragt"));
        });
        s.post("/api/myheroes/{file}/anfrage/{item}/zurueck", ctx -> {
            String file = advance.own(ctx.pathParam("file"), ctx);
            var a = accounts.current(ctx);
            var it = db.query("SELECT i.id, i.text, i.status, i.kind FROM approval_item i JOIN approval ap ON ap.id=i.approval WHERE i.id=? AND ap.account=? AND (ap.file=? OR i.payload LIKE ?)",
                    ctx.pathParam("item"), a.get("id"), file, "%" + file + "%");
            if (it.isEmpty()) throw new NotFoundResponse("Diese Anfrage gibt es nicht (mehr).");
            if (!"offen".equals(it.get(0).get("status"))) throw new IllegalStateException("Der Meister hat schon entschieden: " + it.get(0).get("status"));
            db.exec("UPDATE approval_item SET status='zurückgezogen', comment='vom Spieler zurückgezogen' WHERE id=?", ctx.pathParam("item"));
            mail.notifyMeister("Anfrage zurückgezogen: " + it.get(0).get("text"), a.get("name") + " hat die Anfrage „" + it.get(0).get("text") + "“ zurückgezogen.");
            ctx.json(Map.of("ok", true));
        });
        s.post("/api/myheroes/upload", ctx -> { // nur mit Erlaubnis vom Meister
            var a = accounts.current(ctx);
            if (!"1".equals(String.valueOf(accounts.get(((Number) a.get("id")).longValue()).get("can_upload"))))
                throw new ForbiddenResponse("Hochladen hat dir der Meister nicht freigeschaltet");
            var f = ctx.uploadedFile("file");
            if (f == null) throw new IllegalArgumentException("Es wurde keine Datei mitgeschickt. Bitte eine XML-Datei auswählen.");
            if (f.size() > 5_000_000) throw new IllegalArgumentException("Die Datei ist größer als 5 MB. Eine Helden-XML ist normalerweise viel kleiner, ist es die richtige Datei?");
            byte[] xml = f.content().readAllBytes();
            Map<String, Object> h;
            try { h = Heroes.parse(xml); } catch (Exception e) { throw new IllegalArgumentException("Keine lesbare Helden-XML: " + e.getMessage()); }
            String name = str(h.get("name"));
            if (name.isEmpty()) throw new IllegalArgumentException("In der XML steht kein Heldenname. Ist das wirklich ein Held aus der Helden-Software?");
            Path tmp = Files.createTempFile("upload", ".xml");
            try { Files.write(tmp, xml); advance.probe(tmp); }
            catch (Exception e) { throw new IllegalArgumentException("Die helden.jar kann diesen Helden nicht laden: " + e.getMessage()); }
            finally { Files.deleteIfExists(tmp); }
            String file = heroes.save(name, str(a.get("name")), ((Number) a.get("id")).longValue(), xml, "");
            int mgs = approvalFromXml(db, mail, file, name, str(a.get("name")), a.get("id"), h);
            if (mgs == 0 && autoUebernehmen(db)) db.exec("UPDATE hero SET status='übernommen' WHERE file=?", file);
            mail.notifyMeister("Held hochgeladen: " + name, a.get("name") + " hat den Helden „" + name + "“ als XML hochgeladen.\nDatei: " + heroes.dir().resolve(file)
                    + (mgs > 0 ? "\n\n" + mgs + " Punkt(e) brauchen deine Meistergenehmigung, siehe „Freigaben“." : ""));
            ctx.json(Map.of("file", file));
        });

        // Regel-Info zu Vor-/Nachteilen/SF: eigener Text des Meisters (die helden.jar liefert keine Beschreibungen)
        // Regeltexte aus /data/regeln/*.json (per FTP abgelegt, nie im Repo). Der Text des Meisters hat Vorrang und steht oben.
        for (Javalin a : List.of(m, s)) a.get("/api/regelinfo", ctx -> {
            String n = str(ctx.queryParam("name"));
            List<Map<String, Object>> rt = new ArrayList<>();
            for (Regeln.Eintrag e : regeln.suche(n))
                rt.add(Map.of("name", e.name(), "art", e.art(), "zusatz", e.zusatz(), "quelle", e.quelle(), "text", e.text()));
            ctx.json(Map.of("name", n, "text", db.get("regelinfo:" + n, ""), "regel", rt));
        });
        m.get("/api/regeltexte", ctx -> ctx.json(regeln.status()));
        m.put("/api/regelinfo", ctx -> {
            Map<?, ?> b = ctx.bodyAsClass(Map.class);
            String n = str(b.get("name"));
            if (n.isEmpty()) throw new IllegalArgumentException("Bitte einen Namen angeben.");
            db.put("regelinfo:" + n, cleanBg(b.get("text")));
            ctx.json(Map.of("ok", true));
        });

        // Heldenbild: Hochladen (bis 10 MB, wird verkleinert), Anzeigen, Löschen
        m.get("/api/heroes/{file}/bild", ctx -> bildZeigen(ctx, heroes, ctx.pathParam("file")));
        m.post("/api/heroes/{file}/bild", ctx -> bildHoch(ctx, heroes, ctx.pathParam("file")));
        m.delete("/api/heroes/{file}/bild", ctx -> { heroes.file(ctx.pathParam("file")); Files.deleteIfExists(heroes.bild(ctx.pathParam("file"))); ctx.json(Map.of("ok", true)); });
        s.get("/api/myheroes/{file}/bild", ctx -> bildZeigen(ctx, heroes, advance.own(ctx.pathParam("file"), ctx)));
        s.post("/api/myheroes/{file}/bild", ctx -> bildHoch(ctx, heroes, advance.own(ctx.pathParam("file"), ctx)));
        s.delete("/api/myheroes/{file}/bild", ctx -> { String f = advance.own(ctx.pathParam("file"), ctx); Files.deleteIfExists(heroes.bild(f)); ctx.json(Map.of("ok", true)); });

        new Polls(db, mail, accounts).routes(m, s);
        advance.routes(m, s);

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

    static final String GREETING = "Willkommen, {name}";
    static final String IMPRESSUM = """
Impressum

Angaben gemäß § 5 E-Commerce-Gesetz und Offenlegung gemäß § 25 Mediengesetz

Medieninhaber und Betreiber:
[Vorname Nachname]
[Ort], Österreich

Kontakt: [E-Mail-Adresse]

Blattlinie:
Private, nicht-kommerzielle Seite einer Pen-&-Paper-Runde (Das Schwarze Auge): Organisation der Spielabende, Verwaltung der Helden und Soundboard für den Spieltisch. Kein öffentliches Angebot.

Hinweise:
Das Schwarze Auge ist eine eingetragene Marke der Ulisses Spiele GmbH. Die Heldenerstellung nutzt die Helden-Software (helden-software.de). Diese Seite steht in keiner Verbindung zu den Rechteinhabern.
Icons: Icons8 (icons8.com).""";

    /** Fehlerkette lesbar machen (Mailserver-Fehler stecken oft in der Ursache). */
    static String cause(Throwable e) {
        StringBuilder sb = new StringBuilder();
        for (Throwable x = e; x != null && sb.length() < 600; x = x.getCause()) {
            String m = x.getMessage();
            if (sb.length() > 0) sb.append(" → ");
            sb.append(x.getClass().getSimpleName()).append(m == null ? "" : ": " + m);
        }
        return sb.toString();
    }

    static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();

    static String cleanBg(Object o) { return Jsoup.clean(str(o), Safelist.basic().addTags("h3")); }

    /**
     * Legt für eine hochgeladene/abgelegte XML eine Freigabe-Anfrage an: ein Punkt je in der XML vermerkter Meistergenehmigung.
     * Liefert die Anzahl der Punkte (0 = nichts zu genehmigen, dann wird auch nichts angelegt).
     */
    static int approvalFromXml(Db db, Mail mail, String file, String hero, String player, Object account, Map<String, Object> parsed) {
        List<?> mg = parsed.get("meistergenehmigung") instanceof List<?> l ? l : List.of();
        if (mg.isEmpty()) return 0;
        long id = db.exec("INSERT INTO approval(hero,player,created,account,file) VALUES(?,?,?,?,?)", hero, player, now(), account, file);
        for (Object t : mg) db.exec("INSERT INTO approval_item(approval,kind,text,status,comment) VALUES(?,?,?,?,?)", id, "MG", str(t), "offen", "");
        return mg.size();
    }

    /** Helden automatisch übernehmen, sobald nichts mehr zu genehmigen ist (Konfiguration, Standard: an). */
    static boolean autoUebernehmen(Db db) { return !"0".equals(db.get("autoUebernehmen", "1")); }

    static void bildZeigen(Context ctx, Heroes heroes, String file) throws Exception {
        heroes.file(file);
        Path b = heroes.bild(file);
        if (!Files.exists(b)) throw new NotFoundResponse("Für diesen Helden ist noch kein Bild hochgeladen.");
        ctx.header("Cache-Control", "no-cache");
        ctx.contentType("image/jpeg").result(Files.readAllBytes(b));
    }

    static void bildHoch(Context ctx, Heroes heroes, String file) throws Exception {
        var f = ctx.uploadedFile("bild");
        if (f == null) throw new IllegalArgumentException("Es wurde kein Bild mitgeschickt.");
        if (f.size() > 10L * 1024 * 1024) throw new IllegalArgumentException("Das Bild ist größer als 10 MB. Bitte ein kleineres nehmen.");
        heroes.saveBild(file, f.content().readAllBytes());
        ctx.json(Map.of("ok", true));
    }

    static String extra(Db db, String file) {
        var r = db.query("SELECT extra FROM hero WHERE file=?", file);
        return r.isEmpty() ? "" : str(r.get(0).get("extra"));
    }

    static String background(Db db, String file) {
        var r = db.query("SELECT background FROM hero WHERE file=?", file);
        return r.isEmpty() ? "" : str(r.get(0).get("background"));
    }

    /** Vor-/Nachteile und Sonderfertigkeiten eines Helden: Name → Wert (Wert leer, wenn keiner). */
    @SuppressWarnings("unchecked")
    static Map<String, String> regelNamen(Map<String, Object> h) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Map<String, String> v : (List<Map<String, String>>) h.get("V")) out.putIfAbsent(v.get("name"), str(v.get("value")));
        for (String sf : (List<String>) h.get("SF")) out.putIfAbsent(sf, "");
        return out;
    }

    static Map.Entry<String, String> sessionRegeln(Db db, Heroes heroes, Regeln regeln, String id) throws Exception {
        var r = db.query("SELECT name, heroes FROM session WHERE id=?", id);
        if (r.isEmpty()) throw new NotFoundResponse("Diese Session gibt es nicht mehr, vermutlich wurde sie gelöscht.");
        List<?> files = new com.fasterxml.jackson.databind.ObjectMapper().readValue(str(r.get(0).get("heroes")), List.class);
        if (files.isEmpty()) throw new IllegalArgumentException("In dieser Session sind noch keine Helden.");
        Map<String, Map<String, String>> helden = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (Object f : files) {
            Path p;
            try { p = heroes.file(str(f)); } catch (Exception gone) { continue; } // Held inzwischen gelöscht
            Map<String, Object> h = Heroes.parse(Files.readAllBytes(p));
            helden.put(str(h.get("name")), regelNamen(h));
        }
        return Map.entry(str(r.get(0).get("name")), regeln.zusammenfassung(helden, true));
    }

    /** Heldenbogen als PDF, gedruckt von der helden.jar; ?bg=1 hängt Backgroundstory und Zusatzinfos an, ?regeln=1 die Regelzusammenfassung. */
    static void pdf(Context ctx, HeldenJar jar, Heroes heroes, Db db, Regeln regeln, String file) throws Exception {
        Path p = heroes.file(file);
        Map<String, Object> parsed = Heroes.parse(Files.readAllBytes(p));
        String name = str(parsed.get("name"));
        byte[] pdf;
        boolean mit = "1".equals(ctx.queryParam("bg"));
        String regelHtml = "1".equals(ctx.queryParam("regeln")) ? regeln.zusammenfassung(Map.of(name, regelNamen(parsed)), false) : null;
        try { pdf = Pdf.heldenbogen(jar, p, name, mit ? background(db, file) : null, mit ? extra(db, file) : null, heroes.bild(file), regelHtml); }
        catch (Exception e) { // 4xx statt 5xx: Cloudflare ersetzt 5xx durch eine eigene Seite
            Throwable c = e instanceof java.lang.reflect.InvocationTargetException && e.getCause() != null ? e.getCause() : e;
            throw new IllegalStateException("PDF nicht möglich: " + cause(c));
        }
        ctx.header("Content-Disposition", "attachment; filename=\"" + p.getFileName().toString().replaceAll("\\.xml$", "") + ".pdf\"");
        ctx.contentType("application/pdf").result(pdf);
    }

    static String str(Object o) { return o == null ? "" : o.toString().trim(); }

    static String now() { return java.time.LocalDateTime.now().format(Heroes.TS); }

    static Object me(Accounts accounts, Context ctx) { return accounts.current(ctx).get("id"); }

    /** Entwurf des angemeldeten Kontos, sonst 404. */
    static Map<String, Object> draft(Db db, Accounts accounts, Context ctx) {
        var r = db.query("SELECT * FROM draft WHERE id=? AND account=?", ctx.pathParam("id"), me(accounts, ctx));
        if (r.isEmpty()) throw new NotFoundResponse("Diesen Entwurf gibt es nicht mehr (gelöscht oder schon gesendet).");
        return r.get(0);
    }

    /** Punkte mit Meistergenehmigung laut Regelprüfung der helden.jar. */
    static List<String> mgHints(Creation creation, Map<String, Object> d) throws Exception {
        if (d.get("xml") == null) return List.of();
        List<String> mg = new ArrayList<>();
        for (Object o : (List<?>) creation.finish((String) d.get("xml"), str(d.get("name"))).get("hinweise"))
            if ("MG".equals(((Map<?, ?>) o).get("art"))) mg.add(str(((Map<?, ?>) o).get("text")));
        return mg;
    }

    static Javalin web(String dir, long maxRequest) {
        return Javalin.create(c -> {
            c.staticFiles.add(dir, Location.CLASSPATH);
            c.staticFiles.add(f -> { f.hostedPath = "/common"; f.directory = "/web/common"; f.location = Location.CLASSPATH; });
            c.http.maxRequestSize = maxRequest;
            c.jetty.multipartConfig.maxFileSize(maxRequest, io.javalin.config.SizeUnit.BYTES);
            c.jetty.multipartConfig.maxTotalRequestSize(maxRequest, io.javalin.config.SizeUnit.BYTES);
            c.showJavalinBanner = false;
            // Anfragen, die länger als 3 s dauern, ins Docker-Log (Hängern auf die Spur kommen)
            c.requestLogger.http((ctx, ms) -> { if (ms > 3000 && !ctx.path().startsWith("/audio/")) System.err.println("LANGSAM " + Math.round(ms) + " ms: " + ctx.method() + " " + ctx.path()); });
        });
    }

    static List<Map<String, Object>> approvals(Db db, Long id) {
        var list = id == null
                ? db.query("SELECT * FROM approval a WHERE (a.file IS NOT NULL AND a.file IN (SELECT file FROM hero)) OR (a.file IS NULL AND a.id IN (SELECT approval FROM draft WHERE approval IS NOT NULL)) ORDER BY id DESC")
                : db.query("SELECT * FROM approval WHERE id=?", id);
        for (var a : list) a.put("items", db.query("SELECT id,kind,text,status,comment FROM approval_item WHERE approval=? ORDER BY id", a.get("id")));
        return list;
    }

    static String hmac(Db db, String s) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(HexFormat.of().parseHex(db.get("secret", "")), "HmacSHA256"));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(s.getBytes(StandardCharsets.UTF_8)));
    }

    static String env(String k, String d) { String v = System.getenv(k); return v == null || v.isBlank() ? d : v; }
}
