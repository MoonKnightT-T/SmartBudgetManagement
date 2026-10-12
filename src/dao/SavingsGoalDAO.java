package dao;

import database.Query;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public class SavingsGoalDAO {

    public static List<Map<String, Object>> list(int userId) throws SQLException {
        return Query.select("SELECT goal_id, goal_name, target_amount, saved_amount, deadline "
                + "FROM savings_goals WHERE user_id = ? ORDER BY deadline", userId);
    }

    public static void create(int userId, String name, BigDecimal target, BigDecimal saved, LocalDate deadline)
            throws SQLException {
        Query.insert("INSERT INTO savings_goals (user_id, goal_name, target_amount, saved_amount, deadline) "
                + "VALUES (?, ?, ?, ?, ?)", userId, name, target, saved, deadline);
    }

    public static void update(int goalId, int userId, String name, BigDecimal target, BigDecimal saved,
                              LocalDate deadline) throws SQLException {
        int rows = Query.update("UPDATE savings_goals SET goal_name = ?, target_amount = ?, saved_amount = ?, "
                + "deadline = ? WHERE goal_id = ? AND user_id = ?", name, target, saved, deadline, goalId, userId);
        if (rows == 0) throw new IllegalArgumentException("Goal not found");
    }

    /** Adds money to the saved amount. */
    public static void deposit(int goalId, int userId, BigDecimal amount) throws SQLException {
        int rows = Query.update("UPDATE savings_goals SET saved_amount = saved_amount + ? "
                + "WHERE goal_id = ? AND user_id = ?", amount, goalId, userId);
        if (rows == 0) throw new IllegalArgumentException("Goal not found");
    }

    public static void delete(int goalId, int userId) throws SQLException {
        int rows = Query.update("DELETE FROM savings_goals WHERE goal_id = ? AND user_id = ?", goalId, userId);
        if (rows == 0) throw new IllegalArgumentException("Goal not found");
    }
}
