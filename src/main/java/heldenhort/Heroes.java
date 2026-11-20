package heldenhort;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.*;

/**
 * Helden-XMLs in /data/helden. Kommen vom Spieler-Upload oder per FTP.
 * Jede Datei wird bei Bedarf neu gelesen; Metadaten (Spieler, Status, Backgroundstory) liegen in der DB.
 */
public final class Heroes {
    static final Map<String, String> SHORT = Map.of("Mut", "MU", "Klugheit", "KL", "Intuition", "IN", "Charisma", "CH",
            "Fingerfertigkeit", "FF", "Gewandtheit", "GE", "Konstitution", "KO", "Körperkraft", "KK", "Sozialstatus", "SO");
    static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    private final Db db;
    private final Path dir;

    public Heroes(Db db, Path dir) throws Exception {
        this.db = db;
        this.dir = dir;
        Files.createDirectories(dir);
    }

    public Path dir() { return dir; }

    /** Wird für Helden aufgerufen, die neu per FTP im Ordner auftauchen (Dateiname, gelesene Werte). */
    public java.util.function.BiConsumer<String, Map<String, Object>> onFtp;

    public List<Map<String, Object>> list() throws Exception {
        List<Map<String, Object>> out = new ArrayList<>();
        try (var s = Files.list(dir)) {
            for (Path p : s.filter(f -> f.toString().toLowerCase().endsWith(".xml")).sorted().toList()) {
                Map<String, Object> h;
                try { h = parse(Files.readAllBytes(p)); }
                catch (Exception e) { h = new LinkedHashMap<>(Map.of("name", p.getFileName().toString(), "error", "XML nicht lesbar: " + e.getMessage())); }
                String file = p.getFileName().toString();
                h.put("file", file);
                var meta = db.query("SELECT * FROM hero WHERE file=?", file);
                if (meta.isEmpty()) {
                    // Neu per FTP aufgetaucht: Eingang = Dateidatum
                    String ts = LocalDateTime.ofInstant(Files.getLastModifiedTime(p).toInstant(), ZoneId.systemDefault()).format(TS);
                    db.exec("INSERT INTO hero(file,player,received,status,background) VALUES(?,?,?,?,?)", file, "FTP", ts, "neu", "");
                    meta = db.query("SELECT * FROM hero WHERE file=?", file);
                    if (onFtp != null && !h.containsKey("error")) try { onFtp.accept(file, h); } catch (Exception e) { System.err.println("Freigabe für " + file + ": " + e); }
                }
                h.putAll(meta.get(0));
                out.add(h);
            }
        }
        out.sort(Comparator.comparing((Map<String, Object> m) -> "neu".equals(m.get("status")) ? 0 : 1)
                .thenComparing(m -> String.valueOf(m.get("name"))));
        return out;
    }

    /** Speichert einen vom Spieler gesendeten Helden. Dateiname aus dem Heldennamen, nie aus Nutzereingaben als Pfad. */
    public String save(String name, String player, byte[] xml, String background) throws Exception { return save(name, player, null, xml, background); }

    public String save(String name, String player, Long account, byte[] xml, String background) throws Exception {
        parse(xml); // wirft bei kaputtem XML
        String base = name.replaceAll("[^\\p{L}\\p{N} _-]", "").trim().replace(' ', '_');
        if (base.isEmpty()) base = "Held";
        String file = base + ".xml";
        for (int i = 2; Files.exists(dir.resolve(file)); i++) file = base + "_" + i + ".xml";
        Files.write(dir.resolve(file), xml);
        db.exec("INSERT OR REPLACE INTO hero(file,player,received,status,background,account) VALUES(?,?,?,?,?,?)",
                file, player, LocalDateTime.now().format(TS), "neu", background, account);
        return file;
    }

    /** Löschen = in den Papierkorb (Unterordner .papierkorb, mit Zeitstempel). Nichts geht endgültig verloren. */
    public void trash(String file) throws Exception {
        Path p = file(file), bin = dir.resolve(".papierkorb");
        Files.createDirectories(bin);
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        Files.move(p, bin.resolve(stamp + "_" + file));
        if (Files.exists(bild(file))) Files.move(bild(file), bin.resolve(stamp + "_" + bild(file).getFileName()));
        db.exec("DELETE FROM hero WHERE file=?", file);
    }

    /** Heldenbild: liegt neben den Helden in .bilder/<datei>.jpg (die helden.jar speichert nur einen Pfad). */
    public Path bild(String file) { return dir.resolve(".bilder").resolve(file.replaceAll("\\.xml$", "") + ".jpg"); }

    /** Bild speichern: auf höchstens 800 px Kantenlänge verkleinert, als JPEG. Wirft verständlich bei Unlesbarem. */
    public void saveBild(String file, byte[] data) throws Exception {
        file(file);
        java.awt.image.BufferedImage in = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(data));
        if (in == null) throw new IllegalArgumentException("Das Bild konnte nicht gelesen werden. Bitte JPG, PNG, GIF oder BMP verwenden (kein WEBP/HEIC).");
        double f = Math.min(1.0, 800.0 / Math.max(in.getWidth(), in.getHeight()));
        int w = Math.max(1, (int) Math.round(in.getWidth() * f)), h = Math.max(1, (int) Math.round(in.getHeight() * f));
        java.awt.image.BufferedImage out = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = out.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setColor(java.awt.Color.WHITE); g.fillRect(0, 0, w, h); // Transparenz -> weiß (Druck)
        g.drawImage(in, 0, 0, w, h, null); g.dispose();
        Files.createDirectories(bild(file).getParent());
        javax.imageio.ImageIO.write(out, "jpg", bild(file).toFile());
    }

    public Path file(String file) {
        Path p = dir.resolve(file).normalize();
        if (!p.getParent().equals(dir.normalize()) || !Files.isRegularFile(p)) throw new IllegalArgumentException("Diese Datei gibt es nicht mehr, vermutlich wurde sie gelöscht oder umbenannt. Bitte Seite neu laden.");
        return p;
    }

    /** Liest die für Meister-UI und Suche nötigen Werte. XXE ist abgeschaltet, Dateien kommen von Spielern. */
    static Map<String, Object> parse(byte[] xml) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        f.setExpandEntityReferences(false);
        Document d = f.newDocumentBuilder().parse(new java.io.ByteArrayInputStream(xml));
        Element held = first(d.getDocumentElement(), "held");
        if (held == null) throw new IllegalArgumentException("kein <held> gefunden");
        Map<String, Object> h = new LinkedHashMap<>();
        h.put("name", held.getAttribute("name"));
        h.put("rasse", attr(first(held, "rasse"), "string"));
        h.put("kultur", attr(first(held, "kultur"), "string"));
        List<String> prof = new ArrayList<>();
        for (Element a : all(held, "ausbildung")) {
            String s = a.getAttribute("string");
            if (s.isEmpty()) s = attr(first(a, "profession"), "string");
            if (!s.isEmpty()) prof.add(s);
        }
        h.put("profession", String.join(" / ", prof));

        Map<String, Integer> eig = new LinkedHashMap<>();
        for (Element e : all(held, "eigenschaft")) {
            String k = SHORT.get(e.getAttribute("name"));
            if (k != null) eig.put(k, num(e.getAttribute("value")) + num(e.getAttribute("mod")));
        }
        h.put("E", eig);
        Map<String, Integer> tal = new LinkedHashMap<>();
        for (Element t : all(held, "talent")) if (t.hasAttribute("value")) tal.put(t.getAttribute("name"), num(t.getAttribute("value")));
        h.put("T", tal);
        List<Map<String, String>> vt = new ArrayList<>();
        for (Element v : all(held, "vorteil")) vt.add(Map.of("name", v.getAttribute("name"), "value", v.getAttribute("value")));
        h.put("V", vt);
        List<String> sf = new ArrayList<>();
        for (Element s : all(held, "sonderfertigkeit")) sf.add(s.getAttribute("name"));
        h.put("SF", sf);
        Map<String, String> zb = new LinkedHashMap<>(); // Zauber → ZfW (für die Regelzusammenfassung)
        for (Element z : all(held, "zauber")) if (!z.getAttribute("name").isBlank()) zb.putIfAbsent(z.getAttribute("name"), z.getAttribute("value"));
        h.put("Z", zb);
        h.put("W", begleiter(held)); // Tiere, Vertraute, Dämonen … getrennt vom Helden
        // Meldungen der Regelprüfung, die die helden.jar als Ereignis in den Helden schreibt
        // Aufbau: text="Meistergenehmigung notwendig", obj="<worum es geht>", time=<Zeitstempel>, version=…
        List<String> mg = new ArrayList<>();
        for (Element e : all(held, "ereignis")) {
            String text = e.getAttribute("text"), obj = e.getAttribute("obj");
            if (!(text + " " + obj).contains("Meistergenehmigung")) continue;
            mg.add(lesbar(obj.isBlank() ? text : obj));
        }
        h.put("meistergenehmigung", mg);
        h.put("sheet", sheet(held));
        return h;
    }

    /**
     * Freigabe-Punkt so, dass ihn jeder versteht: ohne Zeitstempel und Programm-Kauderwelsch.
     * „Die folgenden Vor- bzw. Nachteile unterliegen der Meistergenehmigung: X“ → „Vor-/Nachteil braucht deine Genehmigung: X“.
     */
    static String lesbar(String s) {
        if (s == null) return "";
        String t = s.replaceAll("\\b\\d{12,}\\b", " ").replaceAll("\\s+", " ").trim(); // Zeitstempel der helden.jar
        t = t.replaceFirst("(?i)^Die folgenden Vor- bzw\\. Nachteile unterliegen der Meistergenehmigung:\\s*", "Vor-/Nachteil braucht deine Genehmigung: ");
        t = t.replaceFirst("(?i)^Die folgenden Sonderfertigkeiten unterliegen der Meistergenehmigung:\\s*", "Sonderfertigkeit braucht deine Genehmigung: ");
        t = t.replaceFirst("(?i)^Meistergenehmigung notwendig$", "");
        return t;
    }

    /** Abschnitte des Heldenbogens: Tag in der XML -> Überschrift. Reihenfolge = Anzeige-Reihenfolge. */
    static final LinkedHashMap<String, String> SECTIONS = new LinkedHashMap<>();
    static {
        SECTIONS.put("eigenschaft", "Eigenschaften und Basiswerte");
        SECTIONS.put("vorteil", "Vor- und Nachteile");
        SECTIONS.put("sonderfertigkeit", "Sonderfertigkeiten");
        SECTIONS.put("verbilligtesonderfertigkeit", "Verbilligte Sonderfertigkeiten");
        SECTIONS.put("talent", "Talente");
        SECTIONS.put("zauber", "Zauber");
        SECTIONS.put("kampfwerte", "Kampfwerte");
        SECTIONS.put("gegenstand", "Ausrüstung");
        SECTIONS.put("muenze", "Geld");
        SECTIONS.put("ereignis", "Ereignisse");
    }
    static final Set<String> SKIP_ATTR = Set.of("name", "key", "string", "slot", "id");

    /**
     * Ganzer Held, generisch: jedes Element eines bekannten Typs wird eine Zeile mit allen Attributen
     * und den Angaben seiner Kind-Elemente. So bleibt nichts verborgen, auch wenn sich das XML-Format ändert.
     */
    static Map<String, Object> sheet(Element held) {
        Map<String, Object> s = new LinkedHashMap<>();
        Map<String, String> basis = new LinkedHashMap<>();
        basis.put("Geschlecht", attr(first(held, "geschlecht"), "name"));
        basis.put("Rasse", attr(first(held, "rasse"), "string"));
        basis.put("Kultur", attr(first(held, "kultur"), "string"));
        Element gr = first(held, "groesse");
        if (gr != null) basis.put("Größe / Gewicht", gr.getAttribute("value") + " cm / " + gr.getAttribute("gewicht") + " Stein");
        Element au = first(held, "aussehen");
        if (au != null) basis.put("Aussehen", details(au));
        basis.put("Abenteuerpunkte", attr(first(held, "abenteuerpunkte"), "value"));
        basis.put("Freie AP", attr(first(held, "freieabenteuerpunkte"), "value"));
        basis.values().removeIf(v -> v == null || v.isBlank() || v.equals(" cm /  Stein"));
        s.put("basis", basis);

        List<Map<String, Object>> sections = new ArrayList<>();
        for (var e : SECTIONS.entrySet()) {
            List<Map<String, String>> rows = new ArrayList<>();
            for (Element el : all(held, e.getKey())) {
                String name = el.getAttribute("name");
                if (name.isEmpty()) name = el.getAttribute("string");
                if (e.getKey().equals("gegenstand")) { Element n = first(el, "name"); if (n != null && !n.getAttribute("value").isEmpty()) name = n.getAttribute("value") + " (" + name + ")"; }
                if (name.isEmpty() && el.getAttributes().getLength() == 0) continue;
                Map<String, String> row = new LinkedHashMap<>();
                row.put("name", name);
                row.put("value", value(e.getKey(), el));
                row.put("details", details(el));
                rows.add(row);
            }
            if (!rows.isEmpty()) sections.add(Map.of("title", e.getValue(), "tag", e.getKey(), "rows", rows));
        }
        List<String> notes = new ArrayList<>();
        for (Element n : all(held, "notiz")) {
            NamedNodeMap at = n.getAttributes();
            for (int i = 0; i < at.getLength(); i++) { String v = at.item(i).getNodeValue(); if (!v.isBlank() && !v.equals("Notizen")) notes.add(v); }
        }
        s.put("sections", sections);
        s.put("notizen", notes);
        return s;
    }

    /** Der eine Zahlenwert, nach dem man sortiert/sucht. */
    static String value(String tag, Element el) {
        switch (tag) {
            case "eigenschaft": {
                int v = num(el.getAttribute("value")) + num(el.getAttribute("mod"));
                return el.hasAttribute("value") ? String.valueOf(v) : "";
            }
            case "kampfwerte": return "AT " + attr(first(el, "attacke"), "value") + " / PA " + attr(first(el, "parade"), "value");
            case "gegenstand": case "muenze": return el.getAttribute("anzahl");
            default: return el.getAttribute("value");
        }
    }

    /** Alle übrigen Attribute plus Kind-Elemente, lesbar als "a: b · c: d". */
    static String details(Element el) {
        List<String> out = new ArrayList<>();
        NamedNodeMap at = el.getAttributes();
        for (int i = 0; i < at.getLength(); i++) {
            String k = at.item(i).getNodeName(), v = at.item(i).getNodeValue();
            if (!SKIP_ATTR.contains(k) && !k.equals("value") && !k.equals("anzahl") && !v.isBlank()) out.add(k + ": " + v.trim());
        }
        NodeList kids = el.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            if (!(kids.item(i) instanceof Element c)) continue;
            String d = c.getAttribute("name").isEmpty() ? c.getAttribute("value") : c.getAttribute("name");
            List<String> rest = new ArrayList<>();
            NamedNodeMap ca = c.getAttributes();
            for (int j = 0; j < ca.getLength(); j++) {
                String k = ca.item(j).getNodeName(), v = ca.item(j).getNodeValue();
                if (!k.equals("name") && !(k.equals("value") && d.equals(v)) && !v.isBlank()) rest.add(k + " " + v);
            }
            String inner = details(c);
            String part = c.getTagName() + (d.isEmpty() ? "" : " " + d) + (rest.isEmpty() ? "" : " (" + String.join(", ", rest) + ")");
            if (!inner.isEmpty() && rest.isEmpty()) part += " [" + inner + "]";
            out.add(part.trim());
        }
        return String.join(" · ", out);
    }

    static Element first(Element p, String tag) {
        if (p == null) return null;
        NodeList n = p.getElementsByTagName(tag);
        return n.getLength() == 0 ? null : (Element) n.item(0);
    }

    /**
     * Alle Elemente mit diesem Namen unter p, aber nicht aus Begleitern: Tiere, Vertraute, Dämonen usw. stehen als
     * gegenstand/Wesen mit eigenen Eigenschaften, Vorteilen, SF und Zaubern im Helden. Die gehören nicht zum Helden
     * (früher überschrieb z. B. der MU des Raben den MU der Hexe). Ist p selbst ein Wesen (oder darin), zählt alles darunter.
     */
    static List<Element> all(Element p, String tag) {
        NodeList n = p.getElementsByTagName(tag);
        List<Element> out = new ArrayList<>(n.getLength());
        for (int i = 0; i < n.getLength(); i++) {
            Element e = (Element) n.item(i);
            boolean imWesen = false;
            for (Node x = e.getParentNode(); x != null && x != p; x = x.getParentNode()) if ("Wesen".equals(x.getNodeName())) { imWesen = true; break; }
            if (!imWesen) out.add(e);
        }
        return out;
    }

    /** Begleiter des Helden (Tier, Vertrauter, Dämon …): Name, Gattung, Familie, Art und eigene Vor-/Nachteile, SF, Zauber, Talente. */
    static List<Map<String, Object>> begleiter(Element held) {
        List<Map<String, Object>> out = new ArrayList<>();
        NodeList ws = held.getElementsByTagName("Wesen");
        for (int i = 0; i < ws.getLength(); i++) {
            Element w = (Element) ws.item(i);
            Element geg = w.getParentNode() instanceof Element g ? g : null;
            String name = geg == null ? "" : attr(first(geg, "name"), "value");
            if (name.isBlank() && geg != null) name = geg.getAttribute("name");
            Map<String, Object> b = new LinkedHashMap<>();
            b.put("name", name.isBlank() ? "Begleiter" : name);
            b.put("gattung", attr(first(w, "gattung"), "value"));
            b.put("familie", attr(first(w, "familie"), "value"));
            b.put("art", attr(first(w, "wesensart"), "value"));
            Map<String, String> regeln = new LinkedHashMap<>();
            for (Element v : all(w, "vorteil")) regeln.putIfAbsent(v.getAttribute("name"), v.getAttribute("value"));
            for (Element s : all(w, "sonderfertigkeit")) regeln.putIfAbsent(s.getAttribute("name"), "");
            for (Element z : all(w, "zauber")) regeln.putIfAbsent(z.getAttribute("name"), z.getAttribute("value").isBlank() ? "" : "ZfW " + z.getAttribute("value"));
            for (Element t : all(w, "talent")) regeln.putIfAbsent(t.getAttribute("name"), t.getAttribute("value").isBlank() ? "" : "TaW " + t.getAttribute("value"));
            regeln.remove("");
            b.put("regeln", regeln);
            out.add(b);
        }
        return out;
    }

    static String attr(Element e, String a) { return e == null ? "" : e.getAttribute(a); }

    static int num(String s) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return 0; }
    }

    static String utf8(byte[] b) { return new String(b, StandardCharsets.UTF_8); }
}
