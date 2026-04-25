package org.example;

import jakarta.mail.BodyPart;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Session;
import jakarta.mail.Store;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Properties;
import java.util.Scanner;

public class MailReaderClient {
    private static final String DEFAULT_HOST = "127.0.0.1";
    private static final int DEFAULT_IMAP_PORT = 1143;
    private static final int DEFAULT_POP3_PORT = 1110;

    public static void main(String[] args) {
        ReadRequest request = args.length >= 2 ? fromArgs(args) : fromConsole();
        if (request == null) {
            return;
        }

        if (!readWithProtocol(request, "imap")) {
            System.out.println("IMAP unavailable or failed. Trying POP3 fallback...");
            readWithProtocol(request, "pop3");
        }
    }

    private static boolean readWithProtocol(ReadRequest request, String protocol) {
        Properties properties = new Properties();
        properties.put("mail.store.protocol", protocol);
        properties.put("mail." + protocol + ".host", getHost());
        properties.put("mail." + protocol + ".port", String.valueOf("imap".equals(protocol) ? getImapPort() : getPop3Port()));
        properties.put("mail." + protocol + ".starttls.enable", "false");
        properties.put("mail." + protocol + ".ssl.enable", "false");

        Session session = Session.getInstance(properties);
        Store store = null;
        try {
            store = session.getStore(protocol);
            int port = "imap".equals(protocol) ? getImapPort() : getPop3Port();
            store.connect(getHost(), port, request.username, request.password);
            Folder inbox = store.getFolder("INBOX");
            inbox.open(Folder.READ_ONLY);
            try {
                Message[] messages = inbox.getMessages();
                if (messages.length == 0) {
                    System.out.println("No messages in INBOX.");
                    return true;
                }

                System.out.println("Messages from " + protocol.toUpperCase() + " INBOX: " + messages.length);
                for (int i = 0; i < messages.length; i++) {
                    printMessage(i + 1, messages[i], request, protocol, request.readAll || i == 0);
                }
                return true;
            } finally {
                inbox.close(false);
            }
        } catch (MessagingException | IOException e) {
            System.err.println(protocol.toUpperCase() + " read failed: " + e.getMessage());
            return false;
        } finally {
            if (store != null && store.isConnected()) {
                try {
                    store.close();
                } catch (MessagingException ignored) {
                }
            }
        }
    }

    private static void printMessage(int number, Message message, ReadRequest request, String protocol, boolean printBody)
            throws MessagingException, IOException {
        System.out.println();
        System.out.println("[" + number + "]");
        System.out.println("From: " + formatAddresses(message.getFrom()));
        System.out.println("Subject: " + safe(message.getSubject()));
        Date sentDate = message.getSentDate();
        System.out.println("Date: " + (sentDate == null ? "(unknown)" : sentDate));
        if (printBody) {
            Object content = message.getContent();
            System.out.println("message.getContent().getClass(): "
                    + (content == null ? "null" : content.getClass().getName()));
            System.out.println("message.getContentType(): " + message.getContentType());

            String body = extractText(message, content);
            if (body.isEmpty() && "imap".equalsIgnoreCase(protocol)) {
                System.out.println("Debug: Jakarta Mail returned an empty body. Trying manual IMAP BODY[TEXT] fallback...");
                body = manualImapFetchBody(request, number);
            }

            System.out.println("Body:");
            System.out.println(body.isEmpty() ? "(empty body)" : body);
        } else {
            System.out.println("Body: use --all to print every message body");
        }
    }

    private static String extractText(Message message, Object content) throws MessagingException, IOException {
        String text = extractText(content);
        if (!text.isEmpty()) {
            return text;
        }

        try (InputStream inputStream = message.getInputStream()) {
            String raw = readUtf8(inputStream);
            if (!raw.isEmpty()) {
                System.out.println("Debug raw content from message.getInputStream():");
                System.out.println(raw);
            }
            return raw;
        }
    }

    private static String extractText(Object content) throws MessagingException, IOException {
        if (content == null) {
            return "";
        }
        if (content instanceof String) {
            return (String) content;
        }
        if (content instanceof Multipart multipart) {
            StringBuilder builder = new StringBuilder();
            for (int i = 0; i < multipart.getCount(); i++) {
                BodyPart part = multipart.getBodyPart(i);
                if (part.isMimeType("text/plain")) {
                    builder.append(extractText(part.getContent()));
                } else if (part.isMimeType("multipart/*")) {
                    builder.append(extractText(part.getContent()));
                }
            }
            return builder.toString();
        }
        if (content instanceof InputStream inputStream) {
            return readUtf8(inputStream);
        }

        System.out.println("Debug unsupported content object: " + content);
        return "";
    }

    private static String manualImapFetchBody(ReadRequest request, int messageNumber) {
        try (Socket socket = new Socket(getHost(), getImapPort())) {
            socket.setSoTimeout(10_000);
            InputStream input = socket.getInputStream();
            OutputStream output = socket.getOutputStream();

            readImapLine(input);
            sendImapLine(output, "A001 LOGIN " + quoteImap(request.username) + " " + quoteImap(request.password));
            String login = readUntilTag(input, "A001", null);
            if (!login.startsWith("A001 OK")) {
                System.out.println("Manual IMAP fallback LOGIN failed: " + login);
                return "";
            }

            sendImapLine(output, "A002 SELECT INBOX");
            String select = readUntilTag(input, "A002", null);
            if (!select.startsWith("A002 OK")) {
                System.out.println("Manual IMAP fallback SELECT failed: " + select);
                return "";
            }

            StringBuilder body = new StringBuilder();
            sendImapLine(output, "A003 FETCH " + messageNumber + " BODY[TEXT]");
            String fetch = readUntilTag(input, "A003", body);
            if (!fetch.startsWith("A003 OK")) {
                System.out.println("Manual IMAP fallback FETCH failed: " + fetch);
                return "";
            }

            sendImapLine(output, "A004 LOGOUT");
            readUntilTag(input, "A004", null);
            return body.toString();
        } catch (SocketTimeoutException e) {
            System.out.println("Manual IMAP fallback timed out.");
            return "";
        } catch (IOException e) {
            System.out.println("Manual IMAP fallback failed: " + e.getMessage());
            return "";
        }
    }

    private static String readUntilTag(InputStream input, String tag, StringBuilder literalSink) throws IOException {
        String line;
        String lastLine = "";
        while ((line = readImapLine(input)) != null) {
            lastLine = line;
            int literalSize = parseLiteralSize(line);
            if (literalSize >= 0) {
                byte[] literal = readExact(input, literalSize);
                if (literalSink != null) {
                    literalSink.append(new String(literal, StandardCharsets.UTF_8));
                }
                continue;
            }
            if (line.startsWith(tag + " ")) {
                return line;
            }
        }
        return lastLine;
    }

    private static int parseLiteralSize(String line) {
        int open = line.lastIndexOf('{');
        int close = line.lastIndexOf('}');
        if (open < 0 || close <= open) {
            return -1;
        }
        try {
            return Integer.parseInt(line.substring(open + 1, close));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static byte[] readExact(InputStream input, int size) throws IOException {
        byte[] bytes = new byte[size];
        int offset = 0;
        while (offset < size) {
            int read = input.read(bytes, offset, size - offset);
            if (read < 0) {
                throw new IOException("connection closed while reading IMAP literal");
            }
            offset += read;
        }
        return bytes;
    }

    private static String readImapLine(InputStream input) throws IOException {
        StringBuilder builder = new StringBuilder();
        int current;
        boolean readAny = false;
        while ((current = input.read()) != -1) {
            readAny = true;
            if (current == '\n') {
                break;
            }
            if (current != '\r') {
                builder.append((char) current);
            }
        }
        return readAny ? builder.toString() : null;
    }

    private static void sendImapLine(OutputStream output, String line) throws IOException {
        output.write((line + "\r\n").getBytes(StandardCharsets.UTF_8));
        output.flush();
    }

    private static String quoteImap(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String readUtf8(InputStream inputStream) throws IOException {
        if (inputStream == null) {
            return "";
        }
        return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
    }

    private static ReadRequest fromArgs(String[] args) {
        boolean readAll = args.length >= 3 && "--all".equalsIgnoreCase(args[2]);
        return new ReadRequest(args[0], args[1], readAll);
    }

    private static ReadRequest fromConsole() {
        Scanner scanner = new Scanner(System.in);
        System.out.println("Local Jakarta Mail reader");
        System.out.print("Username: ");
        String username = scanner.nextLine().trim();
        System.out.print("Password: ");
        String password = scanner.nextLine();
        if (username.isBlank() || password.isBlank()) {
            System.err.println("Username and password are required.");
            return null;
        }
        return new ReadRequest(username, password, true);
    }

    private static String formatAddresses(jakarta.mail.Address[] addresses) {
        if (addresses == null || addresses.length == 0) {
            return "(unknown)";
        }
        StringBuilder builder = new StringBuilder();
        for (jakarta.mail.Address address : addresses) {
            if (builder.length() > 0) {
                builder.append(", ");
            }
            builder.append(address);
        }
        return builder.toString();
    }

    private static String safe(String value) {
        return value == null || value.isBlank() ? "(no subject)" : value;
    }

    private static String getHost() {
        String value = System.getProperty("mail.host");
        if (value == null || value.isBlank()) {
            value = System.getenv("MAIL_HOST");
        }
        return value == null || value.isBlank() ? DEFAULT_HOST : value.trim();
    }

    private static int getImapPort() {
        return getPort("imap.port", "IMAP_PORT", DEFAULT_IMAP_PORT);
    }

    private static int getPop3Port() {
        return getPort("pop3.port", "POP3_PORT", DEFAULT_POP3_PORT);
    }

    private static int getPort(String propertyName, String environmentName, int defaultPort) {
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

    private static final class ReadRequest {
        private final String username;
        private final String password;
        private final boolean readAll;

        private ReadRequest(String username, String password, boolean readAll) {
            this.username = username;
            this.password = password;
            this.readAll = readAll;
        }
    }
}
