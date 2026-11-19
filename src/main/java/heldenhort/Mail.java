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
        m.setFrom(new InternetAddress(from.isBlank() ? (user.contains("@") ? user : to) : from));
        m.setRecipients(Message.RecipientType.TO, InternetAddress.parse(to));
        m.setSubject("[Heldenhort] " + subject, "UTF-8");
        if (ics == null) m.setText(text, "UTF-8");
        else {
            MimeMultipart mp = new MimeMultipart("mixed");
            MimeBodyPart body = new MimeBodyPart();
            body.setText(text, "UTF-8");
            MimeBodyPart cal = new MimeBodyPart();
            cal.setContent(ics, "text/calendar; charset=UTF-8; method=REQUEST");
            cal.setFileName("termin.ics");
            mp.addBodyPart(body);
            mp.addBodyPart(cal);
            m.setContent(mp);
        }
        Transport.send(m);
    }
}
