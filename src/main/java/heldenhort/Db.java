package heldenhort;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;

/** SQLite unter /data/db. Eine Verbindung, alle Zugriffe synchronisiert (wenige Nutzer, reicht). */
public final class Db {
    private final Connection c;

    public Db(Path file) throws Exception {
        Files.createDirectories(file.getParent());
        c = DriverManager.getConnection("jdbc:sqlite:" + file);
        try (Statement s = c.createStatement()) {
            s.execute("PRAGMA journal_mode=WAL");
            s.execute("CREATE TABLE IF NOT EXISTS kv(k TEXT PRIMARY KEY, v TEXT)");
            s.execute("CREATE TABLE IF NOT EXISTS hero(file TEXT PRIMARY KEY, player TEXT, received TEXT, status TEXT, background TEXT)");
            s.execute("CREATE TABLE IF NOT EXISTS session(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT, heroes TEXT)");
            try { s.execute("ALTER TABLE session ADD COLUMN created TEXT"); } catch (SQLException alreadyThere) { }
            s.execute("CREATE TABLE IF NOT EXISTS approval(id INTEGER PRIMARY KEY AUTOINCREMENT, hero TEXT, player TEXT, created TEXT)");
            try { s.execute("ALTER TABLE hero ADD COLUMN account INTEGER"); } catch (SQLException alreadyThere) { }
            try { s.execute("ALTER TABLE approval ADD COLUMN account INTEGER"); } catch (SQLException alreadyThere) { }
            s.execute("CREATE TABLE IF NOT EXISTS draft(id INTEGER PRIMARY KEY AUTOINCREMENT, account INTEGER, name TEXT, input TEXT, xml TEXT, "
                    + "bg TEXT, approval INTEGER, status TEXT DEFAULT 'entwurf', updated TEXT)");
            s.execute("CREATE TABLE IF NOT EXISTS approval_item(id INTEGER PRIMARY KEY AUTOINCREMENT, approval INTEGER, kind TEXT, text TEXT, status TEXT, comment TEXT)");
        }
    }

    public synchronized List<Map<String, Object>> query(String sql, Object... args) {
        try (PreparedStatement p = prep(sql, args); ResultSet r = p.executeQuery()) {
            List<Map<String, Object>> out = new ArrayList<>();
            ResultSetMetaData m = r.getMetaData();
            while (r.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 1; i <= m.getColumnCount(); i++) row.put(m.getColumnLabel(i), r.getObject(i));
                out.add(row);
            }
            return out;
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    /** Liefert die generierte ID bei INSERT, sonst die Anzahl geänderter Zeilen. */
    public synchronized long exec(String sql, Object... args) {
        try (PreparedStatement p = prep(sql, args)) {
            int n = p.executeUpdate();
            if (sql.trim().toUpperCase().startsWith("INSERT")) {
                try (ResultSet k = p.getGeneratedKeys()) { if (k.next()) return k.getLong(1); }
            }
            return n;
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    public String get(String key, String def) {
        var r = query("SELECT v FROM kv WHERE k=?", key);
        return r.isEmpty() || r.get(0).get("v") == null ? def : (String) r.get(0).get("v");
    }

    public void put(String key, String val) {
        exec("INSERT INTO kv(k,v) VALUES(?,?) ON CONFLICT(k) DO UPDATE SET v=excluded.v", key, val);
    }

    private PreparedStatement prep(String sql, Object... args) throws SQLException {
        PreparedStatement p = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
        for (int i = 0; i < args.length; i++) p.setObject(i + 1, args[i]);
        return p;
    }
}
