package org.example;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import java.awt.BorderLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.rmi.NotBoundException;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.util.List;

public class AuthAdminDashboard extends JFrame {
    private static final String DEFAULT_HOST = "127.0.0.1";
    private static final int DEFAULT_PORT = 1099;
    private static final String SERVICE_NAME = "AuthService";

    private final DefaultListModel<String> usersModel = new DefaultListModel<>();
    private final JList<String> usersList = new JList<>(usersModel);
    private final JTextField usernameField = new JTextField(20);
    private final JPasswordField passwordField = new JPasswordField(20);

    private final JButton refreshButton = new JButton("Refresh Users");
    private final JButton addButton = new JButton("Add User");
    private final JButton updateButton = new JButton("Update Password");
    private final JButton deleteButton = new JButton("Delete User");
    private final JButton testButton = new JButton("Test Login");

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            AuthAdminDashboard dashboard = new AuthAdminDashboard();
            dashboard.setVisible(true);
        });
    }

    public AuthAdminDashboard() {
        super("RMI Authentication Admin Dashboard");
        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        setSize(720, 420);
        setLocationRelativeTo(null);

        buildUi();
        refreshUsers();
    }

    private void buildUi() {
        JPanel root = new JPanel(new BorderLayout(10, 10));
        root.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        usersList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        usersList.addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting()) {
                String selectedUser = usersList.getSelectedValue();
                if (selectedUser != null) {
                    usernameField.setText(selectedUser);
                    passwordField.setText("");
                }
            }
        });

        JScrollPane usersScrollPane = new JScrollPane(usersList);
        usersScrollPane.setBorder(BorderFactory.createTitledBorder("Users"));
        root.add(usersScrollPane, BorderLayout.CENTER);
        root.add(buildAdminPanel(), BorderLayout.EAST);

        setContentPane(root);
    }

    private JPanel buildAdminPanel() {
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.setBorder(BorderFactory.createTitledBorder("Administration"));

        JPanel fields = new JPanel(new GridBagLayout());
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.insets = new Insets(4, 4, 4, 4);
        constraints.fill = GridBagConstraints.HORIZONTAL;

        constraints.gridx = 0;
        constraints.gridy = 0;
        fields.add(new JLabel("Username:"), constraints);
        constraints.gridx = 1;
        fields.add(usernameField, constraints);

        constraints.gridx = 0;
        constraints.gridy = 1;
        fields.add(new JLabel("Password:"), constraints);
        constraints.gridx = 1;
        fields.add(passwordField, constraints);

        panel.add(fields, BorderLayout.NORTH);
        panel.add(buildButtonsPanel(), BorderLayout.CENTER);
        return panel;
    }

    private JPanel buildButtonsPanel() {
        JPanel buttons = new JPanel(new GridLayout(5, 1, 6, 6));

        refreshButton.addActionListener(event -> refreshUsers());
        addButton.addActionListener(event -> addUser());
        updateButton.addActionListener(event -> updatePassword());
        deleteButton.addActionListener(event -> deleteUser());
        testButton.addActionListener(event -> testLogin());

        buttons.add(refreshButton);
        buttons.add(addButton);
        buttons.add(updateButton);
        buttons.add(deleteButton);
        buttons.add(testButton);
        return buttons;
    }

    private void refreshUsers() {
        runRemote("Refresh users", service -> service.listUsers(), users -> {
            usersModel.clear();
            for (String user : users) {
                usersModel.addElement(user);
            }
        });
    }

    private void addUser() {
        Credentials credentials = readCredentials();
        if (credentials == null) {
            return;
        }

        runRemote("Add user", service -> service.createUser(credentials.username, credentials.password), created -> {
            if (created) {
                showInfo("User created successfully.");
                clearPassword();
                refreshUsers();
            } else {
                showError("User could not be created. Check that the fields are filled and the user does not already exist.");
            }
        });
    }

    private void updatePassword() {
        Credentials credentials = readCredentials();
        if (credentials == null) {
            return;
        }

        runRemote("Update password", service -> service.updatePassword(credentials.username, credentials.password), updated -> {
            if (updated) {
                showInfo("Password updated successfully.");
                clearPassword();
            } else {
                showError("Password could not be updated. Check that the user exists and the new password is not empty.");
            }
        });
    }

    private void deleteUser() {
        String username = readUsername();
        if (username == null) {
            return;
        }

        int choice = JOptionPane.showConfirmDialog(
                this,
                "Delete user \"" + username + "\"?",
                "Confirm delete",
                JOptionPane.YES_NO_OPTION);
        if (choice != JOptionPane.YES_OPTION) {
            return;
        }

        runRemote("Delete user", service -> service.deleteUser(username), deleted -> {
            if (deleted) {
                showInfo("User deleted successfully.");
                usernameField.setText("");
                clearPassword();
                refreshUsers();
            } else {
                showError("User could not be deleted. Check that the user exists.");
            }
        });
    }

    private void testLogin() {
        Credentials credentials = readCredentials();
        if (credentials == null) {
            return;
        }

        runRemote("Test login", service -> service.authenticate(credentials.username, credentials.password), authenticated -> {
            if (authenticated) {
                showInfo("Authentication succeeded.");
            } else {
                showError("Authentication failed.");
            }
            clearPassword();
        });
    }

    private Credentials readCredentials() {
        String username = readUsername();
        String password = new String(passwordField.getPassword());
        if (username == null || password.isBlank()) {
            showError("Username and password are required.");
            return null;
        }
        return new Credentials(username, password);
    }

    private String readUsername() {
        String username = usernameField.getText().trim();
        if (username.isEmpty()) {
            showError("Username is required.");
            return null;
        }
        return username;
    }

    private void clearPassword() {
        passwordField.setText("");
    }

    private <T> void runRemote(String actionName, RemoteAction<T> action, RemoteSuccess<T> success) {
        setButtonsEnabled(false);
        new SwingWorker<T, Void>() {
            @Override
            protected T doInBackground() throws Exception {
                return action.execute(lookupService());
            }

            @Override
            protected void done() {
                setButtonsEnabled(true);
                try {
                    success.accept(get());
                } catch (Exception e) {
                    showError(actionName + " failed. Start AuthServer first, then try again.");
                }
            }
        }.execute();
    }

    private AuthService lookupService() throws RemoteException, NotBoundException {
        Registry registry = LocateRegistry.getRegistry(getHost(), getPort(), AuthSocketFactories.clientFactory());
        return (AuthService) registry.lookup(SERVICE_NAME);
    }

    private String getHost() {
        String value = System.getProperty("auth.host");
        if (value == null || value.isBlank()) {
            value = System.getenv("AUTH_HOST");
        }
        return value == null || value.isBlank() ? DEFAULT_HOST : value.trim();
    }

    private int getPort() {
        String value = System.getProperty("auth.port");
        if (value == null || value.isBlank()) {
            value = System.getenv("AUTH_PORT");
        }
        if (value == null || value.isBlank()) {
            return DEFAULT_PORT;
        }

        try {
            int port = Integer.parseInt(value.trim());
            return port > 0 && port <= 65535 ? port : DEFAULT_PORT;
        } catch (NumberFormatException e) {
            return DEFAULT_PORT;
        }
    }

    private void setButtonsEnabled(boolean enabled) {
        refreshButton.setEnabled(enabled);
        addButton.setEnabled(enabled);
        updateButton.setEnabled(enabled);
        deleteButton.setEnabled(enabled);
        testButton.setEnabled(enabled);
    }

    private void showInfo(String message) {
        JOptionPane.showMessageDialog(this, message, "Success", JOptionPane.INFORMATION_MESSAGE);
    }

    private void showError(String message) {
        JOptionPane.showMessageDialog(this, message, "Error", JOptionPane.ERROR_MESSAGE);
    }

    @FunctionalInterface
    private interface RemoteAction<T> {
        T execute(AuthService service) throws RemoteException;
    }

    @FunctionalInterface
    private interface RemoteSuccess<T> {
        void accept(T value);
    }

    private static final class Credentials {
        private final String username;
        private final String password;

        private Credentials(String username, String password) {
            this.username = username;
            this.password = password;
        }
    }
}
