package database;

import java.io.FileInputStream;
import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;

/**
 * The ONLY place that knows the database URL, user and password.
 * They are read from db.properties (in the project root folder).
 */
public class DatabaseConnection {

    private static String url;
    private static String user;
    private static String password;

    static {
        String file = System.getProperty("db.config", "db.properties");
        Properties props = new Properties();
        try (FileInputStream in = new FileInputStream(file)) {
            props.load(in);
            url = props.getProperty("db.url");
            user = props.getProperty("db.user");
            password = props.getProperty("db.password");
        } catch (IOException e) {
            System.err.println("Could not read " + file + " - run the program from the project folder.");
        }
    }

    public static Connection getConnection() throws SQLException {
        if (url == null) throw new SQLException("Database settings are missing (db.properties)");
        return DriverManager.getConnection(url, user, password);
    }
}
