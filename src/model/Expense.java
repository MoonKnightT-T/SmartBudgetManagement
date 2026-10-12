package model;

import java.math.BigDecimal;
import java.time.LocalDate;

/** INHERITANCE: Expense IS-A Transaction. */
public class Expense extends Transaction {

    public Expense(int userId, int categoryId, BigDecimal amount,
                   LocalDate transactionDate, String description) {
        super(userId, categoryId, amount, transactionDate, description);
    }

    @Override   // POLYMORPHISM
    public String getTransactionType() { return "EXPENSE"; }

    @Override
    public BigDecimal getSignedAmount() { return getAmount().negate(); }
}
