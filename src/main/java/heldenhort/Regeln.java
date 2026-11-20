package heldenhort;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jsoup.Jsoup;
import org.jsoup.safety.Safelist;

import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.*;
import java.util.stream.Stream;

/**
 * Regeltexte für die ⓘ-Knöpfe aus /data/regeln/*.json.
 * Die Dateien legt der Meister selbst per FTP ab (aus den eigenen Regelwerken erzeugt), sie gehören nie ins Repo.
 * Format: {"eintraege":[{"name","namen":[…],"art","zusatz","quelle","text"(HTML)}]}. Kosten stehen bewusst nicht drin, die liefert die helden.jar.
 * Neu eingelesen wird automatisch, sobald sich eine Datei ändert.
 */
final class Regeln {
    record Eintrag(String name, List<String> namen, String art, String zusatz, String quelle, String text) {}

    private final Path dir;
    private volatile List<Eintrag> alle = List.of();
    private volatile Map<String, List<Eintrag>> exakt = Map.of();
    private volatile String stamp = "";
    private volatile String fehler = "";

    Regeln(Path dir) { this.dir = dir; }

    Path dir() { return dir; }

    /** Alle passenden Einträge, bester Treffer zuerst (je Quelle höchstens einer). */
    synchronized List<Eintrag> suche(String name) {
        laden();
        String n = norm(name);
        if (n.isEmpty() || alle.isEmpty()) return List.of();
        List<Eintrag> r = exakt.get(n);
        if (r == null) r = exakt.get(norm(ohneKlammern(name)));
        if (r == null && name.contains(":")) {
            String teil = name.substring(name.indexOf(':') + 1);
            r = exakt.get(norm(teil));
            if (r == null) r = exakt.get(norm(ohneKlammern(teil)));
        }
        if (r == null) r = exakt.get(n.replace(" ", ""));
        if (r == null || r.isEmpty()) r = unscharf(name);
        // „Merkmalskenntnis: Einfluss“ → allgemeiner Eintrag „Merkmalskenntnis [einzelnes Merkmal]“
        if (r.isEmpty() && name.contains(":")) r = exakt.getOrDefault(norm(name.substring(0, name.indexOf(':'))), List.of());
        // „Talentspezialisierung Klettern (Freiklettern)“, „Vorurteile gegen …“: hintere Wörter abschneiden
        if (r.isEmpty()) {
            String[] w = norm(ohneKlammern(name.replace(':', ' '))).split(" ");
            for (int k = w.length - 1; k >= 1 && r.isEmpty(); k--) {
                String kurz = String.join(" ", Arrays.copyOf(w, k));
                if (kurz.length() >= 5) r = exakt.getOrDefault(kurz, List.of());
            }
        }
        // je Quelle (Buch) nur der erste Treffer
        Set<String> buch = new HashSet<>();
        List<Eintrag> out = new ArrayList<>();
        for (Eintrag e : r) if (buch.add(e.quelle().replaceAll("\\s*S\\..*$", "") + "|" + e.name())) out.add(e);
        return out;
    }

    private static final List<String> ART = List.of("Tier", "Wesen", "Vorteil", "Nachteil", "Sonderfertigkeit", "Tierfähigkeit", "Talent", "Ritual", "Liturgie", "Zauber");

    /**
     * Regelzusammenfassung als HTML für Vor-/Nachteile und Sonderfertigkeiten (inkl. Manöver, Rituale, Liturgien).
     * helden: Heldenname → (Name aus dem Helden → Wert, z. B. „Goldgier“ → „7“). Jeder Regeltext steht nur einmal, mit den Helden, die ihn haben
     * (mitHelden=false für den Bogen eines einzelnen Helden). Namen ohne Regeltext stehen am Ende.
     */
    String zusammenfassung(Map<String, ? extends Map<String, String>> helden, boolean mitHelden) { return zusammenfassung(helden, mitHelden, this::suche); }

    /** Treffer bevorzugt aus diesen Arten (z. B. bei Begleitern Tier-Fähigkeiten statt der gleichnamigen Helden-SF). */
    List<Eintrag> suche(String name, java.util.Collection<String> bevorzugt) {
        List<Eintrag> l = suche(name);
        List<Eintrag> b = l.stream().filter(e -> bevorzugt.contains(e.art())).toList();
        return b.isEmpty() ? l : b;
    }

    /** Tierart eines Begleiters: erst über den Namen (Familie, dann Gattung), sonst die Bestiariums-Seite, die das Wort am häufigsten nennt. */
    synchronized List<Eintrag> tierart(String... woerter) {
        laden();
        for (String w : woerter) {
            if (w == null || w.isBlank()) continue;
            List<Eintrag> l = suche(w, List.of("Tier", "Wesen"));
            if (!l.isEmpty() && List.of("Tier", "Wesen").contains(l.get(0).art())) return l.subList(0, 1);
        }
        for (String w : woerter) {
            if (w == null || w.isBlank()) continue;
            java.util.regex.Pattern p = java.util.regex.Pattern.compile("(?U)\\b" + java.util.regex.Pattern.quote(w) + "\\w{0,2}\\b", java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.UNICODE_CASE);
            Eintrag best = null; long max = 0;
            for (Eintrag e : alle) if ("Tier".equals(e.art())) {
                long n = p.matcher(e.text()).results().count();
                if (n > max) { max = n; best = e; }
            }
            if (best != null) return List.of(best);
        }
        return List.of();
    }

    /** wie oben, aber mit eigener Suche (Begleiter: Tier-Fähigkeiten bevorzugt, „Tierart: …“ übers Bestiarium). */
    String zusammenfassung(Map<String, ? extends Map<String, String>> helden, boolean mitHelden, java.util.function.Function<String, List<Eintrag>> finder) {
        record Gruppe(List<Eintrag> e, Map<String, Set<String>> wer) {}
        Map<String, Gruppe> gruppen = new LinkedHashMap<>();
        Map<String, Set<String>> ohne = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        helden.forEach((held, namen) -> {
            for (Map.Entry<String, String> nw : namen.entrySet()) {
                String n = nw.getKey();
                if (n == null || n.isBlank()) continue;
                List<Eintrag> l = finder.apply(n);
                if (nw.getValue() != null && !nw.getValue().isBlank()) n = n.trim() + " " + nw.getValue().trim(); // Anzeige mit Wert
                if (l.isEmpty()) { ohne.computeIfAbsent(n.trim(), x -> new TreeSet<>()).add(held); continue; }
                String key = l.stream().map(e -> e.name() + "|" + e.quelle()).sorted().reduce("", String::concat);
                gruppen.computeIfAbsent(key, k -> new Gruppe(l, new TreeMap<>(String.CASE_INSENSITIVE_ORDER)))
                        .wer().computeIfAbsent(held, x -> new TreeSet<>()).add(n.trim());
            }
        });
        List<Gruppe> sortiert = new ArrayList<>(gruppen.values());
        sortiert.sort(Comparator.comparingInt((Gruppe g) -> { int i = ART.indexOf(g.e().get(0).art()); return i < 0 ? 99 : i; })
                .thenComparing(g -> norm(g.e().get(0).name())));
        StringBuilder sb = new StringBuilder();
        String art = null;
        for (Gruppe g : sortiert) {
            Eintrag h = g.e().get(0);
            if (!Objects.equals(art, h.art())) { art = h.art(); sb.append("<h2>").append(esc(mehrzahl(art))).append("</h2>"); }
            // Name wie im Helden (z. B. „Objektsegen (II)“), wenn er vom Buchtitel abweicht
            Set<String> heldNamen = new TreeSet<>();
            g.wer().values().forEach(heldNamen::addAll);
            String titel = heldNamen.size() == 1 ? heldNamen.iterator().next() : h.name();
            sb.append("<h3>").append(esc(titel)).append("</h3>");
            if (mitHelden) {
                List<String> w = new ArrayList<>();
                g.wer().forEach((held, ns) -> w.add(esc(held) + (ns.size() == 1 && ns.contains(titel) ? "" : " <i>(" + esc(String.join(", ", ns)) + ")</i>")));
                sb.append("<p><b>Helden:</b> ").append(String.join(", ", w)).append("</p>");
            }
            for (Eintrag e : g.e()) {
                sb.append("<p><i>").append(esc(e.name())).append(e.zusatz().isBlank() ? "" : " · " + esc(e.zusatz())).append(" · ").append(esc(e.quelle())).append("</i></p>");
                sb.append(e.text());
            }
        }
        if (!ohne.isEmpty()) {
            sb.append("<h2>Ohne Regeltext</h2><ul>");
            ohne.forEach((n, ws) -> sb.append("<li>").append(esc(n)).append(mitHelden ? " – " + esc(String.join(", ", ws)) : "").append("</li>"));
            sb.append("</ul>");
        }
        return sb.toString();
    }

    private static String mehrzahl(String art) {
        return switch (art == null ? "" : art) {
            case "Vorteil" -> "Vorteile"; case "Nachteil" -> "Nachteile"; case "Sonderfertigkeit" -> "Sonderfertigkeiten und Manöver";
            case "Ritual" -> "Rituale"; case "Liturgie" -> "Liturgien"; case "Zauber" -> "Zauber"; case "Talent" -> "Talente";
            case "Tierfähigkeit" -> "Fähigkeiten und Abrichtung"; case "Tier" -> "Tierart"; case "Wesen" -> "Wesen und ihre Eigenschaften"; default -> "Sonstiges";
        };
    }

    private static String esc(String s) { return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"); }

    boolean leer() { laden(); return alle.isEmpty(); }

    Map<String, Object> status() {
        laden();
        List<String> dateien = new ArrayList<>();
        try (Stream<Path> s = Files.isDirectory(dir) ? Files.list(dir) : Stream.empty()) {
            s.filter(p -> p.toString().endsWith(".json")).forEach(p -> dateien.add(p.getFileName().toString()));
        } catch (Exception ignored) { }
        Map<String, Integer> proArt = new TreeMap<>();
        for (Eintrag e : alle) proArt.merge(e.art().isBlank() ? "sonstige" : e.art(), 1, Integer::sum);
        return Map.of("pfad", dir.toString(), "dateien", dateien, "anzahl", alle.size(), "proArt", proArt, "fehler", fehler);
    }

    private void laden() {
        String st = stempel();
        if (st.equals(stamp)) return;
        List<Eintrag> neu = new ArrayList<>();
        StringBuilder err = new StringBuilder();
        ObjectMapper om = new ObjectMapper();
        try (Stream<Path> s = Files.isDirectory(dir) ? Files.list(dir) : Stream.empty()) {
            for (Path p : s.filter(p -> p.toString().endsWith(".json")).sorted().toList()) {
                try {
                    JsonNode root = om.readTree(p.toFile());
                    JsonNode arr = root.isArray() ? root : root.path("eintraege");
                    for (JsonNode e : arr) {
                        String name = e.path("name").asText("").trim();
                        if (name.isEmpty()) continue;
                        List<String> namen = new ArrayList<>(List.of(name));
                        for (JsonNode a : e.path("namen")) if (!a.asText("").isBlank()) namen.add(a.asText());
                        neu.add(new Eintrag(name, namen, e.path("art").asText(""), e.path("zusatz").asText(""), e.path("quelle").asText(p.getFileName().toString()),
                                Jsoup.clean(e.path("text").asText(""), Safelist.basic().addTags("h3", "h4", "table", "tr", "td", "th", "tbody", "thead"))));
                    }
                } catch (Exception ex) {
                    err.append(p.getFileName()).append(": ").append(ex.getMessage()).append("\n");
                }
            }
        } catch (Exception ex) { err.append(ex.getMessage()); }
        Map<String, List<Eintrag>> idx = new HashMap<>();
        for (Eintrag e : neu) for (String a : e.namen()) {
            for (String k : new LinkedHashSet<>(List.of(norm(a), norm(a.replaceAll("\\([^()]*\\)", " ")), norm(ohneKlammern(a)), norm(a).replace(" ", "")))) if (!k.isEmpty()) {
                List<Eintrag> l = idx.computeIfAbsent(k, x -> new ArrayList<>());
                if (!l.contains(e)) l.add(e);
            }
        }
        alle = neu; exakt = idx; fehler = err.toString().trim(); stamp = st;
        System.out.println("Regeltexte: " + neu.size() + " Einträge aus " + dir + (fehler.isEmpty() ? "" : " – Fehler: " + fehler));
    }

    private String stempel() {
        StringBuilder sb = new StringBuilder();
        try (Stream<Path> s = Files.isDirectory(dir) ? Files.list(dir) : Stream.empty()) {
            for (Path p : s.filter(p -> p.toString().endsWith(".json")).sorted().toList())
                sb.append(p.getFileName()).append(Files.size(p)).append(Files.getLastModifiedTime(p).toMillis()).append(';');
        } catch (Exception ignored) { }
        return sb.toString();
    }

    /**
     * Unscharfe Suche für Namen wie „Kugelzauber: Bindung“ (Buch: „Bindung der Kugel“) oder
     * „Druidisches Dolchritual: Ernte“ (Buch: „Ernte des Dolches“): alle Wörter hinter dem Doppelpunkt müssen vorkommen,
     * Wörter davor geben Bonuspunkte. Wörter werden auf ihren Anfang verkürzt verglichen (Dolches ~ Dolchritual).
     */
    private List<Eintrag> unscharf(String name) {
        String haupt = name.contains(":") ? name.substring(name.indexOf(':') + 1) : name;
        String gruppe = name.contains(":") ? name.substring(0, name.indexOf(':')) : "";
        List<String> muss = woerter(ohneKlammern(haupt)), extra = woerter(gruppe);
        if (muss.isEmpty()) return List.of();
        record T(Eintrag e, int score) {}
        List<T> treffer = new ArrayList<>();
        for (Eintrag e : alle) {
            int best = Integer.MIN_VALUE;
            for (String a : e.namen()) {
                List<String> w = woerter(ohneKlammern(a));
                if (w.isEmpty() || !muss.stream().allMatch(m -> w.stream().anyMatch(x -> gleich(m, x)))) continue;
                int sc = 10 - (w.size() - muss.size()) * 2;
                for (String g : extra) if (w.stream().anyMatch(x -> aehnlich(g, x, 4))) sc += 100;
                best = Math.max(best, sc);
            }
            if (best > Integer.MIN_VALUE) treffer.add(new T(e, best));
        }
        if (treffer.isEmpty()) return List.of();
        int max = treffer.stream().mapToInt(T::score).max().orElse(0);
        // nur eindeutige Treffer: ohne Gruppenbonus und bei vielen Gleichstarken lieber nichts als Falsches
        List<Eintrag> top = treffer.stream().filter(t -> t.score() == max).map(T::e).toList();
        // Mit Gruppe („Kugelzauber: …“) muss die Gruppe im Buchnamen anklingen, sonst greift später der Gruppeneintrag
        if (!extra.isEmpty() && max < 50) return List.of();
        return top.size() > 3 ? List.of() : top;
    }

    private static final Set<String> STOP = Set.of("der", "die", "das", "des", "dem", "den", "und", "von", "vom", "zur", "zum", "ein", "eine", "auf", "im", "in");

    private static List<String> woerter(String s) {
        List<String> out = new ArrayList<>();
        for (String w : norm(s).split(" ")) if (!w.isEmpty() && !STOP.contains(w)) out.add(w);
        return out;
    }

    /** gleiches Wort bis auf die Endung (lähmen/lähmt, Dolch/Dolches), nicht nur gleicher Anfang (Hexensalbe ≠ Hexenschuss). */
    private static boolean gleich(String a, String b) {
        if (a.equals(b)) return true;
        int min = Math.min(a.length(), b.length()), k = 0;
        if (min < 4) return false;
        while (k < min && a.charAt(k) == b.charAt(k)) k++;
        return k >= Math.max(4, Math.max(a.length(), b.length()) - 3) || k == min && min >= 5 && Math.max(a.length(), b.length()) - min <= 3;
    }

    private static boolean aehnlich(String a, String b, int n) {
        if (a.equals(b)) return true;
        // römische Ziffern und kurze Wörter nur exakt
        if (a.length() < 4 || b.length() < 4) return false;
        int k = Math.min(n, Math.min(a.length(), b.length()));
        return a.regionMatches(0, b, 0, k) && (k >= Math.min(a.length(), b.length()) || k == n);
    }

    static String ohneKlammern(String s) { return s.replaceAll("\\([^()]*\\)|\\[[^\\[\\]]*\\]", " "); }

    /** klein, ohne Akzente/Satzzeichen, ä→ae usw., Mehrfach-Leerzeichen weg. */
    static String norm(String s) {
        if (s == null) return "";
        String t = s.toLowerCase(Locale.GERMAN).replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss").replace("­", "");
        t = Normalizer.normalize(t, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return t.replace("ph", "f").replaceAll("[^a-z0-9]+", " ").trim(); // Geografie = Geographie
    }
}
