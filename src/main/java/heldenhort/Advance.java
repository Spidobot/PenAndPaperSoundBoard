package heldenhort;

import io.javalin.Javalin;
import io.javalin.http.NotFoundResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * AP und Spezielle Erfahrungen (SE) vergeben, und Steigern durch die Spieler.
 *
 *  - Der Meister merkt AP/SE je Held vor (für alle Helden einer Session oder einzeln) und sendet sie gesammelt aus.
 *    Erst dann werden AP in die Helden-XML geschrieben und SE dem Helden gutgeschrieben.
 *  - Spieler steigern Talente und Eigenschaften selbst. Kosten nach der Steigerungskostentabelle (SKT) von DSA 4.1;
 *    die Spalte eines Talents kommt aus der helden.jar. Eine SE macht die Steigerung eine Spalte billiger und wird verbraucht.
 *  - Wird eine Fertigkeit seit dem letzten Aussenden öfter als X-mal gesteigert (Konfiguration), braucht es eine
 *    Meisterfreigabe; nach der Freigabe führt der Server die Steigerung aus.
 *
 * Die Helden-XML wird direkt geändert (Wert, freie AP, Ereignis wie in der Helden-Software) und danach zur Sicherheit
 * mit der helden.jar probegeladen.
 */
public final class Advance {
    /** SKT DSA 4.1, Spalten A–H; Index 0 = Aktivierung, Index n = Kosten für Wert n. Aus der helden.jar übernommen. */
    static final int[][] SKT = {
        {1, 1, 2, 3, 4, 6, 7, 8, 10, 11, 13, 14, 16, 17, 19, 21, 22, 24, 26, 27, 29, 31, 33, 34, 36, 38, 40, 42, 44, 45, 47, 50},
        {2, 2, 4, 6, 8, 11, 14, 17, 19, 22, 25, 28, 32, 35, 38, 41, 45, 48, 51, 55, 58, 62, 65, 69, 73, 76, 80, 84, 87, 91, 95, 100},
        {3, 2, 6, 9, 13, 17, 21, 25, 29, 34, 38, 43, 47, 51, 55, 60, 65, 70, 75, 80, 85, 95, 100, 105, 110, 115, 120, 125, 130, 135, 140, 150},
        {4, 3, 7, 12, 17, 22, 27, 33, 39, 45, 50, 55, 65, 70, 75, 85, 90, 95, 105, 110, 115, 125, 130, 140, 145, 150, 160, 165, 170, 180, 190, 200},
        {5, 4, 9, 15, 21, 28, 34, 41, 48, 55, 65, 70, 80, 85, 95, 105, 110, 120, 130, 135, 145, 155, 165, 170, 180, 190, 200, 210, 220, 230, 240, 250},
        {8, 6, 14, 22, 32, 41, 50, 60, 75, 85, 95, 105, 120, 130, 140, 155, 165, 180, 195, 210, 220, 230, 250, 260, 270, 290, 300, 310, 330, 340, 350, 375},
        {10, 8, 18, 30, 42, 55, 70, 85, 95, 110, 125, 140, 160, 175, 190, 210, 220, 240, 260, 270, 290, 310, 330, 340, 360, 380, 400, 420, 440, 460, 480, 500},
        {20, 16, 35, 60, 85, 110, 140, 165, 195, 220, 250, 280, 320, 350, 380, 410, 450, 480, 510, 550, 580, 620, 650, 690, 720, 760, 800, 830, 870, 910, 950, 1000}};
    static final String COLS = "ABCDEFGH";
    static final List<String> EIG = List.of("Mut", "Klugheit", "Intuition", "Charisma", "Fingerfertigkeit", "Gewandtheit", "Konstitution", "Körperkraft");
    static final Map<String, String> SHORT = Map.of("MU", "Mut", "KL", "Klugheit", "IN", "Intuition", "CH", "Charisma", "FF", "Fingerfertigkeit", "GE", "Gewandtheit", "KO", "Konstitution", "KK", "Körperkraft");

    private final Db db;
    private final Heroes heroes;
    private final HeldenJar jar;
    private final Accounts accounts;
    private final Mail mail;

    public Advance(Db db, Heroes heroes, HeldenJar jar, Accounts accounts, Mail mail) {
        this.db = db; this.heroes = heroes; this.jar = jar; this.accounts = accounts; this.mail = mail;
        db.exec("CREATE TABLE IF NOT EXISTS award(id INTEGER PRIMARY KEY AUTOINCREMENT, file TEXT, ap INTEGER, se TEXT, note TEXT, created TEXT, sent TEXT)");
        db.exec("CREATE TABLE IF NOT EXISTS se(id INTEGER PRIMARY KEY AUTOINCREMENT, file TEXT, target TEXT, granted TEXT, used TEXT)");
        db.exec("CREATE TABLE IF NOT EXISTS steig(id INTEGER PRIMARY KEY AUTOINCREMENT, file TEXT, kind TEXT, target TEXT, von INTEGER, nach INTEGER, cost INTEGER, se INTEGER, at TEXT)");
        try { db.exec("ALTER TABLE approval_item ADD COLUMN payload TEXT"); } catch (RuntimeException alreadyThere) { }
    }

    // ------------------------------------------------------------------ Kosten

    /** Spalte (A–H) eines Talents laut helden.jar. */
    synchronized String column(String file, String talent) throws Exception {
        String c = columns(file, List.of(talent)).get(talent);
        if (c == null) throw new IllegalArgumentException("Steigerungsspalte für " + talent + " unbekannt");
        return c;
    }

    /** Spalten mehrerer Talente in einem Ladevorgang. Unbekannte fehlen in der Antwort. */
    synchronized Map<String, String> columns(String file, Collection<String> talente) throws Exception {
        Map<String, String> out = new HashMap<>();
        ClassLoader l = jar.loader();
        ClassLoader old = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(l);
        try {
            Object xp = l.loadClass("helden.framework.held.persistenz.XMLPersistierer").getConstructor().newInstance();
            Object h = ((List<?>) Creation.call(xp, "ladeHelden", heroes.file(file).toFile())).get(0);
            Object hw = l.loadClass("helden.plugin.datenplugin.impl.DatenPluginHeldenWerkzeugImpl").getConstructor(l.loadClass("helden.framework.held.H")).newInstance(h);
            for (String talent : talente) {
                try { out.put(talent, String.valueOf(Creation.call(Creation.call(Creation.call(hw, "getTalent", talent), "getTalent"), "getSteigernKategorie"))); }
                catch (Exception notActive) { }
            }
            return out;
        } finally { Thread.currentThread().setContextClassLoader(old); }
    }

    static int cost(String col, int to, boolean se) {
        int c = COLS.indexOf(col);
        if (c < 0) throw new IllegalArgumentException("Unbekannte Spalte " + col);
        if (se) c = Math.max(0, c - 1);
        if (to < 1 || to >= SKT[c].length) throw new IllegalArgumentException("Wert außerhalb der Tabelle");
        return SKT[c][to];
    }

    // ------------------------------------------------------------------ Zustand eines Helden

    Map<String, Object> state(String file) throws Exception {
        Document d = read(file);
        Element held = (Element) d.getElementsByTagName("held").item(0);
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("file", file);
        s.put("name", held.getAttribute("name"));
        s.put("ap", Heroes.num(Heroes.attr(Heroes.first(held, "abenteuerpunkte"), "value")));
        s.put("freieAp", Heroes.num(Heroes.attr(Heroes.first(held, "freieabenteuerpunkte"), "value")));
        Map<String, Integer> eigVal = new HashMap<>();
        List<Map<String, Object>> eig = new ArrayList<>();
        for (Element e : Heroes.all(held, "eigenschaft")) {
            String n = e.getAttribute("name");
            if (!EIG.contains(n)) continue;
            int base = Heroes.num(e.getAttribute("value")), mod = Heroes.num(e.getAttribute("mod")), start = Heroes.num(e.getAttribute("startwert"));
            int max = (int) Math.ceil(start * 1.5) ; // DSA 4.1: höchstens das Anderthalbfache des Startwerts
            eigVal.put(n, base + mod);
            eig.add(new LinkedHashMap<>(Map.of("name", n, "value", base + mod, "base", base, "max", max + mod, "cost", base + 1 <= max ? cost("H", Math.min(base + 1, 31), false) : -1)));
        }
        s.put("eigenschaften", eig);
        List<Map<String, Object>> tal = new ArrayList<>();
        for (Element t : Heroes.all(held, "talent")) {
            if (!t.hasAttribute("value")) continue;
            String n = t.getAttribute("name"), probe = t.getAttribute("probe");
            int v = Heroes.num(t.getAttribute("value"));
            int max = 0;
            for (String k : probe.replaceAll("[()\\s]", "").split("/")) max = Math.max(max, eigVal.getOrDefault(SHORT.getOrDefault(k, ""), 0));
            Map<String, Object> m = new LinkedHashMap<>(Map.of("name", n, "value", v, "probe", probe.trim()));
            if (max > 0) m.put("max", max + 3); // DSA 4.1: höchster Probenwert + 3
            tal.add(m);
        }
        Map<String, String> cols = columns(file, tal.stream().map(t -> (String) t.get("name")).toList());
        Set<String> seTargets = new HashSet<>();
        for (var r : db.query("SELECT DISTINCT target FROM se WHERE file=? AND used IS NULL", file)) seTargets.add(String.valueOf(r.get("target")));
        for (Map<String, Object> t : tal) {
            String c = cols.get(t.get("name"));
            int v = (Integer) t.get("value");
            if (c == null || v + 1 >= SKT[0].length) continue;
            t.put("spalte", c);
            t.put("cost", cost(c, v + 1, false));
            if (seTargets.contains(t.get("name"))) t.put("costSe", cost(c, v + 1, true));
        }
        s.put("talente", tal);
        List<Map<String, Object>> se = new ArrayList<>();
        for (var r : db.query("SELECT target, COUNT(*) n FROM se WHERE file=? AND used IS NULL GROUP BY target ORDER BY target", file)) se.add(r);
        s.put("se", se);
        s.put("limit", Integer.parseInt(db.get("steigerLimit", "0")));
        s.put("seitAussenden", counts(file));
        s.put("offen", db.query("SELECT i.id, i.text, i.status FROM approval_item i JOIN approval a ON a.id=i.approval WHERE i.kind='STEIG' AND i.payload LIKE ? AND i.status='offen'", "%\"file\":\"" + file.replace("\"", "") + "\"%"));
        return s;
    }

    /** Wie oft jede Fertigkeit seit dem letzten Aussenden gesteigert wurde. */
    Map<String, Integer> counts(String file) {
        var last = db.query("SELECT MAX(sent) s FROM award WHERE file=? AND sent IS NOT NULL", file);
        String since = last.isEmpty() || last.get(0).get("s") == null ? "" : String.valueOf(last.get(0).get("s"));
        Map<String, Integer> m = new HashMap<>();
        for (var r : db.query("SELECT target, COUNT(*) n FROM steig WHERE file=? AND at>=? GROUP BY target", file, since))
            m.put(String.valueOf(r.get("target")), ((Number) r.get("n")).intValue());
        return m;
    }

    // ------------------------------------------------------------------ Steigern

    /** Eine Stufe steigern. force = Meister hat freigegeben (Grenze ignorieren). */
    synchronized Map<String, Object> steigern(String file, String kind, String target, boolean useSe, boolean force, Object account) throws Exception {
        Document d = read(file);
        Element held = (Element) d.getElementsByTagName("held").item(0);
        Element el = null;
        String tag = kind.equals("eigenschaft") ? "eigenschaft" : "talent";
        for (Element e : Heroes.all(held, tag)) if (e.getAttribute("name").equals(target) && (!tag.equals("talent") || e.hasAttribute("value"))) el = e;
        if (el == null) throw new IllegalArgumentException(target + " gibt es bei diesem Helden nicht");
        @SuppressWarnings("unchecked") Map<String, Object> st = state(file);
        int von, nach, max;
        String col;
        if (tag.equals("eigenschaft")) {
            if (!EIG.contains(target)) throw new IllegalArgumentException("Nur MU, KL, IN, CH, FF, GE, KO, KK");
            von = Heroes.num(el.getAttribute("value")); nach = von + 1;
            max = (int) Math.ceil(Heroes.num(el.getAttribute("startwert")) * 1.5);
            col = "H";
            if (useSe) throw new IllegalArgumentException("Spezielle Erfahrungen gelten für Talente");
        } else {
            von = Heroes.num(el.getAttribute("value")); nach = von + 1;
            max = ((List<Map<String, Object>>) st.get("talente")).stream().filter(t -> t.get("name").equals(target)).map(t -> (Integer) t.getOrDefault("max", 99)).findFirst().orElse(99);
            col = column(file, target);
        }
        if (nach > max) throw new IllegalArgumentException(target + " ist schon am Maximum (" + max + ")");
        Long seId = null;
        if (useSe) {
            var r = db.query("SELECT id FROM se WHERE file=? AND target=? AND used IS NULL LIMIT 1", file, target);
            if (r.isEmpty()) throw new IllegalArgumentException("Keine Spezielle Erfahrung für " + target + " vorhanden");
            seId = ((Number) r.get(0).get("id")).longValue();
        }
        int cost = cost(col, nach, useSe);
        int frei = (Integer) st.get("freieAp");
        if (cost > frei) throw new IllegalArgumentException("Zu wenig AP: " + target + " auf " + nach + " kostet " + cost + " AP, frei sind " + frei);

        int limit = (Integer) st.get("limit");
        int schon = ((Map<String, Integer>) st.get("seitAussenden")).getOrDefault(target, 0);
        if (!force && limit > 0 && schon >= limit) {
            // Freigabe anfragen statt steigern
            var acc = accounts.get(((Number) account).longValue());
            long aid = db.exec("INSERT INTO approval(hero,player,created,account) VALUES(?,?,?,?)", st.get("name"), acc.get("name"), App.now(), account);
            String text = target + " von " + von + " auf " + nach + " (" + (schon + 1) + ". Steigerung seit dem letzten Aussenden, " + cost + " AP" + (useSe ? ", mit SE" : "") + ")";
            String payload = App.JSON.writeValueAsString(Map.of("file", file, "kind", kind, "target", target, "se", useSe, "account", account));
            db.exec("INSERT INTO approval_item(approval,kind,text,status,comment,payload) VALUES(?,?,?,?,?,?)", aid, "STEIG", text, "offen", "", payload);
            mail.notifyMeister("Steigerung braucht Freigabe: " + st.get("name"), acc.get("name") + " möchte steigern: " + text + "\n\nGrenze laut Konfiguration: " + limit + "x.\nFreigeben unter „Freigaben“.");
            return Map.of("pending", true, "text", text);
        }

        el.setAttribute("value", String.valueOf(nach));
        if (tag.equals("eigenschaft") && el.hasAttribute("startwert") && Heroes.num(el.getAttribute("startwert")) > nach) el.setAttribute("startwert", String.valueOf(nach));
        setFreieAp(d, held, frei - cost);
        ereignis(d, held, tag.equals("eigenschaft") ? "EIGENSCHAFTEN" : "TALENT", target + ": " + (tag.equals("eigenschaft") ? nach + Heroes.num(el.getAttribute("mod")) : nach),
                von, nach, cost + " AP" + (useSe ? " (Spezielle Erfahrung)" : ""));
        write(file, d);
        if (seId != null) db.exec("UPDATE se SET used=? WHERE id=?", App.now(), seId);
        db.exec("INSERT INTO steig(file,kind,target,von,nach,cost,se,at) VALUES(?,?,?,?,?,?,?,?)", file, kind, target, von, nach, cost, useSe ? 1 : 0, App.now());
        return Map.of("ok", true, "cost", cost);
    }

    // ------------------------------------------------------------------ Aussenden

    synchronized List<String> aussenden() throws Exception {
        List<String> done = new ArrayList<>();
        String now = App.now();
        for (var a : db.query("SELECT * FROM award WHERE sent IS NULL ORDER BY id")) {
            String file = String.valueOf(a.get("file"));
            int ap = ((Number) a.get("ap")).intValue();
            List<?> se = App.JSON.readValue(String.valueOf(a.get("se")), List.class);
            Document d = read(file);
            Element held = (Element) d.getElementsByTagName("held").item(0);
            if (ap != 0) {
                Element apE = Heroes.first(held, "abenteuerpunkte");
                if (apE == null) { apE = d.createElement("abenteuerpunkte"); held.appendChild(apE); }
                int alt = Heroes.num(apE.getAttribute("value"));
                apE.setAttribute("value", String.valueOf(alt + ap));
                setFreieAp(d, held, Heroes.num(Heroes.attr(Heroes.first(held, "freieabenteuerpunkte"), "value")) + ap);
                ereignis(d, held, "AP", App.str(a.get("note")).isEmpty() ? "Abenteuerpunkte" : App.str(a.get("note")), alt, alt + ap, ap + " AP");
                write(file, d);
            }
            for (Object t : se) db.exec("INSERT INTO se(file,target,granted) VALUES(?,?,?)", file, App.str(t), now);
            db.exec("UPDATE award SET sent=? WHERE id=?", now, a.get("id"));
            String who = accounts.email(db.query("SELECT account FROM hero WHERE file=?", file).stream().findFirst().map(r -> r.get("account")).orElse(null));
            mail.notify(who, "Neue Abenteuerpunkte für " + held.getAttribute("name"),
                    "Der Meister hat " + held.getAttribute("name") + " " + ap + " AP gegeben" + (se.isEmpty() ? "" : " und Spezielle Erfahrungen in " + String.join(", ", se.stream().map(String::valueOf).toList()))
                            + ".\n\nSteigern kannst du unter „Meine Helden“.");
            done.add(held.getAttribute("name") + ": " + ap + " AP" + (se.isEmpty() ? "" : ", SE " + se));
        }
        return done;
    }

    // ------------------------------------------------------------------ XML

    private Document read(String file) throws Exception {
        var f = javax.xml.parsers.DocumentBuilderFactory.newInstance();
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        return f.newDocumentBuilder().parse(heroes.file(file).toFile());
    }

    /** Schreibt atomar und lässt die helden.jar die Datei probehalber laden; scheitert das, bleibt die alte Datei. */
    private void write(String file, Document d) throws Exception {
        Path p = heroes.file(file), tmp = p.resolveSibling(p.getFileName() + ".neu");
        var t = javax.xml.transform.TransformerFactory.newInstance().newTransformer();
        t.setOutputProperty(javax.xml.transform.OutputKeys.ENCODING, "UTF-8");
        try (var out = Files.newOutputStream(tmp)) { t.transform(new javax.xml.transform.dom.DOMSource(d), new javax.xml.transform.stream.StreamResult(out)); }
        ClassLoader old = Thread.currentThread().getContextClassLoader();
        try {
            ClassLoader l = jar.loader();
            Thread.currentThread().setContextClassLoader(l);
            Object xp = l.loadClass("helden.framework.held.persistenz.XMLPersistierer").getConstructor().newInstance();
            if (((List<?>) Creation.call(xp, "ladeHelden", tmp.toFile())).isEmpty()) throw new IllegalStateException("helden.jar liest die geänderte Datei nicht");
        } catch (Exception e) { Files.deleteIfExists(tmp); throw new IllegalStateException("Änderung verworfen: " + e.getMessage()); }
        finally { Thread.currentThread().setContextClassLoader(old); }
        Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private static void setFreieAp(Document d, Element held, int v) {
        Element f = Heroes.first(held, "freieabenteuerpunkte");
        if (f == null) { f = d.createElement("freieabenteuerpunkte"); held.appendChild(f); }
        f.setAttribute("value", String.valueOf(v));
    }

    /** Ereignis wie die Helden-Software es schreibt (Protokoll im Helden). */
    private static void ereignis(Document d, Element held, String text, String obj, int alt, int neu, String kommentar) {
        Element list = Heroes.first(held, "ereignisse");
        if (list == null) { list = d.createElement("ereignisse"); held.appendChild(list); }
        Element e = d.createElement("ereignis");
        e.setAttribute("Alt", String.valueOf(alt));
        e.setAttribute("Neu", String.valueOf(neu));
        e.setAttribute("kommentar", kommentar);
        e.setAttribute("obj", obj);
        e.setAttribute("text", text);
        e.setAttribute("time", String.valueOf(System.currentTimeMillis()));
        e.setAttribute("version", "Heldenhort");
        list.appendChild(e);
    }

    // ------------------------------------------------------------------ Routen

    public void routes(Javalin m, Javalin s) {
        // Meister: vormerken, ansehen, löschen, aussenden
        m.get("/api/awards", ctx -> ctx.json(db.query("SELECT a.*, h.player FROM award a LEFT JOIN hero h ON h.file=a.file WHERE a.sent IS NULL ORDER BY a.id")));
        m.post("/api/awards", ctx -> {
            Map<?, ?> b = ctx.bodyAsClass(Map.class);
            List<?> list = (List<?>) b.get("awards");
            if (list == null || list.isEmpty()) throw new IllegalArgumentException("Nichts vorzumerken");
            for (Object o : list) {
                Map<?, ?> a = (Map<?, ?>) o;
                String file = App.str(a.get("file"));
                heroes.file(file);
                int ap = a.get("ap") instanceof Number n ? n.intValue() : 0;
                if (ap < 0 || ap > 100000) throw new IllegalArgumentException("AP ungültig");
                List<?> se = a.get("se") instanceof List<?> l ? l : List.of();
                if (ap == 0 && se.isEmpty()) continue;
                db.exec("INSERT INTO award(file,ap,se,note,created) VALUES(?,?,?,?,?)", file, ap, App.JSON.writeValueAsString(se), App.str(b.get("note")), App.now());
            }
            ctx.json(Map.of("ok", true));
        });
        m.delete("/api/awards/{id}", ctx -> { db.exec("DELETE FROM award WHERE id=? AND sent IS NULL", ctx.pathParam("id")); ctx.json(Map.of("ok", true)); });
        m.post("/api/awards/send", ctx -> ctx.json(Map.of("done", aussenden())));
        m.get("/api/advance/{file}", ctx -> ctx.json(state(ctx.pathParam("file"))));

        // Spieler: eigener Held
        s.get("/api/myheroes/{file}/advance", ctx -> ctx.json(state(own(ctx.pathParam("file"), ctx))));
        s.post("/api/myheroes/{file}/steigern", ctx -> {
            String file = own(ctx.pathParam("file"), ctx);
            if (!"übernommen".equals(db.query("SELECT status FROM hero WHERE file=?", file).get(0).get("status")))
                throw new IllegalStateException("Steigern geht erst, wenn der Meister den Helden übernommen hat");
            Map<?, ?> b = ctx.bodyAsClass(Map.class);
            String kind = App.str(b.get("kind"));
            if (!List.of("talent", "eigenschaft").contains(kind)) throw new IllegalArgumentException("Art unbekannt");
            ctx.json(steigern(file, kind, App.str(b.get("target")), Boolean.TRUE.equals(b.get("se")), false, accounts.current(ctx).get("id")));
        });
    }

    /** Wird bei Freigabe einer Steigerung aufgerufen. */
    String approved(String payload) {
        try {
            Map<?, ?> p = App.JSON.readValue(payload, Map.class);
            var r = steigern(App.str(p.get("file")), App.str(p.get("kind")), App.str(p.get("target")), Boolean.TRUE.equals(p.get("se")), true, p.get("account"));
            return "ausgeführt (" + r.get("cost") + " AP)";
        } catch (Exception e) { return "nicht ausgeführt: " + e.getMessage(); }
    }

    private String own(String file, io.javalin.http.Context ctx) {
        Object me = accounts.current(ctx).get("id");
        var r = db.query("SELECT file FROM hero WHERE file=? AND account=?", file, me);
        if (r.isEmpty()) throw new NotFoundResponse("Held unbekannt");
        heroes.file(file);
        return file;
    }

    static String utf8(byte[] b) { return new String(b, StandardCharsets.UTF_8); }
}
