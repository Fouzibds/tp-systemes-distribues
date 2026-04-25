package org.example;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.StringReader;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

public class Pop3Server {
    private static final String SERVER_NAME = SupervisionLogger.POP3;
    private static final int DEFAULT_PORT = 1110;
    private static final Object LIFECYCLE_LOCK = new Object();
    private static final AtomicBoolean STOP_REQUESTED = new AtomicBoolean(false);

    private static volatile ServerSocket serverSocket;
    private static volatile Thread serverThread;

    public static void main(String[] args) {
        int port = getConfiguredPort("pop3.port", "POP3_PORT", DEFAULT_PORT);
        runServer(port);
    }

    public static Thread startInBackground() {
        int port = getConfiguredPort("pop3.port", "POP3_PORT", DEFAULT_PORT);
        synchronized (LIFECYCLE_LOCK) {
            if (serverThread != null
                    && serverThread.isAlive()
                    && serverSocket != null
                    && !serverSocket.isClosed()
                    && !STOP_REQUESTED.get()) {
                return serverThread;
            }

            STOP_REQUESTED.set(false);
            serverThread = new Thread(() -> runServer(port), "POP3-Server");
            serverThread.setDaemon(true);
            serverThread.start();
            return serverThread;
        }
    }

    public static void stopServer() {
        ServerSocket socketToClose;
        synchronized (LIFECYCLE_LOCK) {
            socketToClose = serverSocket;
            if ((serverThread == null || !serverThread.isAlive())
                    && (socketToClose == null || socketToClose.isClosed())) {
                return;
            }
            STOP_REQUESTED.set(true);
        }

        SupervisionLogger.serverEvent(SERVER_NAME, "stop requested");
        if (socketToClose != null && !socketToClose.isClosed()) {
            try {
                socketToClose.close();
            } catch (IOException e) {
                SupervisionLogger.serverEvent(SERVER_NAME, "error while stopping server: " + e.getMessage());
            }
        }
    }

    private static void runServer(int port) {
        ServerSocket localServerSocket = null;
        boolean started = false;
        try {
            AuthClient.warmUp();
            localServerSocket = new ServerSocket(port);
            synchronized (LIFECYCLE_LOCK) {
                serverSocket = localServerSocket;
            }
            started = true;
            SupervisionLogger.setRunning(SERVER_NAME, true);
            SupervisionLogger.serverEvent(SERVER_NAME, "server started on port " + port);
            if (STOP_REQUESTED.get()) {
                localServerSocket.close();
                return;
            }
            while (!localServerSocket.isClosed()) {
                Socket clientSocket;
                try {
                    clientSocket = localServerSocket.accept();
                } catch (SocketException e) {
                    if (STOP_REQUESTED.get()) {
                        break;
                    }
                    throw e;
                }
                String clientIp = clientSocket.getInetAddress().getHostAddress();
                SupervisionLogger.connectionOpened(SERVER_NAME, clientIp);
                new Pop3Session(clientSocket).start();
            }
        } catch (IOException e) {
            if (!STOP_REQUESTED.get()) {
                SupervisionLogger.serverEvent(SERVER_NAME, "server stopped: " + e.getMessage());
            }
        } finally {
            boolean clearRunningState;
            synchronized (LIFECYCLE_LOCK) {
                clearRunningState = serverThread == null || serverThread == Thread.currentThread();
                if (serverSocket == localServerSocket) {
                    serverSocket = null;
                }
                if (serverThread == Thread.currentThread()) {
                    serverThread = null;
                }
            }
            if (started) {
                SupervisionLogger.serverEvent(SERVER_NAME, "server stopped");
            }
            if (localServerSocket != null && !localServerSocket.isClosed()) {
                try {
                    localServerSocket.close();
                } catch (IOException e) {
                    SupervisionLogger.serverEvent(SERVER_NAME, "error while releasing port: " + e.getMessage());
                }
            }
            STOP_REQUESTED.set(false);
            if (clearRunningState) {
                SupervisionLogger.setRunning(SERVER_NAME, false);
            }
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
            if (port < 1 || port > 65535) {
                throw new NumberFormatException("port out of range");
            }
            return port;
        } catch (NumberFormatException e) {
            System.err.println("Invalid port '" + value + "', using default " + defaultPort);
            return defaultPort;
        }
    }
}

class Pop3Session extends Thread {
    private static final String SERVER_NAME = SupervisionLogger.POP3;
    private static final int READ_TIMEOUT_MS = 5 * 60 * 1000;
    private static final Pattern USERNAME_PATTERN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");

    private final Socket socket;
    private final String clientIp;
    private final EmailRepository emailRepository = new EmailRepository();
    private BufferedReader in;
    private PrintWriter out;
    private String username;
    private List<EmailRepository.EmailMessage> emails = new ArrayList<>();
    private List<Boolean> deletionFlags = new ArrayList<>();
    private Pop3State state = Pop3State.AUTHORIZATION;

    private enum Pop3State {
        AUTHORIZATION,
        TRANSACTION,
        UPDATE
    }

    public Pop3Session(Socket socket) {
        this.socket = socket;
        this.clientIp = socket.getInetAddress().getHostAddress();
    }

    @Override
    public void run() {
        try {
            socket.setSoTimeout(READ_TIMEOUT_MS);
            in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);

            send("+OK POP3 server ready");

            String line;
            while ((line = in.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) {
                    send("-ERR Unknown command");
                    continue;
                }

                String[] parts = trimmed.split("\\s+", 2);
                String command = parts[0].toUpperCase(Locale.ROOT);
                String argument = parts.length > 1 ? parts[1] : "";

                logCommand(command, argument);

                switch (command) {
                    case "CAPA":
                        handleCapa();
                        break;
                    case "USER":
                        handleUser(argument);
                        break;
                    case "PASS":
                        handlePass(argument);
                        break;
                    case "STAT":
                        handleStat();
                        break;
                    case "LIST":
                        handleList(argument);
                        break;
                    case "UIDL":
                        handleUidl(argument);
                        break;
                    case "RETR":
                        handleRetr(argument);
                        break;
                    case "TOP":
                        handleTop(argument);
                        break;
                    case "DELE":
                        handleDele(argument);
                        break;
                    case "RSET":
                        handleRset();
                        break;
                    case "NOOP":
                        handleNoop();
                        break;
                    case "QUIT":
                        handleQuit();
                        return;
                    default:
                        send("-ERR Unknown command");
                        break;
                }
            }

            if (state == Pop3State.TRANSACTION) {
                SupervisionLogger.serverEvent(SERVER_NAME, "connection closed without QUIT; pending deletions were not applied");
            }
        } catch (SocketTimeoutException e) {
            if (out != null) {
                send("-ERR Timeout waiting for client input");
            }
            SupervisionLogger.serverEvent(SERVER_NAME, "connection timed out: " + clientIp);
        } catch (IOException e) {
            SupervisionLogger.serverEvent(SERVER_NAME, "session error for " + clientIp + ": " + e.getMessage());
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
            SupervisionLogger.connectionClosed(SERVER_NAME, clientIp);
        }
    }

    private void send(String response) {
        out.println(response);
        SupervisionLogger.response(SERVER_NAME, clientIp, response);
    }

    private void handleUser(String arg) {
        if (state != Pop3State.AUTHORIZATION) {
            send("-ERR USER not allowed after authentication");
            return;
        }

        clearAuthorizationState();
        String candidate = arg.trim();
        if (!isValidUsername(candidate)) {
            send("-ERR Invalid user name");
            return;
        }

        username = candidate;
        send("+OK User accepted");
    }

    private void handlePass(String arg) {
        if (state != Pop3State.AUTHORIZATION) {
            send("-ERR PASS not allowed after authentication");
            return;
        }
        if (username == null) {
            send("-ERR USER required first");
            return;
        }

        AuthClient.AuthResult authResult = AuthClient.authenticate(username, arg);
        if (!authResult.isAvailable()) {
            SupervisionLogger.serverEvent(SERVER_NAME, "authentication service unavailable during PASS for " + username);
            send("-ERR authentication service unavailable");
            return;
        }
        if (!authResult.getValue()) {
            SupervisionLogger.serverEvent(SERVER_NAME, "authentication failed for " + username);
            send("-ERR authentication failed");
            return;
        }

        loadMailbox();
        state = Pop3State.TRANSACTION;
        send("+OK Password accepted");
    }

    private void handleCapa() {
        send("+OK Capability list follows");
        send("USER");
        send("UIDL");
        send("TOP");
        send("RESP-CODES");
        send(".");
    }

    private void handleStat() {
        if (!requireTransaction()) {
            return;
        }

        int count = 0;
        long size = 0;
        for (int i = 0; i < emails.size(); i++) {
            if (!deletionFlags.get(i)) {
                count++;
                size += emails.get(i).size();
            }
        }
        send("+OK " + count + " " + size);
    }

    private void handleList(String arg) {
        if (!requireTransaction()) {
            return;
        }

        String trimmedArg = arg.trim();
        if (!trimmedArg.isEmpty()) {
            Integer index = parseMessageIndex(trimmedArg);
            if (index == null || !isVisibleMessage(index)) {
                send("-ERR No such message");
                return;
            }
            EmailRepository.EmailMessage email = emails.get(index);
            send("+OK " + (index + 1) + " " + email.size());
            return;
        }

        int count = 0;
        for (int i = 0; i < emails.size(); i++) {
            if (!deletionFlags.get(i)) {
                count++;
            }
        }

        send("+OK " + count + " messages");
        for (int i = 0; i < emails.size(); i++) {
            if (!deletionFlags.get(i)) {
                send((i + 1) + " " + emails.get(i).size());
            }
        }
        send(".");
    }

    private void handleRetr(String arg) {
        if (!requireTransaction()) {
            return;
        }

        Integer index = parseMessageIndex(arg.trim());
        if (index == null || index < 0 || index >= emails.size()) {
            send("-ERR No such message");
            return;
        }
        if (deletionFlags.get(index)) {
            send("-ERR Message marked for deletion");
            return;
        }

        EmailRepository.EmailMessage email = emails.get(index);
        send("+OK " + email.size() + " octets");
        try (BufferedReader reader = new BufferedReader(new StringReader(email.toRfcMessage()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith(".")) {
                    send("." + line);
                } else {
                    send(line);
                }
            }
            send(".");
        } catch (IOException e) {
            send("-ERR Could not read message");
            SupervisionLogger.serverEvent(SERVER_NAME, "could not read message: " + e.getMessage());
        }
    }

    private void handleUidl(String arg) {
        if (!requireTransaction()) {
            return;
        }

        String trimmedArg = arg.trim();
        if (!trimmedArg.isEmpty()) {
            Integer index = parseMessageIndex(trimmedArg);
            if (index == null || !isVisibleMessage(index)) {
                send("-ERR No such message");
                return;
            }
            send("+OK " + (index + 1) + " " + uidFor(emails.get(index)));
            return;
        }

        send("+OK Unique-ID listing follows");
        for (int i = 0; i < emails.size(); i++) {
            if (!deletionFlags.get(i)) {
                send((i + 1) + " " + uidFor(emails.get(i)));
            }
        }
        send(".");
    }

    private void handleTop(String arg) {
        if (!requireTransaction()) {
            return;
        }

        String[] parts = arg.trim().split("\\s+");
        if (parts.length != 2) {
            send("-ERR TOP syntax: TOP message lines");
            return;
        }

        Integer index = parseMessageIndex(parts[0]);
        Integer lineCount = parseNonNegativeInt(parts[1]);
        if (index == null || lineCount == null || !isVisibleMessage(index)) {
            send("-ERR No such message");
            return;
        }

        EmailRepository.EmailMessage email = emails.get(index);
        send("+OK Top of message follows");
        sendMultilineContent(topContent(email, lineCount));
        send(".");
    }

    private void handleDele(String arg) {
        if (!requireTransaction()) {
            return;
        }

        Integer index = parseMessageIndex(arg.trim());
        if (index == null || index < 0 || index >= emails.size()) {
            send("-ERR No such message");
            return;
        }
        if (deletionFlags.get(index)) {
            send("-ERR Message already marked for deletion");
            return;
        }

        deletionFlags.set(index, true);
        send("+OK Message marked for deletion");
    }

    private void handleRset() {
        if (!requireTransaction()) {
            return;
        }

        for (int i = 0; i < deletionFlags.size(); i++) {
            deletionFlags.set(i, false);
        }
        send("+OK Deletion marks reset");
    }

    private void handleNoop() {
        if (!requireTransaction()) {
            return;
        }
        send("+OK");
    }

    private void handleQuit() {
        if (state == Pop3State.TRANSACTION) {
            state = Pop3State.UPDATE;
            applyPendingDeletions();
        }
        send("+OK POP3 server signing off");
    }

    private boolean requireTransaction() {
        if (state != Pop3State.TRANSACTION) {
            send("-ERR Authentication required");
            return false;
        }
        return true;
    }

    private void loadMailbox() {
        try {
            emails = emailRepository.fetchEmails(username);
        } catch (Exception e) {
            SupervisionLogger.serverEvent(SERVER_NAME, "could not load mailbox from MySQL for " + username + ": " + e.getMessage());
            emails = new ArrayList<>();
        }

        deletionFlags = new ArrayList<>();
        for (int i = 0; i < emails.size(); i++) {
            deletionFlags.add(false);
        }
    }

    private void applyPendingDeletions() {
        for (int i = deletionFlags.size() - 1; i >= 0; i--) {
            if (!deletionFlags.get(i)) {
                continue;
            }

            EmailRepository.EmailMessage email = emails.get(i);
            try {
                if (emailRepository.deleteEmail(email.getId(), username)) {
                    SupervisionLogger.serverEvent(SERVER_NAME, "marked email " + email.getId() + " as deleted in MySQL");
                } else {
                    SupervisionLogger.serverEvent(SERVER_NAME, "email " + email.getId() + " was not deleted in MySQL");
                }
            } catch (Exception e) {
                SupervisionLogger.serverEvent(SERVER_NAME, "failed to mark email " + email.getId() + " as deleted in MySQL: " + e.getMessage());
            }
        }
    }

    private Integer parseMessageIndex(String arg) {
        try {
            if (arg == null || arg.isBlank()) {
                return null;
            }
            return Integer.parseInt(arg.trim()) - 1;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Integer parseNonNegativeInt(String arg) {
        try {
            int value = Integer.parseInt(arg.trim());
            return value >= 0 ? value : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private boolean isVisibleMessage(int index) {
        return index >= 0 && index < emails.size() && !deletionFlags.get(index);
    }

    private boolean isValidUsername(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return false;
        }
        if (candidate.contains("/") || candidate.contains("\\") || candidate.contains("..")) {
            return false;
        }
        if (candidate.endsWith(".")) {
            return false;
        }
        return USERNAME_PATTERN.matcher(candidate).matches();
    }

    private void clearAuthorizationState() {
        username = null;
        emails = new ArrayList<>();
        deletionFlags = new ArrayList<>();
    }

    private String uidFor(EmailRepository.EmailMessage email) {
        return "mail-" + email.getId();
    }

    private String topContent(EmailRepository.EmailMessage email, int bodyLines) {
        String[] body = email.getBody().replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        StringBuilder builder = new StringBuilder(email.toHeader()).append("\r\n");
        int count = Math.min(bodyLines, body.length);
        for (int i = 0; i < count; i++) {
            builder.append(body[i]).append("\r\n");
        }
        return builder.toString();
    }

    private void sendMultilineContent(String content) {
        try (BufferedReader reader = new BufferedReader(new StringReader(content))) {
            String line;
            while ((line = reader.readLine()) != null) {
                send(line.startsWith(".") ? "." + line : line);
            }
        } catch (IOException e) {
            SupervisionLogger.serverEvent(SERVER_NAME, "could not send multiline POP3 content: " + e.getMessage());
        }
    }

    private void logCommand(String command, String argument) {
        if ("PASS".equals(command)) {
            SupervisionLogger.command(SERVER_NAME, clientIp, "PASS <redacted>");
        } else if (argument == null || argument.isBlank()) {
            SupervisionLogger.command(SERVER_NAME, clientIp, command);
        } else {
            SupervisionLogger.command(SERVER_NAME, clientIp, command + " " + argument);
        }
    }
}
