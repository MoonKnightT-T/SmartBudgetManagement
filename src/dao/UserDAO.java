package dao;

import database.Query;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

public class UserDAO {

    /** Returns user_id, name, password_hash for this email, or null if not found. */
    public static Map<String, Object> findByEmail(String email) throws SQLException {
        List<Map<String, Object>> rows = Query.select(
                "SELECT user_id, name, password_hash FROM users WHERE email = ?", email);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public static int create(String name, String email, String passwordHash) throws SQLException {
        return Query.insert(
                "INSERT INTO users (name, email, password_hash) VALUES (?, ?, ?)",
                name, email, passwordHash);
    }
}
