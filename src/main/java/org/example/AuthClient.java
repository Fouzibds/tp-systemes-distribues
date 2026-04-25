package org.example;

import java.rmi.NotBoundException;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.util.concurrent.atomic.AtomicBoolean;

public final class AuthClient {
    private static final String DEFAULT_HOST = "127.0.0.1";
    private static final int DEFAULT_PORT = 1099;
    private static final String SERVICE_NAME = "AuthService";
    private static final AtomicBoolean WARMED_UP = new AtomicBoolean(false);

    private AuthClient() {
    }

    public static void warmUp() {
        if (!WARMED_UP.compareAndSet(false, true)) {
            return;
        }

        try {
            LocateRegistry.getRegistry(getHost(), getPort(), AuthSocketFactories.clientFactory());
        } catch (RemoteException ignored) {
            // Warm-up should never prevent the servers from starting.
        }
    }

    public static AuthResult authenticate(String username, String password) {
        return call(service -> service.authenticate(username, password));
    }

    public static AuthResult userExists(String username) {
        return call(service -> service.userExists(username));
    }

    private static AuthResult call(RemoteCall remoteCall) {
        try {
            AuthService service = lookupService();
            boolean value = remoteCall.execute(service);
            return AuthResult.available(value);
        } catch (RemoteException | NotBoundException | RuntimeException e) {
            return AuthResult.unavailable("authentication service unavailable");
        }
    }

    private static AuthService lookupService() throws RemoteException, NotBoundException {
        Registry registry = LocateRegistry.getRegistry(getHost(), getPort(), AuthSocketFactories.clientFactory());
        return (AuthService) registry.lookup(SERVICE_NAME);
    }

    private static String getHost() {
        String value = System.getProperty("auth.host");
        if (value == null || value.isBlank()) {
            value = System.getenv("AUTH_HOST");
        }
        return value == null || value.isBlank() ? DEFAULT_HOST : value.trim();
    }

    private static int getPort() {
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

    @FunctionalInterface
    private interface RemoteCall {
        boolean execute(AuthService service) throws RemoteException;
    }

    public static final class AuthResult {
        private final boolean available;
        private final boolean value;
        private final String message;

        private AuthResult(boolean available, boolean value, String message) {
            this.available = available;
            this.value = value;
            this.message = message;
        }

        public static AuthResult available(boolean value) {
            return new AuthResult(true, value, null);
        }

        public static AuthResult unavailable(String message) {
            return new AuthResult(false, false, message);
        }

        public boolean isAvailable() {
            return available;
        }

        public boolean getValue() {
            return value;
        }

        public String getMessage() {
            return message;
        }
    }
}
