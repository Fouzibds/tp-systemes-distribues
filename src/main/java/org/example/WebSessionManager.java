package org.example;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class WebSessionManager {
    public static final String COOKIE_NAME = "WEBMAIL_SESSION";

    private final SecureRandom random = new SecureRandom();
    private final Map<String, String> sessions = new ConcurrentHashMap<>();

    public String createSession(String username) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String sessionId = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        sessions.put(sessionId, username);
        return sessionId;
    }

    public String getUsername(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        return sessions.get(sessionId);
    }

    public void removeSession(String sessionId) {
        if (sessionId != null) {
            sessions.remove(sessionId);
        }
    }
}
