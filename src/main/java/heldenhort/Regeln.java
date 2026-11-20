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
    record Eintrag(String name, List<String> namen, String art, String zusatz, String quelle, String text, String buchkosten) {}

    private final Path dir;
    // Aktueller Suchbereich (je Aufruf mit nimm(...) gesetzt; alle Zugriffe laufen synchronisiert)
    private List<Eintrag> alle = List.of();
    private Map<String, List<Eintrag>> exakt = Map.of();
    private volatile String stamp = "";
    private volatile String fehler = "";

    /** Dateiname → Rubrik: regeln-sonderfertigkeiten.json gehört zu „Sonderfertigkeit“ usw. Unbekannte Dateien gelten für alle Rubriken. */
    static final Map<String, String> TEIL_ART = Map.ofEntries(Map.entry("vorteile", "Vorteil"), Map.entry("nachteile", "Nachteil"),
            Map.entry("sonderfertigkeiten", "Sonderfertigkeit"), Map.entry("talente", "Talent"), Map.entry("zauber", "Zauber"),
            Map.entry("rituale", "Ritual"), Map.entry("liturgien", "Liturgie"), Map.entry("wesen-daemonen", "Wesen"),
            Map.entry("tier-faehigkeiten", "Tierfähigkeit"), Map.entry("tierarten", "Tier"), Map.entry("waffen", "Waffe"),
            Map.entry("schilde", "Schild"), Map.entry("ruestungen", "Rüstung"));
    private record Datei(String stempel, List<Eintrag> eintraege) {}
    private record Bereich(List<Eintrag> alle, Map<String, List<Eintrag>> exakt) {}
    private final Map<Path, Datei> dateiCache = new HashMap<>();
    private final Map<String, Bereich> bereiche = new HashMap<>();

    static String artDerDatei(Path p) {
        String n = p.getFileName().toString().toLowerCase(Locale.ROOT).replaceAll("\\.json$", "").replaceFirst("^regeln-", "");
        return TEIL_ART.get(n);
    }

    Regeln(Path dir) { this.dir = dir; }

    Path dir() { return dir; }

    /** Alle passenden Einträge aus allen Rubriken, bester Treffer zuerst (je Quelle höchstens einer). */
    synchronized List<Eintrag> suche(String name) { laden(); nimm(null); return sucheKern(name); }

    private List<Eintrag> sucheKern(String name) {
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
        return zusammenlegen(out);
    }

    /**
     * Gleicher Text in mehreren Büchern (z. B. SF in WdH und WdS): nur einmal zeigen, mit allen Quellen.
     * Weicht der Wortlaut nur minimal ab (ein, zwei Wörter, z. B. Errata), wird ebenfalls zusammengelegt, aber vermerkt.
     */
    private static List<Eintrag> zusammenlegen(List<Eintrag> in) {
        if (in.size() < 2) return in;
        List<Eintrag> out = new ArrayList<>();
        boolean[] weg = new boolean[in.size()];
        for (int i = 0; i < in.size(); i++) {
            if (weg[i]) continue;
            Eintrag a = in.get(i);
            List<String> quellen = new ArrayList<>(List.of(a.quelle()));
            boolean abweichung = false;
            for (int j = i + 1; j < in.size(); j++) {
                if (weg[j]) continue;
                int d = abstand(woerterText(a.text()), woerterText(in.get(j).text()));
                if (d <= 3) { weg[j] = true; quellen.add(in.get(j).quelle()); abweichung |= d > 0; }
            }
            if (quellen.size() == 1) { out.add(a); continue; }
            String text = a.text() + (abweichung ? "<p><i>Der Wortlaut in den genannten Quellen weicht minimal voneinander ab (z. B. Errata).</i></p>" : "");
            out.add(new Eintrag(a.name(), a.namen(), a.art(), a.zusatz(), String.join(" · ", quellen), text, a.buchkosten()));
        }
        return out;
    }

    private static String[] woerterText(String html) { return norm(html.replaceAll("<[^>]+>", " ")).split(" "); }

    /** Wort-Unterschiede (Einfügen/Löschen/Ersetzen), abgebrochen ab 4: nur „fast gleich“ zählt. */
    private static int abstand(String[] a, String[] b) {
        if (Math.abs(a.length - b.length) > 3) return 99;
        int i = 0, j = 0, diff = 0;
        while (i < a.length && j < b.length) {
            if (a[i].equals(b[j])) { i++; j++; continue; }
            if (++diff > 3) return 99;
            // Ersetzung, Einfügung oder Löschung: was wieder synchron macht
            if (i + 1 < a.length && j + 1 < b.length && a[i + 1].equals(b[j + 1])) { i++; j++; }
            else if (j + 1 < b.length && a[i].equals(b[j + 1])) j++;
            else if (i + 1 < a.length && a[i + 1].equals(b[j])) i++;
            else { i++; j++; }
        }
        return diff + (a.length - i) + (b.length - j);
    }

    // ------------------------------------------------------------------ Voraussetzungen aus dem Regelwerk (Ersatz für die Jar)

    private static final Map<String, String> EIG = Map.of("MU", "Mut", "KL", "Klugheit", "IN", "Intuition", "CH", "Charisma",
            "FF", "Fingerfertigkeit", "GE", "Gewandtheit", "KO", "Konstitution", "KK", "Körperkraft");

    /**
     * Für Einträge, zu denen die helden.jar keine Voraussetzung kennt („ungeprueft“): Voraussetzungen aus dem Regeltext lesen
     * und prüfen, was sich sicher prüfen lässt (Eigenschaften, Sonderfertigkeiten). Die Jar hat immer Vorrang, sie ist das gepatchte Regelwerk.
     * Ergänzt im Erstellungs-Zustand: fehlt (nicht erfüllt), voraussetzung (Originaltext), quelle.
     */
    @SuppressWarnings("unchecked")
    synchronized void voraussetzungen(Map<String, Object> zustand) {
        try {
            laden();
            if (leer()) return;
            Map<String, Integer> eig = new HashMap<>();
            for (Object o : (List<?>) zustand.getOrDefault("eigenschaften", List.of()))
                if (o instanceof Map<?, ?> m && m.get("value") instanceof Number n) eig.put(String.valueOf(m.get("name")), n.intValue());
            Set<String> hat = new HashSet<>();
            for (Object o : (List<?>) zustand.getOrDefault("sf", List.of())) if (o instanceof Map<?, ?> m) hat.add(norm(String.valueOf(m.get("name"))));
            for (Object o : (List<?>) zustand.getOrDefault("vorteile", List.of())) if (o instanceof Map<?, ?> m) hat.add(norm(String.valueOf(m.get("name"))));
            nichtAngeboten(zustand, eig, hat);
            // 0 AP laut helden.jar, im Buch aber mit Kosten (z. B. waffenlose Manöver „Block (30 AP)“): beides zeigen
            for (Object o : (List<?>) zustand.getOrDefault("sfMoeglich", List.of())) {
                if (!(o instanceof Map<?, ?> raw) || !"0".equals(String.valueOf(raw.get("ap")))) continue;
                @SuppressWarnings("unchecked") Map<String, Object> m = (Map<String, Object>) raw;
                nimm(List.of("Sonderfertigkeit"));
                for (Eintrag e : sucheKern(String.valueOf(m.get("name"))))
                    if (!e.buchkosten().isBlank()) { m.put("hinweis", "Die helden.jar führt das mit 0 AP; laut " + e.quelle() + " kostet es " + e.buchkosten() + "."); break; }
            }
            // Tier-Abrichtung/-Fähigkeiten (Zoo-Botanica) bietet die helden.jar mit 0 AP auch Helden an: die gelten nur für Tiere/Begleiter
            for (Object o : (List<?>) zustand.getOrDefault("sfMoeglich", List.of())) {
                if (!(o instanceof Map<?, ?> raw)) continue;
                @SuppressWarnings("unchecked") Map<String, Object> m = (Map<String, Object>) raw;
                String name = String.valueOf(m.get("name"));
                nimm(List.of("Sonderfertigkeit"));
                boolean heldenSf = !sucheKern(name).isEmpty() && !name.contains("(Tier)");
                if (heldenSf) continue;
                nimm(List.of("Tierfähigkeit"));
                List<Eintrag> tier = sucheKern(name);
                if (tier.isEmpty() && !name.contains("(Tier)")) continue;
                m.put("fehlt", List.of("Tier-Fähigkeit bzw. Abrichtung" + (tier.isEmpty() ? "" : " (" + tier.get(0).quelle() + ")")
                        + ": gilt nur für Tiere und Begleiter, nicht für den Helden selbst. Darum kostet sie hier auch 0 AP."));
                m.remove("ungeprueft");
            }
            for (String liste : List.of("sfMoeglich", "vorteileMoeglich"))
                for (Object o : (List<?>) zustand.getOrDefault(liste, List.of())) {
                    if (!(o instanceof Map<?, ?> raw) || !Boolean.TRUE.equals(raw.get("ungeprueft"))) continue;
                    Map<String, Object> m = (Map<String, Object>) raw;
                    String name = String.valueOf(m.get("name"));
                    for (Eintrag e : suche(name, List.of("Sonderfertigkeit", "Vorteil", "Nachteil", "Ritual", "Liturgie"))) {
                        java.util.regex.Matcher mt = java.util.regex.Pattern.compile("<b>\\s*Voraussetzung(?:en)?\\s*:?\\s*</b>(.*?)(?=<b>[^<]{2,40}:|</p>|$)", java.util.regex.Pattern.DOTALL).matcher(e.text());
                        if (!mt.find()) continue;
                        String html = mt.group(1), text = html.replaceAll("<[^>]+>", "").replaceAll("\\s+", " ").trim().replaceAll("[;,.]\\s*$", "");
                        if (text.isEmpty()) continue;
                        m.put("voraussetzung", text);
                        m.put("quelle", e.quelle());
                        List<String> fehlt = pruefe(html, text, eig, hat);
                        if (!fehlt.isEmpty()) m.put("fehlt", fehlt);
                        break;
                    }
                }
        } catch (Exception e) { System.err.println("Voraussetzungen aus dem Regelwerk: " + e); }
    }

    /**
     * Was die helden.jar für diesen Helden gar nicht anbietet (Vor-/Nachteile, Sonderfertigkeiten), trotzdem zeigen:
     * gesperrt, mit dem Grund aus dem Regelwerk (nur für Vollzauberer, nur Elfen, erfordert …, fehlende Werte).
     * Ergebnis: vorteileGesperrt / sfGesperrt im Zustand.
     */
    private void nichtAngeboten(Map<String, Object> z, Map<String, Integer> eig, Set<String> hat) {
        String wer = String.join(", ", java.util.stream.Stream.of("rasse", "kultur", "profession").map(k -> String.valueOf(z.getOrDefault(k, ""))).filter(x -> !x.isBlank() && !"null".equals(x)).toList());
        for (String[] l : new String[][]{{"vorteileMoeglich", "vorteileGesperrt", "Vorteil", "Nachteil"}, {"sfMoeglich", "sfGesperrt", "Sonderfertigkeit", ""}}) {
            Set<String> angeboten = new HashSet<>();
            for (Object o : (List<?>) z.getOrDefault(l[0], List.of())) if (o instanceof Map<?, ?> m) {
                String n = String.valueOf(m.get("name"));
                angeboten.add(norm(n)); angeboten.add(norm(ohneKlammern(n)));
            }
            List<String> arten = l[3].isEmpty() ? List.of(l[2]) : List.of(l[2], l[3]);
            nimm(arten);
            Map<String, Map<String, Object>> out = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            for (Eintrag e : alle) {
                if (!arten.contains(e.art()) || out.containsKey(e.name())) continue;
                boolean da = false;
                for (String a : e.namen()) if (angeboten.contains(norm(a)) || angeboten.contains(norm(ohneKlammern(a))) || hat.contains(norm(a)) || hat.contains(norm(ohneKlammern(a)))) { da = true; break; }
                if (da) continue;
                List<String> gruende = new ArrayList<>(grundAusZusatz(e.zusatz()));
                java.util.regex.Matcher mt = java.util.regex.Pattern.compile("<b>\\s*Voraussetzung(?:en)?\\s*:?\\s*</b>(.*?)(?=<b>[^<]{2,40}:|</p>|$)", java.util.regex.Pattern.DOTALL).matcher(e.text());
                String vtext = null;
                if (mt.find()) {
                    vtext = mt.group(1).replaceAll("<[^>]+>", "").replaceAll("\\s+", " ").trim().replaceAll("[;,.]\\s*$", "");
                    gruende.addAll(pruefe(mt.group(1), vtext, eig, hat));
                    nimm(arten);
                }
                if (gruende.isEmpty())
                    gruende.add(vtext != null && !vtext.isEmpty() ? "Voraussetzung laut Regelwerk: " + vtext
                            : "Die helden.jar bietet das für diesen Helden nicht an" + (wer.isEmpty() ? "" : " (" + wer + ")") + "; laut Regelwerk an Rasse, Kultur oder Profession gebunden");
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", e.name()); m.put("fehlt", gruende); m.put("quelle", e.quelle()); m.put("nichtAngeboten", true);
                if ("Nachteil".equals(e.art())) m.put("nachteil", true);
                if (vtext != null) m.put("voraussetzung", vtext);
                out.put(e.name(), m);
            }
            z.put(l[1], new ArrayList<>(out.values()));
        }
    }

    /** Kürzel aus dem Regelwerk („Magischer Vorteil; ZHV; nur Elfen …“) in lesbare Bedingungen übersetzen. */
    static List<String> grundAusZusatz(String zusatz) {
        List<String> g = new ArrayList<>();
        if (zusatz == null || zusatz.isBlank()) return g;
        for (String t : zusatz.split("\\s*;\\s*")) {
            String x = t.trim().replace("(", "").replace(")", "").replace("*", "");
            switch (x) {
                case "Z" -> g.add("nur für Vollzauberer");
                case "ZH" -> g.add("nur für Voll- und Halbzauberer");
                case "ZHV", "ZHV(G)", "ZHVG" -> g.add("nur für Zauberkundige (Voll-, Halb- oder Viertelzauberer)");
                case "H" -> g.add("nur für Halbzauberer");
                case "V", "VH" -> g.add("nur für Viertelzauberer");
                case "G" -> g.add("nur für Geweihte");
                case "OR" -> g.add("Objektritual: nur mit dem passenden Traditionsartefakt");
                case "ZHB", "ZHVB" -> g.add("nur für Zauberkundige, teils nur borbaradianische Repräsentation");
                default -> {
                    if (x.matches("(?i)^(nur|erfordert|automatisch|vier).*")) g.add(t.trim().replace("erfordert als Voraussetzung", "erfordert"));
                }
            }
        }
        return g;
    }

    /** Prüft Eigenschaftswerte („GE 12“, „IN und GE je 12“, „KL, IN je 13“) und genannte SF/Vorteile (kursiv, „A oder B“). */
    private List<String> pruefe(String html, String text, Map<String, Integer> eig, Set<String> hat) {
        List<String> fehlt = new ArrayList<>();
        java.util.regex.Matcher a = java.util.regex.Pattern.compile("((?:MU|KL|IN|CH|FF|GE|KO|KK)(?:\\s*(?:,|und|/)\\s*(?:MU|KL|IN|CH|FF|GE|KO|KK))*)\\s*(?:je\\s*|mindestens\\s*|von\\s*)?(\\d{1,2})\\b").matcher(text);
        while (a.find()) {
            int min = Integer.parseInt(a.group(2));
            for (String k : a.group(1).split("\\s*(?:,|und|/)\\s*")) {
                Integer v = eig.get(EIG.get(k.trim()));
                if (v != null && v < min) fehlt.add(EIG.get(k.trim()) + " mindestens " + min + " (hat " + v + ")");
            }
        }
        // Sonderfertigkeiten/Vorteile stehen im Regeltext kursiv; „oder“ zwischen zwei kursiven Namen = eins davon genügt
        java.util.regex.Matcher s = java.util.regex.Pattern.compile("<i>([^<]{3,60})</i>(\\s*oder\\s*<i>([^<]{3,60})</i>)?").matcher(html);
        while (s.find()) {
            String x = s.group(1).trim(), y = s.group(3) == null ? null : s.group(3).trim();
            if (!istSf(x) && (y == null || !istSf(y))) continue; // nur echte SF/Vorteile prüfen, sonst lieber nichts sperren
            boolean ok = hatSf(hat, x) || (y != null && hatSf(hat, y));
            if (!ok) fehlt.add("Sonderfertigkeit " + x + (y == null ? "" : " oder " + y));
        }
        return fehlt;
    }

    private boolean istSf(String name) {
        nimm(List.of("Sonderfertigkeit", "Vorteil"));
        return exakt.getOrDefault(norm(name), List.of()).stream().anyMatch(e -> List.of("Sonderfertigkeit", "Vorteil").contains(e.art())); // Nachteile im Voraussetzungstext sind Ausschlüsse, keine Forderung
    }

    private static boolean hatSf(Set<String> hat, String name) {
        String n = norm(ohneKlammern(name));
        if (n.isEmpty()) return true; // nichts Prüfbares
        for (String h : hat) if (h.equals(n) || h.startsWith(n + " ") || n.startsWith(h + " ")) return true;
        return false;
    }

    private static final List<String> ART =List.of("Tier", "Wesen", "Vorteil", "Nachteil", "Sonderfertigkeit", "Tierfähigkeit", "Talent", "Ritual", "Liturgie", "Zauber");

    /**
     * Regelzusammenfassung als HTML für Vor-/Nachteile und Sonderfertigkeiten (inkl. Manöver, Rituale, Liturgien).
     * helden: Heldenname → (Name aus dem Helden → Wert, z. B. „Goldgier“ → „7“). Jeder Regeltext steht nur einmal, mit den Helden, die ihn haben
     * (mitHelden=false für den Bogen eines einzelnen Helden). Namen ohne Regeltext stehen am Ende.
     */
    String zusammenfassung(Map<String, ? extends Map<String, String>> helden, boolean mitHelden) { return zusammenfassung(helden, mitHelden, this::suche); }

    /** Treffer bevorzugt aus diesen Arten (z. B. bei Begleitern Tier-Fähigkeiten statt der gleichnamigen Helden-SF). */
    synchronized List<Eintrag> suche(String name, java.util.Collection<String> bevorzugt) {
        // erst nur in den Dateien dieser Rubriken suchen (schneller, keine Verwechslung), dann notfalls überall
        laden(); nimm(bevorzugt);
        List<Eintrag> l = sucheKern(name);
        if (l.isEmpty()) { nimm(null); l = sucheKern(name); }
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
            nimm(List.of("Tier"));
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

    /** Gibt es überhaupt Regeldateien? (lädt nichts) */
    boolean leer() {
        try (Stream<Path> s = Files.isDirectory(dir) ? Files.list(dir) : Stream.empty()) { return s.noneMatch(p -> p.toString().endsWith(".json")); }
        catch (Exception e) { return true; }
    }

    synchronized Map<String, Object> status() {
        laden(); nimm(null);
        List<String> dateien = new ArrayList<>();
        try (Stream<Path> s = Files.isDirectory(dir) ? Files.list(dir) : Stream.empty()) {
            s.filter(p -> p.toString().endsWith(".json")).forEach(p -> dateien.add(p.getFileName().toString()));
        } catch (Exception ignored) { }
        Map<String, Integer> proArt = new TreeMap<>();
        for (Eintrag e : alle) proArt.merge(e.art().isBlank() ? "sonstige" : e.art(), 1, Integer::sum);
        return Map.of("pfad", dir.toString(), "dateien", dateien, "anzahl", alle.size(), "proArt", proArt, "fehler", fehler);
    }

    /** Verzeichnis prüfen: neue/geänderte/gelöschte Dateien → Suchbereiche neu bauen (Dateien selbst erst bei Bedarf). */
    private void laden() {
        String st = stempel();
        if (st.equals(stamp)) return;
        stamp = st;
        bereiche.clear();
        dateiCache.keySet().removeIf(p -> !Files.exists(p));
    }

    /** Suchbereich setzen: nur die Dateien dieser Rubriken (null = alle). Dateien werden erst beim ersten Bedarf gelesen. */
    private void nimm(java.util.Collection<String> arten) {
        String key = arten == null ? "*" : String.join("|", new TreeSet<>(arten));
        Bereich b = bereiche.get(key);
        if (b == null) {
            List<Eintrag> neu = new ArrayList<>();
            StringBuilder err = new StringBuilder();
            try (Stream<Path> s = Files.isDirectory(dir) ? Files.list(dir) : Stream.empty()) {
                for (Path p : s.filter(p -> p.toString().endsWith(".json")).sorted().toList()) {
                    String art = artDerDatei(p);
                    if (arten != null && art != null && !arten.contains(art)) continue; // andere Rubrik: gar nicht erst lesen
                    try { neu.addAll(datei(p)); }
                    catch (Exception ex) { err.append(p.getFileName()).append(": ").append(ex.getMessage()).append("\n"); }
                }
            } catch (Exception ex) { err.append(ex.getMessage()); }
            Map<String, List<Eintrag>> idx = new HashMap<>();
            for (Eintrag e : neu) for (String a : e.namen()) {
                for (String k : new LinkedHashSet<>(List.of(norm(a), norm(a.replaceAll("\\([^()]*\\)", " ")), norm(ohneKlammern(a)), norm(a).replace(" ", "")))) if (!k.isEmpty()) {
                    List<Eintrag> l = idx.computeIfAbsent(k, x -> new ArrayList<>());
                    if (!l.contains(e)) l.add(e);
                }
            }
            if (arten == null) fehler = err.toString().trim();
            b = new Bereich(neu, idx);
            bereiche.put(key, b);
        }
        alle = b.alle(); exakt = b.exakt();
    }

    /** Eine Datei lesen (aus dem Speicher, solange sie sich nicht geändert hat). */
    private List<Eintrag> datei(Path p) throws Exception {
        String st = Files.size(p) + ":" + Files.getLastModifiedTime(p).toMillis();
        Datei d = dateiCache.get(p);
        if (d != null && d.stempel().equals(st)) return d.eintraege();
        List<Eintrag> neu = new ArrayList<>();
        JsonNode root = new ObjectMapper().readTree(p.toFile());
        JsonNode arr = root.isArray() ? root : root.path("eintraege");
        for (JsonNode e : arr) {
            String name = e.path("name").asText("").trim();
            if (name.isEmpty()) continue;
            List<String> namen = new ArrayList<>(List.of(name));
            for (JsonNode a : e.path("namen")) if (!a.asText("").isBlank()) namen.add(a.asText());
            neu.add(new Eintrag(name, namen, e.path("art").asText(""), e.path("zusatz").asText(""), e.path("quelle").asText(p.getFileName().toString()),
                    Jsoup.clean(e.path("text").asText(""), Safelist.basic().addTags("h3", "h4", "table", "tr", "td", "th", "tbody", "thead")), e.path("buchkosten").asText("")));
        }
        dateiCache.put(p, new Datei(st, neu));
        System.out.println("Regeltexte: " + p.getFileName() + " gelesen (" + neu.size() + " Einträge)");
        return neu;
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
