package org.example;

public interface SupervisionListener {
    void onLogEvent(SupervisionEvent event);

    void onServerStateChanged(String serverName, boolean running, int activeConnections);
}
