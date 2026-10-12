-- =====================================================================
-- Smart Budget Management System - Database Script
-- Requires MySQL 8.0.16 or newer (CHECK constraints are enforced from 8.0.16)
-- WARNING: this script DROPS and recreates smart_budget_db so you can re-run
-- it freely during development. Do not run it on a database with real data.
-- =====================================================================

DROP DATABASE IF EXISTS smart_budget_db;
CREATE DATABASE smart_budget_db
    CHARACTER SET utf8mb4
    COLLATE utf8mb4_unicode_ci;

USE smart_budget_db;

-- ---------------------------------------------------------------------
-- 1. users
-- password_hash stores "salt:hash" produced by PBKDF2 in Java (never plaintext)
-- ---------------------------------------------------------------------
CREATE TABLE users (
    user_id        INT           NOT NULL AUTO_INCREMENT,
    name           VARCHAR(100)  NOT NULL,
    email          VARCHAR(150)  NOT NULL,
    password_hash  VARCHAR(255)  NOT NULL,
    created_at     TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id),
    UNIQUE KEY uq_users_email (email)
) ENGINE=InnoDB;

-- ---------------------------------------------------------------------
-- 2. categories
-- user_id IS NULL      -> predefined category shared by everyone
-- user_id IS NOT NULL  -> custom category owned by one user
-- UNIQUE (category_id, type) exists only so other tables can reference
-- the pair (category_id, type) and keep types consistent.
-- ---------------------------------------------------------------------
CREATE TABLE categories (
    category_id    INT           NOT NULL AUTO_INCREMENT,
    user_id        INT           NULL,
    category_name  VARCHAR(50)   NOT NULL,
    type           ENUM('INCOME','EXPENSE') NOT NULL,
    PRIMARY KEY (category_id),
    UNIQUE KEY uq_category_id_type (category_id, type),
    UNIQUE KEY uq_category_owner_name_type (user_id, category_name, type),
    CONSTRAINT fk_categories_user
        FOREIGN KEY (user_id) REFERENCES users(user_id)
        ON DELETE CASCADE
) ENGINE=InnoDB;

-- ---------------------------------------------------------------------
-- 3. transactions
-- The composite foreign key (category_id, type) guarantees that an INCOME
-- transaction can only use an INCOME category and an EXPENSE transaction
-- only an EXPENSE category. The database enforces this, not just Java.
-- ---------------------------------------------------------------------
CREATE TABLE transactions (
    transaction_id    INT            NOT NULL AUTO_INCREMENT,
    user_id           INT            NOT NULL,
    category_id       INT            NOT NULL,
    amount            DECIMAL(12,2)  NOT NULL,
    type              ENUM('INCOME','EXPENSE') NOT NULL,
    transaction_date  DATE           NOT NULL,
    description       VARCHAR(255)   NULL,
    created_at        TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (transaction_id),
    CONSTRAINT chk_transactions_amount CHECK (amount > 0),
    CONSTRAINT fk_transactions_user
        FOREIGN KEY (user_id) REFERENCES users(user_id)
        ON DELETE CASCADE,
    CONSTRAINT fk_transactions_category
        FOREIGN KEY (category_id, type) REFERENCES categories(category_id, type)
        ON DELETE RESTRICT,
    INDEX idx_transactions_user_date (user_id, transaction_date),
    INDEX idx_transactions_user_category (user_id, category_id)
) ENGINE=InnoDB;

-- ---------------------------------------------------------------------
-- 4. budgets
-- Budgets only make sense for expense categories. category_type defaults to
-- 'EXPENSE' and is part of the composite foreign key; BudgetDAO only accepts
-- expense categories. (A CHECK on it is not allowed together with ON DELETE CASCADE.)
-- ---------------------------------------------------------------------
CREATE TABLE budgets (
    budget_id       INT            NOT NULL AUTO_INCREMENT,
    user_id         INT            NOT NULL,
    category_id     INT            NOT NULL,
    -- must be the SAME ENUM definition as categories.type or the foreign key fails.
    -- Always 'EXPENSE' (BudgetDAO checks the category is an expense category).
    category_type   ENUM('INCOME','EXPENSE') NOT NULL DEFAULT 'EXPENSE',
    monthly_limit   DECIMAL(12,2)  NOT NULL,
    budget_month    TINYINT        NOT NULL,
    budget_year     SMALLINT       NOT NULL,
    PRIMARY KEY (budget_id),
    CONSTRAINT chk_budgets_limit CHECK (monthly_limit > 0),
    CONSTRAINT chk_budgets_month CHECK (budget_month BETWEEN 1 AND 12),
    CONSTRAINT chk_budgets_year  CHECK (budget_year BETWEEN 2000 AND 2100),
    -- prevents duplicate budgets for same user + category + month + year
    UNIQUE KEY uq_budget_user_cat_month_year (user_id, category_id, budget_month, budget_year),
    CONSTRAINT fk_budgets_user
        FOREIGN KEY (user_id) REFERENCES users(user_id)
        ON DELETE CASCADE,
    CONSTRAINT fk_budgets_category
        FOREIGN KEY (category_id, category_type) REFERENCES categories(category_id, type)
        ON DELETE CASCADE
) ENGINE=InnoDB;

-- ---------------------------------------------------------------------
-- 5. savings_goals
-- ---------------------------------------------------------------------
CREATE TABLE savings_goals (
    goal_id        INT            NOT NULL AUTO_INCREMENT,
    user_id        INT            NOT NULL,
    goal_name      VARCHAR(100)   NOT NULL,
    target_amount  DECIMAL(12,2)  NOT NULL,
    saved_amount   DECIMAL(12,2)  NOT NULL DEFAULT 0.00,
    deadline       DATE           NOT NULL,
    created_at     TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (goal_id),
    CONSTRAINT chk_goals_target CHECK (target_amount > 0),
    CONSTRAINT chk_goals_saved  CHECK (saved_amount >= 0),
    CONSTRAINT fk_goals_user
        FOREIGN KEY (user_id) REFERENCES users(user_id)
        ON DELETE CASCADE,
    INDEX idx_goals_user (user_id)
) ENGINE=InnoDB;

-- ---------------------------------------------------------------------
-- Predefined categories (user_id = NULL means available to every user)
-- ---------------------------------------------------------------------
INSERT INTO categories (user_id, category_name, type) VALUES
    (NULL, 'Food',          'EXPENSE'),
    (NULL, 'Travel',        'EXPENSE'),
    (NULL, 'Shopping',      'EXPENSE'),
    (NULL, 'Education',     'EXPENSE'),
    (NULL, 'Bills',         'EXPENSE'),
    (NULL, 'Healthcare',    'EXPENSE'),
    (NULL, 'Entertainment', 'EXPENSE'),
    (NULL, 'Other',         'EXPENSE'),
    (NULL, 'Salary',        'INCOME'),
    (NULL, 'Freelance',     'INCOME'),
    (NULL, 'Investment',    'INCOME'),
    (NULL, 'Gift',          'INCOME'),
    (NULL, 'Other',         'INCOME');
