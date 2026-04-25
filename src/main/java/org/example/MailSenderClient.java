package org.example;

import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;

import java.util.Properties;
import java.util.Scanner;

public class MailSenderClient {
    private static final String DEFAULT_SMTP_HOST = "127.0.0.1";
    private static final int DEFAULT_SMTP_PORT = 2525;

    public static void main(String[] args) {
        MailRequest request = args.length >= 6 ? fromArgs(args) : fromConsole();
        if (request == null) {
            return;
        }

        AuthClient.AuthResult authResult = AuthClient.authenticate(request.username, request.password);
        if (!authResult.isAvailable()) {
            System.err.println("Authentication service unavailable. Start AuthServer first.");
            return;
        }
        if (!authResult.getValue()) {
            System.err.println("Authentication failed for user: " + request.username);
            return;
        }
        if (!request.username.equals(mailboxUsername(request.from))) {
            System.err.println("The authenticated username must match the From mailbox.");
            System.err.println("Example: user1 must send from user1@localhost.");
            return;
        }

        try {
            sendEmail(request);
            System.out.println("Email sent successfully through local SMTP.");
        } catch (MessagingException e) {
            System.err.println("Could not send email: " + e.getMessage());
            System.err.println("Check that SmtpServer is running on localhost:" + getSmtpPort() + ".");
        }
    }

    private static void sendEmail(MailRequest request) throws MessagingException {
        Properties properties = new Properties();
        properties.put("mail.smtp.host", getSmtpHost());
        properties.put("mail.smtp.port", String.valueOf(getSmtpPort()));
        properties.put("mail.smtp.auth", "false");
        properties.put("mail.smtp.starttls.enable", "false");
        properties.put("mail.smtp.localhost", "localhost");

        Session session = Session.getInstance(properties);
        MimeMessage message = new MimeMessage(session);
        message.setFrom(new InternetAddress(normalizeAddress(request.from)));
        message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(normalizeAddress(request.to), false));
        message.setSubject(request.subject, "UTF-8");
        message.setText(request.body, "UTF-8");

        Transport.send(message);
    }

    private static MailRequest fromArgs(String[] args) {
        return new MailRequest(args[0], args[1], args[2], args[3], args[4], joinRemaining(args, 5));
    }

    private static MailRequest fromConsole() {
        Scanner scanner = new Scanner(System.in);
        System.out.println("Local Jakarta Mail SMTP sender");
        System.out.print("Username: ");
        String username = scanner.nextLine().trim();
        System.out.print("Password: ");
        String password = scanner.nextLine();
        System.out.print("From: ");
        String from = scanner.nextLine().trim();
        System.out.print("To: ");
        String to = scanner.nextLine().trim();
        System.out.print("Subject: ");
        String subject = scanner.nextLine();
        System.out.println("Body, finish with a single dot on its own line:");

        StringBuilder body = new StringBuilder();
        while (scanner.hasNextLine()) {
            String line = scanner.nextLine();
            if (".".equals(line)) {
                break;
            }
            body.append(line).append(System.lineSeparator());
        }

        if (username.isBlank() || password.isBlank() || from.isBlank() || to.isBlank() || subject.isBlank()) {
            System.err.println("Username, password, from, to, and subject are required.");
            return null;
        }
        return new MailRequest(username, password, from, to, subject, body.toString());
    }

    private static String normalizeAddress(String value) {
        String address = value == null ? "" : value.trim();
        return address.contains("@") ? address : address + "@localhost";
    }

    private static String mailboxUsername(String address) {
        String normalized = normalizeAddress(address);
        int at = normalized.indexOf('@');
        return at > 0 ? normalized.substring(0, at) : normalized;
    }

    private static String joinRemaining(String[] args, int start) {
        StringBuilder builder = new StringBuilder();
        for (int i = start; i < args.length; i++) {
            if (builder.length() > 0) {
                builder.append(' ');
            }
            builder.append(args[i]);
        }
        return builder.toString();
    }

    private static String getSmtpHost() {
        String value = System.getProperty("smtp.host");
        if (value == null || value.isBlank()) {
            value = System.getenv("SMTP_HOST");
        }
        return value == null || value.isBlank() ? DEFAULT_SMTP_HOST : value.trim();
    }

    private static int getSmtpPort() {
        String value = System.getProperty("smtp.port");
        if (value == null || value.isBlank()) {
            value = System.getenv("SMTP_PORT");
        }
        if (value == null || value.isBlank()) {
            return DEFAULT_SMTP_PORT;
        }

        try {
            int port = Integer.parseInt(value.trim());
            return port > 0 && port <= 65535 ? port : DEFAULT_SMTP_PORT;
        } catch (NumberFormatException e) {
            return DEFAULT_SMTP_PORT;
        }
    }

    private static final class MailRequest {
        private final String username;
        private final String password;
        private final String from;
        private final String to;
        private final String subject;
        private final String body;

        private MailRequest(String username, String password, String from, String to, String subject, String body) {
            this.username = username;
            this.password = password;
            this.from = from;
            this.to = to;
            this.subject = subject;
            this.body = body == null ? "" : body;
        }
    }
}
