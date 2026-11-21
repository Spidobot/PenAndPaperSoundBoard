package heldenhort;

import io.javalin.Javalin;
import java.nio.file.*;
import java.util.*;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/**
 * Ausrüstung, Laden und NSC-Namen – alles aus der helden.jar:
 *  - Katalog (Name, Preis in Kreuzern, Gewicht in Unzen, Art, Quelle) über das Daten-Plugin der Jar.
 *  - Gegenstände und Ausrüstungsplätze werden ins Helden-XML geschrieben (Format wie die Helden-Software), danach liest die Jar
 *    den Helden einmal ein und speichert ihn neu (ergänzt z. B. Fernkampfwaffe/Talent, verwirft Unbekanntes).
 *  - Laden: Der Meister öffnet ihn (mit Preisfaktor), Spieler kaufen mit dem Geld aus der Geldbörse (Mittelreich: D/S/H/K).
 *  - NSC-Namen: Namengeneratoren.xml/Namenlisten.xml der Jar.
 */
public class Ausruestung {
    static final Map<String, Integer> MUENZWERT = new LinkedHashMap<>(); // in Kreuzern
    static { MUENZWERT.put("Dukat", 1000); MUENZWERT.put("Silbertaler", 100); MUENZWERT.put("Heller", 10); MUENZWERT.put("Kreuzer", 1); }
    static final List<String> SLOTS = List.of("nkwaffe1", "nkwaffe2", "fkwaffe1", "schild1", "ruestung1", "ruestung2", "ruestung3", "ruestung4", "ruestung5", "ruestung6");

    private final Db db;
    private final Heroes heroes;
    private final HeldenJar jar;
    private final Advance adv;
    private final Regeln regeln;
    private final Accounts accounts;

    public Ausruestung(Db db, Heroes heroes, HeldenJar jar, Advance adv, Regeln regeln, Accounts accounts) {
        this.db = db; this.heroes = heroes; this.jar = jar; this.adv = adv; this.regeln = regeln; this.accounts = accounts;
        // Warenkorb: Spieler sammeln, Meister prüft (Preise je Position änderbar), erst die Freigabe bucht Ware und Geld
        db.exec("CREATE TABLE IF NOT EXISTS warenkorb(id INTEGER PRIMARY KEY AUTOINCREMENT, file TEXT, account INTEGER, status TEXT, created TEXT, gesendet TEXT, entschieden TEXT, kommentar TEXT)");
        db.exec("CREATE TABLE IF NOT EXISTS warenkorb_pos(id INTEGER PRIMARY KEY AUTOINCREMENT, korb INTEGER, name TEXT, anzahl INTEGER, preis INTEGER, meisterpreis INTEGER)");
    }

    // ------------------------------------------------------------------ Katalog

    private volatile List<Map<String, Object>> katalog;
    private volatile Map<String, Map<String, Object>> katalogNachName;

    /** Alle Gegenstände der helden.jar. Braucht einen geladenen Helden (die Jar liefert den Katalog über einen Helden). */
    List<Map<String, Object>> katalog() throws Exception {
        if (katalog != null) return katalog;
        Path irgendein = null;
        try (var s = Files.list(heroes.dir())) { irgendein = s.filter(p -> p.toString().endsWith(".xml")).findFirst().orElse(null); }
        if (irgendein == null) throw new IllegalStateException("Für den Ausrüstungskatalog braucht die helden.jar einen Helden. Bitte erst einen Helden anlegen oder hochladen.");
        synchronized (jar) {
            ClassLoader l = jar.loader(), old = Thread.currentThread().getContextClassLoader();
            Thread.currentThread().setContextClassLoader(l);
            try {
                l.loadClass("helden.framework.settings.Setting").getMethod("initAll").invoke(null);
                Object xp = l.loadClass("helden.framework.held.persistenz.XMLPersistierer").getConstructor().newInstance();
                Object h = ((List<?>) Creation.call(xp, "ladeHelden", irgendein.toFile())).get(0);
                Object hw = l.loadClass("helden.plugin.datenplugin.impl.DatenPluginHeldenWerkzeugImpl").getConstructor(l.loadClass("helden.framework.held.H")).newInstance(h);
                List<Map<String, Object>> out = new ArrayList<>();
                Set<String> seen = new HashSet<>();
                for (Object x : (List<?>) Creation.call(hw, "getAlleGegenstaende", false)) {
                    String name = String.valueOf(Creation.call(x, "getName"));
                    if (!seen.add(name)) continue;
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("name", name);
                    m.put("preis", Creation.call(x, "getPreis"));      // Kreuzer
                    m.put("gewicht", Creation.call(x, "getGewicht"));  // Unzen
                    Object o = Creation.call(x, "getObject");
                    m.put("art", art(o));
                    m.put("quelle", quelle(o));
                    out.add(m);
                }
                out.sort(Comparator.comparing(m -> String.valueOf(m.get("name")), String.CASE_INSENSITIVE_ORDER));
                Map<String, Map<String, Object>> byName = new HashMap<>();
                for (var m : out) byName.put(String.valueOf(m.get("name")), m);
                katalogNachName = byName;
                return katalog = out;
            } finally { Thread.currentThread().setContextClassLoader(old); }
        }
    }

    /** Art(en) des Gegenstands laut Jar (z. B. „Nahkampfwaffe“, „Fernkampfwaffe“, „Rüstung“, „Schild“, „Gegenstand“). */
    private static String art(Object o) {
        for (Class<?> c = o.getClass(); c != null && c != Object.class; c = c.getSuperclass())
            for (var m : c.getDeclaredMethods())
                if (m.getParameterCount() == 0 && Vector.class.isAssignableFrom(m.getReturnType())) {
                    try { m.setAccessible(true); Object v = m.invoke(o); if (v instanceof Vector<?> vec && !vec.isEmpty()) return String.join(", ", vec.stream().map(String::valueOf).toList()); }
                    catch (Exception ignored) { }
                }
        return "Gegenstand";
    }

    /** Quelle laut Jar, z. B. „AA: 39“ (Aventurisches Arsenal S. 39). */
    private static String quelle(Object o) {
        for (Class<?> c = o.getClass(); c != null && c != Object.class; c = c.getSuperclass())
            for (var m : c.getDeclaredMethods())
                if (m.getParameterCount() == 0 && !m.getReturnType().isPrimitive() && m.getReturnType() != String.class && m.getReturnType() != Object.class) {
                    try { m.setAccessible(true); Object v = m.invoke(o); if (v != null && String.valueOf(v).matches("[A-ZÄÖÜa-z0-9 ]{1,12}: ?\\d+.*")) return String.valueOf(v); }
                    catch (Exception ignored) { }
                }
        return "";
    }

    Map<String, Object> artikel(String name) throws Exception {
        katalog();
        Map<String, Object> m = katalogNachName.get(name);
        if (m == null) throw new IllegalArgumentException("„" + name + "“ kennt die helden.jar nicht als Gegenstand. Bitte aus der Liste wählen.");
        return m;
    }

    // ------------------------------------------------------------------ Geld

    static int geldInKreuzer(Element held) {
        int sum = 0;
        Element b = Heroes.first(held, "geldboerse");
        if (b == null) return 0;
        for (Element m : Heroes.all(b, "muenze"))
            if ("Mittelreich".equals(m.getAttribute("waehrung")) && MUENZWERT.containsKey(m.getAttribute("name")))
                sum += Heroes.num(m.getAttribute("anzahl")) * MUENZWERT.get(m.getAttribute("name"));
        return sum;
    }

    /** Mittelreich-Münzen neu stückeln (andere Währungen bleiben, wie sie sind). */
    static void geldSetzen(Document d, Element held, int kreuzer) {
        Element b = Heroes.first(held, "geldboerse");
        if (b == null) { b = d.createElement("geldboerse"); held.appendChild(b); }
        for (Element m : Heroes.all(b, "muenze")) if ("Mittelreich".equals(m.getAttribute("waehrung")) && MUENZWERT.containsKey(m.getAttribute("name"))) b.removeChild(m);
        int rest = kreuzer;
        for (var en : MUENZWERT.entrySet()) {
            int n = rest / en.getValue();
            rest -= n * en.getValue();
            if (n <= 0) continue;
            Element m = d.createElement("muenze");
            m.setAttribute("anzahl", String.valueOf(n));
            m.setAttribute("name", en.getKey());
            m.setAttribute("waehrung", "Mittelreich");
            b.appendChild(m);
        }
    }

    /** „4 D 5 S“ statt 4500 Kreuzer. */
    static String geldText(int kreuzer) {
        if (kreuzer == 0) return "0 K";
        List<String> t = new ArrayList<>();
        int rest = kreuzer;
        for (var en : MUENZWERT.entrySet()) {
            int n = rest / en.getValue();
            rest -= n * en.getValue();
            if (n > 0) t.add(n + " " + en.getKey().charAt(0));
        }
        return String.join(" ", t);
    }

    // ------------------------------------------------------------------ Tragkraft (WdS S. 144)

    /** Tragkraft in Unzen: KK Stein × 40. */
    static int tragkraftUnzen(Element held) {
        for (Element e : Heroes.all(held, "eigenschaft"))
            if ("Körperkraft".equals(e.getAttribute("name"))) return (Heroes.num(e.getAttribute("value")) + Heroes.num(e.getAttribute("mod"))) * 40;
        return 0;
    }

    /**
     * Last in Unzen: alles im Gepäck (Gewichte laut Katalog der Jar), aber ohne die getragene Rüstung –
     * die behindert über ihre eigene BE, nicht als Traglast.
     */
    private double lastUnzen(Document d, Element held) {
        Map<String, Integer> getragen = new HashMap<>();
        for (Element a : kinder(liste(d, held, "ausrüstungen"), "heldenausruestung"))
            if (a.getAttribute("name").startsWith("ruestung") && "0".equals(a.getAttribute("set").isEmpty() ? "0" : a.getAttribute("set")))
                getragen.merge(a.getAttribute("ruestungsname"), 1, Integer::sum);
        double g = 0;
        for (Element e : kinder(liste(d, held, "gegenstände"), "gegenstand")) {
            if (!kinder(e, "Wesen").isEmpty()) continue;
            Map<String, Object> k = katalogNachName.getOrDefault(e.getAttribute("name"), Map.of());
            int n = Math.max(1, Heroes.num(e.getAttribute("anzahl"))) - getragen.getOrDefault(e.getAttribute("name"), 0);
            if (k.get("gewicht") instanceof Number w && n > 0) g += w.doubleValue() * n;
        }
        return g;
    }

    /** Behinderung durch Last: je angefangene 50 % über der Tragkraft 1 BE. */
    static int lastBe(double last, int tk) { return tk <= 0 || last <= tk ? 0 : (int) Math.ceil((last - tk) / (tk * 0.5)); }

    // ------------------------------------------------------------------ Lesen

    private static Element liste(Document d, Element held, String tag) {
        Element l = null;
        for (Node n = held.getFirstChild(); n != null; n = n.getNextSibling()) if (n instanceof Element e && e.getTagName().equals(tag)) l = e;
        if (l == null) { l = d.createElement(tag); held.appendChild(l); }
        return l;
    }

    private static List<Element> kinder(Element p, String tag) {
        List<Element> out = new ArrayList<>();
        for (Node n = p.getFirstChild(); n != null; n = n.getNextSibling()) if (n instanceof Element e && e.getTagName().equals(tag)) out.add(e);
        return out;
    }

    Map<String, Object> zustand(String file) throws Exception {
        Document d = adv.read(file);
        Element held = (Element) d.getElementsByTagName("held").item(0);
        katalog();
        List<Map<String, Object>> inv = new ArrayList<>();
        double gewicht = 0;
        for (Element g : kinder(liste(d, held, "gegenstände"), "gegenstand")) {
            if (!kinder(g, "Wesen").isEmpty()) continue; // Begleiter (Tier, Vertrauter …) sind kein Gepäck
            String n = g.getAttribute("name");
            int anz = Math.max(1, Heroes.num(g.getAttribute("anzahl")));
            Map<String, Object> k = katalogNachName.getOrDefault(n, Map.of());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", n);
            m.put("anzahl", anz);
            m.put("art", k.getOrDefault("art", "Gegenstand"));
            m.put("gewicht", k.get("gewicht"));
            m.put("preis", k.get("preis"));
            inv.add(m);
        }
        Map<String, String> plaetze = new LinkedHashMap<>();
        for (Element a : kinder(liste(d, held, "ausrüstungen"), "heldenausruestung")) {
            String slot = a.getAttribute("name");
            if (!SLOTS.contains(slot) || !"0".equals(a.getAttribute("set").isEmpty() ? "0" : a.getAttribute("set"))) continue;
            String w = !a.getAttribute("waffenname").isBlank() ? a.getAttribute("waffenname") : !a.getAttribute("ruestungsname").isBlank() ? a.getAttribute("ruestungsname") : a.getAttribute("schildname");
            plaetze.put(slot, w + (a.getAttribute("talent").isBlank() ? "" : " (" + a.getAttribute("talent") + ")"));
        }
        List<String> kampftalente = new ArrayList<>();
        Element tl = Heroes.first(held, "talentliste");
        if (tl != null) for (Element t : kinder(tl, "talent")) if (t.hasAttribute("value") && kampftalent(t.getAttribute("name"))) kampftalente.add(t.getAttribute("name"));
        gewicht = lastUnzen(d, held);
        int geld = geldInKreuzer(held);
        int tk = tragkraftUnzen(held), tol = Integer.parseInt(db.get("ueberladung", "0"));
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("tragkraftStein", tk / 40.0);
        s.put("tragkraftMaxStein", Math.round(tk * (1 + tol / 100.0) / 40.0 * 10) / 10.0);
        s.put("ueberladung", tol);
        s.put("lastBe", lastBe(gewicht, tk));
        s.put("name", held.getAttribute("name"));
        s.put("inventar", inv);
        s.put("plaetze", plaetze);
        s.put("kampftalente", kampftalente);
        s.put("gewichtUnzen", Math.round(gewicht));
        s.put("gewichtStein", Math.round(gewicht / 40.0 * 10) / 10.0); // 1 Stein = 40 Unzen
        s.put("geld", geld);
        s.put("geldText", geldText(geld));
        s.put("laden", ladenInfo());
        return s;
    }

    private static final Set<String> KAMPF = Set.of("Anderthalbhänder", "Dolche", "Fechtwaffen", "Hiebwaffen", "Infanteriewaffen", "Kettenstäbe", "Kettenwaffen",
            "Peitsche", "Raufen", "Ringen", "Säbel", "Schwerter", "Speere", "Stäbe", "Zweihandflegel", "Zweihandhiebwaffen", "Zweihandschwerter/-säbel",
            "Armbrust", "Belagerungswaffen", "Blasrohr", "Bogen", "Diskus", "Lanzenreiten", "Schleuder", "Wurfbeile", "Wurfmesser", "Wurfspeere");
    static boolean kampftalent(String n) { return KAMPF.contains(n); }

    /** Vom Meister gesetzte Preise (Kreuzer) je Gegenstand; ohne Eintrag gilt der Standardpreis × Ladenfaktor. */
    @SuppressWarnings("unchecked")
    Map<String, Integer> eigenePreise() {
        try { Map<String, Object> m = App.JSON.readValue(db.get("ladenPreise", "{}"), Map.class); Map<String, Integer> o = new TreeMap<>(); m.forEach((k, v) -> o.put(k, ((Number) v).intValue())); return o; }
        catch (Exception e) { return new TreeMap<>(); }
    }

    /** Preis im Laden für ein Stück (Kreuzer). */
    int ladenpreis(String name) throws Exception {
        Integer eigen = eigenePreise().get(name);
        if (eigen != null) return eigen;
        return (int) Math.round(((Number) artikel(name).get("preis")).intValue() * Integer.parseInt(db.get("ladenFaktor", "100")) / 100.0);
    }

    /** Katalog mit Ladenpreis (eigener Preis oder Standard × Faktor). */
    List<Map<String, Object>> katalogMitPreisen() throws Exception {
        Map<String, Integer> eigen = eigenePreise();
        int f = Integer.parseInt(db.get("ladenFaktor", "100"));
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> k : katalog()) {
            Map<String, Object> m = new LinkedHashMap<>(k);
            Integer e = eigen.get(String.valueOf(k.get("name")));
            m.put("ladenpreis", e != null ? e : (int) Math.round(((Number) k.get("preis")).intValue() * f / 100.0));
            if (e != null) m.put("eigenerPreis", true);
            out.add(m);
        }
        return out;
    }

    Map<String, Object> ladenInfo() {
        return Map.of("offen", "1".equals(db.get("ladenOffen", "0")), "faktor", Integer.parseInt(db.get("ladenFaktor", "100")));
    }

    // ------------------------------------------------------------------ Ändern

    /** Schreibt das XML, lässt die Jar es einlesen und neu speichern (wie die Helden-Software es schreibt). */
    private void speichern(String file, Document d) throws Exception {
        adv.write(file, d); // atomar, mit Probe-Laden
        Path p = heroes.file(file), tmp = p.resolveSibling(p.getFileName() + ".jar.xml");
        synchronized (jar) {
            ClassLoader l = jar.loader(), old = Thread.currentThread().getContextClassLoader();
            Thread.currentThread().setContextClassLoader(l);
            try {
                Object xp = l.loadClass("helden.framework.held.persistenz.XMLPersistierer").getConstructor().newInstance();
                Object h = ((List<?>) Creation.call(xp, "ladeHelden", p.toFile())).get(0);
                for (var m : xp.getClass().getMethods()) if (m.getName().equals("speichereHelden")) m.invoke(xp, tmp.toFile(), h);
                Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception e) {
                Files.deleteIfExists(tmp);
                System.err.println("Ausrüstung: Neu speichern durch die helden.jar fehlgeschlagen, XML bleibt wie geschrieben: " + e);
            } finally { Thread.currentThread().setContextClassLoader(old); }
        }
    }

    /** anzahl > 0 hinzufügen, < 0 entfernen. kaufen: Preis (× Ladenfaktor) aus der Geldbörse. */
    synchronized String aendern(String file, String name, int anzahl, boolean kaufen) throws Exception {
        if (anzahl == 0) throw new IllegalArgumentException("Anzahl fehlt.");
        Map<String, Object> art = artikel(name);
        Document d = adv.read(file);
        Element held = (Element) d.getElementsByTagName("held").item(0);
        Element gl = liste(d, held, "gegenstände");
        Element g = null;
        for (Element e : kinder(gl, "gegenstand")) if (e.getAttribute("name").equals(name) && kinder(e, "Wesen").isEmpty()) { g = e; break; }
        String text;
        if (anzahl > 0) {
            if (kaufen) {
                int preis = ladenpreis(name) * anzahl;
                int tk = tragkraftUnzen(held), tol = Integer.parseInt(db.get("ueberladung", "0"));
                double neu = lastUnzen(d, held) + ((Number) art.get("gewicht")).doubleValue() * anzahl, max = tk * (1 + tol / 100.0);
                if (tk > 0 && neu > max && !"0".equals(db.get("tragkraftSperre", "1")))
                    throw new IllegalArgumentException("Zu schwer: Mit " + anzahl + "× " + name + " trüge " + held.getAttribute("name") + " " + Math.round(neu / 4.0) / 10.0
                            + " Stein, die Tragkraft ist " + tk / 40 + " Stein (KK, WdS S. 144)" + (tol > 0 ? " plus " + tol + " % Toleranz = " + Math.round(max / 4.0) / 10.0 + " Stein" : "")
                            + ". Erst etwas ablegen oder weniger kaufen.");
                int geld = geldInKreuzer(held);
                if (preis > geld) throw new IllegalArgumentException(anzahl + "× " + name + " kostet " + geldText(preis) + ", " + held.getAttribute("name") + " hat nur " + geldText(geld) + ". Es fehlen " + geldText(preis - geld) + ".");
                geldSetzen(d, held, geld - preis);
                text = anzahl + "× " + name + " gekauft für " + geldText(preis) + ", übrig: " + geldText(geld - preis);
                Advance.ereignis(d, held, "Einkauf", anzahl + "× " + name, geld, geld - preis, geldText(preis));
            } else text = anzahl + "× " + name + " eingetragen";
            if (g == null) { g = d.createElement("gegenstand"); g.setAttribute("name", name); g.setAttribute("anzahl", "0"); g.setAttribute("slot", "0"); gl.appendChild(g); }
            g.setAttribute("anzahl", String.valueOf(Heroes.num(g.getAttribute("anzahl")) + anzahl));
        } else {
            if (g == null) throw new IllegalArgumentException(held.getAttribute("name") + " hat kein(e) " + name + ".");
            int neu = Heroes.num(g.getAttribute("anzahl")) + anzahl;
            if (neu <= 0) {
                gl.removeChild(g);
                for (Element a : kinder(liste(d, held, "ausrüstungen"), "heldenausruestung")) // angelegt? dann ablegen
                    if (name.equals(a.getAttribute("waffenname")) || name.equals(a.getAttribute("ruestungsname")) || name.equals(a.getAttribute("schildname"))) a.getParentNode().removeChild(a);
                text = name + " entfernt";
            } else { g.setAttribute("anzahl", String.valueOf(neu)); text = (-anzahl) + "× " + name + " entfernt, noch " + neu; }
        }
        speichern(file, d);
        return text;
    }

    /** Waffe/Rüstung/Schild auf einen Platz legen (Set 0). name leer = Platz leeren. */
    synchronized String anlegen(String file, String slot, String name, String talent) throws Exception {
        if (!SLOTS.contains(slot)) throw new IllegalArgumentException("Unbekannter Ausrüstungsplatz.");
        Document d = adv.read(file);
        Element held = (Element) d.getElementsByTagName("held").item(0);
        Element al = liste(d, held, "ausrüstungen");
        for (Element a : kinder(al, "heldenausruestung")) if (a.getAttribute("name").equals(slot) && "0".equals(a.getAttribute("set").isEmpty() ? "0" : a.getAttribute("set"))) al.removeChild(a);
        if (name == null || name.isBlank()) { speichern(file, d); return "Platz geleert"; }
        boolean hat = kinder(liste(d, held, "gegenstände"), "gegenstand").stream().anyMatch(e -> e.getAttribute("name").equals(name));
        if (!hat) throw new IllegalArgumentException(held.getAttribute("name") + " hat " + name + " nicht dabei. Erst ins Inventar aufnehmen.");
        String art = String.valueOf(artikel(name).get("art"));
        Element a = d.createElement("heldenausruestung");
        a.setAttribute("name", slot);
        a.setAttribute("set", "0");
        a.setAttribute("slot", "0");
        if (slot.startsWith("nkwaffe")) {
            if (!art.contains("Nahkampfwaffe")) throw new IllegalArgumentException(name + " ist keine Nahkampfwaffe (laut helden.jar: " + art + ").");
            if (talent == null || talent.isBlank()) throw new IllegalArgumentException("Bitte das Kampftalent wählen, mit dem " + name + " geführt wird.");
            int bf = bruchfaktor(name);
            a.setAttribute("bezeichner", "");
            a.setAttribute("bfakt", String.valueOf(bf));
            a.setAttribute("bfmin", String.valueOf(bf));
            a.setAttribute("hand", "rechts");
            a.setAttribute("schild", "0");
            a.setAttribute("talent", talent);
            a.setAttribute("waffenname", name);
        } else if (slot.equals("fkwaffe1")) {
            if (!art.contains("Fernkampfwaffe")) throw new IllegalArgumentException(name + " ist keine Fernkampfwaffe (laut helden.jar: " + art + ").");
            if (talent == null || talent.isBlank()) throw new IllegalArgumentException("Bitte das Fernkampftalent wählen, mit dem " + name + " geführt wird.");
            a.setAttribute("talent", talent);
            a.setAttribute("waffenname", name);
        } else if (slot.equals("schild1")) {
            if (!art.contains("Schild") && !art.contains("Parierwaffe")) throw new IllegalArgumentException(name + " ist kein Schild und keine Parierwaffe (laut helden.jar: " + art + ").");
            a.setAttribute("schildname", name);
            a.setAttribute("verwendungsArt", art.contains("Schild") ? "Schild" : "Parierwaffe");
        } else {
            if (!art.contains("Rüstung")) throw new IllegalArgumentException(name + " ist keine Rüstung (laut helden.jar: " + art + ").");
            a.setAttribute("ruestungsname", name);
        }
        al.insertBefore(a, al.getFirstChild());
        speichern(file, d);
        return name + " angelegt";
    }

    /** Bruchfaktor aus der Waffentabelle des Arsenals (Regeldaten): „… Länge BF INI … DK 1W+3 12/4 60 90 2 +1 …“. Ohne Daten 0. */
    private int bruchfaktor(String name) {
        try {
            for (Regeln.Eintrag e : regeln.suche(name, List.of("Waffe"))) {
                String t = e.text().replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ");
                var m = java.util.regex.Pattern.compile("TP TP/KK Gewicht Länge BF INI Preis WM Bem\\. DK \\S+ \\S+ \\S+ \\S+ (-?\\d+)").matcher(t);
                if (m.find()) return Integer.parseInt(m.group(1));
            }
        } catch (Exception ignored) { }
        return 0;
    }

    // ------------------------------------------------------------------ Warenkorb

    /** Offener (noch nicht gesendeter) oder angefragter Korb eines Helden; legt bei Bedarf einen offenen an. */
    private Map<String, Object> korbFuer(String file, Object account, boolean anlegen) {
        var r = db.query("SELECT * FROM warenkorb WHERE file=? AND status IN ('offen','angefragt') ORDER BY id DESC LIMIT 1", file);
        if (!r.isEmpty()) return r.get(0);
        if (!anlegen) return null;
        long id = db.exec("INSERT INTO warenkorb(file,account,status,created) VALUES(?,?,?,?)", file, account, "offen", App.now());
        return db.query("SELECT * FROM warenkorb WHERE id=?", id).get(0);
    }

    /** Korb mit Positionen, Summen und Name des Helden. */
    Map<String, Object> korbDaten(Map<String, Object> k) throws Exception {
        Map<String, Object> m = new LinkedHashMap<>(k);
        List<Map<String, Object>> pos = db.query("SELECT * FROM warenkorb_pos WHERE korb=? ORDER BY id", k.get("id"));
        int summe = 0;
        for (var p : pos) {
            int st = p.get("meisterpreis") != null ? ((Number) p.get("meisterpreis")).intValue() : ((Number) p.get("preis")).intValue();
            p.put("stueck", st);
            p.put("summe", st * ((Number) p.get("anzahl")).intValue());
            summe += st * ((Number) p.get("anzahl")).intValue();
            Map<String, Object> kat = katalogNachName == null ? Map.of() : katalogNachName.getOrDefault(String.valueOf(p.get("name")), Map.of());
            p.put("gewicht", kat.get("gewicht"));
        }
        m.put("pos", pos);
        m.put("summe", summe);
        m.put("summeText", geldText(summe));
        try {
            Document d = adv.read(String.valueOf(k.get("file")));
            Element held = (Element) d.getElementsByTagName("held").item(0);
            m.put("held", held.getAttribute("name"));
            m.put("geld", geldInKreuzer(held));
            m.put("geldText", geldText(geldInKreuzer(held)));
        } catch (Exception e) { m.put("held", k.get("file")); }
        return m;
    }

    synchronized Map<String, Object> korbZustand(String file) throws Exception {
        katalog();
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> k = korbFuer(file, null, false);
        out.put("korb", k == null ? null : korbDaten(k));
        var letzte = db.query("SELECT * FROM warenkorb WHERE file=? AND status IN ('freigegeben','abgelehnt') ORDER BY id DESC LIMIT 3", file);
        List<Map<String, Object>> l = new ArrayList<>();
        for (var x : letzte) l.add(korbDaten(x));
        out.put("letzte", l);
        out.put("laden", ladenInfo());
        return out;
    }

    synchronized void korbHinzu(String file, Object account, String name, int anzahl) throws Exception {
        if (!"1".equals(db.get("ladenOffen", "0"))) throw new IllegalStateException("Der Laden ist zu. Einkaufen geht, wenn der Meister ihn öffnet.");
        if (anzahl < 1 || anzahl > 999) throw new IllegalArgumentException("Anzahl zwischen 1 und 999.");
        artikel(name);
        Map<String, Object> k = korbFuer(file, account, true);
        if (!"offen".equals(k.get("status"))) throw new IllegalStateException("Dein Warenkorb liegt gerade beim Meister. Neue Artikel gehen, sobald er entschieden hat.");
        var vorh = db.query("SELECT id, anzahl FROM warenkorb_pos WHERE korb=? AND name=? AND meisterpreis IS NULL", k.get("id"), name);
        if (!vorh.isEmpty()) db.exec("UPDATE warenkorb_pos SET anzahl=anzahl+? WHERE id=?", anzahl, vorh.get(0).get("id"));
        else db.exec("INSERT INTO warenkorb_pos(korb,name,anzahl,preis) VALUES(?,?,?,?)", k.get("id"), name, anzahl, ladenpreis(name));
    }

    /** Position entfernen; Spieler nur im offenen Korb, Meister auch im angefragten. */
    synchronized void korbWeg(long posId, String file, boolean meister) {
        var p = db.query("SELECT p.id, k.status, k.file FROM warenkorb_pos p JOIN warenkorb k ON k.id=p.korb WHERE p.id=?", posId);
        if (p.isEmpty()) throw new io.javalin.http.NotFoundResponse("Diesen Artikel gibt es im Warenkorb nicht mehr.");
        if (file != null && !file.equals(p.get(0).get("file"))) throw new io.javalin.http.NotFoundResponse("Dieser Warenkorb gehört nicht zu deinem Helden.");
        if (!meister && !"offen".equals(p.get(0).get("status"))) throw new IllegalStateException("Der Warenkorb liegt beim Meister, ändern kann ihn gerade nur er.");
        db.exec("DELETE FROM warenkorb_pos WHERE id=?", posId);
    }

    synchronized String korbSenden(String file, Object account) throws Exception {
        Map<String, Object> k = korbFuer(file, account, false);
        if (k == null || !"offen".equals(k.get("status"))) throw new IllegalStateException("Es gibt keinen offenen Warenkorb zum Senden.");
        Map<String, Object> d = korbDaten(k);
        if (((List<?>) d.get("pos")).isEmpty()) throw new IllegalArgumentException("Der Warenkorb ist leer.");
        db.exec("UPDATE warenkorb SET status='angefragt', gesendet=? WHERE id=?", App.now(), k.get("id"));
        return "Warenkorb an den Meister geschickt (" + d.get("summeText") + ").";
    }

    List<Map<String, Object>> koerbeOffen() throws Exception {
        katalog();
        List<Map<String, Object>> out = new ArrayList<>();
        for (var k : db.query("SELECT * FROM warenkorb WHERE status='angefragt' ORDER BY gesendet")) out.add(korbDaten(k));
        return out;
    }

    /** Meister gibt frei: Geld und Tragkraft prüfen, dann alle Positionen ins Gepäck, Geld abziehen – in einem Schritt. */
    synchronized String korbFreigeben(long id) throws Exception {
        var r = db.query("SELECT * FROM warenkorb WHERE id=? AND status='angefragt'", id);
        if (r.isEmpty()) throw new IllegalStateException("Dieser Warenkorb wartet nicht (mehr) auf Freigabe.");
        Map<String, Object> d = korbDaten(r.get(0));
        String file = String.valueOf(r.get(0).get("file"));
        List<?> pos = (List<?>) d.get("pos");
        if (pos.isEmpty()) throw new IllegalArgumentException("Der Warenkorb ist leer. Ablehnen oder Artikel stehen lassen.");
        Document doc = adv.read(file);
        Element held = (Element) doc.getElementsByTagName("held").item(0);
        int summe = ((Number) d.get("summe")).intValue(), geld = geldInKreuzer(held);
        if (summe > geld) throw new IllegalArgumentException(held.getAttribute("name") + " hat nur " + geldText(geld) + ", der Warenkorb kostet " + geldText(summe) + ". Es fehlen " + geldText(summe - geld) + ". Preise senken, Artikel entfernen oder ablehnen.");
        double neu = lastUnzen(doc, held);
        for (Object o : pos) { Map<?, ?> p = (Map<?, ?>) o; if (p.get("gewicht") instanceof Number w) neu += w.doubleValue() * ((Number) p.get("anzahl")).intValue(); }
        int tk = tragkraftUnzen(held), tol = Integer.parseInt(db.get("ueberladung", "0"));
        if (tk > 0 && neu > tk * (1 + tol / 100.0) && !"0".equals(db.get("tragkraftSperre", "1")))
            throw new IllegalArgumentException("Zu schwer: " + held.getAttribute("name") + " trüge danach " + Math.round(neu / 4.0) / 10.0 + " Stein, Tragkraft " + tk / 40 + " Stein"
                    + (tol > 0 ? " plus " + tol + " %" : "") + " (WdS S. 144). Artikel entfernen oder in der Konfiguration die Sperre lockern.");
        Element gl = liste(doc, held, "gegenstände");
        for (Object o : pos) {
            Map<?, ?> p = (Map<?, ?>) o;
            String name = String.valueOf(p.get("name"));
            Element g = null;
            for (Element e : kinder(gl, "gegenstand")) if (e.getAttribute("name").equals(name) && kinder(e, "Wesen").isEmpty()) { g = e; break; }
            if (g == null) { g = doc.createElement("gegenstand"); g.setAttribute("name", name); g.setAttribute("anzahl", "0"); g.setAttribute("slot", "0"); gl.appendChild(g); }
            g.setAttribute("anzahl", String.valueOf(Heroes.num(g.getAttribute("anzahl")) + ((Number) p.get("anzahl")).intValue()));
        }
        geldSetzen(doc, held, geld - summe);
        Advance.ereignis(doc, held, "Einkauf", "Warenkorb " + id, geld, geld - summe, geldText(summe));
        speichern(file, doc);
        db.exec("UPDATE warenkorb SET status='freigegeben', entschieden=? WHERE id=?", App.now(), id);
        return held.getAttribute("name") + ": Einkauf für " + geldText(summe) + " gebucht, übrig " + geldText(geld - summe) + ".";
    }

    synchronized String korbAblehnen(long id, String grund) throws Exception {
        var r = db.query("SELECT * FROM warenkorb WHERE id=? AND status='angefragt'", id);
        if (r.isEmpty()) throw new IllegalStateException("Dieser Warenkorb wartet nicht (mehr) auf Freigabe.");
        Map<String, Object> d = korbDaten(r.get(0));
        db.exec("UPDATE warenkorb SET status='abgelehnt', entschieden=?, kommentar=? WHERE id=?", App.now(), grund, id);
        return d.get("held") + ": Warenkorb abgelehnt.";
    }

    // ------------------------------------------------------------------ Kampfwerte (für LeP-/Treffer-Verfolgung in der Initiative)

    /** LeP, Wundschwelle und RS je Zone, wie die helden.jar sie für den Bogen rechnet (XMLDatenGenerator). */
    Map<String, Object> kampfwerte(String file) throws Exception {
        Path p = heroes.file(file);
        synchronized (jar) {
            ClassLoader l = jar.loader(), old = Thread.currentThread().getContextClassLoader();
            Thread.currentThread().setContextClassLoader(l);
            try {
                l.loadClass("helden.framework.settings.Setting").getMethod("initAll").invoke(null);
                Object xp = l.loadClass("helden.framework.held.persistenz.XMLPersistierer").getConstructor().newInstance();
                Object h = ((List<?>) Creation.call(xp, "ladeHelden", p.toFile())).get(0);
                org.w3c.dom.Document d = (org.w3c.dom.Document) Creation.call(l.loadClass("helden.plugin.datenxmlplugin.XMLDatenGenerator"), "genDaten", h, 3);
                Element root = d.getDocumentElement();
                Map<String, Object> m = new LinkedHashMap<>();
                Element le = Heroes.first(root, "lebensenergie");
                m.put("le", le == null ? null : num(Heroes.first(le, "akt")));
                Element ws = Heroes.first(root, "wundschwelle");
                m.put("ws", ws == null ? null : num(ws));
                Element rz = Heroes.first(root, "ruestungzonen");
                Map<String, Integer> rs = new LinkedHashMap<>();
                if (rz != null) for (String z : List.of("kopf", "brust", "ruecken", "bauch", "linkerarm", "rechterarm", "linkesbein", "rechtesbein", "gesamtschutz", "behinderung"))
                    if (Heroes.first(rz, z) != null) rs.put(z, num(Heroes.first(rz, z)));
                m.put("rs", rs);
                return m;
            } finally { Thread.currentThread().setContextClassLoader(old); }
        }
    }

    private static Integer num(Element e) {
        if (e == null) return null;
        try { return Integer.parseInt(e.getTextContent().trim()); } catch (NumberFormatException x) { return null; }
    }

    // ------------------------------------------------------------------ NSC-Namen

    private volatile Map<String, List<String>> namenlisten;
    private volatile List<Map<String, Object>> generatoren;

    private org.w3c.dom.Document jarXml(String pfad) throws Exception {
        try (var in = jar.loader().getResourceAsStream(pfad)) {
            if (in == null) throw new IllegalStateException("In der helden.jar fehlt " + pfad);
            var f = javax.xml.parsers.DocumentBuilderFactory.newInstance();
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            return f.newDocumentBuilder().parse(in);
        }
    }

    private synchronized void namenLaden() throws Exception {
        if (generatoren != null) return;
        Map<String, List<String>> nl = new HashMap<>();
        var dl = jarXml("helden/framework/namen/io/Namenlisten.xml");
        var ls = dl.getElementsByTagName("Namenliste");
        for (int i = 0; i < ls.getLength(); i++) {
            Element l = (Element) ls.item(i);
            List<String> n = new ArrayList<>();
            var ns = l.getElementsByTagName("Name");
            for (int k = 0; k < ns.getLength(); k++) { String s = ns.item(k).getTextContent().trim(); if (!s.isEmpty()) n.add(s); }
            nl.put(l.getAttribute("Bezeichnung"), n);
        }
        List<Map<String, Object>> gen = new ArrayList<>();
        var dg = jarXml("helden/framework/namen/io/Namengeneratoren.xml");
        var gs = dg.getElementsByTagName("Namengenerator");
        for (int i = 0; i < gs.getLength(); i++) {
            Element g = (Element) gs.item(i);
            Map<String, List<String>> formate = new LinkedHashMap<>();
            for (String art : List.of("Maennlich", "MaennlichAdlig", "Weiblich", "WeiblichAdlig")) {
                List<String> f = new ArrayList<>();
                var fe = g.getElementsByTagName(art);
                for (int k = 0; k < fe.getLength(); k++) {
                    var fs = ((Element) fe.item(k)).getElementsByTagName("Format");
                    for (int j = 0; j < fs.getLength(); j++) f.add(fs.item(j).getTextContent().trim());
                }
                formate.put(art, f);
            }
            Map<String, String> platz = new HashMap<>();
            var ps = g.getElementsByTagName("Platzhalter");
            for (int k = 0; k < ps.getLength(); k++) {
                Element p = (Element) ps.item(k);
                if (!p.getAttribute("Platzhalter").isEmpty()) platz.put(p.getAttribute("Platzhalter"), p.getAttribute("Namenliste"));
            }
            gen.add(Map.of("name", g.getAttribute("Bezeichnung"), "formate", formate, "platz", platz));
        }
        namenlisten = nl;
        generatoren = gen;
    }

    @SuppressWarnings("unchecked")
    List<String> namen(String generator, boolean weiblich, boolean adlig, int anzahl) throws Exception {
        namenLaden();
        Map<String, Object> g = generatoren.stream().filter(x -> x.get("name").equals(generator)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Diesen Namensgenerator gibt es in der helden.jar nicht: " + generator));
        Map<String, List<String>> formate = (Map<String, List<String>>) g.get("formate");
        List<String> f = formate.getOrDefault((weiblich ? "Weiblich" : "Maennlich") + (adlig ? "Adlig" : ""), List.of());
        if (f.isEmpty()) f = formate.getOrDefault(weiblich ? "Weiblich" : "Maennlich", List.of());
        if (f.isEmpty()) throw new IllegalArgumentException(generator + " hat keine " + (weiblich ? "weiblichen" : "männlichen") + " Namen.");
        Map<String, String> platz = (Map<String, String>) g.get("platz");
        Random r = new Random();
        List<String> out = new ArrayList<>();
        for (int i = 0; i < Math.max(1, Math.min(anzahl, 50)); i++) {
            String s = f.get(r.nextInt(f.size()));
            for (var en : platz.entrySet()) {
                List<String> l = namenlisten.getOrDefault(en.getValue(), List.of());
                while (s.contains(en.getKey())) s = s.replaceFirst(java.util.regex.Pattern.quote(en.getKey()), java.util.regex.Matcher.quoteReplacement(l.isEmpty() ? "?" : l.get(r.nextInt(l.size()))));
            }
            out.add(s.replaceAll("\\s+", " ").trim());
        }
        return out;
    }

    List<String> generatoren() throws Exception {
        namenLaden();
        return generatoren.stream().map(x -> String.valueOf(x.get("name"))).sorted().toList();
    }

    // ------------------------------------------------------------------ Routen

    public void routes(Javalin m, Javalin s) {
        // Katalog: für beide Seiten
        for (Javalin app : List.of(m, s)) app.get("/api/katalog", ctx -> ctx.json(katalogMitPreisen()));
        m.post("/api/laden/preis", ctx -> { // eigener Preis je nach Verfügbarkeit; preis = null/leer: wieder Standardpreis
            Map<?, ?> b = ctx.bodyAsClass(Map.class);
            String name = App.str(b.get("name"));
            artikel(name);
            Map<String, Integer> eigen = eigenePreise();
            if (b.get("preis") instanceof Number n) {
                if (n.intValue() < 0 || n.intValue() > 100_000_000) throw new IllegalArgumentException("Preis bitte zwischen 0 und 100000 Dukaten.");
                eigen.put(name, n.intValue());
            } else eigen.remove(name);
            db.put("ladenPreise", App.JSON.writeValueAsString(eigen));
            ctx.json(Map.of("text", b.get("preis") instanceof Number n ? name + " kostet im Laden jetzt " + geldText(n.intValue()) : name + ": wieder Standardpreis", "eigene", eigen.size()));
        });
        // Meister
        m.get("/api/heroes/{file}/ausruestung", ctx -> ctx.json(zustand(heroes.file(ctx.pathParam("file")).getFileName().toString())));
        m.post("/api/heroes/{file}/ausruestung", ctx -> {
            String file = heroes.file(ctx.pathParam("file")).getFileName().toString();
            Map<?, ?> b = ctx.bodyAsClass(Map.class);
            String text = b.get("slot") != null ? anlegen(file, App.str(b.get("slot")), App.str(b.get("name")), App.str(b.get("talent")))
                    : aendern(file, App.str(b.get("name")), b.get("anzahl") instanceof Number n ? n.intValue() : 1, Boolean.TRUE.equals(b.get("kaufen")));
            ctx.json(Map.of("text", text, "zustand", zustand(file)));
        });
        m.post("/api/laden", ctx -> {
            Map<?, ?> b = ctx.bodyAsClass(Map.class);
            if (b.get("offen") != null) db.put("ladenOffen", Boolean.TRUE.equals(b.get("offen")) ? "1" : "0");
            if (b.get("faktor") instanceof Number n) {
                if (n.intValue() < 10 || n.intValue() > 1000) throw new IllegalArgumentException("Preisfaktor zwischen 10 und 1000 %.");
                db.put("ladenFaktor", String.valueOf(n.intValue()));
            }
            ctx.json(ladenInfo());
        });
        for (Javalin app : List.of(m, s)) app.get("/api/laden", ctx -> ctx.json(ladenInfo()));
        m.get("/api/heroes/{file}/kampfwerte", ctx -> ctx.json(kampfwerte(heroes.file(ctx.pathParam("file")).getFileName().toString())));
        m.get("/api/namen/generatoren", ctx -> ctx.json(generatoren()));
        m.get("/api/namen", ctx -> ctx.json(namen(App.str(ctx.queryParam("g")), "w".equals(ctx.queryParam("s")), "1".equals(ctx.queryParam("adlig")),
                ctx.queryParam("n") == null ? 10 : Integer.parseInt(ctx.queryParam("n")))));
        // Warenkörbe: Meister prüft, ändert Preise je Position, entfernt Artikel, gibt frei oder lehnt ab
        m.get("/api/warenkoerbe", ctx -> ctx.json(koerbeOffen()));
        m.put("/api/warenkoerbe/pos/{id}", ctx -> {
            Object p = ctx.bodyAsClass(Map.class).get("preis");
            if (p != null && !(p instanceof Number)) throw new IllegalArgumentException("Preis nicht lesbar.");
            if (p instanceof Number n && (n.intValue() < 0 || n.intValue() > 100_000_000)) throw new IllegalArgumentException("Preis zwischen 0 und 100000 Dukaten.");
            db.exec("UPDATE warenkorb_pos SET meisterpreis=? WHERE id=?", p == null ? null : ((Number) p).intValue(), Long.parseLong(ctx.pathParam("id")));
            ctx.json(Map.of("ok", true));
        });
        m.delete("/api/warenkoerbe/pos/{id}", ctx -> { korbWeg(Long.parseLong(ctx.pathParam("id")), null, true); ctx.json(Map.of("ok", true)); });
        m.post("/api/warenkoerbe/{id}/freigeben", ctx -> ctx.json(Map.of("text", korbFreigeben(Long.parseLong(ctx.pathParam("id"))))));
        m.post("/api/warenkoerbe/{id}/ablehnen", ctx -> ctx.json(Map.of("text", korbAblehnen(Long.parseLong(ctx.pathParam("id")), App.str(ctx.bodyAsClass(Map.class).get("grund"))))));
        // Spieler: Warenkorb
        s.get("/api/myheroes/{file}/warenkorb", ctx -> ctx.json(korbZustand(adv.own(ctx.pathParam("file"), ctx))));
        s.post("/api/myheroes/{file}/warenkorb", ctx -> {
            String file = adv.own(ctx.pathParam("file"), ctx);
            if (!"übernommen".equals(db.query("SELECT status FROM hero WHERE file=?", file).get(0).get("status")))
                throw new IllegalStateException("Einkaufen geht erst, wenn der Meister den Helden übernommen hat.");
            Map<?, ?> b = ctx.bodyAsClass(Map.class);
            korbHinzu(file, accounts.current(ctx).get("id"), App.str(b.get("name")), b.get("anzahl") instanceof Number n ? n.intValue() : 1);
            ctx.json(korbZustand(file));
        });
        s.delete("/api/myheroes/{file}/warenkorb/{pos}", ctx -> { String file = adv.own(ctx.pathParam("file"), ctx); korbWeg(Long.parseLong(ctx.pathParam("pos")), file, false); ctx.json(korbZustand(file)); });
        s.post("/api/myheroes/{file}/warenkorb/senden", ctx -> { String file = adv.own(ctx.pathParam("file"), ctx); String t = korbSenden(file, accounts.current(ctx).get("id")); var z = korbZustand(file); z.put("text", t); ctx.json(z); });

        // Spieler: eigener Held
        s.get("/api/myheroes/{file}/ausruestung", ctx -> ctx.json(zustand(adv.own(ctx.pathParam("file"), ctx))));
        s.post("/api/myheroes/{file}/ausruestung", ctx -> {
            String file = adv.own(ctx.pathParam("file"), ctx);
            if (!"übernommen".equals(db.query("SELECT status FROM hero WHERE file=?", file).get(0).get("status")))
                throw new IllegalStateException("Ausrüstung ändern geht erst, wenn der Meister den Helden übernommen hat.");
            Map<?, ?> b = ctx.bodyAsClass(Map.class);
            String text;
            if (b.get("slot") != null) text = anlegen(file, App.str(b.get("slot")), App.str(b.get("name")), App.str(b.get("talent")));
            else {
                int n = b.get("anzahl") instanceof Number x ? x.intValue() : 1;
                if (n > 0) throw new IllegalStateException("Einkaufen geht über den Warenkorb: erst hineinlegen, dann an den Meister schicken.");
                else text = aendern(file, App.str(b.get("name")), n, false); // ablegen/wegwerfen geht immer
            }
            ctx.json(Map.of("text", text, "zustand", zustand(file)));
        });
    }
}
