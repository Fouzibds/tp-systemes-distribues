package org.example;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;

public class WebMailServer {
    private static final int DEFAULT_PORT = 8080;

    private final WebSessionManager sessions = new WebSessionManager();
    private final EmailRepository emailRepository = new EmailRepository();
    private final SmtpWebClient smtpWebClient = new SmtpWebClient();
    private int port = DEFAULT_PORT;

    public static void main(String[] args) throws IOException {
        int port = getConfiguredPort();
        new WebMailServer().start(port);
    }

    public void start(int port) throws IOException {
        this.port = port;
        WebTemplates.setInstancePort(port);
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/", this::handle);
        server.setExecutor(Executors.newFixedThreadPool(10));
        server.start();
        System.out.println("WebMailServer started on port " + port);
        System.out.println("Web mail login: http://localhost:" + port + "/login");
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            String method = exchange.getRequestMethod().toUpperCase(Locale.ROOT);
            String path = exchange.getRequestURI().getPath();

            if ("GET".equals(method) && "/".equals(path)) {
                redirect(exchange, "/inbox");
            } else if ("GET".equals(method) && "/health".equals(path)) {
                health(exchange);
            } else if ("GET".equals(method) && "/login".equals(path)) {
                showLogin(exchange, null);
            } else if ("POST".equals(method) && "/login".equals(path)) {
                handleLogin(exchange);
            } else if ("GET".equals(method) && "/logout".equals(path)) {
                handleLogout(exchange);
            } else if ("GET".equals(method) && "/inbox".equals(path)) {
                Map<String, String> params = queryParams(exchange);
                handleFolder(exchange, "inbox", null, params.get("notice"), null);
            } else if ("GET".equals(method) && "/sent".equals(path)) {
                handleFolder(exchange, "sent", null, queryParams(exchange).get("notice"), null);
            } else if ("GET".equals(method) && "/trash".equals(path)) {
                handleFolder(exchange, "trash", null, queryParams(exchange).get("notice"), null);
            } else if ("GET".equals(method) && "/folder".equals(path)) {
                Map<String, String> params = queryParams(exchange);
                handleFolder(exchange, params.getOrDefault("name", "inbox"), null, params.get("notice"), null);
            } else if ("GET".equals(method) && "/message".equals(path)) {
                handleMessage(exchange);
            } else if ("GET".equals(method) && "/compose".equals(path)) {
                handleCompose(exchange, "", "", "", null);
            } else if ("POST".equals(method) && "/send".equals(path)) {
                handleSend(exchange);
            } else if ("POST".equals(method) && "/delete".equals(path)) {
                handleDelete(exchange);
            } else if ("POST".equals(method) && "/bulk".equals(path)) {
                handleBulk(exchange);
            } else if ("GET".equals(method) && "/search".equals(path)) {
                handleSearch(exchange);
            } else {
                html(exchange, 404, WebTemplates.redirectPage("/inbox"));
            }
        } catch (Exception e) {
            html(exchange, 500, WebTemplates.loginPage("Server error: " + e.getMessage()));
        } finally {
            exchange.close();
        }
    }

    private void health(HttpExchange exchange) throws IOException {
        text(exchange, 200, "OK - WebMail instance on port " + port);
    }

    private void showLogin(HttpExchange exchange, String error) throws IOException {
        html(exchange, 200, WebTemplates.loginPage(error));
    }

    private void handleLogin(HttpExchange exchange) throws IOException {
        Map<String, String> form = readForm(exchange);
        String username = form.getOrDefault("username", "").trim();
        String password = form.getOrDefault("password", "");

        AuthClient.AuthResult authResult = AuthClient.authenticate(username, password);
        if (!authResult.isAvailable()) {
            showLogin(exchange, "Authentication service unavailable. Start AuthServer first.");
            return;
        }
        if (!authResult.getValue()) {
            showLogin(exchange, "Invalid username or password.");
            return;
        }

        String sessionId = sessions.createSession(username);
        exchange.getResponseHeaders().add("Set-Cookie", WebSessionManager.COOKIE_NAME + "=" + sessionId + "; Path=/; HttpOnly; SameSite=Lax");
        redirect(exchange, "/inbox");
    }

    private void handleLogout(HttpExchange exchange) throws IOException {
        String sessionId = readSessionId(exchange);
        sessions.removeSession(sessionId);
        exchange.getResponseHeaders().add("Set-Cookie", WebSessionManager.COOKIE_NAME + "=; Path=/; Max-Age=0; HttpOnly; SameSite=Lax");
        redirect(exchange, "/login");
    }

    private void handleFolder(HttpExchange exchange, String folder, String query, String notice, String error) throws IOException {
        String username = requireUser(exchange);
        if (username == null) {
            return;
        }

        try {
            List<EmailRepository.EmailMessage> emails = emailRepository.fetchFolder(username, folder);
            int unreadCount = emailRepository.countUnread(username);
            html(exchange, 200, WebTemplates.mailboxPage(username, folder, emails, query, notice, error, unreadCount));
        } catch (Exception e) {
            html(exchange, 200, WebTemplates.mailboxPage(username, folder, List.of(), query, null, "Database unavailable: " + e.getMessage(), 0));
        }
    }

    private void handleMessage(HttpExchange exchange) throws IOException {
        String username = requireUser(exchange);
        if (username == null) {
            return;
        }

        Integer id = parseInt(queryParams(exchange).get("id"));
        if (id == null) {
            html(exchange, 200, WebTemplates.messagePage(username, null, "Invalid message id.", 0));
            return;
        }

        try {
            EmailRepository.EmailMessage message = emailRepository.fetchMessageForOwner(id, username);
            if (message != null && username.equals(message.getRecipient()) && !message.isDeleted()) {
                emailRepository.markEmailSeen(id, username, true);
                message = emailRepository.fetchMessageForOwner(id, username);
            }
            int unreadCount = emailRepository.countUnread(username);
            html(exchange, 200, WebTemplates.messagePage(username, message, null, unreadCount));
        } catch (Exception e) {
            html(exchange, 200, WebTemplates.messagePage(username, null, "Database unavailable: " + e.getMessage(), 0));
        }
    }

    private void handleCompose(HttpExchange exchange, String to, String subject, String body, String error) throws IOException {
        String username = requireUser(exchange);
        if (username == null) {
            return;
        }
        int unreadCount = 0;
        try {
            unreadCount = emailRepository.countUnread(username);
        } catch (Exception ignored) {
        }
        html(exchange, 200, WebTemplates.composePage(username, to, subject, body, error, unreadCount));
    }

    private void handleSend(HttpExchange exchange) throws IOException {
        String username = requireUser(exchange);
        if (username == null) {
            return;
        }

        Map<String, String> form = readForm(exchange);
        String to = form.getOrDefault("to", "").trim();
        String subject = form.getOrDefault("subject", "").trim();
        String body = form.getOrDefault("body", "").trim();
        if (to.isBlank() || subject.isBlank() || body.isBlank()) {
            handleCompose(exchange, to, subject, body, "To, subject, and body are required.");
            return;
        }

        String recipientUsername = SmtpWebClient.recipientUsername(to);
        AuthClient.AuthResult userExists = AuthClient.userExists(recipientUsername);
        if (!userExists.isAvailable()) {
            handleCompose(exchange, to, subject, body, "Authentication service unavailable. Start AuthServer first.");
            return;
        }
        if (!userExists.getValue()) {
            handleCompose(exchange, to, subject, body, "Recipient does not exist.");
            return;
        }

        try {
            smtpWebClient.send(username, to, subject, body);
            redirect(exchange, "/sent?notice=Message%20sent");
        } catch (Exception e) {
            handleCompose(exchange, to, subject, body, "Could not send message through SMTP: " + e.getMessage());
        }
    }

    private void handleDelete(HttpExchange exchange) throws IOException {
        String username = requireUser(exchange);
        if (username == null) {
            return;
        }

        Integer id = parseInt(readForm(exchange).get("id"));
        if (id == null) {
            handleFolder(exchange, "inbox", null, null, "Invalid message id.");
            return;
        }

        try {
            emailRepository.deleteForOwner(id, username);
            redirect(exchange, "/inbox?notice=Message%20deleted");
        } catch (Exception e) {
            handleFolder(exchange, "inbox", null, null, "Could not delete message: " + e.getMessage());
        }
    }

    private void handleBulk(HttpExchange exchange) throws IOException {
        String username = requireUser(exchange);
        if (username == null) {
            return;
        }

        Map<String, String> form = readForm(exchange);
        String action = form.getOrDefault("action", "");
        String folder = form.getOrDefault("folder", "inbox");
        List<Integer> ids = parseIds(form.getOrDefault("ids", ""));
        if (ids.isEmpty()) {
            redirect(exchange, folderPath(folder) + "?notice=No%20messages%20selected");
            return;
        }

        int changed = 0;
        try {
            for (Integer id : ids) {
                switch (action) {
                    case "delete":
                        changed += emailRepository.deleteForOwner(id, username) ? 1 : 0;
                        break;
                    case "restore":
                        changed += emailRepository.restoreEmail(id, username) ? 1 : 0;
                        break;
                    case "permanent-delete":
                        changed += emailRepository.permanentlyDeleteEmail(id, username) ? 1 : 0;
                        break;
                    case "read":
                        changed += emailRepository.markEmailSeen(id, username, true) ? 1 : 0;
                        break;
                    case "unread":
                        changed += emailRepository.markEmailSeen(id, username, false) ? 1 : 0;
                        break;
                    case "star":
                        changed += emailRepository.setStarred(id, username, true) ? 1 : 0;
                        break;
                    case "unstar":
                        changed += emailRepository.setStarred(id, username, false) ? 1 : 0;
                        break;
                    case "important":
                        changed += emailRepository.setImportant(id, username, true) ? 1 : 0;
                        break;
                    case "unimportant":
                        changed += emailRepository.setImportant(id, username, false) ? 1 : 0;
                        break;
                    case "spam":
                        changed += emailRepository.setSpam(id, username, true) ? 1 : 0;
                        break;
                    case "not-spam":
                        changed += emailRepository.setSpam(id, username, false) ? 1 : 0;
                        break;
                    default:
                        break;
                }
            }
            redirect(exchange, folderPath(folder) + "?notice=" + changed + "%20message(s)%20updated");
        } catch (Exception e) {
            handleFolder(exchange, folder, null, null, "Action failed: " + e.getMessage());
        }
    }

    private void handleSearch(HttpExchange exchange) throws IOException {
        String username = requireUser(exchange);
        if (username == null) {
            return;
        }

        String query = queryParams(exchange).getOrDefault("q", "").trim();
        if (query.isBlank()) {
            redirect(exchange, "/inbox");
            return;
        }

        try {
            String field = queryParams(exchange).getOrDefault("field", "ALL");
            boolean unread = "on".equals(queryParams(exchange).get("unread"));
            boolean starred = "on".equals(queryParams(exchange).get("starred"));
            boolean important = "on".equals(queryParams(exchange).get("important"));
            List<EmailRepository.EmailMessage> matches = emailRepository.searchEmails(username, field, query, unread, starred, important);
            int unreadCount = emailRepository.countUnread(username);
            html(exchange, 200, WebTemplates.mailboxPage(username, "search", matches, query, null, null, unreadCount));
        } catch (Exception e) {
            html(exchange, 200, WebTemplates.mailboxPage(username, "search", List.of(), query, null, "Search failed: " + e.getMessage(), 0));
        }
    }

    private String requireUser(HttpExchange exchange) throws IOException {
        String username = sessions.getUsername(readSessionId(exchange));
        if (username == null) {
            redirect(exchange, "/login");
        }
        return username;
    }

    private Map<String, String> readForm(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        return parseUrlEncoded(body);
    }

    private Map<String, String> queryParams(HttpExchange exchange) {
        String query = exchange.getRequestURI().getRawQuery();
        return parseUrlEncoded(query == null ? "" : query);
    }

    private Map<String, String> parseUrlEncoded(String value) {
        Map<String, String> params = new LinkedHashMap<>();
        if (value == null || value.isBlank()) {
            return params;
        }

        String[] pairs = value.split("&");
        for (String pair : pairs) {
            int equals = pair.indexOf('=');
            String key = equals >= 0 ? pair.substring(0, equals) : pair;
            String val = equals >= 0 ? pair.substring(equals + 1) : "";
            params.put(urlDecode(key), urlDecode(val));
        }
        return params;
    }

    private String urlDecode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private String readSessionId(HttpExchange exchange) {
        List<String> cookies = exchange.getRequestHeaders().get("Cookie");
        if (cookies == null) {
            return null;
        }

        for (String cookieHeader : cookies) {
            String[] cookieParts = cookieHeader.split(";");
            for (String cookie : cookieParts) {
                String trimmed = cookie.trim();
                int equals = trimmed.indexOf('=');
                if (equals > 0 && WebSessionManager.COOKIE_NAME.equals(trimmed.substring(0, equals))) {
                    return trimmed.substring(equals + 1);
                }
            }
        }
        return null;
    }

    private void redirect(HttpExchange exchange, String location) throws IOException {
        exchange.getResponseHeaders().add("Location", location);
        exchange.sendResponseHeaders(302, -1);
    }

    private void html(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", "text/html; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream outputStream = exchange.getResponseBody()) {
            outputStream.write(bytes);
        }
    }

    private void text(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", "text/plain; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream outputStream = exchange.getResponseBody()) {
            outputStream.write(bytes);
        }
    }

    private Integer parseInt(String value) {
        try {
            if (value == null || value.isBlank()) {
                return null;
            }
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private List<Integer> parseIds(String value) {
        List<Integer> ids = new ArrayList<>();
        if (value == null || value.isBlank()) {
            return ids;
        }
        for (String part : value.split(",")) {
            Integer id = parseInt(part);
            if (id != null) {
                ids.add(id);
            }
        }
        return ids;
    }

    private String folderPath(String folder) {
        String normalized = folder == null ? "inbox" : folder.toLowerCase(Locale.ROOT);
        switch (normalized) {
            case "sent":
                return "/sent";
            case "trash":
                return "/trash";
            case "inbox":
                return "/inbox";
            default:
                return "/folder?name=" + normalized;
        }
    }

    private static int getConfiguredPort() {
        String value = System.getProperty("web.port");
        if (value == null || value.isBlank()) {
            value = System.getenv("WEB_PORT");
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
}
