package dao;

import database.Query;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public class BudgetDAO {

    /**
     * Budgets of one month with the amount spent so far.
     * JOIN (budgets + categories), LEFT JOIN (transactions), SUM() and GROUP BY in one query.
     */
    public static List<Map<String, Object>> listForMonth(int userId, int month, int year) throws SQLException {
        LocalDate start = LocalDate.of(year, month, 1);
        LocalDate end = start.plusMonths(1);
        return Query.select(
                "SELECT b.budget_id, b.category_id, c.category_name, b.monthly_limit, "
                        + "COALESCE(SUM(t.amount), 0) AS spent "
                        + "FROM budgets b "
                        + "JOIN categories c ON c.category_id = b.category_id "
                        + "LEFT JOIN transactions t ON t.user_id = b.user_id AND t.category_id = b.category_id "
                        + "  AND t.type = 'EXPENSE' AND t.transaction_date >= ? AND t.transaction_date < ? "
                        + "WHERE b.user_id = ? AND b.budget_month = ? AND b.budget_year = ? "
                        + "GROUP BY b.budget_id, b.category_id, c.category_name, b.monthly_limit "
                        + "ORDER BY c.category_name",
                start, end, userId, month, year);
    }

    public static void create(int userId, int categoryId, BigDecimal limit, int month, int year) throws SQLException {
        List<Map<String, Object>> ok = Query.select(
                "SELECT category_id FROM categories WHERE category_id = ? AND type = 'EXPENSE' "
                        + "AND (user_id IS NULL OR user_id = ?)", categoryId, userId);
        if (ok.isEmpty()) throw new IllegalArgumentException("Please choose a valid expense category");
        try {
            Query.insert("INSERT INTO budgets (user_id, category_id, monthly_limit, budget_month, budget_year) "
                    + "VALUES (?, ?, ?, ?, ?)", userId, categoryId, limit, month, year);
        } catch (SQLException e) {
            if (e.getErrorCode() == 1062)   // UNIQUE (user, category, month, year)
                throw new IllegalArgumentException("A budget for this category and month already exists");
            throw e;
        }
    }

    public static void updateLimit(int budgetId, int userId, BigDecimal limit) throws SQLException {
        int rows = Query.update("UPDATE budgets SET monthly_limit = ? WHERE budget_id = ? AND user_id = ?",
                limit, budgetId, userId);
        if (rows == 0) throw new IllegalArgumentException("Budget not found");
    }

    public static void delete(int budgetId, int userId) throws SQLException {
        int rows = Query.update("DELETE FROM budgets WHERE budget_id = ? AND user_id = ?", budgetId, userId);
        if (rows == 0) throw new IllegalArgumentException("Budget not found");
    }
}
