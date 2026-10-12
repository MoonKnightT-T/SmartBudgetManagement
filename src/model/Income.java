package model;

import java.math.BigDecimal;
import java.time.LocalDate;

/** INHERITANCE: Income IS-A Transaction. */
public class Income extends Transaction {

    public Income(int userId, int categoryId, BigDecimal amount,
                  LocalDate transactionDate, String description) {
        super(userId, categoryId, amount, transactionDate, description);
    }

    @Override   // POLYMORPHISM
    public String getTransactionType() { return "INCOME"; }

    @Override
    public BigDecimal getSignedAmount() { return getAmount(); }
}
