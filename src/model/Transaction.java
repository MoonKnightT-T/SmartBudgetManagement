package model;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * ABSTRACTION + ENCAPSULATION:
 * Abstract parent of Income and Expense. Fields are private and are only
 * reachable through getters/setters, which also validate the data.
 */
public abstract class Transaction {

    private int userId;
    private int categoryId;
    private BigDecimal amount;
    private LocalDate transactionDate;
    private String description;

    public Transaction(int userId, int categoryId, BigDecimal amount,
                       LocalDate transactionDate, String description) {
        setUserId(userId);
        setCategoryId(categoryId);
        setAmount(amount);
        setTransactionDate(transactionDate);
        setDescription(description);
    }

    // ---- POLYMORPHISM: every subclass answers these differently ----
    public abstract String getTransactionType();   // "INCOME" or "EXPENSE"
    public abstract BigDecimal getSignedAmount();  // + for income, - for expense

    // ---- getters / setters (with validation) ----
    public int getUserId() { return userId; }
    public void setUserId(int userId) {
        if (userId <= 0) throw new IllegalArgumentException("Invalid user");
        this.userId = userId;
    }

    public int getCategoryId() { return categoryId; }
    public void setCategoryId(int categoryId) {
        if (categoryId <= 0) throw new IllegalArgumentException("Please choose a category");
        this.categoryId = categoryId;
    }

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0)
            throw new IllegalArgumentException("Amount must be greater than zero");
        this.amount = amount;
    }

    public LocalDate getTransactionDate() { return transactionDate; }
    public void setTransactionDate(LocalDate transactionDate) {
        if (transactionDate == null) throw new IllegalArgumentException("Date is required");
        if (transactionDate.getYear() < 2000 || transactionDate.getYear() > 2100)
            throw new IllegalArgumentException("Date must be between years 2000 and 2100");
        this.transactionDate = transactionDate;
    }

    public String getDescription() { return description; }
    public void setDescription(String description) {
        if (description != null && description.length() > 255)
            throw new IllegalArgumentException("Description is too long (max 255 characters)");
        this.description = description;
    }
}
