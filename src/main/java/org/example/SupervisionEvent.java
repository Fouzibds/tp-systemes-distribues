package org.example;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class SupervisionEvent {
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final LocalDateTime timestamp;
    private final String serverName;
    private final String clientIp;
    private final String direction;
    private final String message;

    public SupervisionEvent(String serverName, String clientIp, String direction, String message) {
        this.timestamp = LocalDateTime.now();
        this.serverName = serverName;
        this.clientIp = clientIp == null || clientIp.isBlank() ? "-" : clientIp;
        this.direction = direction;
        this.message = message;
    }

    public String getServerName() {
        return serverName;
    }

    public String formatForDisplay() {
        return FORMATTER.format(timestamp)
                + " [" + clientIp + "] "
                + direction + " "
                + message;
    }
}
