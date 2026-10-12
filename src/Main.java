import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dao.*;
import database.DatabaseConnection;
import model.Expense;
import model.Income;
import model.Transaction;
import service.BudgetService;
import util.ApiException;
import util.Json;
import util.PasswordUtil;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.SecureRandom;
import java.sql.SQLException;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

/**
 * Smart Budget Management System - web server.
 * Browser (web/index.html)  ->  this class (/api/...)  ->  DAO classes  ->  MySQL
 */
public class Main {

    private static final int PORT = 8080;
    private static final BigDecimal MAX_AMOUNT = new BigDecimal("9999999999.99");

    /** Logged-in users: random token (stored in a cookie) -> who they are. */
    private static class Session {
        final int userId;
        final String name;
        Session(int userId, String name) { this.userId = userId; this.name = name; }
    }
    private static final Map<String, Session> sessions = new ConcurrentHashMap<>();

    public static void main(String[] args) throws IOException {
        try (java.sql.Connection c = DatabaseConnection.getConnection()) {
            System.out.println("Database connection OK.");
        } catch (SQLException e) {
            System.err.println("WARNING: cannot connect to the database: " + e.getMessage());
            System.err.println("Check that MySQL is running and db.properties is correct.");
        }
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", PORT), 0);
        server.createContext("/api/", Main::handleApi);
        server.createContext("/", Main::handleStatic);
        server.setExecutor(Executors.newFixedThreadPool(8));
        server.start();
        System.out.println("Smart Budget is running. Open  http://localhost:" + PORT + "  in your browser.");
        System.out.println("(Press Ctrl+C to stop.)");
    }

    // ------------------------------------------------------------------ HTTP plumbing

    private static void handleStatic(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        Path file = Paths.get("web", "index.html");
        if (!path.equals("/") && !path.equals("/index.html")) {
            send(ex, 404, "text/plain; charset=utf-8", "Not found".getBytes(StandardCharsets.UTF_8));
        } else if (!Files.exists(file)) {
            send(ex, 500, "text/plain; charset=utf-8",
                    "web/index.html not found - start the program from the project folder.".getBytes(StandardCharsets.UTF_8));
        } else {
            send(ex, 200, "text/html; charset=utf-8", Files.readAllBytes(file));
        }
    }

    private static void handleApi(HttpExchange ex) throws IOException {
        int status = 200;
        String body;
        try {
            String method = ex.getRequestMethod();
            String path = ex.getRequestURI().getPath().substring(4);   // remove "/api"
            Map<String, String> params = parseForm(ex.getRequestURI().getRawQuery());
            if (method.equals("POST")) {
                params.putAll(parseForm(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            }
            body = Json.toJson(route(method, path, params, ex));
        } catch (ApiException e) {
            status = e.getStatus();
            body = error(e.getMessage());
        } catch (IllegalArgumentException e) {
            status = 400;
            body = error(e.getMessage());
        } catch (DateTimeException e) {
            status = 400;
            body = error("Please enter a valid date");
        } catch (SQLException e) {
            e.printStackTrace();
            status = 500;
            body = error("Database error. Is MySQL running and is db.properties correct?");
        } catch (Exception e) {
            e.printStackTrace();
            status = 500;
            body = error("Something went wrong");
        }
        send(ex, status, "application/json; charset=utf-8", body.getBytes(StandardCharsets.UTF_8));
    }

    private static void send(HttpExchange ex, int status, String type, byte[] bytes) throws IOException {
        ex.getResponseHeaders().set("Content-Type", type);
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static String error(String message) {
        return Json.toJson(map("error", message));
    }

    // ------------------------------------------------------------------ routing

    private static Object route(String method, String path, Map<String, String> p, HttpExchange ex)
            throws SQLException {
        String endpoint = method + " " + path;

        // endpoints that do not need a login
        switch (endpoint) {
            case "POST /register": return register(p, ex);
            case "POST /login":    return login(p, ex);
            case "POST /logout":   return logout(ex);
            default: break;
        }

        Session session = requireSession(ex);
        int uid = session.userId;   // EVERY query below is restricted to this user id

        switch (endpoint) {
            case "GET /me":
                return map("name", session.name);

            case "GET /categories":
                return CategoryDAO.list(uid, optionalType(p.get("type")));

            case "POST /categories/add": {
                String name = text(p, "name", "Category name", 50, true);
                CategoryDAO.add(uid, name, requireType(p.get("type")));
                return map("ok", true);
            }

            case "GET /transactions": {
                int month = intParam(p, "month", 0), year = intParam(p, "year", 0);
                LocalDate from = null, to = null;
                if (year > 0 && month > 0) { from = LocalDate.of(year, month, 1); to = from.plusMonths(1); }
                else if (year > 0)         { from = LocalDate.of(year, 1, 1);     to = from.plusYears(1); }
                if (!blank(p.get("from"))) from = LocalDate.parse(p.get("from"));
                if (!blank(p.get("to")))   to = LocalDate.parse(p.get("to")).plusDays(1);
                List<Map<String, Object>> items = TransactionDAO.list(uid, from, to,
                        optionalType(p.get("type")), intParam(p, "categoryId", 0));
                BigDecimal income = BigDecimal.ZERO, expense = BigDecimal.ZERO;
                for (Map<String, Object> row : items) {
                    BigDecimal amount = new BigDecimal(String.valueOf(row.get("amount")));
                    if ("INCOME".equals(row.get("type"))) income = income.add(amount);
                    else expense = expense.add(amount);
                }
                return map("items", items, "income", income, "expense", expense);
            }

            case "POST /transactions/save": {
                int id = intParam(p, "id", 0);
                String type = requireType(p.get("type"));
                int categoryId = intParam(p, "categoryId", 0);
                BigDecimal amount = money(p, "amount", "Amount", false);
                LocalDate date = LocalDate.parse(required(p, "date", "Date"));
                String description = text(p, "description", "Description", 255, false);
                // POLYMORPHISM: the variable type is Transaction, the object is Income or Expense
                Transaction t = type.equals("INCOME")
                        ? new Income(uid, categoryId, amount, date, description)
                        : new Expense(uid, categoryId, amount, date, description);
                TransactionDAO.save(id, t);
                return map("ok", true);
            }

            case "POST /transactions/delete":
                TransactionDAO.delete(intParam(p, "id", 0), uid);
                return map("ok", true);

            case "GET /dashboard":
                return dashboard(uid, month(p), year(p));

            case "GET /budgets":
                return BudgetService.addStatus(BudgetDAO.listForMonth(uid, month(p), year(p)));

            case "POST /budgets/save": {
                int id = intParam(p, "id", 0);
                BigDecimal limit = money(p, "limit", "Budget limit", false);
                if (id > 0) {
                    BudgetDAO.updateLimit(id, uid, limit);
                } else {
                    BudgetDAO.create(uid, intParam(p, "categoryId", 0), limit, month(p), year(p));
                }
                return map("ok", true);
            }

            case "POST /budgets/delete":
                BudgetDAO.delete(intParam(p, "id", 0), uid);
                return map("ok", true);

            case "GET /goals":
                return goals(uid);

            case "POST /goals/save": {
                int id = intParam(p, "id", 0);
                String name = text(p, "name", "Goal name", 100, true);
                BigDecimal target = money(p, "target", "Target amount", false);
                BigDecimal saved = blank(p.get("saved")) ? BigDecimal.ZERO : money(p, "saved", "Saved amount", true);
                LocalDate deadline = LocalDate.parse(required(p, "deadline", "Deadline"));
                if (id > 0) SavingsGoalDAO.update(id, uid, name, target, saved, deadline);
                else SavingsGoalDAO.create(uid, name, target, saved, deadline);
                return map("ok", true);
            }

            case "POST /goals/deposit":
                SavingsGoalDAO.deposit(intParam(p, "id", 0), uid, money(p, "amount", "Amount", false));
                return map("ok", true);

            case "POST /goals/delete":
                SavingsGoalDAO.delete(intParam(p, "id", 0), uid);
                return map("ok", true);

            case "GET /report":
                return report(uid, intParam(p, "month", 0), year(p));

            default:
                throw new ApiException(404, "Unknown request");
        }
    }

    // ------------------------------------------------------------------ authentication

    private static Object register(Map<String, String> p, HttpExchange ex) throws SQLException {
        String name = text(p, "name", "Name", 100, true);
        String email = required(p, "email", "Email").toLowerCase();
        String password = required(p, "password", "Password");
        if (email.length() > 150 || !email.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$"))
            throw new IllegalArgumentException("Please enter a valid email address");
        if (password.length() < 6) throw new IllegalArgumentException("Password must be at least 6 characters");
        if (UserDAO.findByEmail(email) != null)
            throw new ApiException(409, "An account with this email already exists");
        int userId = UserDAO.create(name, email, PasswordUtil.hash(password));
        startSession(ex, new Session(userId, name));
        return map("name", name);
    }

    private static Object login(Map<String, String> p, HttpExchange ex) throws SQLException {
        String email = required(p, "email", "Email").toLowerCase();
        String password = required(p, "password", "Password");
        Map<String, Object> user = UserDAO.findByEmail(email);
        // same message for "no such email" and "wrong password" (does not reveal which emails exist)
        if (user == null || !PasswordUtil.verify(password, (String) user.get("password_hash")))
            throw new ApiException(401, "Invalid email or password");
        String name = (String) user.get("name");
        startSession(ex, new Session(((Number) user.get("user_id")).intValue(), name));
        return map("name", name);
    }

    private static Object logout(HttpExchange ex) {
        String token = cookieToken(ex);
        if (token != null) sessions.remove(token);
        ex.getResponseHeaders().add("Set-Cookie", "sid=; Path=/; Max-Age=0; HttpOnly; SameSite=Strict");
        return map("ok", true);
    }

    private static void startSession(HttpExchange ex, Session session) {
        byte[] random = new byte[32];
        new SecureRandom().nextBytes(random);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        sessions.put(token, session);
        ex.getResponseHeaders().add("Set-Cookie", "sid=" + token + "; Path=/; HttpOnly; SameSite=Strict");
    }

    private static String cookieToken(HttpExchange ex) {
        String header = ex.getRequestHeaders().getFirst("Cookie");
        if (header == null) return null;
        for (String part : header.split(";")) {
            part = part.trim();
            if (part.startsWith("sid=")) return part.substring(4);
        }
        return null;
    }

    private static Session requireSession(HttpExchange ex) {
        String token = cookieToken(ex);
        Session s = token == null ? null : sessions.get(token);
        if (s == null) throw new ApiException(401, "Please log in");
        return s;
    }

    // ------------------------------------------------------------------ dashboard / reports / goals

    private static Map<String, Object> dashboard(int uid, int month, int year) throws SQLException {
        LocalDate start = LocalDate.of(year, month, 1);
        LocalDate end = start.plusMonths(1);
        LocalDate prevStart = start.minusMonths(1);

        BigDecimal income = TransactionDAO.sum(uid, "INCOME", start, end);
        BigDecimal expense = TransactionDAO.sum(uid, "EXPENSE", start, end);
        BigDecimal prevIncome = TransactionDAO.sum(uid, "INCOME", prevStart, start);
        BigDecimal prevExpense = TransactionDAO.sum(uid, "EXPENSE", prevStart, start);
        BigDecimal totalSavings = TransactionDAO.sum(uid, "INCOME", null, null)
                .subtract(TransactionDAO.sum(uid, "EXPENSE", null, null));

        // last 6 months for the bar chart (months without data become 0)
        LocalDate trendStart = start.minusMonths(5);
        Map<Integer, Map<String, Object>> byMonth = new HashMap<>();
        for (Map<String, Object> row : ReportDAO.monthlyTotals(uid, trendStart, end)) {
            byMonth.put(((Number) row.get("y")).intValue() * 100 + ((Number) row.get("m")).intValue(), row);
        }
        List<Map<String, Object>> trend = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            LocalDate d = trendStart.plusMonths(i);
            Map<String, Object> row = byMonth.get(d.getYear() * 100 + d.getMonthValue());
            trend.add(map("year", d.getYear(), "month", d.getMonthValue(),
                    "income", row == null ? BigDecimal.ZERO : row.get("income"),
                    "expense", row == null ? BigDecimal.ZERO : row.get("expense")));
        }

        return map(
                "month", month, "year", year,
                "income", income, "expense", expense,
                "balance", income.subtract(expense),
                "totalSavings", totalSavings,
                "prevIncome", prevIncome, "prevExpense", prevExpense,
                "incomeChange", percentChange(income, prevIncome),
                "expenseChange", percentChange(expense, prevExpense),
                "categories", ReportDAO.categoryTotals(uid, "EXPENSE", start, end),
                "budgets", BudgetService.addStatus(BudgetDAO.listForMonth(uid, month, year)),
                "trend", trend);
    }

    /** month = 0 means "whole year". */
    private static Map<String, Object> report(int uid, int month, int year) throws SQLException {
        if (month < 0 || month > 12) throw new IllegalArgumentException("Invalid month");
        LocalDate start = month == 0 ? LocalDate.of(year, 1, 1) : LocalDate.of(year, month, 1);
        LocalDate end = month == 0 ? start.plusYears(1) : start.plusMonths(1);
        BigDecimal income = TransactionDAO.sum(uid, "INCOME", start, end);
        BigDecimal expense = TransactionDAO.sum(uid, "EXPENSE", start, end);

        // one row per month of the year (zeros where there is no data)
        Map<Integer, Map<String, Object>> byMonth = new HashMap<>();
        LocalDate yearStart = LocalDate.of(year, 1, 1);
        for (Map<String, Object> row : ReportDAO.monthlyTotals(uid, yearStart, yearStart.plusYears(1))) {
            byMonth.put(((Number) row.get("m")).intValue(), row);
        }
        List<Map<String, Object>> monthly = new ArrayList<>();
        for (int m = 1; m <= 12; m++) {
            Map<String, Object> row = byMonth.get(m);
            monthly.add(map("month", m,
                    "income", row == null ? BigDecimal.ZERO : row.get("income"),
                    "expense", row == null ? BigDecimal.ZERO : row.get("expense")));
        }
        return map("month", month, "year", year,
                "income", income, "expense", expense, "net", income.subtract(expense),
                "expenseByCategory", ReportDAO.categoryTotals(uid, "EXPENSE", start, end),
                "incomeByCategory", ReportDAO.categoryTotals(uid, "INCOME", start, end),
                "monthly", monthly);
    }

    private static List<Map<String, Object>> goals(int uid) throws SQLException {
        List<Map<String, Object>> rows = SavingsGoalDAO.list(uid);
        for (Map<String, Object> g : rows) {
            BigDecimal target = new BigDecimal(String.valueOf(g.get("target_amount")));
            BigDecimal saved = new BigDecimal(String.valueOf(g.get("saved_amount")));
            BigDecimal percent = saved.multiply(new BigDecimal("100")).divide(target, 1, RoundingMode.HALF_UP);
            if (percent.compareTo(new BigDecimal("100")) > 0) percent = new BigDecimal("100.0");
            LocalDate deadline = LocalDate.parse(g.get("deadline").toString());
            g.put("percent", percent);
            g.put("remaining", target.subtract(saved).max(BigDecimal.ZERO));
            g.put("daysLeft", ChronoUnit.DAYS.between(LocalDate.now(), deadline));
        }
        return rows;
    }

    /** % change versus last month; null when last month had nothing to compare with. */
    private static BigDecimal percentChange(BigDecimal current, BigDecimal previous) {
        if (previous.signum() == 0) return null;
        return current.subtract(previous).multiply(new BigDecimal("100"))
                .divide(previous, 1, RoundingMode.HALF_UP);
    }

    // ------------------------------------------------------------------ input helpers (validation)

    private static Map<String, Object> map(Object... keysAndValues) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) m.put((String) keysAndValues[i], keysAndValues[i + 1]);
        return m;
    }

    private static Map<String, String> parseForm(String raw) {
        Map<String, String> result = new HashMap<>();
        if (raw == null || raw.isEmpty()) return result;
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            String key = eq < 0 ? pair : pair.substring(0, eq);
            String value = eq < 0 ? "" : pair.substring(eq + 1);
            result.put(URLDecoder.decode(key, StandardCharsets.UTF_8), URLDecoder.decode(value, StandardCharsets.UTF_8));
        }
        return result;
    }

    private static boolean blank(String s) { return s == null || s.trim().isEmpty(); }

    private static String required(Map<String, String> p, String key, String label) {
        String v = p.get(key);
        if (blank(v)) throw new IllegalArgumentException(label + " is required");
        return v.trim();
    }

    private static String text(Map<String, String> p, String key, String label, int maxLength, boolean mandatory) {
        String v = p.get(key);
        if (blank(v)) {
            if (mandatory) throw new IllegalArgumentException(label + " is required");
            return null;
        }
        v = v.trim();
        if (v.length() > maxLength) throw new IllegalArgumentException(label + " is too long (max " + maxLength + ")");
        return v;
    }

    private static int intParam(Map<String, String> p, String key, int defaultValue) {
        String v = p.get(key);
        if (blank(v)) return defaultValue;
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid value for " + key);
        }
    }

    private static int month(Map<String, String> p) {
        int m = intParam(p, "month", LocalDate.now().getMonthValue());
        if (m < 1 || m > 12) throw new IllegalArgumentException("Month must be between 1 and 12");
        return m;
    }

    private static int year(Map<String, String> p) {
        int y = intParam(p, "year", LocalDate.now().getYear());
        if (y < 2000 || y > 2100) throw new IllegalArgumentException("Year must be between 2000 and 2100");
        return y;
    }

    /** Parses a money value with 2 decimals and checks it is positive (or zero if allowed). */
    private static BigDecimal money(Map<String, String> p, String key, String label, boolean allowZero) {
        String v = p.get(key);
        if (blank(v)) throw new IllegalArgumentException(label + " is required");
        BigDecimal amount;
        try {
            amount = new BigDecimal(v.trim()).setScale(2, RoundingMode.HALF_UP);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(label + " must be a valid number");
        }
        if (amount.signum() < 0 || (amount.signum() == 0 && !allowZero))
            throw new IllegalArgumentException(label + " must be greater than zero");
        if (amount.compareTo(MAX_AMOUNT) > 0) throw new IllegalArgumentException(label + " is too large");
        return amount;
    }

    private static String requireType(String type) {
        if ("INCOME".equals(type) || "EXPENSE".equals(type)) return type;
        throw new IllegalArgumentException("Type must be INCOME or EXPENSE");
    }

    private static String optionalType(String type) {
        return blank(type) ? null : requireType(type);
    }
}
