package dao;

import database.Query;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

public class CategoryDAO {

    /** Predefined categories (user_id IS NULL) plus this user's own custom ones. */
    public static List<Map<String, Object>> list(int userId, String type) throws SQLException {
        String sql = "SELECT category_id, category_name, type, (user_id IS NOT NULL) AS custom "
                + "FROM categories WHERE (user_id IS NULL OR user_id = ?) ";
        if (type != null) {
            return Query.select(sql + "AND type = ? ORDER BY category_name", userId, type);
        }
        return Query.select(sql + "ORDER BY type, category_name", userId);
    }

    public static void add(int userId, String name, String type) throws SQLException {
        List<Map<String, Object>> clash = Query.select(
                "SELECT category_id FROM categories WHERE user_id IS NULL AND category_name = ? AND type = ?",
                name, type);
        if (!clash.isEmpty()) throw new IllegalArgumentException("That category already exists");
        try {
            Query.insert("INSERT INTO categories (user_id, category_name, type) VALUES (?, ?, ?)",
                    userId, name, type);
        } catch (SQLException e) {
            if (e.getErrorCode() == 1062) throw new IllegalArgumentException("You already have that category");
            throw e;
        }
    }
}
