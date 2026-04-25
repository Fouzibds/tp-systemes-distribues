package org.example;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

public class SmtpWebClient {
    private static final String DEFAULT_HOST = "127.0.0.1";
    private static final int DEFAULT_PORT = 2525;

    public boolean send(String fromUsername, String to, String subject, String body) throws IOException {
        String sender = fromUsername + "@localhost";
        String recipient = normalizeRecipient(to);

        try (Socket socket = new Socket(getHost(), getPort());
             BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
             PrintWriter writer = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true)) {

            expectPositive(reader.readLine());
            command(writer, reader, "HELO webmail.local");
            command(writer, reader, "MAIL FROM:<" + sender + ">");
            command(writer, reader, "RCPT TO:<" + recipient + ">");

            writer.println("DATA");
            expectCode(reader.readLine(), "354");
            writer.println("Subject: " + cleanHeader(subject));
            writer.println();
            for (String line : body.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1)) {
                writer.println(line.startsWith(".") ? "." + line : line);
            }
            writer.println(".");
            expectPositive(reader.readLine());
            command(writer, reader, "QUIT");
            return true;
        }
    }

    public static String normalizeRecipient(String value) {
        String recipient = value == null ? "" : value.trim();
        if (!recipient.contains("@")) {
            recipient = recipient + "@localhost";
        }
        return recipient;
    }

    public static String recipientUsername(String value) {
        String recipient = normalizeRecipient(value);
        int at = recipient.indexOf('@');
        return at > 0 ? recipient.substring(0, at) : recipient;
    }

    private void command(PrintWriter writer, BufferedReader reader, String command) throws IOException {
        writer.println(command);
        expectPositive(reader.readLine());
    }

    private void expectPositive(String response) throws IOException {
        if (response == null || !(response.startsWith("2") || response.startsWith("3"))) {
            throw new IOException("SMTP error: " + response);
        }
    }

    private void expectCode(String response, String code) throws IOException {
        if (response == null || !response.startsWith(code)) {
            throw new IOException("SMTP error: " + response);
        }
    }

    private String cleanHeader(String value) {
        String cleaned = value == null ? "" : value.replace('\r', ' ').replace('\n', ' ').trim();
        return cleaned.isEmpty() ? "(no subject)" : cleaned;
    }

    private String getHost() {
        String value = System.getProperty("smtp.host");
        if (value == null || value.isBlank()) {
            value = System.getenv("SMTP_HOST");
        }
        return value == null || value.isBlank() ? DEFAULT_HOST : value.trim();
    }

    private int getPort() {
        String value = System.getProperty("smtp.port");
        if (value == null || value.isBlank()) {
            value = System.getenv("SMTP_PORT");
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
