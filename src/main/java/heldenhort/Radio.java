package heldenhort;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Soundboard als Internetradio: der Server spielt, alle hören zu.
 * Je Kanal (Musik, Hintergrund) ein MP3-Datenstrom in Echtzeit ab der aktuellen Stelle laut Server-Zustand.
 * Wer einschaltet, hört, was gerade läuft; Pause beim Zuhörer ist nur stumm, beim Wiedereinschalten ist er wieder live.
 * Nichts davon hängt an der Uhr oder der Dateidauer im Browser.
 */
final class Radio {
    /** So viel Ton liegt beim Zuhörer im Voraus (Puffer gegen Aussetzer, gleichzeitig der Startvorlauf). */
    static final double LEAD = 3.0;

    /** Bildindex einer MP3: Byte-Anfang und Startzeit jedes Frames. */
    record Index(long[] off, double[] t, double dauer, long mtime, long size) {
        /** Frame, das zur Zeit t (Sekunden im Titel) läuft. */
        int frameAt(double sek) {
            int i = Arrays.binarySearch(t, sek);
            return Math.max(0, Math.min(t.length - 1, i >= 0 ? i : -i - 2));
        }
        int frames() { return t.length; } // off hat einen Eintrag mehr (Ende des letzten Frames)
    }

    private static final Map<Path, Index> CACHE = new LinkedHashMap<>(16, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<Path, Index> e) { return size() > 12; }
    };

    /** Index aus dem Speicher, ohne Platte (null, wenn noch nicht eingelesen oder Datei geändert). */
    static Index cached(Path p) {
        Index ix;
        synchronized (CACHE) { ix = CACHE.get(p); }
        try { if (ix != null && ix.mtime() == Files.getLastModifiedTime(p).toMillis() && ix.size() == Files.size(p)) return ix; }
        catch (IOException ignored) { }
        return null;
    }

    static Index index(Path p) throws IOException {
        Index ix = cached(p);
        if (ix != null) return ix;
        ix = scan(p);
        synchronized (CACHE) { CACHE.put(p, ix); }
        return ix;
    }

    private static final int[] BR1 = {0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 0};
    private static final int[] BR2 = {0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160, 0};
    private static final int[] SR1 = {44100, 48000, 32000, 0};

    /** Länge eines Layer-III-Frames ab Kopf h (4 Byte) und dessen Dauer in Samples; 0 = kein gültiger Kopf. */
    private static long[] frame(int b1, int b2) {
        int ver = (b1 >> 3) & 3, layer = (b1 >> 1) & 3, bri = (b2 >> 4) & 15, sri = (b2 >> 2) & 3, pad = (b2 >> 1) & 1;
        if (ver == 1 || layer != 1 || bri == 0 || bri == 15 || sri == 3) return null;
        boolean m1 = ver == 3;
        int sr = SR1[sri] / (m1 ? 1 : ver == 2 ? 2 : 4), br = (m1 ? BR1 : BR2)[bri];
        int len = (m1 ? 144 : 72) * br * 1000 / sr + pad;
        return new long[]{len, m1 ? 1152 : 576, sr};
    }

    /** Liest die ganze Datei einmal durch (nur Kopfzeilen werden ausgewertet). */
    static Index scan(Path p) throws IOException {
        long size = Files.size(p), mtime = Files.getLastModifiedTime(p).toMillis();
        byte[] all;
        try (InputStream in = Files.newInputStream(p)) { all = in.readAllBytes(); }
        int i = 0;
        if (all.length > 10 && all[0] == 'I' && all[1] == 'D' && all[2] == '3')
            i = 10 + ((all[6] & 0x7f) << 21 | (all[7] & 0x7f) << 14 | (all[8] & 0x7f) << 7 | (all[9] & 0x7f));
        int end = all.length;
        if (end >= 128 && all[end - 128] == 'T' && all[end - 127] == 'A' && all[end - 126] == 'G') end -= 128; // ID3v1
        long[] off = new long[1024];
        double[] t = new double[1024];
        int n = 0;
        double sek = 0;
        long letztesEnde = 0;
        while (i + 4 <= end) {
            if ((all[i] & 0xff) != 0xff || (all[i + 1] & 0xe0) != 0xe0) { i++; continue; }
            long[] f = frame(all[i + 1] & 0xff, all[i + 2] & 0xff);
            if (f == null || f[0] < 24) { i++; continue; }
            int len = (int) f[0];
            // Gegenprobe: dahinter muss wieder ein Kopf stehen (oder Dateiende), sonst war es nur ein zufälliges 0xFFE
            if (i + len + 1 < end && ((all[i + len] & 0xff) != 0xff || (all[i + len + 1] & 0xe0) != 0xe0)) { i++; continue; }
            if (n == off.length) { off = Arrays.copyOf(off, n * 2); t = Arrays.copyOf(t, n * 2); }
            off[n] = i; t[n] = sek; n++; letztesEnde = Math.min(i + len, end);
            sek += (double) f[1] / f[2];
            i += len;
        }
        if (n == 0) throw new IOException("Kein MP3-Ton in " + p.getFileName());
        long[] o = Arrays.copyOf(off, n + 1);
        o[n] = letztesEnde; // Ende des letzten Frames (Müll dahinter nicht mitschicken)
        return new Index(o, Arrays.copyOf(t, n), sek, mtime, size);
    }

    /**
     * Ein Zuhörer auf einem Kanal. Läuft, bis der Meister den Titel wechselt, anhält oder spult (dann verbindet
     * der Browser neu) oder der Zuhörer abschaltet. Schickt immer bis LEAD Sekunden über die aktuelle Stelle hinaus.
     */
    static void stream(io.javalin.http.Context ctx, Sound sound, String chName) throws Exception {
        var res = ctx.res();
        Sound.Channel k = sound.ch.get(chName);
        if (k == null) { res.setStatus(404); return; }
        String file; long gen; double startPos; Path p;
        synchronized (sound) {
            if (!k.playing || k.file == null) { res.setStatus(204); return; }
            file = k.file; gen = k.gen; p = sound.resolve(file);
        }
        Index ix = index(p);
        synchronized (sound) {
            if (k.gen != gen) { res.setStatus(204); return; } // inzwischen umgeschaltet
            startPos = k.pos();
        }
        long wallStart = System.currentTimeMillis();
        if (!k.repeat && startPos >= ix.dauer()) { res.setStatus(204); return; }
        double tTrack = startPos % ix.dauer();
        int i = ix.frameAt(tTrack);

        res.setStatus(200);
        res.setContentType("audio/mpeg");
        res.setHeader("Cache-Control", "no-cache, no-store");
        res.setHeader("X-Accel-Buffering", "no"); // nginx/NPM: nicht puffern, sofort durchreichen
        res.setHeader("icy-name", "Heldenhort " + chName);
        OutputStream out = res.getOutputStream();
        try (FileChannel fc = FileChannel.open(p, StandardOpenOption.READ)) {
            var wc = Channels.newChannel(out);
            double gesendetBis = 0;          // Sekunden ab Verbindungsbeginn, bis zu denen der Zuhörer Ton hat
            double rundenVersatz = -tTrack;  // Titelzeit + rundenVersatz = Sekunden ab Verbindungsbeginn
            while (true) {
                synchronized (sound) { if (k.gen != gen || !k.playing || !file.equals(k.file)) break; }
                double jetzt = (System.currentTimeMillis() - wallStart) / 1000.0;
                double ziel = jetzt + LEAD;
                // Frames bis „ziel“ am Stück schicken
                int von = i;
                while (gesendetBis < ziel) {
                    if (i >= ix.frames()) {
                        if (von < i) { send(fc, wc, ix.off()[von], ix.off()[i]); }
                        if (!k.repeat) { out.flush(); return; } // Titel zu Ende, nicht wiederholen
                        rundenVersatz += ix.dauer();
                        i = 0; von = 0;
                    }
                    double frameEnde = (i + 1 < ix.frames() ? ix.t()[i + 1] : ix.dauer()) + rundenVersatz;
                    gesendetBis = frameEnde;
                    i++;
                }
                if (von < i) send(fc, wc, ix.off()[von], ix.off()[i]);
                out.flush();
                Thread.sleep(250);
            }
        } catch (IOException clientGone) {
            // Zuhörer hat abgeschaltet oder neu verbunden: normal
        }
    }

    private static void send(FileChannel fc, java.nio.channels.WritableByteChannel wc, long from, long to) throws IOException {
        for (long pos = from; pos < to; ) {
            long n = fc.transferTo(pos, to - pos, wc);
            if (n <= 0) break;
            pos += n;
        }
    }
}
