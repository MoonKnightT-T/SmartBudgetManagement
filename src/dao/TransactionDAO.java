package dao;

import database.DatabaseConnection;
import database.Query;
import model.Transaction;

import java.math.BigDecimal;
import java.sql.*;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class TransactionDAO {

    /**
     * Adds (transactionId == 0) or edits a transaction.
     * Uses a DATABASE TRANSACTION: checking the category and writing the row
     * either both happen or neither does (commit / rollback).
     * POLYMORPHISM: t can be an Income or an Expense - t.getTransactionType() decides.
     */
    public static void save(int transactionId, Transaction t) throws SQLException {
        try (Connection c = DatabaseConnection.getConnection()) {
            c.setAutoCommit(false);
            try {
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT type FROM categories WHERE category_id = ? AND (user_id IS NULL OR user_id = ?)")) {
                    ps.setInt(1, t.getCategoryId());
                    ps.setInt(2, t.getUserId());
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) throw new IllegalArgumentException("Invalid category");
                        if (!rs.getString("type").equals(t.getTransactionType()))
                            throw new IllegalArgumentException("Category does not match the transaction type");
                    }
                }
                if (transactionId == 0) {
                    try (PreparedStatement ps = c.prepareStatement(
                            "INSERT INTO transactions (user_id, category_id, amount, type, transaction_date, description) "
                                    + "VALUES (?, ?, ?, ?, ?, ?)")) {
                        ps.setInt(1, t.getUserId());
                        ps.setInt(2, t.getCategoryId());
                        ps.setBigDecimal(3, t.getAmount());
                        ps.setString(4, t.getTransactionType());
                        ps.setDate(5, java.sql.Date.valueOf(t.getTransactionDate()));
                        ps.setString(6, t.getDescription());
                        ps.executeUpdate();
                    }
                } else {
                    try (PreparedStatement ps = c.prepareStatement(
                            "UPDATE transactions SET category_id = ?, amount = ?, type = ?, transaction_date = ?, description = ? "
                                    + "WHERE transaction_id = ? AND user_id = ?")) {
                        ps.setInt(1, t.getCategoryId());
                        ps.setBigDecimal(2, t.getAmount());
                        ps.setString(3, t.getTransactionType());
                        ps.setDate(4, java.sql.Date.valueOf(t.getTransactionDate()));
                        ps.setString(5, t.getDescription());
                        ps.setInt(6, transactionId);
                        ps.setInt(7, t.getUserId());
                        if (ps.executeUpdate() == 0) throw new IllegalArgumentException("Transaction not found");
                    }
                }
                c.commit();
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            }
        }
    }

    /** user_id in the WHERE clause = a user can only delete their own rows. */
    public static void delete(int transactionId, int userId) throws SQLException {
        int rows = Query.update("DELETE FROM transactions WHERE transaction_id = ? AND user_id = ?",
                transactionId, userId);
        if (rows == 0) throw new IllegalArgumentException("Transaction not found");
    }

    /** Filtered history. Any filter that is null / 0 is ignored. JOIN gives the category name. */
    public static List<Map<String, Object>> list(int userId, LocalDate from, LocalDate toExclusive,
                                                 String type, int categoryId) throws SQLException {
        StringBuilder sql = new StringBuilder(
                "SELECT t.transaction_id, t.amount, t.type, t.transaction_date, t.description, "
                        + "t.category_id, c.category_name "
                        + "FROM transactions t JOIN categories c ON c.category_id = t.category_id "
                        + "WHERE t.user_id = ? ");
        List<Object> params = new ArrayList<>();
        params.add(userId);
        if (from != null) { sql.append("AND t.transaction_date >= ? "); params.add(from); }
        if (toExclusive != null) { sql.append("AND t.transaction_date < ? "); params.add(toExclusive); }
        if (type != null) { sql.append("AND t.type = ? "); params.add(type); }
        if (categoryId > 0) { sql.append("AND t.category_id = ? "); params.add(categoryId); }
        sql.append("ORDER BY t.transaction_date DESC, t.transaction_id DESC LIMIT 1000");
        return Query.select(sql.toString(), params.toArray());
    }

    /** SUM() of one type. from/toExclusive null = all time. */
    public static BigDecimal sum(int userId, String type, LocalDate from, LocalDate toExclusive) throws SQLException {
        String sql = "SELECT COALESCE(SUM(amount), 0) AS total FROM transactions WHERE user_id = ? AND type = ?";
        List<Map<String, Object>> rows;
        if (from == null || toExclusive == null) {
            rows = Query.select(sql, userId, type);
        } else {
            rows = Query.select(sql + " AND transaction_date >= ? AND transaction_date < ?",
                    userId, type, from, toExclusive);
        }
        return new BigDecimal(String.valueOf(rows.get(0).get("total")));
    }
}
