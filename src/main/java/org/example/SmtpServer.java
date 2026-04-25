package org.example;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
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

public class SmtpServer {
    private static final String SERVER_NAME = SupervisionLogger.SMTP;
    private static final int DEFAULT_PORT = 2525;
    private static final Object LIFECYCLE_LOCK = new Object();
    private static final AtomicBoolean STOP_REQUESTED = new AtomicBoolean(false);

    private static volatile ServerSocket serverSocket;
    private static volatile Thread serverThread;

    public static void main(String[] args) {
        int port = getConfiguredPort("smtp.port", "SMTP_PORT", DEFAULT_PORT);
        runServer(port);
    }

    public static Thread startInBackground() {
        int port = getConfiguredPort("smtp.port", "SMTP_PORT", DEFAULT_PORT);
        synchronized (LIFECYCLE_LOCK) {
            if (serverThread != null
                    && serverThread.isAlive()
                    && serverSocket != null
                    && !serverSocket.isClosed()
                    && !STOP_REQUESTED.get()) {
                return serverThread;
            }

            STOP_REQUESTED.set(false);
            serverThread = new Thread(() -> runServer(port), "SMTP-Server");
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
                new SmtpSession(clientSocket).start();
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

class SmtpSession extends Thread {
    private static final String SERVER_NAME = SupervisionLogger.SMTP;
    private static final int READ_TIMEOUT_MS = 5 * 60 * 1000;
    private static final Pattern USERNAME_PATTERN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");
    private static final String DEFAULT_SUBJECT = "Test Email";

    private final Socket socket;
    private final String clientIp;
    private final EmailRepository emailRepository = new EmailRepository();
    private BufferedReader in;
    private PrintWriter out;

    private enum SmtpState {
        CONNECTED,
        HELO_RECEIVED,
        MAIL_FROM_SET,
        RCPT_TO_SET,
        DATA_RECEIVING
    }

    private SmtpState state;
    private String sender;
    private final List<String> recipients;
    private final StringBuilder dataBuffer;

    public SmtpSession(Socket socket) {
        this.socket = socket;
        this.clientIp = socket.getInetAddress().getHostAddress();
        this.state = SmtpState.CONNECTED;
        this.recipients = new ArrayList<>();
        this.dataBuffer = new StringBuilder();
    }

    @Override
    public void run() {
        try {
            socket.setSoTimeout(READ_TIMEOUT_MS);
            in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);

            send("220 smtp.example.com Service Ready");

            String line;
            while ((line = in.readLine()) != null) {
                SupervisionLogger.command(SERVER_NAME, clientIp, line);

                if (state == SmtpState.DATA_RECEIVING) {
                    handleDataLine(line);
                    continue;
                }

                String command = extractToken(line).toUpperCase(Locale.ROOT);
                String argument = extractArgument(line);

                switch (command) {
                    case "HELO":
                    case "EHLO":
                        handleHelo(argument);
                        break;
                    case "MAIL":
                        handleMailFrom(argument);
                        break;
                    case "RCPT":
                        handleRcptTo(argument);
                        break;
                    case "DATA":
                        handleData();
                        break;
                    case "RSET":
                        handleRset();
                        break;
                    case "NOOP":
                        send("250 OK");
                        break;
                    case "QUIT":
                        handleQuit();
                        return;
                    default:
                        send("500 Command unrecognized");
                        break;
                }
            }

            if (state == SmtpState.DATA_RECEIVING) {
                SupervisionLogger.serverEvent(SERVER_NAME, "connection interrupted during DATA; message was not stored");
            }
        } catch (SocketTimeoutException e) {
            if (out != null) {
                send("421 Timeout waiting for client input");
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

    private void handleHelo(String arg) {
        resetTransaction();
        state = SmtpState.HELO_RECEIVED;
        send("250 Hello " + arg);
    }

    private void handleMailFrom(String arg) {
        if (state != SmtpState.HELO_RECEIVED) {
            send("503 Bad sequence of commands");
            return;
        }

        if (!arg.toUpperCase(Locale.ROOT).matches("^FROM:\\s*<[^>]+>$")) {
            send("501 Syntax error in parameters or arguments");
            return;
        }

        String potentialEmail = arg.substring(5).trim();
        potentialEmail = potentialEmail.substring(1, potentialEmail.length() - 1).trim();

        String email = extractEmail(potentialEmail);
        if (email == null) {
            send("501 Syntax error in parameters or arguments");
            return;
        }

        String username = extractMailboxUsername(email);
        if (username == null) {
            send("550 Invalid sender mailbox");
            return;
        }

        AuthClient.AuthResult authResult = AuthClient.userExists(username);
        if (!authResult.isAvailable()) {
            SupervisionLogger.serverEvent(SERVER_NAME, "authentication service unavailable during MAIL FROM");
            send("451 Requested action aborted: authentication service unavailable");
            return;
        }
        if (!authResult.getValue()) {
            send("550 Sender rejected: unknown user");
            return;
        }

        sender = email;
        recipients.clear();
        dataBuffer.setLength(0);
        state = SmtpState.MAIL_FROM_SET;
        send("250 OK");
    }

    private void handleRcptTo(String arg) {
        if (state != SmtpState.MAIL_FROM_SET && state != SmtpState.RCPT_TO_SET) {
            send("503 Bad sequence of commands");
            return;
        }

        if (!arg.toUpperCase(Locale.ROOT).startsWith("TO:")) {
            send("501 Syntax error in parameters or arguments");
            return;
        }

        String potentialEmail = arg.substring(3).trim();
        String email = extractEmail(potentialEmail);
        if (email == null) {
            send("501 Syntax error in parameters or arguments");
            return;
        }

        String username = extractMailboxUsername(email);
        if (username == null) {
            send("550 Invalid recipient mailbox");
            return;
        }

        AuthClient.AuthResult authResult = AuthClient.userExists(username);
        if (!authResult.isAvailable()) {
            SupervisionLogger.serverEvent(SERVER_NAME, "authentication service unavailable during RCPT TO");
            send("451 Requested action aborted: authentication service unavailable");
            return;
        }
        if (!authResult.getValue()) {
            send("550 No such user");
            return;
        }

        recipients.add(email);
        state = SmtpState.RCPT_TO_SET;
        send("250 OK");
    }

    private void handleData() {
        if (state != SmtpState.RCPT_TO_SET || recipients.isEmpty()) {
            send("503 Bad sequence of commands");
            return;
        }

        state = SmtpState.DATA_RECEIVING;
        send("354 Start mail input; end with <CRLF>.<CRLF>");
    }

    private void handleDataLine(String line) {
        if (line.equals(".")) {
            boolean stored = storeEmail(dataBuffer.toString());
            resetTransaction();
            state = SmtpState.HELO_RECEIVED;

            if (stored) {
                send("250 OK: Message accepted for delivery");
            } else {
                send("451 Requested action aborted: local error in processing");
            }
            return;
        }

        if (line.startsWith("..")) {
            line = line.substring(1);
        }
        dataBuffer.append(line).append("\r\n");
    }

    private void handleRset() {
        resetTransaction();
        if (state != SmtpState.CONNECTED) {
            state = SmtpState.HELO_RECEIVED;
        }
        send("250 OK");
    }

    private void handleQuit() {
        send("221 smtp.example.com Service closing transmission channel");
    }

    private String extractToken(String line) {
        String trimmed = line.trim();
        int index = trimmed.indexOf(' ');
        return index >= 0 ? trimmed.substring(0, index) : trimmed;
    }

    private String extractArgument(String line) {
        String trimmed = line.trim();
        int index = trimmed.indexOf(' ');
        return index >= 0 ? trimmed.substring(index + 1).trim() : "";
    }

    private String extractEmail(String input) {
        String email = input.trim();
        if (email.startsWith("<") && email.endsWith(">")) {
            email = email.substring(1, email.length() - 1).trim();
        }

        int at = email.indexOf('@');
        if (at <= 0 || at != email.lastIndexOf('@') || at >= email.length() - 1) {
            return null;
        }
        if (email.contains("/") || email.contains("\\") || email.contains("..")) {
            return null;
        }
        return email;
    }

    private String extractMailboxUsername(String email) {
        int at = email.indexOf('@');
        if (at <= 0) {
            return null;
        }

        String username = email.substring(0, at);
        return isValidUsername(username) ? username : null;
    }

    private boolean isValidUsername(String username) {
        if (username == null || username.isBlank()) {
            return false;
        }
        if (username.contains("/") || username.contains("\\") || username.contains("..")) {
            return false;
        }
        if (username.endsWith(".")) {
            return false;
        }
        return USERNAME_PATTERN.matcher(username).matches();
    }

    private void resetTransaction() {
        sender = null;
        recipients.clear();
        dataBuffer.setLength(0);
    }

    private boolean storeEmail(String data) {
        boolean allStored = true;
        String subject = extractSubject(data);
        String body = extractBody(data);
        for (String recipient : recipients) {
            String username = extractMailboxUsername(recipient);
            if (username == null) {
                SupervisionLogger.serverEvent(SERVER_NAME, "invalid recipient mailbox while storing: " + recipient);
                allStored = false;
                continue;
            }

            try {
                int emailId = emailRepository.storeEmail(sender, username, subject, body);
                if (emailId > 0) {
                    SupervisionLogger.serverEvent(SERVER_NAME, "stored email for " + recipient + " in MySQL with id " + emailId);
                } else {
                    SupervisionLogger.serverEvent(SERVER_NAME, "database did not return an id while storing email for " + recipient);
                    allStored = false;
                }
            } catch (Exception e) {
                SupervisionLogger.serverEvent(SERVER_NAME, "error storing email for " + recipient + " in MySQL: " + e.getMessage());
                allStored = false;
            }
        }
        return allStored;
    }

    private String extractSubject(String data) {
        String[] lines = data.split("\\r?\\n");
        for (String line : lines) {
            if (line.isBlank()) {
                break;
            }
            if (line.toLowerCase(Locale.ROOT).startsWith("subject:")) {
                String subject = line.substring("subject:".length()).trim();
                return subject.isEmpty() ? DEFAULT_SUBJECT : subject;
            }
        }
        return DEFAULT_SUBJECT;
    }

    private String extractBody(String data) {
        String normalized = data.replace("\r\n", "\n").replace('\r', '\n');
        int separator = normalized.indexOf("\n\n");
        if (separator >= 0) {
            return normalized.substring(separator + 2).replace("\n", "\r\n");
        }
        return data;
    }
}
