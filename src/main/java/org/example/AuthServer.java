package org.example;

import java.rmi.AlreadyBoundException;
import java.rmi.NotBoundException;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.ExportException;
import java.rmi.server.UnicastRemoteObject;
import java.util.concurrent.atomic.AtomicBoolean;

public class AuthServer {
    private static final int DEFAULT_PORT = 1099;
    private static final String DEFAULT_HOST = "127.0.0.1";
    private static final String SERVICE_NAME = "AuthService";
    private static final Object LIFECYCLE_LOCK = new Object();
    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);

    private static volatile Registry registry;
    private static volatile AuthServiceImpl service;
    private static volatile Thread serverThread;
    private static volatile boolean registryCreatedLocally;

    public static void main(String[] args) {
        runServer();
    }

    public static Thread startInBackground() {
        synchronized (LIFECYCLE_LOCK) {
            if (serverThread != null && serverThread.isAlive()) {
                return serverThread;
            }

            serverThread = new Thread(AuthServer::runServer, "Auth-RMI-Server");
            serverThread.setDaemon(true);
            serverThread.start();
            return serverThread;
        }
    }

    public static void stopServer() {
        synchronized (LIFECYCLE_LOCK) {
            RUNNING.set(false);
            if (serverThread != null) {
                serverThread.interrupt();
            }
        }

        if (registry != null) {
            try {
                registry.unbind(SERVICE_NAME);
            } catch (RemoteException | NotBoundException ignored) {
            }
        }

        if (service != null) {
            try {
                UnicastRemoteObject.unexportObject(service, true);
            } catch (Exception ignored) {
            }
        }

        if (registryCreatedLocally && registry != null) {
            try {
                UnicastRemoteObject.unexportObject(registry, true);
            } catch (Exception ignored) {
            }
        }

        synchronized (LIFECYCLE_LOCK) {
            registry = null;
            service = null;
            serverThread = null;
            registryCreatedLocally = false;
        }

        System.out.println("Auth RMI server stopped");
    }

    private static void runServer() {
        try {
            int registryPort = getConfiguredPort("auth.port", "AUTH_PORT", DEFAULT_PORT);
            int servicePort = getConfiguredServicePort(registryPort);
            System.setProperty("java.rmi.server.hostname", getConfiguredHost());
            Registry localRegistry;
            boolean createdLocally = false;

            try {
                localRegistry = LocateRegistry.createRegistry(
                        registryPort,
                        AuthSocketFactories.clientFactory(),
                        AuthSocketFactories.serverFactory());
                createdLocally = true;
            } catch (ExportException e) {
                localRegistry = LocateRegistry.getRegistry(
                        getConfiguredHost(),
                        registryPort,
                        AuthSocketFactories.clientFactory());
                localRegistry.list();
            }

            AuthServiceImpl localService = new AuthServiceImpl();
            AuthService localStub = (AuthService) UnicastRemoteObject.exportObject(
                    localService,
                    servicePort,
                    AuthSocketFactories.clientFactory(),
                    AuthSocketFactories.serverFactory());
            try {
                localRegistry.bind(SERVICE_NAME, localStub);
            } catch (AlreadyBoundException e) {
                localRegistry.rebind(SERVICE_NAME, localStub);
            }

            synchronized (LIFECYCLE_LOCK) {
                registry = localRegistry;
                service = localService;
                registryCreatedLocally = createdLocally;
                RUNNING.set(true);
            }

            System.out.println("Auth RMI server started on registry port " + registryPort);
            System.out.println("Auth remote service exported on port " + servicePort);
            System.out.println("Auth service bound as " + SERVICE_NAME);

            while (RUNNING.get()) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    if (!RUNNING.get()) {
                        break;
                    }
                }
            }
        } catch (RemoteException e) {
            System.err.println("Failed to start Auth RMI server: " + e.getMessage());
        } finally {
            stopServer();
        }
    }

    private static int getConfiguredPort(String propertyName, String environmentName, int defaultPort) {
        String value = System.getProperty(propertyName);
        if (value == null || value.isBlank()) {
            value = System.getenv(environmentName);
        }
        if (value == null || value.isBlank()) {
            return defaultPort;
        }

        try {
            int port = Integer.parseInt(value.trim());
            return port > 0 && port <= 65535 ? port : defaultPort;
        } catch (NumberFormatException e) {
            return defaultPort;
        }
    }

    private static int getConfiguredServicePort(int registryPort) {
        int defaultPort = registryPort >= 65535 ? registryPort : registryPort + 1;
        return getConfiguredPort("auth.service.port", "AUTH_SERVICE_PORT", defaultPort);
    }

    private static String getConfiguredHost() {
        String value = System.getProperty("auth.host");
        if (value == null || value.isBlank()) {
            value = System.getenv("AUTH_HOST");
        }
        return value == null || value.isBlank() ? DEFAULT_HOST : value.trim();
    }
}
