package heldenhort;

import io.javalin.http.Context;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.*;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * Spielerkonten: der Meister legt sie an, Spieler ändern ihr Passwort und hinterlegen eine Mail-Adresse,
 * die erst nach Klick auf den Bestätigungslink gilt.
 */
public final class Accounts {
    private static final SecureRandom RND = new SecureRandom();
    private final Db db;

    public Accounts(Db db) {
        this.db = db;
        db.exec("CREATE TABLE IF NOT EXISTS account(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT UNIQUE COLLATE NOCASE, pw TEXT, "
                + "tokver INTEGER DEFAULT 0, email TEXT, email_pending TEXT, email_token TEXT, created TEXT)");
        try { db.exec("ALTER TABLE account ADD COLUMN can_upload INTEGER DEFAULT 0"); } catch (RuntimeException alreadyThere) { }
        try { db.exec("ALTER TABLE account ADD COLUMN last_login TEXT"); } catch (RuntimeException alreadyThere) { }
        try { db.exec("ALTER TABLE account ADD COLUMN last_seen TEXT"); } catch (RuntimeException alreadyThere) { }
    }

    public List<Map<String, Object>> list() {
        return db.query("SELECT id,name,email,email_pending,created,can_upload,last_login,last_seen FROM account ORDER BY name COLLATE NOCASE");
    }

    public long create(String name, String pw) {
        name = name == null ? "" : name.trim();
        if (!name.matches("[\\p{L}\\p{N} ._-]{2,40}")) throw new IllegalArgumentException("Name: 2 bis 40 Zeichen, nur Buchstaben, Ziffern, Leerzeichen, . _ -");
        checkStartPw(pw);
        if (!db.query("SELECT 1 FROM account WHERE name=?", name).isEmpty()) throw new IllegalArgumentException("Den Namen gibt es schon");
        return db.exec("INSERT INTO account(name,pw,created) VALUES(?,?,?)", name, hash(pw), LocalDateTime.now().format(Heroes.TS));
    }

    /** Vom Spieler selbst gesetzt: mindestens 8 Zeichen. */
    public void setPassword(long id, String pw) {
        checkPw(pw);
        store(id, pw);
    }

    /** Vom Meister gesetzt (Startpasswort): keine Mindestlänge. */
    public void setStartPassword(long id, String pw) {
        checkStartPw(pw);
        store(id, pw);
    }

    private void store(long id, String pw) {
        db.exec("UPDATE account SET pw=?, tokver=tokver+1 WHERE id=?", hash(pw), id); // meldet alte Sitzungen ab
    }

    public void delete(long id) { db.exec("DELETE FROM account WHERE id=?", id); }

    /** Login: liefert die Konto-ID oder null. */
    public Long login(String name, String pw) {
        var r = db.query("SELECT id,pw FROM account WHERE name=?", name == null ? "" : name.trim());
        if (r.isEmpty()) { hash("x"); return null; } // gleiche Laufzeit wie ein echter Versuch
        if (!verify(pw, (String) r.get(0).get("pw"))) return null;
        long id = ((Number) r.get(0).get("id")).longValue();
        String now = LocalDateTime.now().format(Heroes.TS);
        db.exec("UPDATE account SET last_login=?, last_seen=? WHERE id=?", now, now, id);
        return id;
    }

    public boolean checkOld(long id, String pw) {
        var r = db.query("SELECT pw FROM account WHERE id=?", id);
        return !r.isEmpty() && verify(pw, (String) r.get(0).get("pw"));
    }

    public Map<String, Object> get(long id) {
        var r = db.query("SELECT id,name,email,email_pending,tokver,can_upload FROM account WHERE id=?", id);
        return r.isEmpty() ? null : r.get(0);
    }

    /** Neue Adresse vormerken; gilt erst nach Bestätigung. Liefert das Token für den Link. */
    public String requestEmail(long id, String email) {
        email = email == null ? "" : email.trim();
        if (!email.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+") || email.length() > 120) throw new IllegalArgumentException("Mail-Adresse ungültig");
        String t = HexFormat.of().formatHex(bytes(24));
        db.exec("UPDATE account SET email_pending=?, email_token=? WHERE id=?", email, t, id);
        return t;
    }

    public boolean confirmEmail(String token) {
        if (token == null || token.length() != 48) return false;
        var r = db.query("SELECT id,email_pending FROM account WHERE email_token=?", token);
        if (r.isEmpty()) return false;
        db.exec("UPDATE account SET email=email_pending, email_pending=NULL, email_token=NULL WHERE id=?", r.get(0).get("id"));
        return true;
    }

    /** Bestätigte Mail-Adresse oder null. */
    public String email(Object id) {
        var r = db.query("SELECT email FROM account WHERE id=?", id);
        return r.isEmpty() ? null : (String) r.get(0).get("email");
    }

    // --- Sitzungs-Cookie: id.tokver.hmac

    public String token(long id) throws Exception {
        String body = id + "." + get(id).get("tokver");
        return body + "." + App.hmac(db, "acc|" + body);
    }

    /** Angemeldetes Konto oder null. */
    public Map<String, Object> current(Context ctx) {
        try {
            String t = ctx.cookie("hh");
            if (t == null) return null;
            String[] p = t.split("\\.");
            if (p.length != 3) return null;
            String body = p[0] + "." + p[1];
            if (!MessageDigest.isEqual(App.hmac(db, "acc|" + body).getBytes(), p[2].getBytes())) return null;
            Map<String, Object> a = get(Long.parseLong(p[0]));
            if (a == null || !String.valueOf(a.get("tokver")).equals(p[1])) return null;
            seen(((Number) a.get("id")).longValue());
            return a;
        } catch (Exception e) { return null; }
    }

    private final Map<Long, Long> seenAt = new java.util.concurrent.ConcurrentHashMap<>();

    /** „Zuletzt da“: höchstens einmal pro Minute in die DB schreiben. */
    private void seen(long id) {
        long now = System.currentTimeMillis();
        Long last = seenAt.get(id);
        if (last != null && now - last < 60_000) return;
        seenAt.put(id, now);
        db.exec("UPDATE account SET last_seen=? WHERE id=?", LocalDateTime.now().format(Heroes.TS), id);
    }

    // --- Passwörter: PBKDF2 aus der Standardbibliothek

    private static void checkStartPw(String pw) {
        if (pw == null || pw.isEmpty()) throw new IllegalArgumentException("Passwort fehlt");
        if (pw.length() > 200) throw new IllegalArgumentException("Passwort zu lang");
    }

    private static void checkPw(String pw) {
        if (pw == null || pw.length() < 8) throw new IllegalArgumentException("Passwort: mindestens 8 Zeichen");
        if (pw.length() > 200) throw new IllegalArgumentException("Passwort zu lang");
    }

    static String hash(String pw) {
        byte[] salt = bytes(16);
        return "pbkdf2$210000$" + HexFormat.of().formatHex(salt) + "$" + HexFormat.of().formatHex(pbkdf2(pw, salt, 210000));
    }

    static boolean verify(String pw, String stored) {
        if (pw == null || stored == null) return false;
        String[] p = stored.split("\\$");
        byte[] want = HexFormat.of().parseHex(p[3]);
        return MessageDigest.isEqual(want, pbkdf2(pw, HexFormat.of().parseHex(p[2]), Integer.parseInt(p[1])));
    }

    private static byte[] pbkdf2(String pw, byte[] salt, int iter) {
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(new PBEKeySpec(pw.toCharArray(), salt, iter, 256)).getEncoded();
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    private static byte[] bytes(int n) { byte[] b = new byte[n]; RND.nextBytes(b); return b; }

    static String utf8(byte[] b) { return new String(b, StandardCharsets.UTF_8); }
}
