package heldenhort;

import io.javalin.Javalin;
import io.javalin.http.NotFoundResponse;
import java.util.*;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Begleiter steigern (Tiere, Vertraute) nach WdZ S. 123–125:
 *  - Nur Vertraute (Held hat die SF Vertrautenbindung, Begleiter ist ein Tier) haben eigene AP. Pauschal vergebene AP:
 *    ein Viertel der AP der Hexe, die der Hexe nicht abgezogen werden. Die Hexe kann eigene AP übertragen (dauerhaft);
 *    je 50 übertragene AP steigt die Loyalität um 1 (Start 15, höchstens 25).
 *  - Steigern nach SKT-Spalte F: Eigenschaften, AT, PA, GS direkt (12 → 13 kostet F[13] = 130 AP); LE, AE, MR werden wie bei
 *    Helden hinzugekauft (erster Punkt 6 AP, zweiter 14 …). Kein Wert über das Anderthalbfache des Startwerts, außer AE und
 *    Ritualkenntnis.
 *  - Vertrautenzauber mit Lernkosten aus dem Regelwerk (WdZ S. 125 ff.), Zwiegespräch/Krötenschlag sind schon dabei.
 *  - Normale tierische Begleiter steigern nicht mit AP; Ausbildung über Abrichten (ZBA). Werte trägt der Meister direkt ein.
 * Startwerte (für die Obergrenze), AP-Vorrat, Loyalität und Ritualkenntnis stehen in der Datenbank, die Werte selbst im Helden-XML.
 */
public class Begleiter {
    private static final int F = 5; // Spalte F der SKT („wie Fertrauter“)
    static final List<String> EIG = List.of("Mut", "Klugheit", "Intuition", "Charisma", "Fingerfertigkeit", "Gewandtheit", "Konstitution", "Körperkraft");
    private static final Map<String, String> KURZ = Map.of("Mut", "MU", "Klugheit", "KL", "Intuition", "IN", "Charisma", "CH", "Fingerfertigkeit", "FF",
            "Gewandtheit", "GE", "Konstitution", "KO", "Körperkraft", "KK");

    private final Db db;
    private final Advance adv;
    private final Regeln regeln;
    private final Accounts accounts;
    private final Mail mail;

    private final HeldenJar jar;

    public Begleiter(Db db, Advance adv, Regeln regeln, Accounts accounts, Mail mail, HeldenJar jar) {
        this.db = db; this.adv = adv; this.regeln = regeln; this.accounts = accounts; this.mail = mail; this.jar = jar;
        db.exec("CREATE TABLE IF NOT EXISTS begleiter(file TEXT, idx INTEGER, name TEXT, ap INTEGER DEFAULT 0, frei INTEGER DEFAULT 0, lo INTEGER, "
                + "uebertragen INTEGER DEFAULT 0, rk INTEGER, start TEXT, PRIMARY KEY(file, idx))");
        // Vertrautenzauber und vom Meister eingetragene Tricks/Ausbildungen: nicht ins XML (die helden.jar kennt sie nicht und
        // verwirft sonst beim Einlesen den ganzen Begleiter), sondern hier
        db.exec("CREATE TABLE IF NOT EXISTS begleiter_kann(file TEXT, idx INTEGER, name TEXT, art TEXT, ap INTEGER, at TEXT, PRIMARY KEY(file, idx, name))");
        db.exec("CREATE TABLE IF NOT EXISTS begleiter_log(id INTEGER PRIMARY KEY AUTOINCREMENT, file TEXT, idx INTEGER, was TEXT, ap INTEGER, at TEXT, wer TEXT)");
    }

    // ------------------------------------------------------------------ Tier-Manöver der helden.jar (Setting „Tiere“)

    private volatile List<Map<String, Object>> tierSf;

    /** Sonderfertigkeiten für Tiere laut helden.jar (helden/framework/settings/settingstiere.xml): Name, ob ein Wert/eine Auswahl dazugehört („#*“). */
    List<Map<String, Object>> tierSf() {
        if (tierSf != null) return tierSf;
        List<Map<String, Object>> out = new ArrayList<>();
        try (var in = jar.loader().getResourceAsStream("helden/framework/settings/settingstiere.xml")) {
            if (in == null) return List.of();
            var doc = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(in);
            var nl = doc.getElementsByTagName("include");
            for (int i = 0; i < nl.getLength(); i++) {
                String id = ((Element) nl.item(i)).getAttribute("id");
                if (!id.startsWith("S")) continue;
                boolean wahl = id.endsWith("#*");
                out.add(Map.of("name", id.substring(1).replaceAll("#.*$", ""), "wahl", wahl));
            }
        } catch (Exception e) { System.err.println("Tier-Manöver aus der helden.jar nicht lesbar: " + e); return List.of(); }
        out.sort(Comparator.comparing(m -> String.valueOf(m.get("name")), String.CASE_INSENSITIVE_ORDER));
        return tierSf = out;
    }

    /** Für die PDFs: Vertrautenzauber/Fähigkeiten aus der Datenbank und Vertrauten-Daten an die Begleiter aus Heroes.begleiter hängen. */
    @SuppressWarnings("unchecked")
    void anreichern(String file, Map<String, Object> h) {
        try {
            List<Map<String, Object>> ws = (List<Map<String, Object>>) h.getOrDefault("W", List.of());
            for (var z : db.query("SELECT * FROM begleiter WHERE file=?", file)) {
                int idx = ((Number) z.get("idx")).intValue();
                if (idx >= ws.size()) continue;
                Map<String, Object> b = ws.get(idx);
                Map<String, String> regelnB = new LinkedHashMap<>((Map<String, String>) b.getOrDefault("regeln", Map.of()));
                for (var k : db.query("SELECT name, art FROM begleiter_kann WHERE file=? AND idx=? ORDER BY at", file, idx)) regelnB.putIfAbsent(App.str(k.get("name")), "");
                b.put("regeln", regelnB);
                if (z.get("lo") != null)
                    b.put("info", "Vertrauter: Ritualkenntnis (Vertrautenmagie) " + (z.get("rk") == null ? 3 : z.get("rk")) + ", Loyalität " + z.get("lo") + " von 25, Vertrauten-AP " + z.get("frei") + " frei von " + z.get("ap"));
            }
        } catch (Exception e) { System.err.println("Begleiter fürs PDF: " + e); }
    }

    // ------------------------------------------------------------------ Lesen

    private static List<Element> wesen(Element held) {
        List<Element> out = new ArrayList<>();
        NodeList ws = held.getElementsByTagName("Wesen");
        for (int i = 0; i < ws.getLength(); i++) out.add((Element) ws.item(i));
        return out;
    }

    private static Element held(Document d) { return (Element) d.getElementsByTagName("held").item(0); }

    private static String name(Element w) {
        Element geg = w.getParentNode() instanceof Element g ? g : null;
        String n = geg == null ? "" : Heroes.attr(Heroes.first(geg, "name"), "value");
        if (n.isBlank() && geg != null) n = geg.getAttribute("name");
        return n.isBlank() ? "Begleiter" : n;
    }

    /** Direktes Kind (nicht aus einem verschachtelten Wesen). */
    private static Element kind(Element p, String tag) {
        for (Node n = p.getFirstChild(); n != null; n = n.getNextSibling()) if (n instanceof Element e && e.getTagName().equals(tag)) return e;
        return null;
    }

    private static Element eig(Element w, String name) {
        Element es = kind(w, "eigenschaften");
        if (es == null) return null;
        for (Node n = es.getFirstChild(); n != null; n = n.getNextSibling()) if (n instanceof Element e && name.equals(e.getAttribute("name"))) return e;
        return null;
    }

    private static Element angriff(Element w, String name) {
        Element as = kind(w, "angriffe");
        if (as == null) return null;
        for (Node n = as.getFirstChild(); n != null; n = n.getNextSibling()) if (n instanceof Element e && name.equals(e.getAttribute("name"))) return e;
        return null;
    }

    private static boolean hatSf(Element held, String name) {
        for (Element s : Heroes.all(held, "sonderfertigkeit")) if (s.getAttribute("name").equals(name)) return true;
        return false;
    }

    private static boolean hatVorteil(Element held, String name) {
        for (Element s : Heroes.all(held, "vorteil")) if (s.getAttribute("name").startsWith(name)) return true;
        return false;
    }

    /** Alle steigerbaren Werte des Begleiters: Schlüssel → aktueller Wert (Ritualkenntnis kommt aus der Datenbank). */
    private static Map<String, Integer> werte(Element w) {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (String e : EIG) { Element x = eig(w, e); if (x != null) m.put(e, Heroes.num(x.getAttribute("value"))); }
        for (String e : List.of("Lebensenergie", "Astralenergie", "Magieresistenz", "Geschwindigkeit")) { Element x = eig(w, e); if (x != null) m.put(e, Heroes.num(x.getAttribute("value"))); }
        Element as = kind(w, "angriffe");
        if (as != null) for (Node n = as.getFirstChild(); n != null; n = n.getNextSibling())
            if (n instanceof Element a && "angriff".equals(a.getTagName())) {
                Element at = kind(a, "at"), pa = kind(a, "pa");
                if (at != null) m.put("AT:" + a.getAttribute("name"), Heroes.num(at.getAttribute("value")));
                if (pa != null) m.put("PA:" + a.getAttribute("name"), Heroes.num(pa.getAttribute("value")));
            }
        return m;
    }

    private Map<String, Object> zeile(String file, int idx, Element w, boolean vertraut) throws Exception {
        var r = db.query("SELECT * FROM begleiter WHERE file=? AND idx=?", file, idx);
        if (r.isEmpty()) { // erste Begegnung: heutige Werte gelten als Startwerte (Obergrenze 1,5 × Start)
            db.exec("INSERT INTO begleiter(file,idx,name,ap,frei,lo,uebertragen,rk,start) VALUES(?,?,?,?,?,?,?,?,?)", file, idx, name(w), 0, 0,
                    vertraut ? 15 : null, 0, vertraut ? 3 : null, App.JSON.writeValueAsString(werte(w)));
            r = db.query("SELECT * FROM begleiter WHERE file=? AND idx=?", file, idx);
        }
        Map<String, Object> z = r.get(0);
        if (vertraut && z.get("lo") == null) { db.exec("UPDATE begleiter SET lo=15, rk=COALESCE(rk,3) WHERE file=? AND idx=?", file, idx); z.put("lo", 15); z.put("rk", 3); }
        return z;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Integer> start(Map<String, Object> z) {
        try { Map<String, Object> m = App.JSON.readValue(App.str(z.get("start")), Map.class); Map<String, Integer> o = new HashMap<>(); m.forEach((k, v) -> o.put(k, ((Number) v).intValue())); return o; }
        catch (Exception e) { return Map.of(); }
    }

    private static String label(String key) {
        if (key.startsWith("AT:")) return "AT (" + key.substring(3) + ")";
        if (key.startsWith("PA:")) return "PA (" + key.substring(3) + ")";
        return switch (key) {
            case "Lebensenergie" -> "LE"; case "Astralenergie" -> "AE"; case "Magieresistenz" -> "MR"; case "Geschwindigkeit" -> "GS"; case "RK" -> "Ritualkenntnis (Vertrautenmagie)";
            default -> key + (KURZ.containsKey(key) ? " (" + KURZ.get(key) + ")" : "");
        };
    }

    /** Hinzugekauft wie LE/AE/MR bei Helden (Kosten nach Zahl der gekauften Punkte), sonst direkt nach Zielwert. */
    private static boolean zukauf(String key) { return List.of("Lebensenergie", "Astralenergie", "Magieresistenz").contains(key); }

    /** Kosten für +1, oder Grund, warum nicht. */
    private static Object[] naechster(String key, int wert, Integer startwert) {
        boolean ohneGrenze = key.equals("Astralenergie") || key.equals("RK");
        if (!ohneGrenze && startwert != null && startwert > 0 && wert + 1 > startwert * 3 / 2)
            return new Object[]{null, "Höchstwert " + (startwert * 3 / 2) + " erreicht (1,5 × Startwert " + startwert + ", WdZ S. 124)"};
        if (!ohneGrenze && startwert != null && startwert <= 0)
            return new Object[]{null, "Startwert 0: nicht steigerbar (1,5 × 0)"};
        int idx = zukauf(key) ? Math.max(1, wert - (startwert == null ? wert : startwert) + 1) : Math.max(1, wert + 1);
        if (idx >= Advance.SKT[F].length) return new Object[]{null, "Ende der Steigerungstabelle"};
        return new Object[]{Advance.SKT[F][idx], null};
    }

    /** Vertrautenzauber aus dem Regelwerk, passend zur Tierart: Name → {kosten, text, quelle, grund}. */
    private List<Map<String, Object>> zauberAngebot(Element held, Element w, Set<String> kann) {
        String art = (Heroes.attr(kind(w, "gattung"), "value") + " " + Heroes.attr(kind(w, "familie"), "value")).toLowerCase(Locale.ROOT);
        boolean machtvoll = hatVorteil(held, "Machtvolle");
        List<Map<String, Object>> out = new ArrayList<>();
        for (Regeln.Eintrag e : regeln.vertrautenzauber()) {
            String t = e.text().replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ");
            var m = java.util.regex.Pattern.compile("Tierarten\\s*:\\s*(.*?)\\s+(Ritualprobe|Lernkosten|Technik)").matcher(t);
            String tierarten = m.find() ? m.group(1).trim() : "";
            Map<String, Object> z = new LinkedHashMap<>();
            z.put("name", e.name());
            z.put("quelle", e.quelle());
            z.put("tierarten", tierarten);
            int ap = Regeln.buchAp(e);
            if (ap < 0 && e.buchkosten().matches("\\s*0\\s*AP.*")) ap = 0;
            if (machtvoll && e.buchkosten().contains("Hälfte") && ap > 0) ap = ap / 2;
            z.put("ap", ap < 0 ? null : ap);
            String grund = null;
            if (kann.contains(e.name())) grund = List.of("Zwiegespräch", "Krötenschlag").contains(e.name()) ? "kann er schon (durch die Vertrautenbindung)" : "kann er schon";
            else if (ap < 0) grund = "Lernkosten stehen nicht im Regelwerk – der Meister trägt den Zauber ein";
            else if (tierarten.toLowerCase(Locale.ROOT).contains("machtvolle") && !machtvoll) grund = "nur für Machtvolle Vertraute (Vorteil der Hexe)";
            else if (tierarten.toLowerCase(Locale.ROOT).startsWith("nur ") || !tierarten.toLowerCase(Locale.ROOT).startsWith("alle")) {
                boolean passt = false;
                for (String a : tierarten.replaceFirst("(?i)^nur\\s+", "").split("[,/]| und | oder "))
                    if (!a.isBlank() && art.contains(a.trim().toLowerCase(Locale.ROOT).replaceAll("e$", ""))) passt = true;
                if (!passt) grund = "nur für " + tierarten.replaceFirst("(?i)^nur\\s+", "");
            }
            if (grund != null) z.put("grund", grund);
            out.add(z);
        }
        return out;
    }

    private static Set<String> zauberVon(Element w) {
        Set<String> s = new LinkedHashSet<>();
        Element zs = kind(w, "zauber");
        if (zs != null) for (Node n = zs.getFirstChild(); n != null; n = n.getNextSibling()) if (n instanceof Element z && !z.getAttribute("name").isBlank()) s.add(z.getAttribute("name"));
        return s;
    }

    /** Zustand aller Begleiter eines Helden (für Spieler und Meister). */
    List<Map<String, Object>> zustand(String file) throws Exception {
        Document d = adv.read(file);
        Element held = held(d);
        boolean bindung = hatSf(held, "Vertrautenbindung");
        int heldFrei = Heroes.num(Heroes.attr(Heroes.first(held, "freieabenteuerpunkte"), "value"));
        List<Map<String, Object>> out = new ArrayList<>();
        List<Element> ws = wesen(held);
        for (int i = 0; i < ws.size(); i++) {
            Element w = ws.get(i);
            String wesensart = Heroes.attr(kind(w, "wesensart"), "value");
            boolean vertraut = bindung && "Tier".equalsIgnoreCase(wesensart);
            Map<String, Object> z = zeile(file, i, w, vertraut);
            Map<String, Integer> st = start(z);
            Map<String, Object> b = new LinkedHashMap<>();
            b.put("idx", i);
            b.put("name", name(w));
            b.put("art", String.join(", ", List.of(Heroes.attr(kind(w, "gattung"), "value"), Heroes.attr(kind(w, "familie"), "value"), wesensart).stream().filter(x -> !x.isBlank()).toList()));
            b.put("vertraut", vertraut);
            b.put("ap", z.get("ap"));
            b.put("frei", z.get("frei"));
            b.put("lo", z.get("lo"));
            b.put("uebertragen", z.get("uebertragen"));
            b.put("heldFrei", heldFrei);
            List<Map<String, Object>> wl = new ArrayList<>();
            Map<String, Integer> werte = werte(w);
            if (vertraut) werte.put("RK", z.get("rk") == null ? 3 : ((Number) z.get("rk")).intValue());
            for (var en : werte.entrySet()) {
                Integer s0 = en.getKey().equals("RK") ? Integer.valueOf(3) : st.get(en.getKey());
                Object[] nx = naechster(en.getKey(), en.getValue(), s0);
                Map<String, Object> x = new LinkedHashMap<>();
                x.put("key", en.getKey());
                x.put("label", label(en.getKey()));
                x.put("wert", en.getValue());
                if (s0 != null) x.put("start", s0);
                if (nx[0] != null) x.put("kosten", nx[0]);
                if (nx[1] != null) x.put("grund", nx[1]);
                x.put("zukauf", zukauf(en.getKey()));
                wl.add(x);
            }
            b.put("werte", wl);
            Set<String> kann = zauberVon(w);
            for (var k : db.query("SELECT name FROM begleiter_kann WHERE file=? AND idx=? AND art='Vertrautenzauber' ORDER BY at", file, i)) kann.add(App.str(k.get("name")));
            if (vertraut) { // durch die Vertrautenbindung schon dabei (WdZ S. 122/124)
                kann.add("Zwiegespräch");
                if (Heroes.attr(kind(w, "gattung"), "value").toLowerCase(Locale.ROOT).contains("kröte")) kann.add("Krötenschlag");
            }
            b.put("zauber", kann);
            List<String> sf = new ArrayList<>();
            for (Element s : Heroes.all(w, "sonderfertigkeit")) {
                String a = Heroes.attr(Heroes.first(s, "auswahl"), "name");
                sf.add(s.getAttribute("name") + (a.isBlank() ? "" : " (" + a + ")"));
            }
            for (var k : db.query("SELECT name FROM begleiter_kann WHERE file=? AND idx=? AND art<>'Vertrautenzauber' ORDER BY at", file, i)) sf.add(App.str(k.get("name")));
            b.put("sf", sf);
            if (vertraut) b.put("zauberAngebot", zauberAngebot(held, w, kann));
            b.put("hinweis", vertraut
                    ? "Vertrauter (WdZ S. 124): eigene AP, Steigern nach Spalte F, kein Wert über das 1,5‑fache des Startwerts (außer AE und Ritualkenntnis)."
                    : bindung ? "Kein Tier (" + wesensart + "): steigert nicht über Vertrauten-AP. Werte trägt der Meister ein."
                    : "Normaler tierischer Begleiter: steigert nicht mit AP (nur Vertraute haben eigene AP, WdZ S. 124). Ausbildung über Abrichten (ZBA S. 20), die Werte trägt der Meister ein.");
            b.put("log", db.query("SELECT was, ap, at, wer FROM begleiter_log WHERE file=? AND idx=? ORDER BY id DESC LIMIT 15", file, i));
            out.add(b);
        }
        return out;
    }

    // ------------------------------------------------------------------ Ändern

    private synchronized Map<String, Object> steigern(String file, int idx, String key, String wer) throws Exception {
        Document d = adv.read(file);
        Element held = held(d);
        List<Element> ws = wesen(held);
        if (idx < 0 || idx >= ws.size()) throw new NotFoundResponse("Diesen Begleiter gibt es im Helden nicht mehr.");
        Element w = ws.get(idx);
        boolean vertraut = hatSf(held, "Vertrautenbindung") && "Tier".equalsIgnoreCase(Heroes.attr(kind(w, "wesensart"), "value"));
        if (!vertraut) throw new IllegalArgumentException(name(w) + " ist kein Vertrauter: normale Begleiter steigern nicht mit AP (WdZ S. 124). Der Meister trägt Änderungen ein.");
        Map<String, Object> z = zeile(file, idx, w, true);
        Map<String, Integer> werte = werte(w);
        int alt;
        if (key.equals("RK")) alt = z.get("rk") == null ? 3 : ((Number) z.get("rk")).intValue();
        else if (werte.containsKey(key)) alt = werte.get(key);
        else throw new IllegalArgumentException("Diesen Wert hat " + name(w) + " nicht. Bitte Seite neu laden.");
        Object[] nx = naechster(key, alt, key.equals("RK") ? Integer.valueOf(3) : start(z).get(key));
        if (nx[0] == null) throw new IllegalArgumentException(label(key) + ": " + nx[1]);
        int kosten = (Integer) nx[0], frei = ((Number) z.get("frei")).intValue();
        if (kosten > frei) throw new IllegalArgumentException(label(key) + " +1 kostet " + kosten + " AP, " + name(w) + " hat nur " + frei + " freie AP. Dir fehlen " + (kosten - frei) + " AP.");
        if (key.equals("RK")) db.exec("UPDATE begleiter SET rk=? WHERE file=? AND idx=?", alt + 1, file, idx);
        else {
            Element e = key.startsWith("AT:") ? kind(angriff(w, key.substring(3)), "at") : key.startsWith("PA:") ? kind(angriff(w, key.substring(3)), "pa") : eig(w, key);
            e.setAttribute("value", String.valueOf(alt + 1));
            Advance.ereignis(d, held, "Begleiter gesteigert", name(w) + ": " + label(key), alt, alt + 1, kosten + " AP (Vertrauten-AP)");
            adv.write(file, d);
        }
        db.exec("UPDATE begleiter SET frei=frei-? WHERE file=? AND idx=?", kosten, file, idx);
        String was = label(key) + " " + alt + " → " + (alt + 1);
        db.exec("INSERT INTO begleiter_log(file,idx,was,ap,at,wer) VALUES(?,?,?,?,?,?)", file, idx, was, kosten, App.now(), wer);
        return Map.of("text", name(w) + ": " + was + " für " + kosten + " AP", "kosten", kosten);
    }

    private synchronized Map<String, Object> lernen(String file, int idx, String zauber, String wer) throws Exception {
        Document d = adv.read(file);
        Element held = held(d);
        List<Element> ws = wesen(held);
        if (idx < 0 || idx >= ws.size()) throw new NotFoundResponse("Diesen Begleiter gibt es im Helden nicht mehr.");
        Element w = ws.get(idx);
        if (!(hatSf(held, "Vertrautenbindung") && "Tier".equalsIgnoreCase(Heroes.attr(kind(w, "wesensart"), "value"))))
            throw new IllegalArgumentException("Vertrautenzauber lernen nur Vertraute (WdZ S. 125).");
        Map<String, Object> z = zeile(file, idx, w, true);
        Set<String> schon = zauberVon(w);
        for (var k : db.query("SELECT name FROM begleiter_kann WHERE file=? AND idx=? AND art='Vertrautenzauber'", file, idx)) schon.add(App.str(k.get("name")));
        schon.add("Zwiegespräch");
        Map<String, Object> ang = zauberAngebot(held, w, schon).stream().filter(x -> x.get("name").equals(zauber)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("„" + zauber + "“ ist kein Vertrautenzauber aus dem Regelwerk."));
        if (ang.get("grund") != null) throw new IllegalArgumentException(zauber + ": " + ang.get("grund"));
        int kosten = ((Number) ang.get("ap")).intValue(), frei = ((Number) z.get("frei")).intValue();
        if (kosten > frei) throw new IllegalArgumentException(zauber + " kostet " + kosten + " AP, " + name(w) + " hat nur " + frei + " freie AP. Dir fehlen " + (kosten - frei) + " AP.");
        db.exec("INSERT INTO begleiter_kann(file,idx,name,art,ap,at) VALUES(?,?,?,?,?,?)", file, idx, zauber, "Vertrautenzauber", kosten, App.now());
        db.exec("UPDATE begleiter SET frei=frei-? WHERE file=? AND idx=?", kosten, file, idx);
        db.exec("INSERT INTO begleiter_log(file,idx,was,ap,at,wer) VALUES(?,?,?,?,?,?)", file, idx, "Vertrautenzauber " + zauber + " gelernt", kosten, App.now(), wer);
        return Map.of("text", name(w) + " kann jetzt " + zauber + " (" + kosten + " AP)", "kosten", kosten);
    }

    /** Hexe überträgt eigene freie AP auf den Vertrauten (dauerhaft, WdZ S. 124); je 50 übertragene AP +1 Loyalität (höchstens 25). */
    private synchronized Map<String, Object> uebertragen(String file, int idx, int ap, String wer) throws Exception {
        if (ap <= 0 || ap > 100000) throw new IllegalArgumentException("Bitte eine positive Zahl AP angeben.");
        Document d = adv.read(file);
        Element held = held(d);
        List<Element> ws = wesen(held);
        if (idx < 0 || idx >= ws.size()) throw new NotFoundResponse("Diesen Begleiter gibt es im Helden nicht mehr.");
        Element w = ws.get(idx);
        if (!(hatSf(held, "Vertrautenbindung") && "Tier".equalsIgnoreCase(Heroes.attr(kind(w, "wesensart"), "value"))))
            throw new IllegalArgumentException("AP übertragen geht nur auf einen Vertrauten (Vertrautenbindung, WdZ S. 124).");
        Map<String, Object> z = zeile(file, idx, w, true);
        int heldFrei = Heroes.num(Heroes.attr(Heroes.first(held, "freieabenteuerpunkte"), "value"));
        if (ap > heldFrei) throw new IllegalArgumentException(held.getAttribute("name") + " hat nur " + heldFrei + " freie AP.");
        Advance.setFreieAp(d, held, heldFrei - ap);
        Advance.ereignis(d, held, "AP an Vertrauten übertragen", name(w), heldFrei, heldFrei - ap, ap + " AP");
        adv.write(file, d);
        int vorher = ((Number) z.get("uebertragen")).intValue(), lo = z.get("lo") == null ? 15 : ((Number) z.get("lo")).intValue();
        int plusLo = (vorher + ap) / 50 - vorher / 50, neuLo = Math.min(25, lo + plusLo);
        db.exec("UPDATE begleiter SET ap=ap+?, frei=frei+?, uebertragen=uebertragen+?, lo=? WHERE file=? AND idx=?", ap, ap, ap, neuLo, file, idx);
        db.exec("INSERT INTO begleiter_log(file,idx,was,ap,at,wer) VALUES(?,?,?,?,?,?)", file, idx,
                ap + " AP von " + held.getAttribute("name") + " übertragen" + (neuLo > lo ? ", Loyalität " + lo + " → " + neuLo : ""), ap, App.now(), wer);
        return Map.of("text", ap + " AP an " + name(w) + " übertragen" + (neuLo > lo ? ", Loyalität steigt auf " + neuLo : lo >= 25 && plusLo > 0 ? " (Loyalität schon auf dem Höchstwert 25)" : ""));
    }

    /** AP an alle Vertrauten eines Helden (beim Aussenden: ein Viertel der AP der Hexe, ihr nicht abgezogen). Liefert Text für die Mail oder "". */
    String vertrautenAp(String file, int heldAp, String anlass) {
        if (heldAp <= 0) return "";
        try {
            List<String> t = new ArrayList<>();
            for (Map<String, Object> b : zustand(file)) {
                if (!Boolean.TRUE.equals(b.get("vertraut"))) continue;
                int ap = heldAp / 4;
                if (ap <= 0) continue;
                db.exec("UPDATE begleiter SET ap=ap+?, frei=frei+? WHERE file=? AND idx=?", ap, ap, file, b.get("idx"));
                db.exec("INSERT INTO begleiter_log(file,idx,was,ap,at,wer) VALUES(?,?,?,?,?,?)", file, b.get("idx"),
                        ap + " AP vom Meister (ein Viertel von " + heldAp + ")" + (anlass.isBlank() ? "" : ": " + anlass), ap, App.now(), "Meister");
                t.add(b.get("name") + " bekommt " + ap + " AP (ein Viertel, WdZ S. 124)");
            }
            return String.join("; ", t);
        } catch (Exception e) { System.err.println("Vertrauten-AP: " + e); return ""; }
    }

    /** Meister: Wert direkt setzen (z. B. nach Abrichten), ohne AP. */
    private synchronized String setzen(String file, int idx, String key, int wert) throws Exception {
        Document d = adv.read(file);
        Element held = held(d);
        List<Element> ws = wesen(held);
        if (idx < 0 || idx >= ws.size()) throw new NotFoundResponse("Diesen Begleiter gibt es im Helden nicht mehr.");
        Element w = ws.get(idx);
        if (wert < 0 || wert > 999) throw new IllegalArgumentException("Wert zwischen 0 und 999");
        zeile(file, idx, w, hatSf(held, "Vertrautenbindung") && "Tier".equalsIgnoreCase(Heroes.attr(kind(w, "wesensart"), "value")));
        int alt;
        if (key.equals("RK")) { var r = db.query("SELECT rk FROM begleiter WHERE file=? AND idx=?", file, idx); alt = r.get(0).get("rk") == null ? 3 : ((Number) r.get(0).get("rk")).intValue(); db.exec("UPDATE begleiter SET rk=? WHERE file=? AND idx=?", wert, file, idx); }
        else {
            Element e = key.startsWith("AT:") ? kind(angriff(w, key.substring(3)), "at") : key.startsWith("PA:") ? kind(angriff(w, key.substring(3)), "pa") : eig(w, key);
            if (e == null) throw new IllegalArgumentException("Diesen Wert hat " + name(w) + " nicht.");
            alt = Heroes.num(e.getAttribute("value"));
            e.setAttribute("value", String.valueOf(wert));
            Advance.ereignis(d, held, "Begleiter (Meister)", name(w) + ": " + label(key), alt, wert, "vom Meister gesetzt");
            adv.write(file, d);
        }
        db.exec("INSERT INTO begleiter_log(file,idx,was,ap,at,wer) VALUES(?,?,?,?,?,?)", file, idx, label(key) + " " + alt + " → " + wert + " (Meister)", 0, App.now(), "Meister");
        return name(w) + ": " + label(key) + " " + alt + " → " + wert;
    }

    /** Meister: Sonderfertigkeit/Ausbildung/Trick eintragen; bei Vertrauten optional AP abziehen (WdZ: TaP* × 10, Tricks 10–50 AP). */
    private synchronized String sfEintragen(String file, int idx, String sf, String wahl, int ap) throws Exception {
        if (sf.isBlank()) throw new IllegalArgumentException("Name der Fähigkeit fehlt.");
        Document d = adv.read(file);
        Element held = held(d);
        List<Element> ws = wesen(held);
        if (idx < 0 || idx >= ws.size()) throw new NotFoundResponse("Diesen Begleiter gibt es im Helden nicht mehr.");
        Element w = ws.get(idx);
        boolean vertraut = hatSf(held, "Vertrautenbindung") && "Tier".equalsIgnoreCase(Heroes.attr(kind(w, "wesensart"), "value"));
        Map<String, Object> z = zeile(file, idx, w, vertraut);
        if (ap > 0 && !vertraut) throw new IllegalArgumentException(name(w) + " ist kein Vertrauter und hat keine AP. Fähigkeit ohne AP eintragen (Abrichten).");
        if (ap > 0 && ap > ((Number) z.get("frei")).intValue()) throw new IllegalArgumentException(name(w) + " hat nur " + z.get("frei") + " freie AP.");
        Map<String, Object> def = tierSf().stream().filter(x -> String.valueOf(x.get("name")).equalsIgnoreCase(sf)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("„" + sf + "“ kennt die helden.jar nicht als Tier-Fähigkeit. Bitte aus der Liste wählen (Tricks über „Trick“ mit dem Namen des Tricks als Wert)."));
        String name = String.valueOf(def.get("name"));
        boolean mitWahl = Boolean.TRUE.equals(def.get("wahl"));
        if (mitWahl && wahl.isBlank()) throw new IllegalArgumentException(name + " braucht einen Wert, z. B. Hinterhalt 12, Gelände Wald, Trick Schlüssel holen.");
        for (Element s : Heroes.all(w, "sonderfertigkeit"))
            if (s.getAttribute("name").equals(name) && (!mitWahl || Heroes.attr(Heroes.first(s, "auswahl"), "name").equalsIgnoreCase(wahl)))
                throw new IllegalArgumentException(name(w) + " kann " + name + (mitWahl ? " (" + wahl + ")" : "") + " schon.");
        Element ss = kind(w, "sonderfertigkeiten");
        if (ss == null) { ss = d.createElement("sonderfertigkeiten"); w.appendChild(ss); }
        Element ne = d.createElement("sonderfertigkeit");
        ne.setAttribute("name", name);
        if (mitWahl) { Element au = d.createElement("auswahl"); au.setAttribute("name", wahl.trim()); ne.appendChild(au); } // so speichert es die helden.jar selbst
        ss.appendChild(ne);
        Advance.ereignis(d, held, "Begleiter lernt", name(w) + ": " + name + (mitWahl ? " (" + wahl.trim() + ")" : ""), 0, 0, ap > 0 ? ap + " AP (Vertrauten-AP)" : "vom Meister eingetragen");
        adv.write(file, d);
        if (ap > 0) db.exec("UPDATE begleiter SET frei=frei-? WHERE file=? AND idx=?", ap, file, idx);
        String was = name + (mitWahl ? " (" + wahl.trim() + ")" : "");
        db.exec("INSERT INTO begleiter_log(file,idx,was,ap,at,wer) VALUES(?,?,?,?,?,?)", file, idx, was + " eingetragen (Meister)", ap, App.now(), "Meister");
        return name(w) + " kann jetzt " + was + (ap > 0 ? " (" + ap + " AP)" : "");
    }

    /** Meister: Begleiter aus dem Helden entfernen (z. B. gestorben). Datenbank-Einträge der folgenden Begleiter rücken nach. */
    private synchronized String entfernen(String file, int idx) throws Exception {
        Document d = adv.read(file);
        Element held = held(d);
        List<Element> ws = wesen(held);
        if (idx < 0 || idx >= ws.size()) throw new NotFoundResponse("Diesen Begleiter gibt es im Helden nicht mehr.");
        Element w = ws.get(idx);
        String name = name(w);
        Node geg = w.getParentNode();
        geg.getParentNode().removeChild(geg);
        Advance.ereignis(d, held, "Begleiter entfernt", name, 0, 0, "vom Meister entfernt");
        adv.write(file, d);
        for (String t : List.of("begleiter", "begleiter_log", "begleiter_kann")) {
            db.exec("DELETE FROM " + t + " WHERE file=? AND idx=?", file, idx);
            db.exec("UPDATE " + t + " SET idx=idx-1 WHERE file=? AND idx>?", file, idx);
        }
        return name + " ist aus " + held.getAttribute("name") + " entfernt.";
    }

    // ------------------------------------------------------------------ Routen

    public void routes(Javalin m, Javalin s) {
        s.get("/api/myheroes/{file}/begleiter", ctx -> ctx.json(zustand(adv.own(ctx.pathParam("file"), ctx))));
        s.post("/api/myheroes/{file}/begleiter/{idx}/steigern", ctx -> {
            String file = adv.own(ctx.pathParam("file"), ctx);
            if (!"übernommen".equals(db.query("SELECT status FROM hero WHERE file=?", file).get(0).get("status")))
                throw new IllegalStateException("Steigern geht erst, wenn der Meister den Helden übernommen hat.");
            ctx.json(steigern(file, Integer.parseInt(ctx.pathParam("idx")), App.str(ctx.bodyAsClass(Map.class).get("key")), App.str(accounts.current(ctx).get("name"))));
        });
        s.post("/api/myheroes/{file}/begleiter/{idx}/lernen", ctx -> {
            String file = adv.own(ctx.pathParam("file"), ctx);
            if (!"übernommen".equals(db.query("SELECT status FROM hero WHERE file=?", file).get(0).get("status")))
                throw new IllegalStateException("Lernen geht erst, wenn der Meister den Helden übernommen hat.");
            ctx.json(lernen(file, Integer.parseInt(ctx.pathParam("idx")), App.str(ctx.bodyAsClass(Map.class).get("zauber")), App.str(accounts.current(ctx).get("name"))));
        });
        s.post("/api/myheroes/{file}/begleiter/{idx}/uebertragen", ctx -> {
            String file = adv.own(ctx.pathParam("file"), ctx);
            if (!"übernommen".equals(db.query("SELECT status FROM hero WHERE file=?", file).get(0).get("status")))
                throw new IllegalStateException("AP übertragen geht erst, wenn der Meister den Helden übernommen hat.");
            Object ap = ctx.bodyAsClass(Map.class).get("ap");
            ctx.json(uebertragen(file, Integer.parseInt(ctx.pathParam("idx")), ap instanceof Number n ? n.intValue() : 0, App.str(accounts.current(ctx).get("name"))));
        });
        // Meister
        m.get("/api/heroes/{file}/begleiter", ctx -> ctx.json(zustand(ctx.pathParam("file"))));
        m.get("/api/tier-manoever", ctx -> ctx.json(tierSf()));
        m.post("/api/heroes/{file}/begleiter/{idx}/wert", ctx -> {
            Map<?, ?> b = ctx.bodyAsClass(Map.class);
            if (!(b.get("wert") instanceof Number n)) throw new IllegalArgumentException("Wert als Zahl angeben.");
            ctx.json(Map.of("text", setzen(ctx.pathParam("file"), Integer.parseInt(ctx.pathParam("idx")), App.str(b.get("key")), n.intValue())));
        });
        m.post("/api/heroes/{file}/begleiter/{idx}/sf", ctx -> {
            Map<?, ?> b = ctx.bodyAsClass(Map.class);
            ctx.json(Map.of("text", sfEintragen(ctx.pathParam("file"), Integer.parseInt(ctx.pathParam("idx")), App.str(b.get("name")), App.str(b.get("wahl")), b.get("ap") instanceof Number n ? n.intValue() : 0)));
        });
        m.delete("/api/heroes/{file}/begleiter/{idx}", ctx -> ctx.json(Map.of("text", entfernen(ctx.pathParam("file"), Integer.parseInt(ctx.pathParam("idx"))))));
        m.post("/api/heroes/{file}/begleiter/{idx}/ap", ctx -> { // Sonder-AP für einen Vertrauten
            String file = ctx.pathParam("file");
            int idx = Integer.parseInt(ctx.pathParam("idx"));
            Object ap = ctx.bodyAsClass(Map.class).get("ap");
            int n = ap instanceof Number x ? x.intValue() : 0;
            if (n <= 0 || n > 100000) throw new IllegalArgumentException("AP bitte als positive Zahl.");
            var st = zustand(file);
            if (idx < 0 || idx >= st.size() || !Boolean.TRUE.equals(st.get(idx).get("vertraut"))) throw new IllegalArgumentException("AP gibt es nur für Vertraute (WdZ S. 124).");
            db.exec("UPDATE begleiter SET ap=ap+?, frei=frei+? WHERE file=? AND idx=?", n, n, file, idx);
            db.exec("INSERT INTO begleiter_log(file,idx,was,ap,at,wer) VALUES(?,?,?,?,?,?)", file, idx, n + " AP vom Meister", n, App.now(), "Meister");
            ctx.json(Map.of("text", st.get(idx).get("name") + " bekommt " + n + " AP"));
        });
    }
}
