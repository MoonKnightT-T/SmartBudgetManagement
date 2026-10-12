# Smart Budget Management System
Java + JDBC + MySQL backend, with a browser dashboard (HTML/CSS/JavaScript, no frameworks).

## Run it (Windows)
1. Install JDK 17+ (check: `javac -version`) and MySQL 8 (check: `SELECT VERSION();`).
2. Create the database: MySQL Workbench -> File -> Open SQL Script -> `database/smart_budget_db.sql` -> lightning bolt.
3. Download the MySQL Connector/J jar and put it in the `lib` folder.
4. Open `db.properties` and put YOUR MySQL password after `db.password=`.
5. Double-click `run.bat`. Wait for: `Smart Budget is running.`
6. Open http://localhost:8080 in Chrome. Create an account and start.

Mac/Linux: run `./run.sh` instead of run.bat.

## Architecture
Browser (web/index.html) -> Main.java (/api/...) -> service -> dao -> database/Query + DatabaseConnection -> MySQL

| Folder | Purpose |
|---|---|
| src/model | Transaction (abstract), Income, Expense - OOP |
| src/dao | All SQL (PreparedStatement), always filtered by user_id |
| src/service | BudgetService - 80% warning / exceeded rule |
| src/database | DatabaseConnection (reads db.properties), Query helper |
| src/util | PasswordUtil (PBKDF2 hashing), Json, ApiException |
| web | The dashboard website |
| database | smart_budget_db.sql |

## Troubleshooting
- "Cannot connect to the database": MySQL not running, wrong password in db.properties, or driver jar missing from lib.
- "Address already in use": something uses port 8080. Close the old run window, or change PORT in Main.java.
- "No suitable driver": the jar is not inside lib/.
- Page says "Cannot reach the server": run.bat window was closed.
