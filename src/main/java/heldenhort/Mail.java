package heldenhort;

import jakarta.mail.*;
import jakarta.mail.internet.*;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Benachrichtigung an den Meister. SMTP-Daten kommen aus der Konfiguration. */
public final class Mail {
    private final Db db;
    private final ExecutorService bg = Executors.newSingleThreadExecutor();

    public Mail(Db db) { this.db = db; }

    /** Im Hintergrund senden, Fehler nur loggen: eine kaputte Mail darf keinen Spieler-Request scheitern lassen. */
    public void notifyMeister(String subject, String text) { notify(db.get("mailTo", ""), subject, text); }

    /** An einen Spieler (bestätigte Adresse) oder den Meister; leere Adresse = nichts tun. */
    public void notify(String to, String subject, String text) {
        if (to == null || to.isBlank()) return;
        bg.submit(() -> {
            try { send(to, subject, text); } catch (Exception e) { System.err.println("Mail fehlgeschlagen: " + e.getMessage()); }
        });
    }

    // Gesammelte Freigabe-Entscheidungen je Adresse: Held → Zeilen. Eine Mail, wenn 3 Minuten lang nichts dazukam.
    private final java.util.Map<String, java.util.Map<String, java.util.List<String>>> sammel = new java.util.HashMap<>();
    private final java.util.Map<String, java.util.concurrent.ScheduledFuture<?>> sammelTimer = new java.util.HashMap<>();
    private final java.util.concurrent.ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
    static final long SAMMEL_SEKUNDEN = 180;

    /** Entscheidung vormerken statt sofort mailen; mehrere Klicks hintereinander ergeben eine Mail. */
    public synchronized void sammeln(String to, String held, String zeile) {
        if (to == null || to.isBlank()) return;
        sammel.computeIfAbsent(to, k -> new java.util.LinkedHashMap<>()).computeIfAbsent(held, k -> new java.util.ArrayList<>()).add(zeile);
        var alt = sammelTimer.remove(to);
        if (alt != null) alt.cancel(false);
        sammelTimer.put(to, timer.schedule(() -> sammelSenden(to), SAMMEL_SEKUNDEN, java.util.concurrent.TimeUnit.SECONDS));
    }

    private void sammelSenden(String to) {
        java.util.Map<String, java.util.List<String>> m;
        synchronized (this) { m = sammel.remove(to); sammelTimer.remove(to); }
        if (m == null || m.isEmpty()) return;
        StringBuilder b = new StringBuilder("Hallo,\n\nder Meister hat über deine Anfragen entschieden:\n");
        m.forEach((held, zeilen) -> b.append("\n").append(held).append("\n").append(String.join("\n", zeilen)).append("\n"));
        b.append("\nDetails siehst du auf der Spielerseite bei deinem Helden.");
        notify(to, "Entscheidung des Meisters: " + String.join(", ", m.keySet()), b.toString());
    }

    public void send(String subject, String text) throws MessagingException { send(db.get("mailTo", ""), subject, text); }

    public void send(String to, String subject, String text) throws MessagingException { send(to, subject, text, null); }

    /** Einladung mit Kalenderdatei; landet in Outlook/Google/Apple als Termin-Einladung. */
    public void notifyIcs(String to, String subject, String text, String ics) {
        if (to == null || to.isBlank()) return;
        bg.submit(() -> {
            try { send(to, subject, text, ics); } catch (Exception e) { System.err.println("Mail fehlgeschlagen: " + e.getMessage()); }
        });
    }

    public void send(String to, String subject, String text, String ics) throws MessagingException {
        String host = db.get("smtpHost", "");
        if (host.isBlank() || to.isBlank()) throw new MessagingException("SMTP-Server oder Empfänger fehlt in der Konfiguration");
        int port = Integer.parseInt(db.get("smtpPort", "587"));
        String user = db.get("smtpUser", ""), pass = db.get("smtpPass", "");
        Properties p = new Properties();
        p.put("mail.smtp.host", host);
        p.put("mail.smtp.port", String.valueOf(port));
        p.put("mail.smtp.connectiontimeout", "10000");
        p.put("mail.smtp.timeout", "10000");
        if (port == 465) p.put("mail.smtp.ssl.enable", "true"); else p.put("mail.smtp.starttls.enable", "true");
        Session s;
        if (user.isBlank()) s = Session.getInstance(p);
        else {
            p.put("mail.smtp.auth", "true");
            s = Session.getInstance(p, new Authenticator() {
                @Override protected PasswordAuthentication getPasswordAuthentication() { return new PasswordAuthentication(user, pass); }
            });
        }
        MimeMessage m = new MimeMessage(s);
        String from = db.get("smtpFrom", "");
        // Absender muss zum SMTP-Postfach gehören. Nie den Empfänger als Absender nehmen: das lehnt jeder Server ab.
        if (from.isBlank() && !user.contains("@"))
            throw new MessagingException("Absender fehlt: Der SMTP-Benutzer „" + user + "“ ist keine Mail-Adresse. Bitte unter „Absender“ die Adresse eintragen, die zu diesem Postfach gehört.");
        m.setFrom(new InternetAddress(from.isBlank() ? user : from));
        m.setRecipients(Message.RecipientType.TO, InternetAddress.parse(to));
        m.setSubject("[Heldenhort] " + subject, "UTF-8");
        // Text und gestaltete HTML-Fassung; Mailprogramme ohne HTML zeigen den Text
        MimeMultipart alt = new MimeMultipart("alternative");
        MimeBodyPart plain = new MimeBodyPart();
        plain.setText(text + "\n\n-- \nHeldenhort · euer DSA-Spieltisch\nDiese Nachricht wurde automatisch verschickt.", "UTF-8");
        MimeBodyPart html = new MimeBodyPart();
        html.setContent(html(subject, text), "text/html; charset=UTF-8");
        alt.addBodyPart(plain);
        alt.addBodyPart(html);
        if (ics == null) m.setContent(alt);
        else {
            MimeMultipart mp = new MimeMultipart("mixed");
            MimeBodyPart body = new MimeBodyPart();
            body.setContent(alt);
            MimeBodyPart cal = new MimeBodyPart();
            cal.setContent(ics, "text/calendar; charset=UTF-8; method=REQUEST");
            cal.setFileName("termin.ics");
            mp.addBodyPart(body);
            mp.addBodyPart(cal);
            m.setContent(mp);
        }
        Transport.send(m);
    }

    /**
     * Schlichte Markenfassung: dunkle Kopfleiste mit Schriftzug, heller Inhalt, kleine Fußzeile.
     * Nur Tabellen und Inline-Styles, damit es auch in Outlook und Webmailern gleich aussieht.
     */
    static String html(String subject, String text) {
        String body = esc(text)
                .replaceAll("(https?://[^\\s<]+)", "<a href=\"$1\" style=\"color:#8a6a1f\">$1</a>")
                .replace("\n  ", "\n&emsp;&ensp;") // eingerückte Zeilen (Entscheidung/Kommentar unter einem Punkt)
                .replace("\n", "<br>");
        return "<!doctype html><html lang=\"de\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width\"></head>"
                + "<body style=\"margin:0;padding:0;background:#efece6\">"
                + "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" style=\"background:#efece6\"><tr><td align=\"center\" style=\"padding:24px 12px\">"
                + "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" style=\"max-width:560px;background:#ffffff;border:1px solid #ddd6c8;border-radius:6px\">"
                + "<tr><td style=\"background:#1d2220;border-radius:6px 6px 0 0;padding:14px 24px;font-family:Georgia,'Times New Roman',serif;"
                + "font-size:15px;letter-spacing:3px;color:#d9b45a\">&#11041;&nbsp; HELDENHORT</td></tr>"
                + "<tr><td style=\"padding:22px 24px 6px;font-family:Georgia,'Times New Roman',serif;font-size:19px;color:#1d2220\">" + esc(subject) + "</td></tr>"
                + "<tr><td style=\"padding:6px 24px 24px;font-family:-apple-system,'Segoe UI',Arial,sans-serif;font-size:15px;line-height:1.55;color:#2b2f2d\">" + body + "</td></tr>"
                + "<tr><td style=\"border-top:1px solid #ece6da;padding:12px 24px;font-family:-apple-system,'Segoe UI',Arial,sans-serif;font-size:12px;color:#8a8478\">"
                + "Heldenhort · euer DSA-Spieltisch<br>Diese Nachricht wurde automatisch verschickt.</td></tr>"
                + "</table></td></tr></table></body></html>";
    }

    private static String esc(String s) { return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;"); }
}
