package heldenhort;

import java.awt.*;
import java.beans.PropertyChangeSupport;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import javax.swing.*;

/**
 * Heldenerstellung mit der Erschaffungslogik der helden.jar, ohne Bildschirm.
 *
 * Zwei Abschnitte:
 *  1. rkp(): Regelbasis, Geschlecht, Rasse, Kultur, Profession (+ Varianten) und die Auswahlen der Profession.
 *     Wird bei jeder Anfrage aus den Angaben des Spielers neu durchgespielt. Offene Auswahlen werden als
 *     Formular beschrieben (die Swing-Panels der Jar werden ausgelesen und befüllt).
 *  2. edit()/finish(): ab dann ist der Entwurf die Entwurfs-XML der Jar (Speichern/Laden). Änderungen werden
 *     über die Jar angewendet, die Jar rechnet Kosten und prüft.
 *
 * Nur lesbare Klassen- und Methodennamen der Jar werden benutzt; verschleierte Namen nur über Typen (Reflection).
 */
public final class Creation {
    private static final String W = "helden.gui.erschaffung.werkzeug.";
    private static final String Z = "helden.gui.erschaffung.zustaende.";
    private final HeldenJar jar;
    private boolean ready;

    public Creation(HeldenJar jar) {
        this.jar = jar;
        jar.onReload(() -> { synchronized (this) { ready = false; } });
    }

    // ------------------------------------------------------------------ Abschnitt 1

    /** Spielt die Angaben durch und liefert Auswahllisten, offene Auswahl oder den fertigen Entwurf. */
    public synchronized Map<String, Object> rkp(Map<String, Object> in) throws Exception { return rkp(init(), in); }

    private Map<String, Object> rkp(ClassLoader l, Map<String, Object> in) throws Exception {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("version", jar.version());

        Class<?> setC = l.loadClass("helden.framework.settings.Setting"), setsC = l.loadClass("helden.framework.settings.Settings");
        List<String> settings = new ArrayList<>();
        for (Object s : (List<?>) call(setC, "getHauptSettings")) settings.add((String) call(s, "getName"));
        String setting = str(in.get("setting"));
        if (!settings.contains(setting)) setting = settings.get(0);
        out.put("settings", settings);
        out.put("setting", setting);
        Class<?> gC = l.loadClass("helden.framework.Geschlecht");
        List<String> gs = new ArrayList<>();
        Object g = null;
        for (Object x : gC.getEnumConstants()) { gs.add(x.toString()); if (x.toString().equals(str(in.get("geschlecht")))) g = x; }
        if (g == null) g = gC.getEnumConstants()[0];
        out.put("geschlechter", gs);
        out.put("geschlecht", g.toString());

        Object hew = newHew(l);
        Object sets = call(setsC, "getSettingsBySetting", call(setC, "getByName", setting));
        call(hew, "setSetting", sets);
        call(hew, "setGeschlecht", g);
        call(hew, "setMaxGP", 110);
        call(hew, "setMaxGPEigenschaft", 100);
        call(hew, "setMaxEigenschaft", 14);
        call(hew, "naechstePhase");

        // Rasse, Kultur, Profession nacheinander; jeder Schritt braucht den vorigen
        String[][] steps = {{"rasse", "RassenZustand"}, {"kultur", "KulturZustand"}, {"profession", "ProfessionsZustand"}};
        for (String[] st : steps) {
            Object z = l.loadClass(Z + st[1]).getConstructors()[0].newInstance(hew);
            if (st[0].equals("profession")) call(z, "getZusatzGuiElemente"); // legt Kontrollkästchen an, die setRPK braucht
            List<?> moeglich = (List<?>) call(z, "getMoeglicheRPK");
            Set<String> ueblich = new HashSet<>();
            for (Object u : (List<?>) call(z, "getUebliche")) ueblich.add(u.toString());
            List<Map<String, Object>> opts = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (Object o : moeglich) {
                if (!(Boolean) call(z, "anzeigen", o, null)) continue; // filtert u. a. andere Regelbasen
                if (!seen.add(o.toString())) continue;
                opts.add(Map.of("name", o.toString(), "ueblich", ueblich.contains(o.toString())));
            }
            Map<String, Object> step = new LinkedHashMap<>();
            step.put("options", opts);
            out.put(st[0], step);

            Map<?, ?> want = in.get(st[0]) instanceof Map<?, ?> m ? m : null;
            Object pick = null;
            if (want != null) for (Object o : moeglich) if (o.toString().equals(str(want.get("name")))) { pick = o; break; }
            if (pick == null) { out.put("next", st[0]); return out; }
            step.put("name", pick.toString());

            List<?> vs = (List<?>) call(pick, "getMoeglicheVarianten");
            List<String> vnames = new ArrayList<>();
            for (Object v : vs) vnames.add(v.toString());
            boolean needV = (Boolean) call(pick, "hatVariantenAuswahl") && !vs.isEmpty();
            step.put("varianten", vnames);
            step.put("varianteNoetig", needV);
            ArrayList<Object> chosen = new ArrayList<>();
            List<?> wantV = want.get("varianten") instanceof List<?> wl ? wl : List.of();
            for (Object v : vs) if (wantV.contains(v.toString())) chosen.add(v);
            if (needV && chosen.isEmpty()) { out.put("next", st[0]); return out; }
            step.put("gewaehlteVarianten", chosen.stream().map(Object::toString).toList());
            call(z, "setRPK", pick, chosen);
        }
        if (!(Boolean) call(hew, "isNaechstePhaseErlaubt")) throw new IllegalArgumentException("Diese Kombination lässt die helden.jar nicht zu");
        call(hew, "naechstePhase"); // -> Auswahlen

        // Auswahlen der Profession/Kultur: Panels der Jar auslesen und mit den Antworten befüllen
        Object az = l.loadClass(Z + "AuswahlZustand").getConstructors()[0].newInstance(hew);
        call(az, "getPanel");
        call(az, "update");
        List<?> answers = in.get("answers") instanceof List<?> al ? al : List.of();
        List<Map<String, Object>> done = new ArrayList<>();
        for (int i = 0; ; i++) {
            boolean more;
            try { more = (Boolean) call(az, "naechsteAuswahl"); }
            catch (RuntimeException e) { if (!e.getClass().getSimpleName().equals("forsuper") && !String.valueOf(e.getClass()).contains("KeineWeitere")) throw e; more = false; }
            if (!more) break;
            Component panel = (Component) call(az, "getAuswahlpanel");
            Map<String, Object> desc = describe(az, panel);
            desc.put("index", i);
            if (i >= answers.size() || !(answers.get(i) instanceof Map<?, ?> ans)) {
                out.put("auswahlenFertig", done);
                out.put("pending", desc);
                out.put("next", "auswahl");
                return out;
            }
            apply(panel, ans);
            boolean gueltig;
            try { gueltig = (Boolean) call(az, "auswahlGueltig"); }
            catch (java.awt.HeadlessException frage) {
                // Die Jar würde jetzt per Dialog fragen, ob Restpunkte verfallen sollen. Das geht ohne Bildschirm nicht
                // (und „Ja“ lässt sich nicht nachbauen, die Jar speichert dabei selbst). Also: alle Punkte verteilen.
                desc.put("error", "Es sind noch Punkte übrig. Bitte alle Punkte verteilen, erst dann geht es weiter.");
                out.put("auswahlenFertig", done);
                out.put("pending", desc);
                out.put("next", "auswahl");
                return out;
            }
            if (!gueltig) {
                desc.put("error", "Diese Auswahl ist so nicht gültig. Bitte genau die verlangte Anzahl wählen.");
                out.put("auswahlenFertig", done);
                out.put("pending", desc);
                out.put("next", "auswahl");
                return out;
            }
            done.add(Map.of("title", desc.get("title")));
        }
        out.put("auswahlenFertig", done);
        call(hew, "naechstePhase"); // -> Haupt
        call(hew, "setName", str(in.get("name")));
        out.put("draft", save(l, hew));
        out.put("next", "haupt");
        return out;
    }

    /**
     * Vor- und Nachteile mit Art (über die lesbare Plugin-Schnittstelle der Jar) und GP.
     * Die GP stehen in der Jar nur in einem verschleierten Feld (GP × 100, Vorteile negativ). Das Feld wird nicht über
     * seinen Namen gesucht, sondern über diese Eigenschaft; findet sich keins, fehlen die GP eben (kein Absturz bei Jar-Updates).
     */
    private static List<Map<String, Object>> vorteilInfos(ClassLoader l, Iterable<?> list) throws Exception { return vorteilInfos(l, list, null); }

    // --- Settings der helden.jar (helden/framework/settings/*.xml): welches Element gehört zu welchem Setting

    private record Setting(String name, String beschreibung, boolean haupt, List<String> support, Set<String> ids) {}
    private static final List<String> SETTING_DATEIEN = List.of("settings.xml", "settingsMyranor.xml", "settingsTharun.xml", "settingsddz.xml",
            "settingstiere.xml", "settingSpielhilfenDSA.xml", "settingSpielhilfenDDZ.xml", "settingSpielhilfenMyranor.xml", "settingescheundkork.xml",
            "settingsbote.xml", "settingsudw.xml", "settingswda.xml", "settingswesenAventurien.xml", "settingswesenMyranor.xml");
    private static volatile List<Setting> settings;

    private static List<Setting> settings(ClassLoader l) {
        if (settings != null) return settings;
        List<Setting> out = new ArrayList<>();
        for (String f : SETTING_DATEIEN) {
            try (var in = l.getResourceAsStream("helden/framework/settings/" + f)) {
                if (in == null) continue;
                var dbf = javax.xml.parsers.DocumentBuilderFactory.newInstance();
                var doc = dbf.newDocumentBuilder().parse(in);
                var nl = doc.getElementsByTagName("setting");
                for (int i = 0; i < nl.getLength(); i++) {
                    org.w3c.dom.Element st = (org.w3c.dom.Element) nl.item(i);
                    List<String> sup = new ArrayList<>();
                    Set<String> ids = new HashSet<>();
                    var su = st.getElementsByTagName("support");
                    for (int k = 0; k < su.getLength(); k++) sup.add(((org.w3c.dom.Element) su.item(k)).getAttribute("name"));
                    var inc = st.getElementsByTagName("include");
                    for (int k = 0; k < inc.getLength(); k++) ids.add(settingId(((org.w3c.dom.Element) inc.item(k)).getAttribute("id")));
                    var be = st.getElementsByTagName("beschreibung");
                    String b = be.getLength() == 0 ? "" : be.item(0).getTextContent().replaceAll("\\s+", " ").trim();
                    out.add(new Setting(st.getAttribute("name"), b, "true".equals(st.getAttribute("hauptsetting")), sup, ids));
                }
            } catch (Exception e) { System.err.println("Setting " + f + " nicht lesbar: " + e); }
        }
        return settings = out;
    }

    /** „SGelände#*“ → „SGelände“; „Gelände ()“ → „Gelände“: Auswahl-Platzhalter weg, damit beide Schreibweisen zusammenpassen. */
    private static String settingId(String id) { return id.replaceAll("#.*$", "").replaceAll("\\s*\\(\\)\\s*$", "").trim(); }

    /**
     * Was die helden.jar laut ihren Setting-Dateien nur in anderen Settings führt (Tiere, Myranor, Tharun, Die Dunklen Zeiten …), ist für
     * diesen Helden gesperrt, mit Grund. Erlaubt sind das Hauptsetting des Helden und die Settings, die es unterstützt (support).
     */
    private static void nachSetting(ClassLoader l, String haupt, String praefix, List<Map<String, Object>> liste) {
        List<Setting> alle = settings(l);
        Set<String> erlaubt = new HashSet<>(List.of(haupt));
        for (Setting st : alle) if (st.name().equals(haupt)) erlaubt.addAll(st.support());
        if (alle.stream().noneMatch(st -> st.name().equals(haupt))) return; // unbekanntes Setting: nichts sperren
        for (Map<String, Object> m : liste) {
            String id = settingId(praefix + m.get("name"));
            List<Setting> in = alle.stream().filter(st -> st.ids().contains(id)).toList();
            if (in.isEmpty() || in.stream().anyMatch(st -> erlaubt.contains(st.name()))) continue;
            String wo = String.join(", ", in.stream().map(st -> {
                String b = st.beschreibung().replaceAll("<[^>]+>", " ").replaceAll("\s+", " ").trim();
                boolean kurz = !b.isEmpty() && b.length() < 90 && !b.contains("http") && !b.startsWith(st.name());
                return "„" + st.name() + "“" + (kurz ? " (" + b + ")" : "");
            }).toList());
            List<String> fehlt = new ArrayList<>();
            fehlt.add("Gehört laut helden.jar nur zum Setting " + wo + ", nicht zu „" + haupt + "“. Für diesen Helden darum nicht wählbar.");
            if (m.get("fehlt") instanceof List<?> alt) for (Object o : alt) fehlt.add(String.valueOf(o));
            m.put("fehlt", fehlt);
            m.remove("ungeprueft");
            m.put("fremdesSetting", true);
        }
    }

    private static List<Map<String, Object>> vorteilInfos(ClassLoader l, Iterable<?> list, Object tester) throws Exception {
        List<Object> items = new ArrayList<>();
        for (Object v : list) items.add(v);
        java.lang.reflect.Constructor<?> dpc = l.loadClass("helden.plugin.datenplugin.impl.DatenPluginVorteilImpl").getConstructors()[0];
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object v : items) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", v.toString());
            try { m.put("nachteil", call(dpc.newInstance(v), "isNachteil")); } catch (Exception e) { m.put("nachteil", false); }
            for (Method mm : v.getClass().getMethods()) {
                if (mm.getParameterCount() != 0 || mm.getReturnType() != int.class || mm.getName().equals("hashCode")) continue;
                try { int x = (Integer) mm.invoke(v); if (x != 0 && x % 25 == 0 && Math.abs(x) >= 25 && Math.abs(x) <= 10000) { m.put("gp", Math.abs(x) / 100.0); break; } } catch (Exception ignored) { }
            }
            List<String> fehlt = fehlendeVoraussetzungen(tester, v);
            if (fehlt == null) m.put("ungeprueft", true); else if (!fehlt.isEmpty()) m.put("fehlt", fehlt);
            out.add(m);
        }
        out.sort(Comparator.comparing(m -> m.get("name").toString()));
        return out;
    }

    /** Beschreibt ein Auswahl-Panel als Liste von Bedienelementen mit Rasterposition. */
    private static Map<String, Object> describe(Object az, Component panel) throws Exception {
        List<Map<String, Object>> controls = new ArrayList<>();
        walk(panel, controls, new int[]{0});
        Map<String, Object> d = new LinkedHashMap<>();
        // Überschrift: Texte des Zustands-Panels außerhalb des Auswahl-Panels
        List<String> texts = new ArrayList<>();
        Object main = call(az, "getPanel");
        if (main instanceof Component c) collectTexts(c, panel, texts);
        d.put("title", texts.isEmpty() ? "Auswahl" : String.join(" ", texts));
        d.put("controls", controls);
        return d;
    }

    private static void collectTexts(Component c, Component skip, List<String> out) {
        if (c == skip || !c.isVisible()) return;
        String t = c instanceof JLabel lb ? lb.getText() : c instanceof javax.swing.text.JTextComponent tc ? tc.getText() : null;
        if (t != null) { t = t.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim(); if (!t.isEmpty() && !out.contains(t)) out.add(t); }
        if (c instanceof JComponent jc && jc.getBorder() instanceof javax.swing.border.TitledBorder tb && tb.getTitle() != null && !tb.getTitle().isBlank()) out.add(tb.getTitle());
        if (c instanceof Container k && !(c instanceof JList<?>)) for (Component x : k.getComponents()) collectTexts(x, skip, out);
    }

    private static void walk(Component c, List<Map<String, Object>> out, int[] id) {
        if (!c.isVisible()) return;
        Map<String, Object> m = null;
        if (c instanceof JList<?> li) {
            List<String> items = new ArrayList<>();
            for (int i = 0; i < li.getModel().getSize(); i++) items.add(String.valueOf(li.getModel().getElementAt(i)));
            m = new LinkedHashMap<>(Map.of("type", "list", "items", items, "multi", li.getSelectionMode() != ListSelectionModel.SINGLE_SELECTION,
                    "selected", Arrays.stream(li.getSelectedIndices()).boxed().toList()));
        } else if (c instanceof JRadioButton || c instanceof JCheckBox) {
            AbstractButton b = (AbstractButton) c;
            m = new LinkedHashMap<>(Map.of("type", c instanceof JRadioButton ? "radio" : "check", "label", text(b.getText()), "selected", b.isSelected(), "enabled", b.isEnabled()));
        } else if (c instanceof JSpinner sp) {
            m = new LinkedHashMap<>(Map.of("type", "spin", "value", sp.getValue()));
            if (sp.getModel() instanceof SpinnerNumberModel nm) { m.put("min", nm.getMinimum()); m.put("max", nm.getMaximum()); }
        } else if (c instanceof JComboBox<?> cb) {
            List<String> items = new ArrayList<>();
            for (int i = 0; i < cb.getItemCount(); i++) items.add(String.valueOf(cb.getItemAt(i)));
            m = new LinkedHashMap<>(Map.of("type", "combo", "items", items, "selected", cb.getSelectedIndex()));
        } else if (c instanceof JLabel lb && lb.getText() != null && !text(lb.getText()).isEmpty()) {
            m = new LinkedHashMap<>(Map.of("type", "label", "label", text(lb.getText())));
        } else if (c instanceof javax.swing.text.JTextComponent tc && tc.getText() != null && !tc.getText().isBlank()) {
            m = new LinkedHashMap<>(Map.of("type", "value", "label", tc.getText().trim()));
        }
        if (m != null) {
            // Textfelder nur zur Anzeige: keine ID, damit gespeicherte Antworten älterer Entwürfe gültig bleiben
            m.put("id", "value".equals(m.get("type")) ? -1 : id[0]++);
            Container p = c.getParent();
            if (p != null && p.getLayout() instanceof GridBagLayout gb) {
                GridBagConstraints k = gb.getConstraints(c);
                m.put("row", k.gridy); m.put("col", k.gridx); m.put("grid", System.identityHashCode(p));
            } else if (p != null && p.getLayout() instanceof GridLayout gl) {
                int idx = Arrays.asList(p.getComponents()).indexOf(c), cols = gl.getColumns() > 0 ? gl.getColumns() : Math.max(1, (p.getComponentCount() + gl.getRows() - 1) / Math.max(1, gl.getRows()));
                m.put("row", idx / cols); m.put("col", idx % cols); m.put("grid", System.identityHashCode(p));
            }
            out.add(m);
        }
        if (c instanceof Container k && !(c instanceof JList<?>) && !(c instanceof JSpinner) && !(c instanceof JComboBox<?>))
            for (Component x : k.getComponents()) walk(x, out, id);
    }

    /** Setzt die Antwort des Spielers in die Bedienelemente, gleiche Reihenfolge wie walk(). */
    private static void apply(Component panel, Map<?, ?> ans) {
        List<Component> comps = new ArrayList<>();
        collect(panel, comps);
        for (int i = 0; i < comps.size(); i++) {
            Object v = ans.get(String.valueOf(i));
            if (v == null) continue;
            Component c = comps.get(i);
            if (c instanceof JList<?> li && v instanceof List<?> idx)
                li.setSelectedIndices(idx.stream().mapToInt(o -> ((Number) o).intValue()).filter(n -> n >= 0 && n < li.getModel().getSize()).toArray());
            else if (c instanceof AbstractButton b && v instanceof Boolean want && b.isSelected() != want && b.isEnabled()) b.doClick();
            else if (c instanceof JSpinner sp && v instanceof Number n) sp.setValue(n.intValue());
            else if (c instanceof JComboBox<?> cb && v instanceof Number n && n.intValue() < cb.getItemCount()) cb.setSelectedIndex(n.intValue());
        }
    }

    private static void collect(Component c, List<Component> out) {
        if (!c.isVisible()) return;
        if (c instanceof JList<?> || c instanceof JRadioButton || c instanceof JCheckBox || c instanceof JSpinner || c instanceof JComboBox<?>
                || (c instanceof JLabel lb && lb.getText() != null && !text(lb.getText()).isEmpty())) out.add(c);
        if (c instanceof Container k && !(c instanceof JList<?>) && !(c instanceof JSpinner) && !(c instanceof JComboBox<?>))
            for (Component x : k.getComponents()) collect(x, out);
    }

    private static String text(String s) { return s == null ? "" : s.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim(); }

    // ------------------------------------------------------------------ Abschnitt 2

    /** Wendet Änderungen auf den Entwurf an und liefert neuen Entwurf + Zustand. */
    public synchronized Map<String, Object> edit(String draft, List<?> ops) throws Exception { return edit(init(), draft, ops); }

    private Map<String, Object> edit(ClassLoader l, String draft, List<?> ops) throws Exception {
        String xml = draft;
        // Vor- und Nachteile: über die Entwurfs-XML (Wert setzen ist in der Jar verschleiert)
        for (Object o : ops == null ? List.of() : ops) {
            Map<?, ?> op = (Map<?, ?>) o;
            String kind = str(op.get("op")), name = str(op.get("name"));
            if (kind.equals("vorteil+")) xml = addVorteil(xml, name, str(op.get("value")));
            else if (kind.equals("vorteil-")) xml = removeVorteil(xml, name);
            else if (kind.equals("talent")) xml = setTalent(xml, name, ((Number) op.get("value")).intValue());
        }
        Object hew = load(l, xml);
        for (Object o : ops == null ? List.of() : ops) {
            Map<?, ?> op = (Map<?, ?>) o;
            String kind = str(op.get("op")), name = str(op.get("name"));
            switch (kind) {
                case "eig" -> {
                    Object e = eigenschaft(l, name);
                    Object he = call(hew, "getHswEigenschaften");
                    int v = ((Number) op.get("value")).intValue();
                    int min = (Integer) call(he, "getMinimalEigenschaftswert", e), max = (Integer) call(he, "getMaximalEigenschaftswert", e);
                    if (v < min || v > max) throw new IllegalArgumentException(name + " muss zwischen " + min + " und " + max + " liegen");
                    call(he, "setEigenschaftInklModi", e, v);
                }
                case "sf+" -> {
                    Object sfw = call(hew, "getSf"), sf = null;
                    for (Object s : (Iterable<?>) call(sfw, "getMoeglicheSonderfertigkeiten")) if (s.toString().equals(name)) sf = s;
                    if (sf == null) throw new IllegalArgumentException("Sonderfertigkeit nicht wählbar: " + name);
                    List<?> kas = (List<?>) call(sfw, "getMoeglicheKostenArten", sf);
                    Object ka = kas.get(0);
                    for (Object k : kas) if (k.toString().equals(str(op.get("kostenart")))) ka = k;
                    call(sfw, "add", sf, ka);
                }
                case "sf-" -> {
                    Object sfw = call(hew, "getSf");
                    for (Object s : ((Map<?, ?>) call(sfw, "getKostenArtFuerSf")).keySet()) if (s.toString().equals(name)) call(sfw, "remove", s);
                }
                case "name" -> call(hew, "setName", str(op.get("value")));
                default -> { }
            }
        }
        call(hew, "recalc");
        String newDraft = save(l, hew);
        Map<String, Object> out = state(l, load(l, newDraft), newDraft);
        out.put("draft", newDraft);
        return out;
    }

    /**
     * Was kostet +1 bei jedem Talent? Rechnet die helden.jar selbst: Talent probehalber um 1 erhöhen, Kosten vergleichen.
     * Liefert Talentname -> Änderung je Kostenart (z. B. {"AP": 3}), oder "nicht" -> Grund, wenn +1 nicht geht.
     */
    public Map<String, Object> talentCosts(String draft, Set<String> only) throws Exception {
        Map<String, Integer> base = baseCosts(draft);
        Map<String, Object> out = new LinkedHashMap<>();
        Set<String> aktiv = new HashSet<>();
        for (org.w3c.dom.Element t : Heroes.all(parse(draft).getDocumentElement(), "talent")) {
            if (!t.hasAttribute("value")) continue;
            String n = t.getAttribute("name");
            aktiv.add(n);
            if (only != null && !only.contains(n)) continue;
            // je Talent einzeln sperren, damit andere Spieler zwischendurch weiterarbeiten können
            try { out.put(n, costOne(draft, n, Heroes.num(t.getAttribute("value")) + 1, base)); }
            catch (Exception e) { out.put(n, Map.of("nicht", String.valueOf(e.getMessage()))); }
        }
        // noch nicht aktivierte Talente: Aktivierungskosten (Talent probehalber auf 0 setzen, rechnet die helden.jar)
        if (only != null) for (String n : only) {
            if (aktiv.contains(n)) continue;
            try { out.put(n, costOne(draft, n, 0, base)); }
            catch (Exception e) { out.put(n, Map.of("nicht", String.valueOf(e.getMessage()))); }
        }
        return out;
    }

    private synchronized Map<String, Integer> baseCosts(String draft) throws Exception { return used(load(init(), draft)); }

    private synchronized Map<String, Integer> costOne(String draft, String name, int value, Map<String, Integer> base) throws Exception {
        Object hew = load(init(), setTalent(draft, name, value));
        call(hew, "recalc");
        Map<String, Integer> d = new LinkedHashMap<>();
        used(hew).forEach((k, v) -> { int x = v - base.getOrDefault(k, 0); if (x != 0) d.put(k, x); });
        return d;
    }

    private static Map<String, Integer> used(Object hew) throws Exception {
        Object k = call(hew, "getKosten");
        Map<String, Integer> m = new LinkedHashMap<>();
        for (Object ka : (List<?>) call(k, "getAlleKostenArten")) m.put(ka.toString(), (Integer) call(k, "getKosten", ka));
        return m;
    }

    /** Fertiger Held als XML plus Meldungen der Regelprüfung. */
    public synchronized Map<String, Object> finish(String draft, String name) throws Exception { return finish(init(), draft, name); }

    /**
     * Selbsttest für eine neue helden.jar, in eigenem ClassLoader: einen Helden komplett bauen
     * (Rasse/Kultur/Profession mit Varianten, alle Auswahlen, Werte ändern, abschließen, XML).
     * Wirft, wenn irgendein Schritt der Anbindung nicht mehr passt.
     */
    public String selfTest(java.net.URLClassLoader l) throws Exception {
        ClassLoader old = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(l);
        try {
            l.loadClass("helden.framework.settings.Setting").getMethod("initAll").invoke(null);
            Map<String, Object> in = new LinkedHashMap<>(Map.of("name", "Selbsttest", "geschlecht", "männlich"));
            List<Object> answers = new ArrayList<>();
            in.put("answers", answers);
            Map<String, Object> r = null;
            for (int guard = 0; guard < 40; guard++) {
                r = rkp(l, in);
                String next = str(r.get("next"));
                if (next.equals("haupt")) break;
                if (next.equals("auswahl")) {
                    Map<?, ?> p = (Map<?, ?>) r.get("pending");
                    int idx = ((Number) p.get("index")).intValue();
                    if (p.get("error") != null && answers.size() > idx) throw new IllegalStateException("Auswahl lässt sich nicht beantworten: " + p.get("title"));
                    Map<String, Object> ans = new LinkedHashMap<>();
                    Set<Object> cols = new HashSet<>();
                    for (Object o : (List<?>) p.get("controls")) {
                        Map<?, ?> c = (Map<?, ?>) o;
                        String id = String.valueOf(c.get("id"));
                        if ("list".equals(c.get("type"))) ans.put(id, List.of(0));
                        if ("radio".equals(c.get("type")) && Boolean.TRUE.equals(c.get("enabled")) && cols.add(c.get("grid") + "-" + c.get("col"))) ans.put(id, true);
                    }
                    while (answers.size() <= idx) answers.add(null);
                    answers.set(idx, ans);
                    continue;
                }
                Map<?, ?> st = (Map<?, ?>) r.get(next);
                if (st == null) throw new IllegalStateException("Schritt fehlt: " + next);
                Map<String, Object> sel = new LinkedHashMap<>();
                if (st.get("name") == null) {
                    List<?> opts = (List<?>) st.get("options");
                    if (opts.isEmpty()) throw new IllegalStateException("Keine Optionen für " + next);
                    Object pick = opts.get(0);
                    for (Object o : opts) if (Boolean.TRUE.equals(((Map<?, ?>) o).get("ueblich"))) { pick = o; break; }
                    sel.put("name", ((Map<?, ?>) pick).get("name"));
                } else {
                    sel.put("name", st.get("name"));
                    List<?> vs = (List<?>) st.get("varianten");
                    if (Boolean.TRUE.equals(st.get("varianteNoetig")) && !vs.isEmpty()) sel.put("varianten", List.of(vs.get(0)));
                }
                in.put(next, sel);
            }
            if (r == null || r.get("draft") == null) throw new IllegalStateException("Herkunft und Profession lassen sich so nicht abschließen. Bitte alle Auswahlen treffen.");
            Map<String, Object> e = edit(l, (String) r.get("draft"), List.of());
            List<?> eig = (List<?>) e.get("eigenschaften");
            Map<?, ?> mu = (Map<?, ?>) eig.get(0);
            e = edit(l, (String) e.get("draft"), List.of(Map.of("op", "eig", "name", mu.get("name"), "value", mu.get("min"))));
            String xml = (String) finish(l, (String) e.get("draft"), "Selbsttest").get("xml");
            if (!xml.contains("Selbsttest") || !xml.contains("<eigenschaft")) throw new IllegalStateException("Die Helden-XML ist unvollständig. Bitte in der Helden-Software neu speichern.");
            // Steigern braucht die SKT-Spalte eines Talents (getSteigernKategorie über die Plugin-Schnittstelle)
            Path tf = Files.createTempFile("selbsttest", ".xml");
            try {
                Files.writeString(tf, xml);
                Object xp = l.loadClass("helden.framework.held.persistenz.XMLPersistierer").getConstructor().newInstance();
                Object h = ((List<?>) call(xp, "ladeHelden", tf.toFile())).get(0);
                Object hw = l.loadClass("helden.plugin.datenplugin.impl.DatenPluginHeldenWerkzeugImpl").getConstructor(l.loadClass("helden.framework.held.H")).newInstance(h);
                org.w3c.dom.Element t1 = null;
                for (org.w3c.dom.Element t : Heroes.all(parse(xml).getDocumentElement(), "talent")) if (t.hasAttribute("value")) { t1 = t; break; }
                if (t1 == null) throw new IllegalStateException("Kein Talent im Testhelden");
                String col = String.valueOf(call(call(call(hw, "getTalent", t1.getAttribute("name")), "getTalent"), "getSteigernKategorie"));
                if (!col.matches("[A-H]")) throw new IllegalStateException("Steigerungsspalte nicht lesbar: " + col);
            } finally { Files.deleteIfExists(tf); }
            return "Selbsttest ok (" + ((Map<?, ?>) r.get("rasse")).get("name") + ", " + ((Map<?, ?>) r.get("profession")).get("name") + ")";
        } finally { Thread.currentThread().setContextClassLoader(old); }
    }

    private Map<String, Object> finish(ClassLoader l, String draft, String name) throws Exception {
        Object hew = load(l, draft);
        if (name != null && !name.isBlank()) call(hew, "setName", name);
        List<Map<String, String>> hints = hints(hew);
        call(hew, "heldAbschliessen");
        Object xp = l.loadClass("helden.framework.held.persistenz.XMLPersistierer").getConstructor().newInstance();
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        call(xp, "writeHeldenXML", call(hew, "getHeld"), bo);
        String xml = bo.toString(StandardCharsets.UTF_8);
        // Beim Abschließen schreibt die Jar weitere Punkte als "Meistergenehmigung notwendig" in den Helden
        // (z. B. nicht genutzte AP). Alles, was nicht schon als Hinweis der Prüfung bekannt ist, ist eine Meistergenehmigung.
        Set<String> known = new HashSet<>();
        for (Map<String, String> h : hints) known.add(h.get("text"));
        List<Map<String, String>> all = new ArrayList<>(hints);
        for (org.w3c.dom.Element e : Heroes.all(parse(xml).getDocumentElement(), "ereignis"))
            if ("Meistergenehmigung notwendig".equals(e.getAttribute("text")) && known.add(e.getAttribute("obj")))
                all.add(Map.of("art", "MG", "text", e.getAttribute("obj")));
        return Map.of("xml", xml, "hinweise", all);
    }

    private Map<String, Object> state(ClassLoader l, Object hew, String draft) throws Exception {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("rasse", String.valueOf(call(hew, "getRasse")));
        s.put("kultur", String.valueOf(call(hew, "getKultur")));
        try { s.put("profession", String.valueOf(call(hew, "getProfession"))); } catch (Exception ignored) { }

        Object k = call(hew, "getKosten");
        Map<String, Object> kosten = new LinkedHashMap<>();
        for (Object ka : (List<?>) call(k, "getAlleKostenArten")) {
            int used = (Integer) call(k, "getKosten", ka), avail = (Integer) call(k, "getVerfuegbarePunkte", ka);
            if (used != 0 || avail > 0) kosten.put(ka.toString(), List.of(used, avail));
        }
        s.put("kosten", kosten);
        Object he = call(hew, "getHswEigenschaften");
        s.put("eigenschaftenGP", List.of(call(he, "getEigenschaftenGP"), call(he, "erlaubteAnzahlGPinEigenschaften")));

        // Werte aus dem Entwurf (so wie die Jar sie gespeichert hat), Grenzen aus der Jar
        var doc = parse(draft);
        org.w3c.dom.Element held = (org.w3c.dom.Element) doc.getElementsByTagName("held").item(0);
        List<Map<String, Object>> eig = new ArrayList<>();
        for (String n : List.of("Mut", "Klugheit", "Intuition", "Charisma", "Fingerfertigkeit", "Gewandtheit", "Konstitution", "Körperkraft", "Sozialstatus")) {
            org.w3c.dom.Element e = byName(held, "eigenschaft", n);
            Object eo = eigenschaft(l, n);
            int mod = e == null ? 0 : Heroes.num(e.getAttribute("mod"));
            int base = e == null ? 0 : Heroes.num(e.getAttribute("value"));
            eig.add(Map.of("name", n, "value", base + mod, "min", call(he, "getMinimalEigenschaftswert", eo), "max", call(he, "getMaximalEigenschaftswert", eo)));
        }
        s.put("eigenschaften", eig);

        List<Map<String, String>> vt = new ArrayList<>();
        for (org.w3c.dom.Element v : Heroes.all(held, "vorteil")) if (v.getParentNode() != null && !"wertvorteile".equals(v.getParentNode().getNodeName()))
            vt.add(Map.of("name", v.getAttribute("name"), "value", v.getAttribute("value")));
        s.put("vorteile", vt);
        Object hv = call(hew, "getHswVorteile");
        Object kat = call(hv, "getMoegliche");
        String setting = "DSA4.1"; // Hauptsetting des Helden (<settings name="…"/> im XML)
        org.w3c.dom.Element se = Heroes.first(held, "settings");
        if (se != null && !se.getAttribute("name").isBlank()) setting = se.getAttribute("name");
        List<Map<String, Object>> vtm = vorteilInfos(l, (Iterable<?>) call(kat, "getListe"), bedingungsTester(l, hew));
        nachSetting(l, setting, "V", vtm);
        s.put("vorteileMoeglich", vtm);

        Object sfw = call(hew, "getSf");
        Set<String> userSf = new TreeSet<>();
        Map<String, Object[]> userKosten = new HashMap<>(); // selbst gewählte SF: Kosten laut Jar und gewählte Kostenart (GP/AP)
        for (Map.Entry<?, ?> en : ((Map<?, ?>) call(sfw, "getKostenArtFuerSf")).entrySet()) {
            userSf.add(en.getKey().toString());
            Object ap = null;
            try { ap = call(en.getKey(), "getKosten"); } catch (Exception ignored) { }
            userKosten.put(en.getKey().toString(), new Object[]{ap, String.valueOf(en.getValue())});
        }
        List<Map<String, Object>> sf = new ArrayList<>();
        for (org.w3c.dom.Element e : Heroes.all(held, "sonderfertigkeit")) {
            if (e.getParentNode() == null || "kostensf".equals(e.getParentNode().getNodeName()) || "usersf".equals(e.getParentNode().getNodeName())) continue;
            String n = e.getAttribute("name");
            Map<String, Object> m = new LinkedHashMap<>(Map.of("name", n, "eigene", userSf.contains(n), "details", Heroes.details(e)));
            Object[] uk = userKosten.get(n);
            if (uk != null) { if (uk[0] != null) m.put("ap", uk[0]); m.put("kostenart", uk[1]); }
            sf.add(m);
        }
        s.put("sf", sf);
        List<Map<String, Object>> sfm = new ArrayList<>();
        Object tester = bedingungsTester(l, hew);
        for (Object x : (Iterable<?>) call(sfw, "getMoeglicheSonderfertigkeiten")) {
            List<String> kas = new ArrayList<>();
            for (Object ka : (List<?>) call(sfw, "getMoeglicheKostenArten", x)) kas.add(ka.toString());
            Map<String, Object> m = new LinkedHashMap<>(Map.of("name", x.toString(), "ap", call(x, "getKosten"), "kostenarten", kas));
            List<String> fehlt = fehlendeVoraussetzungen(tester, x);
            if (fehlt == null) m.put("ungeprueft", true); else if (!fehlt.isEmpty()) m.put("fehlt", fehlt);
            sfm.add(m);
        }
        nachSetting(l, setting, "S", sfm);
        s.put("sfMoeglich", sfm);

        Object tal = call(hew, "getTalente");
        Map<String, Object> tobj = new HashMap<>();
        for (Object t : (List<?>) call(tal, "getAktivierteTalente")) tobj.put(t.toString(), t);
        List<Map<String, Object>> talente = new ArrayList<>();
        for (org.w3c.dom.Element t : Heroes.all(held, "talent")) {
            if (!t.hasAttribute("value")) continue;
            String n = t.getAttribute("name");
            Map<String, Object> m = new LinkedHashMap<>(Map.of("name", n, "value", Heroes.num(t.getAttribute("value")), "probe", t.getAttribute("probe").trim()));
            Object to = tobj.get(n);
            if (to != null) m.put("max", call(tal, "getMaximalWert", to));
            talente.add(m);
        }
        s.put("talente", talente);
        Set<String> tm = new TreeSet<>();
        for (Object t : (List<?>) call(tal, "getNichtAktivierteTalente")) tm.add(t.toString());
        s.put("talenteMoeglich", tm);
        s.put("talenteAktivierbar", List.of(call(tal, "getAnzahlAktivierterTalente"), call(tal, "getErlaubteAnzahlAktivierterTalente")));
        s.put("hinweise", hints(hew));
        return s;
    }

    // ------------------------------------------------------------------ Voraussetzungen (BedingungsTester der helden.jar)

    /** Prüfer der helden.jar für Voraussetzungen, gebunden an den Helden im Werkzeug; null, wenn die Jar ihn nicht (mehr) hat. */
    static Object bedingungsTester(ClassLoader l, Object hew) {
        try {
            Class<?> heldC = l.loadClass("helden.framework.held.o0oO");
            Object held = null;
            for (Class<?> c = hew.getClass(); c != null && held == null; c = c.getSuperclass())
                for (Method m : c.getDeclaredMethods())
                    if (m.getParameterCount() == 0 && heldC.isAssignableFrom(m.getReturnType())) { m.setAccessible(true); held = m.invoke(hew); if (held != null) break; }
            if (held == null) return null;
            return l.loadClass("helden.framework.held.C.floatsuper").getConstructor(heldC).newInstance(held);
        } catch (Throwable e) { System.err.println("Voraussetzungs-Prüfer nicht verfügbar: " + e); return null; }
    }

    /**
     * Nicht erfüllte Voraussetzungen eines Elements (SF, Vorteil …), so wie die helden.jar sie formuliert.
     * Leer = erfüllt; null = die Jar kennt keine Voraussetzung (dann prüft das Regelwerk als Ersatz, siehe Regeln.voraussetzungen).
     */
    @SuppressWarnings("unchecked")
    static List<String> fehlendeVoraussetzungen(Object tester, Object element) {
        if (tester == null || element == null) return null;
        try {
            Class<?> bvC = tester.getClass().getClassLoader().loadClass("helden.framework.bedingungen.BedingungsVerknuepfung");
            Class<?> abC = tester.getClass().getClassLoader().loadClass("helden.framework.bedingungen.AbstraktBedingung");
            // SF liefern eine Verknüpfung, Vor-/Nachteile eine einzelne AbstraktBedingung: beides nehmen, Einzelne in UND verpacken
            Object bed = null;
            for (Class<?> c = element.getClass(); c != null && bed == null; c = c.getSuperclass())
                for (Method m : c.getDeclaredMethods())
                    if (m.getParameterCount() == 0 && abC.isAssignableFrom(m.getReturnType()) && !java.lang.reflect.Modifier.isStatic(m.getModifiers())) {
                        m.setAccessible(true); bed = m.invoke(element); if (bed != null) break;
                    }
            if (bed != null && !bvC.isInstance(bed)) {
                Object arr = java.lang.reflect.Array.newInstance(abC, 1);
                java.lang.reflect.Array.set(arr, 0, bed);
                bed = bvC.getMethod("AND", arr.getClass()).invoke(null, arr);
            }
            if (bed == null || (Boolean) bvC.getMethod("isEmpty").invoke(bed)) return null; // Jar kennt keine Voraussetzung → Regelwerk prüft
            List<Method> leaf = new ArrayList<>();
            for (Method m : tester.getClass().getMethods())
                if (m.getReturnType() == boolean.class && m.getParameterCount() == 1 && m.getParameterTypes()[0] == abC && !java.lang.reflect.Modifier.isStatic(m.getModifiers())) leaf.add(m);
            if (DEBUG_BED) {
                StringBuilder sb = new StringBuilder();
                for (Object b : (List<?>) bvC.getMethod("getBedingungen").invoke(bed)) {
                    sb.append("\n   ").append(b.getClass().getSimpleName()).append(" ").append(b);
                    for (Method m : leaf) sb.append(" | ").append(m.invoke(tester, b));
                }
                System.err.println("Voraussetzung " + element + " [" + bvC.getMethod("getVerknuepfungsArt").invoke(bed) + "]" + sb);
            }
            // Gesamturteil der Jar zuerst: MOEGLICH = nichts fehlt (Enum-Name ist lesbar)
            for (Method m : tester.getClass().getMethods())
                if (m.getParameterCount() == 3 && m.getParameterTypes()[0] == bvC && m.getParameterTypes()[1] == ArrayList.class) {
                    Object erg = m.invoke(tester, bed, new ArrayList<String>(), new ArrayList<String>());
                    if (String.valueOf(erg).toUpperCase(Locale.ROOT).startsWith("MOEGLICH")) return List.of();
                    break;
                }
            List<String> f = fehlend(tester, bed, bvC, leaf);
            // Jar sagt „noch nicht sicher“, aber kein Einzelpunkt ist eindeutig offen: dann die Bedingung im Wortlaut zeigen
            return f.isEmpty() ? List.of(lesbar(String.valueOf(bed)) + " (laut helden.jar noch nicht sicher erfüllt)") : f;
        } catch (Throwable e) { if (DEBUG_BED) System.err.println("Voraussetzung " + element + ": " + e); }
        return null;
    }

    static boolean DEBUG_BED = Boolean.getBoolean("heldenhort.debugBedingungen");

    /** Bedingungsbaum auswerten: UND = alle nicht erfüllten Teile, ODER = „eins von …“, wenn keiner erfüllt ist. */
    private static List<String> fehlend(Object tester, Object node, Class<?> bvC, List<Method> leaf) throws Exception {
        if (bvC.isInstance(node)) {
            String art = String.valueOf(bvC.getMethod("getVerknuepfungsArt").invoke(node)).toUpperCase(Locale.ROOT);
            List<List<String>> teile = new ArrayList<>();
            for (Object b : (List<?>) bvC.getMethod("getBedingungen").invoke(node)) teile.add(fehlend(tester, b, bvC, leaf));
            if (art.contains("OR") || art.contains("ODER")) {
                if (teile.stream().anyMatch(List::isEmpty)) return List.of();
                List<String> alle = new ArrayList<>();
                teile.forEach(alle::addAll);
                return alle.size() == 1 ? alle : List.of("eins von: " + String.join(" oder ", alle));
            }
            List<String> out = new ArrayList<>();
            teile.forEach(out::addAll);
            return out;
        }
        if (leaf.isEmpty()) return List.of();
        // Zwei Prüfmethoden der Jar (Reihenfolge nicht fest): offen nur, wenn keine den Punkt als erfüllt meldet
        boolean ok = false;
        for (Method m : leaf) ok |= (Boolean) m.invoke(tester, node);
        return ok ? List.of() : List.of(lesbar(String.valueOf(node)));
    }

    /** „Gewandtheit>=12“ → „Gewandtheit mindestens 12“, „(A ODER B)“ → „A oder B“ */
    static String lesbar(String b) {
        return b.trim().replaceAll("^\\((.*)\\)$", "$1").replace(">=", " mindestens ").replace("<=", " höchstens ")
                .replace(" ODER ", " oder ").replace(" UND ", " und ").replaceAll("\\s+", " ").trim();
    }

    private static List<Map<String, String>> hints(Object hew) throws Exception {
        List<Map<String, String>> out = new ArrayList<>();
        for (Object h : (List<?>) call(call(hew, "getPruefer"), "pruefe")) {
            String art = String.valueOf(call(h, "getHinweisart"));
            out.add(Map.of("art", art.startsWith("MEISTER") ? "MG" : art.equals("FEHLER") ? "F" : "H", "text", h.toString()));
        }
        return out;
    }

    // ------------------------------------------------------------------ Helfer

    private synchronized ClassLoader init() throws Exception {
        ClassLoader l = jar.loader();
        Thread.currentThread().setContextClassLoader(l); // die Jar lädt Icons/Ressourcen über den Thread-ClassLoader
        if (!ready) {
            l.loadClass("helden.framework.settings.Setting").getMethod("initAll").invoke(null);
            ready = true;
        }
        return l;
    }

    private static Object newHew(ClassLoader l) throws Exception {
        return l.loadClass(W + "HEW2").getConstructor(PropertyChangeSupport.class).newInstance(new PropertyChangeSupport(new Object()));
    }

    /** Lädt einen Entwurf wie die Oberfläche der Jar: Laden, dann Phase direkt auf "Haupt" (ohne Neuberechnung). */
    private static Object load(ClassLoader l, String xml) throws Exception {
        if (!xml.contains("Erschaffungszwischenstand")) throw new IllegalArgumentException("Dieser Entwurf ist beschädigt und lässt sich nicht öffnen. Bitte einen neuen Helden anlegen.");
        Path f = Files.createTempFile("entwurf", ".xml");
        try {
            Files.writeString(f, xml);
            Object hew = newHew(l);
            Object ld = l.loadClass(W + "Laden").getConstructors()[0].newInstance(hew, f.toString());
            if ((Boolean) call(ld, "isLadeFehler")) throw new IllegalArgumentException("Die helden.jar kann diesen Entwurf nicht laden. Bitte einen neuen Helden anlegen oder dem Meister Bescheid geben.");
            Class<?> ph = l.loadClass(W + "HEW2$PHASEN");
            for (Field fd : hew.getClass().getFields()) if (fd.getType() == ph) for (Object c : ph.getEnumConstants()) if (c.toString().equals("Haupt")) fd.set(hew, c);
            return hew;
        } finally { Files.deleteIfExists(f); }
    }

    private static String save(ClassLoader l, Object hew) throws Exception {
        Path f = Files.createTempFile("entwurf", ".xml");
        try {
            Object sp = l.loadClass(W + "Speichern").getConstructors()[0].newInstance(hew);
            call(sp, "save", f.toString());
            return Files.readString(f);
        } finally { Files.deleteIfExists(f); }
    }

    private static Object eigenschaft(ClassLoader l, String name) throws Exception {
        Class<?> c = l.loadClass("helden.framework.B.new");
        for (Field f : c.getFields()) if (f.getType() == c && Modifier.isStatic(f.getModifiers())) { Object o = f.get(null); if (o.toString().equals(name)) return o; }
        throw new IllegalArgumentException("Eigenschaft unbekannt: " + name);
    }

    // --- XML-Änderungen am Entwurf (DOM, damit Sonderzeichen sicher sind)

    private static org.w3c.dom.Document parse(String xml) throws Exception {
        var f = javax.xml.parsers.DocumentBuilderFactory.newInstance();
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        f.setFeature(javax.xml.XMLConstants.FEATURE_SECURE_PROCESSING, true);
        return f.newDocumentBuilder().parse(new java.io.ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    private static String serialize(org.w3c.dom.Document d) throws Exception {
        var t = javax.xml.transform.TransformerFactory.newInstance().newTransformer();
        var w = new java.io.StringWriter();
        t.transform(new javax.xml.transform.dom.DOMSource(d), new javax.xml.transform.stream.StreamResult(w));
        return w.toString();
    }

    private static org.w3c.dom.Element byName(org.w3c.dom.Element p, String tag, String name) {
        for (org.w3c.dom.Element e : Heroes.all(p, tag)) if (e.getAttribute("name").equals(name)) return e;
        return null;
    }

    /** Liste der Vorteile im Helden (nicht die Liste in erschaffungsdaten). */
    private static org.w3c.dom.Element vorteilListe(org.w3c.dom.Document d) {
        org.w3c.dom.Element held = (org.w3c.dom.Element) d.getElementsByTagName("held").item(0);
        org.w3c.dom.Element vt = Heroes.first(held, "vt");
        if (vt == null) { vt = d.createElement("vt"); held.appendChild(vt); }
        return vt;
    }

    private static String addVorteil(String xml, String name, String value) throws Exception {
        if (name.isBlank()) throw new IllegalArgumentException("Diesen Vor- oder Nachteil hat der Held nicht (mehr).");
        var d = parse(xml);
        org.w3c.dom.Element vt = vorteilListe(d);
        org.w3c.dom.Element e = d.createElement("vorteil");
        e.setAttribute("name", name);
        if (!value.isBlank()) e.setAttribute("value", value);
        vt.appendChild(e);
        return serialize(d);
    }

    private static String removeVorteil(String xml, String name) throws Exception {
        var d = parse(xml);
        org.w3c.dom.Element vt = vorteilListe(d);
        for (org.w3c.dom.Element e : Heroes.all(vt, "vorteil")) if (e.getAttribute("name").equals(name)) { vt.removeChild(e); break; }
        return serialize(d);
    }

    private static String setTalent(String xml, String name, int value) throws Exception {
        var d = parse(xml);
        org.w3c.dom.Element held = (org.w3c.dom.Element) d.getElementsByTagName("held").item(0);
        // nur in der Talentliste suchen: SF wie Scharfschütze (Bogen) tragen ein gleichnamiges <talent name="Bogen"/> ohne Wert
        org.w3c.dom.Element liste0 = Heroes.first(held, "talentliste");
        org.w3c.dom.Element t = byName(liste0 == null ? held : liste0, "talent", name);
        if (t == null) {
            org.w3c.dom.Element liste = liste0;
            if (liste == null) throw new IllegalArgumentException("Im Entwurf fehlt die Talentliste. Bitte Herkunft neu wählen.");
            t = d.createElement("talent");
            t.setAttribute("name", name);
            liste.appendChild(t);
        }
        t.setAttribute("value", String.valueOf(value));
        return serialize(d);
    }

    // --- Reflection

    static Object call(Object o, String name, Object... a) throws Exception {
        Class<?> c = o instanceof Class<?> k ? k : o.getClass();
        Object target = o instanceof Class<?> ? null : o;
        for (Class<?> x = c; x != null; x = x.getSuperclass())
            for (Method m : x.getDeclaredMethods()) {
                if (!m.getName().equals(name) || m.getParameterCount() != a.length || m.isBridge()) continue;
                Class<?>[] p = m.getParameterTypes();
                boolean ok = true;
                for (int i = 0; i < p.length; i++) if (a[i] != null && !wrap(p[i]).isInstance(a[i])) { ok = false; break; }
                if (!ok) continue;
                m.setAccessible(true);
                try { return m.invoke(target, a); }
                catch (InvocationTargetException e) {
                    if (e.getCause() instanceof RuntimeException r) throw r;
                    if (e.getCause() instanceof Exception ex) throw ex;
                    throw e;
                }
            }
        throw new NoSuchMethodException(c.getName() + "." + name + "/" + a.length);
    }

    private static Class<?> wrap(Class<?> p) {
        return !p.isPrimitive() ? p : p == int.class ? Integer.class : p == boolean.class ? Boolean.class : p == long.class ? Long.class : p;
    }

    private static String str(Object o) { return o == null ? "" : o.toString().trim(); }
}
