package service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;

/** Business rules for budgets: the 80% warning and the over-budget alert. */
public class BudgetService {

    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final BigDecimal EIGHTY = new BigDecimal("80");

    /** Adds percent, remaining and status (OK / WARNING / EXCEEDED) to every budget row. */
    public static List<Map<String, Object>> addStatus(List<Map<String, Object>> budgets) {
        for (Map<String, Object> b : budgets) {
            BigDecimal limit = new BigDecimal(String.valueOf(b.get("monthly_limit")));
            BigDecimal spent = new BigDecimal(String.valueOf(b.get("spent")));
            b.put("percent", spent.multiply(HUNDRED).divide(limit, 1, RoundingMode.HALF_UP));
            b.put("remaining", limit.subtract(spent));
            b.put("status", status(spent, limit));
        }
        return budgets;
    }

    public static String status(BigDecimal spent, BigDecimal limit) {
        if (spent.compareTo(limit) > 0) return "EXCEEDED";                                  // over 100%
        if (spent.multiply(HUNDRED).compareTo(limit.multiply(EIGHTY)) >= 0) return "WARNING"; // 80% or more
        return "OK";
    }
}
