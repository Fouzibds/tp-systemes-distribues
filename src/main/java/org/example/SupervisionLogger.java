package org.example;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public final class SupervisionLogger {
    public static final String SMTP = "SMTP";
    public static final String POP3 = "POP3";
    public static final String IMAP = "IMAP";

    private static final CopyOnWriteArrayList<SupervisionListener> LISTENERS = new CopyOnWriteArrayList<>();
    private static final Map<String, AtomicBoolean> RUNNING = new ConcurrentHashMap<>();
    private static final Map<String, AtomicInteger> ACTIVE_CONNECTIONS = new ConcurrentHashMap<>();

    private SupervisionLogger() {
    }

    public static void addListener(SupervisionListener listener) {
        LISTENERS.addIfAbsent(listener);
    }

    public static void removeListener(SupervisionListener listener) {
        LISTENERS.remove(listener);
    }

    public static void setRunning(String serverName, boolean running) {
        runningFlag(serverName).set(running);
        notifyState(serverName);
    }

    public static boolean isRunning(String serverName) {
        return runningFlag(serverName).get();
    }

    public static int getActiveConnections(String serverName) {
        return activeCounter(serverName).get();
    }

    public static void connectionOpened(String serverName, String clientIp) {
        activeCounter(serverName).incrementAndGet();
        log(serverName, clientIp, "event", "client connected");
        notifyState(serverName);
    }

    public static void connectionClosed(String serverName, String clientIp) {
        AtomicInteger counter = activeCounter(serverName);
        counter.updateAndGet(value -> Math.max(0, value - 1));
        log(serverName, clientIp, "event", "client disconnected");
        notifyState(serverName);
    }

    public static void command(String serverName, String clientIp, String command) {
        log(serverName, clientIp, "client ->", command);
    }

    public static void response(String serverName, String clientIp, String response) {
        log(serverName, clientIp, "server ->", response);
    }

    public static void serverEvent(String serverName, String message) {
        log(serverName, "-", "event", message);
    }

    private static void log(String serverName, String clientIp, String direction, String message) {
        SupervisionEvent event = new SupervisionEvent(serverName, clientIp, direction, message);
        System.out.println(serverName + " " + event.formatForDisplay());
        for (SupervisionListener listener : LISTENERS) {
            listener.onLogEvent(event);
        }
    }

    private static void notifyState(String serverName) {
        boolean running = isRunning(serverName);
        int activeConnections = getActiveConnections(serverName);
        for (SupervisionListener listener : LISTENERS) {
            listener.onServerStateChanged(serverName, running, activeConnections);
        }
    }

    private static AtomicBoolean runningFlag(String serverName) {
        return RUNNING.computeIfAbsent(serverName, ignored -> new AtomicBoolean(false));
    }

    private static AtomicInteger activeCounter(String serverName) {
        return ACTIVE_CONNECTIONS.computeIfAbsent(serverName, ignored -> new AtomicInteger(0));
    }
}
