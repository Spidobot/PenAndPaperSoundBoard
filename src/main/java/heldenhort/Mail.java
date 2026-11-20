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

    // Freigaben: eine Mail je Held, sobald alle Punkte entschieden sind. Kurze Pause, damit schnelle Korrekturen
    // (z. B. gleich danach „Entscheidung ändern“) in derselben Mail landen statt zwei Mails auszulösen.
    private final java.util.Map<String, java.util.concurrent.ScheduledFuture<?>> heldTimer = new java.util.HashMap<>();
    private final java.util.concurrent.ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
    static final long PAUSE_SEKUNDEN = 30;

    /** key = Freigabe-Anfrage; neuer Aufruf ersetzt den noch nicht gesendeten (es zählt immer der letzte Gesamtstand). */
    public synchronized void heldEntschieden(String key, String to, String held, String entscheidungen) {
        if (to == null || to.isBlank()) return;
        var alt = heldTimer.remove(key);
        if (alt != null) alt.cancel(false);
        String text = "Seid gegrüßt,\n\nder Meister hat über alle offenen Punkte deines Helden „" + held + "“ entschieden:\n\n"
                + entscheidungen + "\nDetails siehst du auf der Spielerseite bei deinem Helden.";
        heldTimer.put(key, timer.schedule(() -> { synchronized (this) { heldTimer.remove(key); } notify(to, "Entscheidung des Meisters: " + held, text); },
                PAUSE_SEKUNDEN, java.util.concurrent.TimeUnit.SECONDS));
    }

    /** Mail mit Datei-Anhang (z. B. Helden-PDF), im Hintergrund. */
    public void notifyAnhang(String to, String subject, String text, byte[] datei, String dateiname) {
        if (to == null || to.isBlank()) return;
        bg.submit(() -> {
            try { send(to, subject, text, null, datei, dateiname); } catch (Exception e) { System.err.println("Mail fehlgeschlagen: " + e.getMessage()); }
        });
    }

    public void send(String subject, String text) throws MessagingException { send(db.get("mailTo", ""), subject, text); }

    public void send(String to, String subject, String text) throws MessagingException { send(to, subject, text, null); }

    /** Absenderadresse (wie beim Versand), für ORGANIZER in Kalendereinladungen; leer, wenn keine bekannt. */
    public String absender() {
        String from = db.get("smtpFrom", ""), user = db.get("smtpUser", "");
        return !from.isBlank() ? from : user.contains("@") ? user : "";
    }

    /** Einladung mit Kalenderdatei; landet in Outlook/Google/Apple als Termin-Einladung. */
    public void notifyIcs(String to, String subject, String text, String ics) {
        if (to == null || to.isBlank()) return;
        bg.submit(() -> {
            try { send(to, subject, text, ics); } catch (Exception e) { System.err.println("Mail fehlgeschlagen: " + e.getMessage()); }
        });
    }

    public void send(String to, String subject, String text, String ics) throws MessagingException { send(to, subject, text, ics, null, null); }

    public void send(String to, String subject, String text, String ics, byte[] datei, String dateiname) throws MessagingException {
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
        // Text und gestaltete HTML-Fassung (mit eingebettetem W20-Logo); Mailprogramme ohne HTML zeigen den Text
        String gruss = gruss();
        MimeMultipart alt = new MimeMultipart("alternative");
        MimeBodyPart plain = new MimeBodyPart();
        plain.setText(text + "\n\n" + gruss + "\n\n-- \nHeldenhort · euer DSA-Spieltisch\nDiese Nachricht wurde automatisch verschickt.", "UTF-8");
        MimeMultipart related = new MimeMultipart("related");
        MimeBodyPart html = new MimeBodyPart();
        html.setContent(html(subject, text, gruss), "text/html; charset=UTF-8");
        related.addBodyPart(html);
        try (var in = Mail.class.getResourceAsStream("/mail/logo.png")) {
            if (in != null) {
                MimeBodyPart logo = new MimeBodyPart();
                logo.setDataHandler(new jakarta.activation.DataHandler(new jakarta.mail.util.ByteArrayDataSource(in.readAllBytes(), "image/png")));
                logo.setHeader("Content-ID", "<logo>");
                logo.setDisposition(MimeBodyPart.INLINE);
                logo.setFileName("heldenhort.png");
                related.addBodyPart(logo);
            }
        } catch (java.io.IOException e) { System.err.println("Mail-Logo fehlt: " + e); }
        MimeBodyPart htmlTeil = new MimeBodyPart();
        htmlTeil.setContent(related);
        alt.addBodyPart(plain);
        alt.addBodyPart(htmlTeil);
        if (ics == null && datei == null) m.setContent(alt);
        else {
            MimeMultipart mp = new MimeMultipart("mixed");
            MimeBodyPart body = new MimeBodyPart();
            body.setContent(alt);
            mp.addBodyPart(body);
            if (ics != null) {
                MimeBodyPart cal = new MimeBodyPart();
                cal.setContent(ics, "text/calendar; charset=UTF-8; method=REQUEST");
                cal.setFileName("termin.ics");
                mp.addBodyPart(cal);
            }
            if (datei != null) {
                MimeBodyPart a = new MimeBodyPart();
                a.setDataHandler(new jakarta.activation.DataHandler(new jakarta.mail.util.ByteArrayDataSource(datei, "application/pdf")));
                try { a.setFileName(MimeUtility.encodeText(dateiname, "UTF-8", null)); } catch (java.io.UnsupportedEncodingException e) { a.setFileName("held.pdf"); }
                mp.addBodyPart(a);
            }
            m.setContent(mp);
        }
        Transport.send(m);
    }

    /**
     * Schlichte Markenfassung: dunkle Kopfleiste mit Schriftzug, heller Inhalt, kleine Fußzeile.
     * Nur Tabellen und Inline-Styles, damit es auch in Outlook und Webmailern gleich aussieht.
     */
    /** Abschlussgruß des Meisters: wechselnd, hochnäsig, aventurisch. */
    static final String[] GRUESSE = {
            "Mit erhabenen Grüßen aus dem Meisterturm\nEuer Meister\nHüter der Regeln, Herr über Würfelglück und Patzertabellen",
            "Hochachtungsvoll (und keineswegs zu euren Gunsten gewürfelt)\nEuer Meister",
            "Praios zum Gruße und Phex zum Trotze\nEuer Meister, der die Regeln kennt, auch die, die ihr nicht lesen wolltet",
            "In meisterlicher Herablassung\nEuer Meister\nP.S. Der Würfel lügt nie. Ich manchmal.",
            "Gezeichnet in Tinte und Weisheit\nEuer Meister\nErster Archivar der Wege der Helden, Zweiter Bewahrer des Sichtschirms",
            "Mit dem Wohlwollen, das ein Spielleiter eben aufbringen kann\nEuer Meister\nRondra mit euch, Boron mit euren Gegnern",
            "Möge eure Tasche voller Dukaten und euer Kopf voller Regelkenntnis sein (letzteres wäre neu)\nEuer erhabener Meister",
    };

    static String gruss() { return GRUESSE[java.util.concurrent.ThreadLocalRandom.current().nextInt(GRUESSE.length)]; }

    static String html(String subject, String text) { return html(subject, text, gruss()); }

    static String html(String subject, String text, String gruss) {
        String g = esc(gruss).replace("\n", "<br>");
        String body = esc(text)
                .replaceAll("(https?://[^\\s<]+)", "<a href=\"$1\" style=\"color:#8a6a1f\">$1</a>")
                .replace("\n  ", "\n&emsp;&ensp;") // eingerückte Zeilen (Entscheidung/Kommentar unter einem Punkt)
                .replace("\n", "<br>");
        return "<!doctype html><html lang=\"de\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width\"></head>"
                + "<body style=\"margin:0;padding:0;background:#efece6\">"
                + "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" style=\"background:#efece6\"><tr><td align=\"center\" style=\"padding:24px 12px\">"
                + "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" style=\"max-width:560px;background:#ffffff;border:1px solid #ddd6c8;border-radius:6px\">"
                + "<tr><td style=\"background:#1d2220;border-radius:6px 6px 0 0;padding:14px 24px;font-family:Georgia,'Times New Roman',serif;"
                + "font-size:16px;letter-spacing:3px;color:#d9b45a\"><img src=\"cid:logo\" width=\"28\" height=\"28\" alt=\"\" "
                + "style=\"vertical-align:middle;border:0;margin-right:10px\">HELDENHORT</td></tr>"
                + "<tr><td style=\"padding:22px 24px 6px;font-family:Georgia,'Times New Roman',serif;font-size:19px;color:#1d2220\">" + esc(subject) + "</td></tr>"
                + "<tr><td style=\"padding:6px 24px 18px;font-family:-apple-system,'Segoe UI',Arial,sans-serif;font-size:15px;line-height:1.55;color:#2b2f2d\">" + body + "</td></tr>"
                + "<tr><td style=\"padding:4px 24px 22px;font-family:Georgia,'Times New Roman',serif;font-size:15px;font-style:italic;line-height:1.5;color:#5b4a22\">" + g + "</td></tr>"
                + "<tr><td style=\"border-top:1px solid #ece6da;padding:12px 24px;font-family:-apple-system,'Segoe UI',Arial,sans-serif;font-size:12px;color:#8a8478\">"
                + "Heldenhort · euer DSA-Spieltisch<br>Diese Nachricht wurde automatisch verschickt.</td></tr>"
                + "</table></td></tr></table></body></html>";
    }

    private static String esc(String s) { return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;"); }
}
