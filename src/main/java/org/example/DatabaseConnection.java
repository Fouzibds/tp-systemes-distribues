package org.example;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

public final class DatabaseConnection {
    public static final String DEFAULT_JDBC_URL = "jdbc:mysql://localhost:3306/mailserver";
    public static final String DEFAULT_DB_USER = "root";
    public static final String DEFAULT_DB_PASSWORD = "root";

    private DatabaseConnection() {
    }

    public static Connection getConnection() throws SQLException {
        return DriverManager.getConnection(getJdbcUrl(), getDbUser(), getDbPassword());
    }

    public static String getJdbcUrl() {
        return readConfig("db.url", "DB_URL", DEFAULT_JDBC_URL);
    }

    public static String getDbUser() {
        return readConfig("db.user", "DB_USER", DEFAULT_DB_USER);
    }

    public static String getDbPassword() {
        return readConfig("db.password", "DB_PASSWORD", DEFAULT_DB_PASSWORD);
    }

    private static String readConfig(String propertyName, String environmentName, String defaultValue) {
        String value = System.getProperty(propertyName);
        if (value == null || value.isBlank()) {
            value = System.getenv(environmentName);
        }
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }
}
