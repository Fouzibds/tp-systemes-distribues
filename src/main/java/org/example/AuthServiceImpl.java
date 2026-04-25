package org.example;

import java.util.List;

public class AuthServiceImpl implements AuthService {
    private final UserRepository userRepository = new UserRepository();

    public AuthServiceImpl() {
    }

    @Override
    public synchronized boolean authenticate(String username, String password) {
        String normalizedUsername = normalize(username);
        if (normalizedUsername == null || isBlank(password)) {
            return false;
        }

        try {
            return userRepository.authenticate(normalizedUsername, password);
        } catch (Exception e) {
            System.err.println("Database authentication failed: " + e.getMessage());
            return false;
        }
    }

    @Override
    public synchronized boolean userExists(String username) {
        String normalizedUsername = normalize(username);
        if (normalizedUsername == null) {
            return false;
        }

        try {
            return userRepository.userExists(normalizedUsername);
        } catch (Exception e) {
            System.err.println("Database user lookup failed: " + e.getMessage());
            return false;
        }
    }

    @Override
    public synchronized boolean createUser(String username, String password) {
        String normalizedUsername = normalize(username);
        if (normalizedUsername == null || isBlank(password)) {
            return false;
        }

        try {
            return userRepository.createUser(normalizedUsername, password);
        } catch (Exception e) {
            System.err.println("Database user creation failed: " + e.getMessage());
            return false;
        }
    }

    @Override
    public synchronized boolean updatePassword(String username, String newPassword) {
        String normalizedUsername = normalize(username);
        if (normalizedUsername == null || isBlank(newPassword)) {
            return false;
        }

        try {
            return userRepository.updatePassword(normalizedUsername, newPassword);
        } catch (Exception e) {
            System.err.println("Database password update failed: " + e.getMessage());
            return false;
        }
    }

    @Override
    public synchronized boolean deleteUser(String username) {
        String normalizedUsername = normalize(username);
        if (normalizedUsername == null) {
            return false;
        }

        try {
            return userRepository.deleteUser(normalizedUsername);
        } catch (Exception e) {
            System.err.println("Database user deletion failed: " + e.getMessage());
            return false;
        }
    }

    @Override
    public synchronized List<String> listUsers() {
        try {
            return userRepository.listUsers();
        } catch (Exception e) {
            System.err.println("Database user listing failed: " + e.getMessage());
            return List.of();
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private String normalize(String username) {
        if (isBlank(username)) {
            return null;
        }
        return username.trim();
    }
}
