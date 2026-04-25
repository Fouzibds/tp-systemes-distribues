package org.example;

import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public class UserRepository {
    public boolean authenticate(String username, String password) throws SQLException {
        return callBooleanProcedure("{CALL authenticate_user(?, ?)}", username, password);
    }

    public boolean userExists(String username) throws SQLException {
        return callBooleanProcedure("{CALL user_exists(?)}", username);
    }

    public boolean createUser(String username, String password) throws SQLException {
        return callBooleanProcedure("{CALL create_user(?, ?)}", username, password);
    }

    public boolean updatePassword(String username, String newPassword) throws SQLException {
        return callBooleanProcedure("{CALL update_password(?, ?)}", username, newPassword);
    }

    public boolean deleteUser(String username) throws SQLException {
        return callBooleanProcedure("{CALL delete_user(?)}", username);
    }

    public List<String> listUsers() throws SQLException {
        List<String> users = new ArrayList<>();
        String sql = "SELECT username FROM users WHERE active = TRUE ORDER BY username ASC";
        try (Connection connection = DatabaseConnection.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                users.add(resultSet.getString("username"));
            }
        }
        return users;
    }

    private boolean callBooleanProcedure(String callSql, String... values) throws SQLException {
        try (Connection connection = DatabaseConnection.getConnection();
             CallableStatement statement = connection.prepareCall(callSql)) {
            for (int i = 0; i < values.length; i++) {
                statement.setString(i + 1, values[i]);
            }

            boolean hasResultSet = statement.execute();
            if (!hasResultSet) {
                return false;
            }

            try (ResultSet resultSet = statement.getResultSet()) {
                return resultSet.next() && resultSet.getBoolean(1);
            }
        }
    }
}
