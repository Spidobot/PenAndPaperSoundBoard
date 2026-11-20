package heldenhort;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Setzt einfaches HTML (h2/h3/p/ul/li, fett, kursiv) als echten Text ins PDF, zweispaltig wie im Regelwerk.
 * Für lange Texte wie die Regelzusammenfassung: klein und durchsuchbar (der Swing-Weg zeichnet Text als Pfade).
 */
final class Textsatz {
    private record Run(String text, boolean bold, boolean italic) {}
    private record Block(String tag, List<Run> runs) {}
    private record Wort(String text, PDFont font, boolean spaceBefore) {}

    private static final float W = PDRectangle.A4.getWidth(), H = PDRectangle.A4.getHeight();
    private static final float RAND = 42, GAP = 18, COLW = (W - 2 * RAND - GAP) / 2;

    private final PDDocument doc;
    private PDPageContentStream cs;
    private int col = 0;
    private float y;

    private Textsatz(PDDocument doc) { this.doc = doc; }

    static void append(PDDocument doc, String title, String html) throws Exception {
        Textsatz t = new Textsatz(doc);
        List<Block> blocks = new ArrayList<>();
        blocks.add(new Block("h1", List.of(new Run(title, true, false))));
        walk(Jsoup.parseBodyFragment(html).body(), blocks, null, false, false);
        try { for (Block b : blocks) t.block(b); }
        finally { if (t.cs != null) t.cs.close(); }
    }

    private static final java.util.Set<String> BLOCK = java.util.Set.of("p", "h1", "h2", "h3", "h4", "li", "div", "ul", "ol", "br", "tr", "hr");

    private static Block walk(Node n, List<Block> out, Block cur, boolean b, boolean i) {
        for (Node c : n.childNodes()) {
            if (c instanceof TextNode tn) {
                String s = tn.getWholeText().replaceAll("\\s+", " ");
                if (s.isBlank() && cur == null) continue;
                if (cur == null) { cur = new Block("p", new ArrayList<>()); out.add(cur); }
                cur.runs().add(new Run(s, b, i));
            } else if (c instanceof Element e) {
                String tag = e.normalName();
                boolean bb = b || tag.equals("b") || tag.equals("strong"), ii = i || tag.equals("i") || tag.equals("em");
                if (BLOCK.contains(tag)) {
                    if (tag.equals("ul") || tag.equals("ol") || tag.equals("div") || tag.equals("tr")) { walk(e, out, null, bb, ii); cur = null; continue; }
                    if (tag.equals("br")) { cur = null; continue; }
                    if (tag.equals("hr")) { out.add(new Block("hr", new ArrayList<>())); cur = null; continue; } // neue Seite
                    Block nb = new Block(tag, new ArrayList<>());
                    out.add(nb);
                    walk(e, out, nb, bb || tag.startsWith("h"), ii);
                    cur = null;
                } else cur = walk(e, out, cur, bb, ii);
            }
        }
        return cur;
    }

    private void neueSeite() throws Exception {
        if (cs != null) cs.close();
        PDPage p = new PDPage(PDRectangle.A4);
        doc.addPage(p);
        cs = new PDPageContentStream(doc, p);
        col = 0;
        y = H - RAND;
    }

    private void neueSpalte() throws Exception {
        if (cs == null || col == 1) neueSeite();
        else { col = 1; y = H - RAND; }
    }

    private void block(Block b) throws Exception {
        if (b.tag().equals("hr")) { if (cs != null) neueSeite(); return; }
        float size = switch (b.tag()) { case "h1" -> 15; case "h2" -> 12.5f; case "h3" -> 10.5f; default -> 9; };
        float before = switch (b.tag()) { case "h1" -> 0; case "h2" -> 10; case "h3" -> 7; default -> 3; };
        float lead = size * 1.25f;
        String prefix = b.tag().equals("li") ? "•" : "";
        List<Wort> woerter = new ArrayList<>();
        boolean space = false;
        if (!prefix.isEmpty()) woerter.add(new Wort(prefix, PDType1Font.TIMES_ROMAN, false));
        for (Run r : b.runs()) {
            PDFont f = r.bold() ? (r.italic() ? PDType1Font.TIMES_BOLD_ITALIC : PDType1Font.TIMES_BOLD)
                    : (r.italic() ? PDType1Font.TIMES_ITALIC : PDType1Font.TIMES_ROMAN);
            String t = r.text();
            if (t.startsWith(" ")) space = true;
            for (String w : t.trim().split(" ")) {
                if (w.isEmpty()) continue;
                woerter.add(new Wort(sicher(f, w), f, space));
                space = true;
            }
            space = t.endsWith(" ");
        }
        if (woerter.isEmpty()) return;
        if (cs == null) neueSeite();
        y -= before;
        // Überschrift nicht allein am Spaltenende
        float need = b.tag().startsWith("h") ? lead * 3 : lead;
        if (y - need < RAND) neueSpalte();
        float einzug = prefix.isEmpty() ? 0 : 8, max = COLW - einzug;
        List<Wort> zeile = new ArrayList<>();
        float breite = 0;
        for (Wort w : woerter) {
            float ww = w.font().getStringWidth(w.text()) / 1000 * size;
            float sp = zeile.isEmpty() ? 0 : (w.spaceBefore() ? w.font().getStringWidth(" ") / 1000 * size : 0);
            if (!zeile.isEmpty() && breite + sp + ww > max) {
                zeile(zeile, einzug, size, lead);
                zeile.clear(); breite = 0; sp = 0;
            }
            zeile.add(w);
            breite += sp + ww;
        }
        if (!zeile.isEmpty()) zeile(zeile, einzug, size, lead);
    }

    private void zeile(List<Wort> z, float einzug, float size, float lead) throws Exception {
        if (y - lead < RAND) neueSpalte();
        y -= lead;
        float x = RAND + col * (COLW + GAP) + einzug;
        boolean erstes = true;
        for (Wort w : z) {
            if (!erstes && w.spaceBefore()) x += w.font().getStringWidth(" ") / 1000 * size;
            cs.beginText();
            cs.setFont(w.font(), size);
            cs.newLineAtOffset(x, y);
            cs.showText(w.text());
            cs.endText();
            x += w.font().getStringWidth(w.text()) / 1000 * size;
            erstes = false;
        }
    }

    /** Zeichen, die die Standardschrift nicht kann (WinAnsi), ersetzen statt abzubrechen. */
    private static String sicher(PDFont f, String s) {
        StringBuilder b = new StringBuilder();
        s.codePoints().forEach(cp -> {
            String c = new String(Character.toChars(cp));
            try { f.encode(c); b.append(c); }
            catch (Exception e) { b.append(switch (cp) { case 0x2212 -> "-"; case 0x2009, 0x202F, 0x2007 -> " "; case 0x2264 -> "<="; case 0x2265 -> ">="; default -> "?"; }); }
        });
        return b.toString();
    }
}
