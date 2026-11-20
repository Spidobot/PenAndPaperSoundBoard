package heldenhort;

import de.rototor.pdfbox.graphics2d.PdfBoxGraphics2D;
import java.awt.*;
import java.awt.print.PageFormat;
import java.awt.print.Paper;
import java.awt.print.Printable;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.util.List;
import javax.swing.*;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;

/**
 * Heldenbogen als PDF – gezeichnet von der helden.jar selbst.
 * Weg wie beim Drucken in der Helden-Software: Held laden, über die Plugin-Schnittstelle
 * (DatenAustausch3Impl.printHeld) das Printable holen und jede Seite in eine PDF-Seite zeichnen.
 * Vorher berechnet XMLDatenGenerator.genDaten die Bewaffnung; ohne das bricht die Jar bei den Kampfseiten ab.
 * Die Backgroundstory wird auf Wunsch als eigene Seiten angehängt.
 */
public final class Pdf {
    private static final float W = PDRectangle.A4.getWidth(), H = PDRectangle.A4.getHeight();

    /** Synchronisiert auf die Jar: deren Einstellungen sind statisch. */
    public static byte[] heldenbogen(HeldenJar jar, Path heroXml, String title, String backgroundHtml) throws Exception { return heldenbogen(jar, heroXml, title, backgroundHtml, null); }

    public static byte[] heldenbogen(HeldenJar jar, Path heroXml, String title, String backgroundHtml, String extraHtml) throws Exception {
        return heldenbogen(jar, heroXml, title, backgroundHtml, extraHtml, null);
    }

    /** bild = Heldenbild; wird nur für den Druck in eine Kopie der XML eingetragen (<portraet value="Pfad"/>), so druckt die Jar es mit. */
    public static byte[] heldenbogen(HeldenJar jar, Path heroXml, String title, String backgroundHtml, String extraHtml, Path bild) throws Exception {
        return heldenbogen(jar, heroXml, title, backgroundHtml, extraHtml, bild, null);
    }

    /** regelnHtml = Regelzusammenfassung der Vor-/Nachteile, SF und Zauber (aus /data/regeln), wird hinten angehängt. */
    public static byte[] heldenbogen(HeldenJar jar, Path heroXml, String title, String backgroundHtml, String extraHtml, Path bild, String regelnHtml) throws Exception {
        if (bild != null && java.nio.file.Files.exists(bild)) {
            String xml = java.nio.file.Files.readString(heroXml, java.nio.charset.StandardCharsets.UTF_8);
            String tag = "<portraet value=\"" + bild.toAbsolutePath().toString().replace("&", "&amp;").replace("\"", "&quot;") + "\"/>";
            xml = xml.matches("(?s).*<portraet[^>]*/>.*") ? xml.replaceFirst("<portraet[^>]*/>", java.util.regex.Matcher.quoteReplacement(tag)) : xml.replaceFirst("(<held[^>]*>)", "$1" + java.util.regex.Matcher.quoteReplacement(tag));
            Path tmp = java.nio.file.Files.createTempFile("druck", ".xml");
            try { java.nio.file.Files.writeString(tmp, xml, java.nio.charset.StandardCharsets.UTF_8); return heldenbogen(jar, tmp, title, backgroundHtml, extraHtml, null, regelnHtml); }
            finally { java.nio.file.Files.deleteIfExists(tmp); }
        }
        synchronized (jar) {
            ClassLoader l = jar.loader();
            ClassLoader old = Thread.currentThread().getContextClassLoader();
            Thread.currentThread().setContextClassLoader(l);
            try (PDDocument doc = new PDDocument()) {
                l.loadClass("helden.framework.settings.Setting").getMethod("initAll").invoke(null);
                Object xp = l.loadClass("helden.framework.held.persistenz.XMLPersistierer").getConstructor().newInstance();
                Object held = ((List<?>) Creation.call(xp, "ladeHelden", heroXml.toFile())).get(0);
                Creation.call(l.loadClass("helden.plugin.datenxmlplugin.XMLDatenGenerator"), "genDaten", held, 3);
                Class<?> listC = l.loadClass("helden.gui.E"); // HeldenListe: die Auswahl, aus der printHeld druckt
                @SuppressWarnings("unchecked")
                JList<Object> list = (JList<Object>) listC.getConstructor(javax.swing.event.ListSelectionListener.class)
                        .newInstance((javax.swing.event.ListSelectionListener) e -> { });
                list.setListData(new Object[]{held});
                list.setSelectedIndex(0);
                Object da = l.loadClass("helden.plugin.datenxmlplugin.DatenAustausch3Impl").getConstructor(listC).newInstance(list);
                Printable pr = (Printable) Creation.call(da, "printHeld");

                PageFormat pf = new PageFormat();
                Paper paper = new Paper();
                paper.setSize(W, H);
                paper.setImageableArea(0, 0, W, H);
                pf.setPaper(paper);
                for (int i = 0; i < 60; i++) {
                    PdfBoxGraphics2D g = new PdfBoxGraphics2D(doc, W, H);
                    int r;
                    try { r = pr.print(g, pf, i); }
                    catch (Exception pageFailed) { g.dispose(); continue; } // eine kaputte Seite soll nicht den ganzen Bogen verhindern
                    g.dispose();
                    if (r != Printable.PAGE_EXISTS) break;
                    addForm(doc, g);
                }
                if (backgroundHtml != null && !backgroundHtml.isBlank()) appendHtml(doc, title + " – Backgroundstory", backgroundHtml);
                if (extraHtml != null && !extraHtml.isBlank()) appendHtml(doc, title + " – Zusatzinfos", extraHtml);
                if (regelnHtml != null && !regelnHtml.isBlank()) Textsatz.append(doc, title + " – Regelzusammenfassung", regelnHtml);
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                doc.save(out);
                return out.toByteArray();
            } finally { Thread.currentThread().setContextClassLoader(old); }
        }
    }

    /** Einfaches HTML (Überschriften, Absätze, Listen, fett/kursiv) als Text-PDF, z. B. Regelzusammenfassung einer Session. */
    public static byte[] html(String title, String html) throws Exception {
        try (PDDocument doc = new PDDocument()) {
            Textsatz.append(doc, title, html);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    private static void addForm(PDDocument doc, PdfBoxGraphics2D g) throws Exception {
        PDPage page = new PDPage(PDRectangle.A4);
        doc.addPage(page);
        try (PDPageContentStream cs = new PDPageContentStream(doc, page)) { cs.drawForm(g.getXFormObject()); }
    }

    /** Backgroundstory als Seiten (HTML wie im Editor, gesetzt von Swing). */
    private static void appendHtml(PDDocument doc, String title, String html) throws Exception {
        float margin = 50, width = W - 2 * margin, pageH = H - 2 * margin;
        JEditorPane ed = new JEditorPane("text/html", "<html><body style='font-family:serif;font-size:11pt'>"
                + "<h2>" + escape(title) + "</h2>" + html + "</body></html>");
        ed.setSize(new Dimension((int) width, Integer.MAX_VALUE / 4));
        int total = ed.getPreferredSize().height;
        ed.setSize(new Dimension((int) width, total));
        for (int y = 0; y < total; y += (int) pageH) {
            PdfBoxGraphics2D g = new PdfBoxGraphics2D(doc, W, H);
            g.translate(margin, margin);
            g.setClip(0, 0, (int) width, (int) pageH);
            g.translate(0, -y);
            ed.print(g);
            g.dispose();
            addForm(doc, g);
        }
    }

    private static String escape(String s) { return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"); }
}
