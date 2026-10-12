package dao;

import database.Query;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Reporting queries: JOIN + SUM() + COUNT() + GROUP BY. */
public class ReportDAO {

    /** Total per category for one type (EXPENSE or INCOME) in a date range. */
    public static List<Map<String, Object>> categoryTotals(int userId, String type, LocalDate from,
                                                           LocalDate toExclusive) throws SQLException {
        return Query.select(
                "SELECT c.category_name, SUM(t.amount) AS total, COUNT(*) AS entries "
                        + "FROM transactions t JOIN categories c ON c.category_id = t.category_id "
                        + "WHERE t.user_id = ? AND t.type = ? AND t.transaction_date >= ? AND t.transaction_date < ? "
                        + "GROUP BY c.category_id, c.category_name ORDER BY total DESC",
                userId, type, from, toExclusive);
    }

    /** Income and expense totals per calendar month in a date range. */
    public static List<Map<String, Object>> monthlyTotals(int userId, LocalDate from, LocalDate toExclusive)
            throws SQLException {
        return Query.select(
                "SELECT YEAR(transaction_date) AS y, MONTH(transaction_date) AS m, "
                        + "SUM(CASE WHEN type = 'INCOME'  THEN amount ELSE 0 END) AS income, "
                        + "SUM(CASE WHEN type = 'EXPENSE' THEN amount ELSE 0 END) AS expense "
                        + "FROM transactions "
                        + "WHERE user_id = ? AND transaction_date >= ? AND transaction_date < ? "
                        + "GROUP BY YEAR(transaction_date), MONTH(transaction_date) ORDER BY y, m",
                userId, from, toExclusive);
    }
}
