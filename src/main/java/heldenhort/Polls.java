package heldenhort;

import io.javalin.Javalin;
import io.javalin.http.NotFoundResponse;
import java.util.*;

/**
 * Terminabstimmung: Der Meister gibt Termine vor, Spieler stimmen je Termin ab (ja / vielleicht / nein).
 * Abstimmen sollen die Konten, denen ein Held in der gewählten Session gehört (ohne Session: alle Konten).
 * Mails gehen nur an den Meister: bei jeder Stimme und einmal, wenn alle abgestimmt haben.
 */
public final class Polls {
    static final List<String> ANSWERS = List.of("ja", "vielleicht", "nein");
    private final Db db;
    private final Mail mail;
    private final Accounts accounts;

    public Polls(Db db, Mail mail, Accounts accounts) {
        this.db = db; this.mail = mail; this.accounts = accounts;
        db.exec("CREATE TABLE IF NOT EXISTS poll(id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT, session INTEGER, note TEXT, created TEXT, closed INTEGER DEFAULT 0, allnotified INTEGER DEFAULT 0)");
        db.exec("CREATE TABLE IF NOT EXISTS poll_option(id INTEGER PRIMARY KEY AUTOINCREMENT, poll INTEGER, label TEXT)");
        try { db.exec("ALTER TABLE poll ADD COLUMN fixed INTEGER"); } catch (RuntimeException alreadyThere) { }
        try { db.exec("ALTER TABLE poll ADD COLUMN fixed_hours REAL"); } catch (RuntimeException alreadyThere) { }
        db.exec("CREATE TABLE IF NOT EXISTS poll_vote(poll INTEGER, option INTEGER, account INTEGER, answer TEXT, at TEXT, PRIMARY KEY(option, account))");
    }

    /** Für wen dieses Konto abstimmen darf: sich selbst und den vom Meister eingetragenen Partner (vote_for). */
    Set<String> darfFuer(Object me) {
        Set<String> s = new HashSet<>(List.of(String.valueOf(me)));
        var r = db.query("SELECT vote_for FROM account WHERE id=?", me);
        if (!r.isEmpty() && r.get(0).get("vote_for") != null) s.add(String.valueOf(r.get(0).get("vote_for")));
        return s;
    }

    /** Konten, die abstimmen sollen. */
    List<Map<String, Object>> voters(Object session) {
        if (session == null) return db.query("SELECT id,name FROM account ORDER BY name COLLATE NOCASE");
        var s = db.query("SELECT heroes FROM session WHERE id=?", session);
        if (s.isEmpty()) return List.of();
        List<Map<String, Object>> out = new ArrayList<>();
        Set<Object> seen = new HashSet<>();
        try {
            for (Object file : App.JSON.readValue(String.valueOf(s.get(0).get("heroes")), List.class))
                for (var h : db.query("SELECT a.id,a.name FROM hero h JOIN account a ON a.id=h.account WHERE h.file=?", file))
                    if (seen.add(h.get("id"))) out.add(h);
        } catch (Exception e) { throw new IllegalStateException(e); }
        return out;
    }

    Map<String, Object> full(Map<String, Object> p) {
        Map<String, Object> m = new LinkedHashMap<>(p);
        m.put("options", db.query("SELECT id,label FROM poll_option WHERE poll=? ORDER BY id", p.get("id")));
        m.put("votes", db.query("SELECT v.option, v.account, v.answer, a.name FROM poll_vote v LEFT JOIN account a ON a.id=v.account WHERE v.poll=?", p.get("id")));
        m.put("voters", voters(p.get("session")));
        var s = p.get("session") == null ? List.<Map<String, Object>>of() : db.query("SELECT name FROM session WHERE id=?", p.get("session"));
        m.put("sessionName", s.isEmpty() ? null : s.get(0).get("name"));
        return m;
    }

    public void routes(Javalin meister, Javalin spieler) {
        // --- Meister
        meister.get("/api/polls", ctx -> {
            List<Map<String, Object>> out = new ArrayList<>();
            for (var p : db.query("SELECT * FROM poll ORDER BY id DESC")) out.add(full(p));
            ctx.json(out);
        });
        meister.post("/api/polls", ctx -> {
            Map<?, ?> b = ctx.bodyAsClass(Map.class);
            String title = App.str(b.get("title"));
            List<?> opts = b.get("options") instanceof List<?> l ? l : List.of();
            if (title.isEmpty()) throw new IllegalArgumentException("Bitte einen Titel eintragen.");
            List<String> labels = new ArrayList<>();
            for (Object o : opts) { String s = App.str(o); if (!s.isEmpty()) labels.add(s); }
            if (labels.isEmpty()) throw new IllegalArgumentException("Mindestens einen Termin angeben");
            Object session = b.get("session") instanceof Number n ? n.longValue() : null;
            long id = db.exec("INSERT INTO poll(title,session,note,created) VALUES(?,?,?,?)", title, session, App.str(b.get("note")), App.now());
            for (String l : labels) db.exec("INSERT INTO poll_option(poll,label) VALUES(?,?)", id, l);
            ctx.json(Map.of("id", id));
        });
        meister.post("/api/polls/{id}/close", ctx -> {
            db.exec("UPDATE poll SET closed=? WHERE id=?", Boolean.TRUE.equals(ctx.bodyAsClass(Map.class).get("closed")) ? 1 : 0, ctx.pathParam("id"));
            ctx.json(Map.of("ok", true));
        });
        meister.post("/api/polls/{id}/fix", ctx -> {
            var p = db.query("SELECT * FROM poll WHERE id=?", ctx.pathParam("id"));
            if (p.isEmpty()) throw new NotFoundResponse("Diese Terminabstimmung gibt es nicht mehr, vermutlich wurde sie gelöscht.");
            Map<?, ?> b = ctx.bodyAsClass(Map.class);
            var opt = db.query("SELECT * FROM poll_option WHERE id=? AND poll=?", b.get("option"), ctx.pathParam("id"));
            if (opt.isEmpty()) throw new IllegalArgumentException("Diesen Termin gibt es in der Abstimmung nicht mehr.");
            double hours = b.get("hours") instanceof Number n ? n.doubleValue() : 4;
            if (hours <= 0 || hours > 48) throw new IllegalArgumentException("Dauer zwischen 0 und 48 Stunden");
            java.time.LocalDateTime start;
            try { start = java.time.LocalDateTime.parse(String.valueOf(opt.get(0).get("label"))); }
            catch (Exception e) { throw new IllegalArgumentException("Der Termin hat kein lesbares Datum"); }
            db.exec("UPDATE poll SET fixed=?, fixed_hours=?, closed=1 WHERE id=?", opt.get(0).get("id"), hours, p.get(0).get("id"));
            var f = full(db.query("SELECT * FROM poll WHERE id=?", p.get(0).get("id")).get(0));
            String title = String.valueOf(p.get(0).get("title")), note = App.str(p.get(0).get("note"));
            String uid = "heldenhort-poll-" + p.get(0).get("id");
            String when = start.format(java.time.format.DateTimeFormatter.ofPattern("EEEE, dd.MM.yyyy 'um' HH:mm", java.util.Locale.GERMAN));
            String text = "Der Termin für „" + title + "“ steht fest: " + when + " (" + (hours % 1 == 0 ? String.valueOf((int) hours) : String.valueOf(hours)) + " Stunden)."
                    + (note.isEmpty() ? "" : "\n" + note)
                    + "\n\nDie Einladung hängt als Kalenderdatei (termin.ics) an: im Mailprogramm öffnen oder „Zum Kalender hinzufügen“ wählen, dann steht der Termin samt Erinnerung einen Tag vorher in deinem Kalender.";
            List<String> sent = new ArrayList<>(), ohne = new ArrayList<>();
            String org = mail.absender();
            for (Object v : (List<?>) f.get("voters")) {
                Map<?, ?> vm = (Map<?, ?>) v;
                String to = accounts.email(vm.get("id")), name = String.valueOf(vm.get("name"));
                if (to == null || to.isBlank()) { ohne.add(name); continue; }
                mail.notifyIcs(to, "Termin: " + title, text, ics(uid, title, note, start, hours, org, name, to));
                sent.add(name);
            }
            String meisterMail = db.get("mailTo", "");
            if (!meisterMail.isBlank()) mail.notifyIcs(meisterMail, "Termin: " + title, text, ics(uid, title, note, start, hours, org, "Meister", meisterMail));
            ctx.json(Map.of("sent", sent, "ohne", ohne, "meister", !meisterMail.isBlank()));
        });
        meister.delete("/api/polls/{id}", ctx -> {
            String id = ctx.pathParam("id");
            db.exec("DELETE FROM poll_vote WHERE poll=?", id);
            db.exec("DELETE FROM poll_option WHERE poll=?", id);
            db.exec("DELETE FROM poll WHERE id=?", id);
            ctx.json(Map.of("ok", true));
        });

        // --- Kalenderdatei zum fixierten Termin, jederzeit nachladbar (falls die Mail fehlt oder gelöscht wurde)
        for (var app : List.of(meister, spieler)) app.get("/api/polls/{id}/ics", ctx -> {
            var p = db.query("SELECT * FROM poll WHERE id=?", ctx.pathParam("id"));
            if (p.isEmpty()) throw new NotFoundResponse("Diese Terminabstimmung gibt es nicht mehr.");
            if (p.get(0).get("fixed") == null) throw new IllegalArgumentException("Für diese Abstimmung steht noch kein Termin fest.");
            var opt = db.query("SELECT label FROM poll_option WHERE id=?", p.get(0).get("fixed"));
            if (opt.isEmpty()) throw new IllegalArgumentException("Der fixierte Termin ist nicht mehr in der Abstimmung.");
            double hours = p.get(0).get("fixed_hours") instanceof Number n ? n.doubleValue() : 4;
            String ics = ics("heldenhort-poll-" + p.get(0).get("id"), String.valueOf(p.get(0).get("title")), App.str(p.get(0).get("note")),
                    java.time.LocalDateTime.parse(String.valueOf(opt.get(0).get("label"))), hours, null, null, null);
            ctx.header("Content-Disposition", "attachment; filename=\"termin.ics\"");
            ctx.contentType("text/calendar; charset=UTF-8").result(ics);
        });

        // --- Spieler: offene Abstimmungen, bei denen das Konto abstimmen soll (selbst oder für den eingetragenen Partner)
        spieler.get("/api/polls", ctx -> {
            Object me = accounts.current(ctx).get("id");
            Set<String> ich = darfFuer(me);
            List<Map<String, Object>> out = new ArrayList<>();
            for (var p : db.query("SELECT * FROM poll WHERE closed=0 OR fixed IS NOT NULL ORDER BY id DESC LIMIT 30")) {
                var f = full(p);
                List<Map<String, Object>> fuer = new ArrayList<>();
                for (Object v : (List<?>) f.get("voters")) {
                    Map<?, ?> vm = (Map<?, ?>) v;
                    if (ich.contains(String.valueOf(vm.get("id")))) fuer.add(Map.of("id", vm.get("id"), "name", vm.get("name"), "selbst", String.valueOf(vm.get("id")).equals(String.valueOf(me))));
                }
                if (fuer.isEmpty()) continue;
                fuer.sort(Comparator.comparing(x -> !Boolean.TRUE.equals(x.get("selbst")))); // erst die eigene Stimme, dann die für den Partner
                f.put("fuer", fuer);
                out.add(f);
            }
            ctx.json(out);
        });
        spieler.post("/api/polls/{id}/vote", ctx -> {
            var a = accounts.current(ctx);
            var p = db.query("SELECT * FROM poll WHERE id=? AND closed=0", ctx.pathParam("id"));
            if (p.isEmpty()) throw new NotFoundResponse("Abstimmung unbekannt oder geschlossen");
            var f = full(p.get(0));
            Map<?, ?> b = ctx.bodyAsClass(Map.class);
            Object wer = b.get("fuer") instanceof Number n ? (Object) n.longValue() : a.get("id");
            if (!darfFuer(a.get("id")).contains(String.valueOf(wer)))
                throw new IllegalArgumentException("Für dieses Konto darfst du nicht abstimmen. Das stellt der Meister unter Spieler ein.");
            if (((List<?>) f.get("voters")).stream().noneMatch(v -> Objects.equals(String.valueOf(((Map<?, ?>) v).get("id")), String.valueOf(wer))))
                throw new IllegalArgumentException(String.valueOf(wer).equals(String.valueOf(a.get("id"))) ? "Du bist bei dieser Abstimmung nicht dabei" : "Dein Partner ist bei dieser Abstimmung nicht dabei");
            String werName = String.valueOf(db.query("SELECT name FROM account WHERE id=?", wer).get(0).get("name"));
            Map<?, ?> answers = b.get("answers") instanceof Map<?, ?> m ? m : Map.of();
            Set<String> optionIds = new HashSet<>();
            for (Object o : (List<?>) f.get("options")) optionIds.add(String.valueOf(((Map<?, ?>) o).get("id")));
            List<String> lines = new ArrayList<>();
            for (var e : answers.entrySet()) {
                String opt = String.valueOf(e.getKey()), ans = App.str(e.getValue());
                if (!optionIds.contains(opt) || !ANSWERS.contains(ans)) throw new IllegalArgumentException("Ungültige Stimme");
                db.exec("INSERT INTO poll_vote(poll,option,account,answer,at) VALUES(?,?,?,?,?) ON CONFLICT(option,account) DO UPDATE SET answer=excluded.answer, at=excluded.at",
                        p.get(0).get("id"), Long.parseLong(opt), wer, ans, App.now());
                for (Object o : (List<?>) f.get("options")) if (String.valueOf(((Map<?, ?>) o).get("id")).equals(opt)) lines.add(((Map<?, ?>) o).get("label") + ": " + ans);
            }
            String title = String.valueOf(p.get(0).get("title"));
            String wie = werName.equals(String.valueOf(a.get("name"))) ? werName : werName + " (abgestimmt von " + a.get("name") + ")";
            mail.notifyMeister("Termin: " + wie + " hat abgestimmt", "Abstimmung „" + title + "“\n\n" + String.join("\n", lines));
            // Alle Stimmen da? (jede Person hat zu jedem Termin abgestimmt) -> einmal melden
            var now = full(p.get(0));
            int need = ((List<?>) now.get("voters")).size() * ((List<?>) now.get("options")).size();
            long have = ((List<?>) now.get("votes")).stream().filter(v -> ((List<?>) now.get("voters")).stream()
                    .anyMatch(x -> Objects.equals(String.valueOf(((Map<?, ?>) x).get("id")), String.valueOf(((Map<?, ?>) v).get("account"))))).count();
            if (need > 0 && have >= need && Objects.equals(String.valueOf(p.get(0).get("allnotified")), "0")) {
                db.exec("UPDATE poll SET allnotified=1 WHERE id=?", p.get(0).get("id"));
                mail.notifyMeister("Termin: alle haben abgestimmt", "Bei „" + title + "“ haben jetzt alle abgestimmt.\n\n" + summary(now));
            }
            ctx.json(full(p.get(0)));
        });
    }

    /** Kalendereinladung (RFC 5545); Zeit in der Zeitzone des Servers (TZ), als UTC geschrieben. */
    /**
     * Kalendereinladung (RFC 5545). Mit ORGANIZER und ATTENDEE erkennen Gmail, Outlook und Apple Mail sie als Einladung mit
     * „Zum Kalender hinzufügen“; ohne Absender (organizer leer) wird es eine schlichte Kalenderdatei (METHOD:PUBLISH).
     * Erinnerung einen Tag und zwei Stunden vorher.
     */
    static String ics(String uid, String title, String note, java.time.LocalDateTime start, double hours, String organizer, String name, String attendee) {
        var z = java.time.ZoneId.of(System.getenv().getOrDefault("TZ", "Europe/Vienna").isBlank() ? "Europe/Vienna" : System.getenv().getOrDefault("TZ", "Europe/Vienna"));
        var f = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'");
        var s = start.atZone(z).withZoneSameInstant(java.time.ZoneOffset.UTC);
        var e = s.plusMinutes(Math.round(hours * 60));
        var now = java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC);
        boolean einladung = organizer != null && !organizer.isBlank() && attendee != null && !attendee.isBlank();
        List<String> l = new ArrayList<>(List.of("BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//Heldenhort//DE", "CALSCALE:GREGORIAN",
                "METHOD:" + (einladung ? "REQUEST" : "PUBLISH"), "BEGIN:VEVENT",
                "UID:" + uid + "@heldenhort", "SEQUENCE:0", "DTSTAMP:" + now.format(f), "DTSTART:" + s.format(f), "DTEND:" + e.format(f),
                "SUMMARY:" + escIcs(title), "DESCRIPTION:" + escIcs(note.isEmpty() ? "Spielrunde (Heldenhort)" : note), "STATUS:CONFIRMED", "TRANSP:OPAQUE"));
        if (einladung) {
            l.add("ORGANIZER;CN=Heldenhort:mailto:" + organizer);
            l.add("ATTENDEE;CN=" + escIcs(name == null ? attendee : name).replace(":", "") + ";ROLE=REQ-PARTICIPANT;PARTSTAT=NEEDS-ACTION;RSVP=FALSE:mailto:" + attendee);
        }
        for (String vorher : List.of("-P1D", "-PT2H"))
            l.addAll(List.of("BEGIN:VALARM", "ACTION:DISPLAY", "DESCRIPTION:" + escIcs(title), "TRIGGER:" + vorher, "END:VALARM"));
        l.addAll(List.of("END:VEVENT", "END:VCALENDAR", ""));
        StringBuilder out = new StringBuilder();
        for (String z1 : l) out.append(falte(z1)).append("\r\n");
        return out.toString().replaceAll("(\r\n)+$", "\r\n");
    }

    /** Zeilen über 75 Byte falten (RFC 5545 3.1). */
    static String falte(String z) {
        StringBuilder sb = new StringBuilder();
        int bytes = 0;
        for (int i = 0; i < z.length(); ) {
            int cp = z.codePointAt(i), n = new String(Character.toChars(cp)).getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            if (bytes + n > 74) { sb.append("\r\n "); bytes = 1; }
            sb.appendCodePoint(cp); bytes += n; i += Character.charCount(cp);
        }
        return sb.toString();
    }

    static String escIcs(String s) { return s.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,").replace("\n", "\\n"); }

    static String summary(Map<String, Object> f) {
        StringBuilder sb = new StringBuilder();
        for (Object o : (List<?>) f.get("options")) {
            Map<?, ?> opt = (Map<?, ?>) o;
            int ja = 0, viel = 0, nein = 0;
            for (Object v : (List<?>) f.get("votes")) {
                Map<?, ?> vm = (Map<?, ?>) v;
                if (!Objects.equals(String.valueOf(vm.get("option")), String.valueOf(opt.get("id")))) continue;
                switch (String.valueOf(vm.get("answer"))) { case "ja" -> ja++; case "vielleicht" -> viel++; default -> nein++; }
            }
            sb.append(opt.get("label")).append(": ").append(ja).append(" ja, ").append(viel).append(" vielleicht, ").append(nein).append(" nein\n");
        }
        return sb.toString();
    }
}
