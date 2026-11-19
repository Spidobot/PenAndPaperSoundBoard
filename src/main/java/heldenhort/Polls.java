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
        db.exec("CREATE TABLE IF NOT EXISTS poll_vote(poll INTEGER, option INTEGER, account INTEGER, answer TEXT, at TEXT, PRIMARY KEY(option, account))");
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
            if (title.isEmpty()) throw new IllegalArgumentException("Titel fehlt");
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
        meister.delete("/api/polls/{id}", ctx -> {
            String id = ctx.pathParam("id");
            db.exec("DELETE FROM poll_vote WHERE poll=?", id);
            db.exec("DELETE FROM poll_option WHERE poll=?", id);
            db.exec("DELETE FROM poll WHERE id=?", id);
            ctx.json(Map.of("ok", true));
        });

        // --- Spieler: offene Abstimmungen, bei denen das Konto abstimmen soll
        spieler.get("/api/polls", ctx -> {
            Object me = accounts.current(ctx).get("id");
            List<Map<String, Object>> out = new ArrayList<>();
            for (var p : db.query("SELECT * FROM poll WHERE closed=0 ORDER BY id DESC")) {
                var f = full(p);
                if (((List<?>) f.get("voters")).stream().anyMatch(v -> Objects.equals(String.valueOf(((Map<?, ?>) v).get("id")), String.valueOf(me)))) out.add(f);
            }
            ctx.json(out);
        });
        spieler.post("/api/polls/{id}/vote", ctx -> {
            var a = accounts.current(ctx);
            var p = db.query("SELECT * FROM poll WHERE id=? AND closed=0", ctx.pathParam("id"));
            if (p.isEmpty()) throw new NotFoundResponse("Abstimmung unbekannt oder geschlossen");
            var f = full(p.get(0));
            if (((List<?>) f.get("voters")).stream().noneMatch(v -> Objects.equals(String.valueOf(((Map<?, ?>) v).get("id")), String.valueOf(a.get("id")))))
                throw new IllegalArgumentException("Du bist bei dieser Abstimmung nicht dabei");
            Map<?, ?> b = ctx.bodyAsClass(Map.class);
            Map<?, ?> answers = b.get("answers") instanceof Map<?, ?> m ? m : Map.of();
            Set<String> optionIds = new HashSet<>();
            for (Object o : (List<?>) f.get("options")) optionIds.add(String.valueOf(((Map<?, ?>) o).get("id")));
            List<String> lines = new ArrayList<>();
            for (var e : answers.entrySet()) {
                String opt = String.valueOf(e.getKey()), ans = App.str(e.getValue());
                if (!optionIds.contains(opt) || !ANSWERS.contains(ans)) throw new IllegalArgumentException("Ungültige Stimme");
                db.exec("INSERT INTO poll_vote(poll,option,account,answer,at) VALUES(?,?,?,?,?) ON CONFLICT(option,account) DO UPDATE SET answer=excluded.answer, at=excluded.at",
                        p.get(0).get("id"), Long.parseLong(opt), a.get("id"), ans, App.now());
                for (Object o : (List<?>) f.get("options")) if (String.valueOf(((Map<?, ?>) o).get("id")).equals(opt)) lines.add(((Map<?, ?>) o).get("label") + ": " + ans);
            }
            String title = String.valueOf(p.get(0).get("title"));
            mail.notifyMeister("Termin: " + a.get("name") + " hat abgestimmt", "Abstimmung „" + title + "“\n\n" + String.join("\n", lines));
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
