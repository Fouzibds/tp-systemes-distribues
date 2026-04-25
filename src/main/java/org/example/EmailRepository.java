package org.example;

import java.nio.charset.StandardCharsets;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class EmailRepository {
    public int storeEmail(String sender, String recipient, String subject, String body) throws SQLException {
        try (Connection connection = DatabaseConnection.getConnection();
             CallableStatement statement = connection.prepareCall("{CALL store_email(?, ?, ?, ?)}")) {
            statement.setString(1, sender);
            statement.setString(2, recipient);
            statement.setString(3, subject);
            statement.setString(4, body);

            boolean hasResultSet = statement.execute();
            if (!hasResultSet) {
                return -1;
            }

            try (ResultSet resultSet = statement.getResultSet()) {
                return resultSet.next() ? resultSet.getInt(1) : -1;
            }
        }
    }

    public List<EmailMessage> fetchEmails(String username) throws SQLException {
        return fetchFolder(username, "inbox");
    }

    public List<EmailMessage> fetchFolder(String username, String folder) throws SQLException {
        String normalizedFolder = normalizeFolder(folder);
        String sender = username + "@localhost";
        String sql;

        switch (normalizedFolder) {
            case "sent":
                sql = baseSelect() + " WHERE sender = ? AND sender_deleted = FALSE ORDER BY created_at DESC, id DESC";
                return queryMany(sql, sender);
            case "trash":
                sql = baseSelect() + " WHERE recipient = ? AND deleted = TRUE AND permanently_deleted = FALSE ORDER BY created_at DESC, id DESC";
                return queryMany(sql, username);
            case "starred":
                sql = ownerVisibleWhere("is_starred = TRUE");
                return queryMany(sql, username, sender);
            case "important":
                sql = ownerVisibleWhere("is_important = TRUE");
                return queryMany(sql, username, sender);
            case "spam":
                sql = baseSelect() + " WHERE recipient = ? AND deleted = FALSE AND permanently_deleted = FALSE AND is_spam = TRUE ORDER BY created_at DESC, id DESC";
                return queryMany(sql, username);
            case "promotions":
            case "social":
            case "updates":
                sql = baseSelect() + " WHERE recipient = ? AND deleted = FALSE AND permanently_deleted = FALSE AND is_spam = FALSE AND category = ? ORDER BY created_at DESC, id DESC";
                return queryMany(sql, username, titleCase(normalizedFolder));
            default:
                sql = baseSelect() + " WHERE recipient = ? AND deleted = FALSE AND permanently_deleted = FALSE AND is_spam = FALSE ORDER BY created_at DESC, id DESC";
                return queryMany(sql, username);
        }
    }

    public EmailMessage fetchEmailById(int emailId, String username) throws SQLException {
        return fetchMessageForOwner(emailId, username);
    }

    public EmailMessage fetchMessageForOwner(int emailId, String username) throws SQLException {
        String sql = baseSelect() + " WHERE id = ? AND ((recipient = ? AND permanently_deleted = FALSE) OR sender = ?)";
        List<EmailMessage> messages = queryMany(sql, emailId, username, username + "@localhost");
        return messages.isEmpty() ? null : messages.get(0);
    }

    public boolean markEmailSeen(int emailId, String username, boolean seen) throws SQLException {
        String sql = "UPDATE emails SET is_seen = ? WHERE id = ? AND recipient = ? AND permanently_deleted = FALSE";
        return update(sql, seen, emailId, username);
    }

    public boolean deleteEmail(int emailId, String username) throws SQLException {
        String sql = "UPDATE emails SET deleted = TRUE WHERE id = ? AND recipient = ? AND deleted = FALSE AND permanently_deleted = FALSE";
        return update(sql, emailId, username);
    }

    public boolean deleteForOwner(int emailId, String username) throws SQLException {
        EmailMessage message = fetchMessageForOwner(emailId, username);
        if (message == null) {
            return false;
        }
        if (username.equals(message.getRecipient())) {
            return deleteEmail(emailId, username);
        }
        if ((username + "@localhost").equals(message.getSender())) {
            return update("UPDATE emails SET sender_deleted = TRUE WHERE id = ? AND sender = ? AND permanently_deleted = FALSE",
                    emailId, username + "@localhost");
        }
        return false;
    }

    public boolean restoreEmail(int emailId, String username) throws SQLException {
        try (Connection connection = DatabaseConnection.getConnection();
             CallableStatement statement = connection.prepareCall("{CALL restore_email(?, ?)}")) {
            statement.setInt(1, emailId);
            statement.setString(2, username);
            return readBooleanResult(statement);
        }
    }

    public boolean permanentlyDeleteEmail(int emailId, String username) throws SQLException {
        try (Connection connection = DatabaseConnection.getConnection();
             CallableStatement statement = connection.prepareCall("{CALL permanently_delete_email(?, ?)}")) {
            statement.setInt(1, emailId);
            statement.setString(2, username);
            return readBooleanResult(statement);
        }
    }

    public boolean setStarred(int emailId, String username, boolean starred) throws SQLException {
        return updateForOwner(emailId, username, "is_starred", starred);
    }

    public boolean setImportant(int emailId, String username, boolean important) throws SQLException {
        return updateForOwner(emailId, username, "is_important", important);
    }

    public boolean setSpam(int emailId, String username, boolean spam) throws SQLException {
        return update("UPDATE emails SET is_spam = ? WHERE id = ? AND recipient = ? AND permanently_deleted = FALSE",
                spam, emailId, username);
    }

    public List<EmailMessage> searchEmails(String username, String field, String keyword) throws SQLException {
        String sender = username + "@localhost";
        String normalizedField = field == null ? "ALL" : field.trim().toUpperCase(Locale.ROOT);
        String pattern = "%" + keyword + "%";
        String condition;
        switch (normalizedField) {
            case "FROM":
                condition = "sender LIKE ?";
                return queryMany(searchSql(condition), username, sender, pattern);
            case "TO":
                condition = "recipient LIKE ?";
                return queryMany(searchSql(condition), username, sender, pattern);
            case "SUBJECT":
                condition = "subject LIKE ?";
                return queryMany(searchSql(condition), username, sender, pattern);
            case "BODY":
                condition = "body LIKE ?";
                return queryMany(searchSql(condition), username, sender, pattern);
            default:
                condition = "(sender LIKE ? OR recipient LIKE ? OR subject LIKE ? OR body LIKE ?)";
                return queryMany(searchSql(condition), username, sender, pattern, pattern, pattern, pattern);
        }
    }

    public List<EmailMessage> searchEmails(String username, String field, String keyword,
                                           boolean unreadOnly, boolean starredOnly, boolean importantOnly) throws SQLException {
        List<EmailMessage> base = searchEmails(username, field, keyword == null ? "" : keyword);
        List<EmailMessage> filtered = new ArrayList<>();
        for (EmailMessage message : base) {
            if (unreadOnly && message.isSeen()) {
                continue;
            }
            if (starredOnly && !message.isStarred()) {
                continue;
            }
            if (importantOnly && !message.isImportant()) {
                continue;
            }
            filtered.add(message);
        }
        return filtered;
    }

    public int countUnread(String username) throws SQLException {
        String sql = "SELECT COUNT(*) FROM emails WHERE recipient = ? AND is_seen = FALSE AND deleted = FALSE AND permanently_deleted = FALSE AND is_spam = FALSE";
        try (Connection connection = DatabaseConnection.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, username);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getInt(1) : 0;
            }
        }
    }

    private String ownerVisibleWhere(String extraCondition) {
        return baseSelect() + " WHERE " + extraCondition
                + " AND ((recipient = ? AND deleted = FALSE AND permanently_deleted = FALSE) OR (sender = ? AND sender_deleted = FALSE))"
                + " ORDER BY created_at DESC, id DESC";
    }

    private String searchSql(String condition) {
        return baseSelect()
                + " WHERE ((recipient = ? AND deleted = FALSE AND permanently_deleted = FALSE) OR (sender = ? AND sender_deleted = FALSE))"
                + " AND " + condition
                + " ORDER BY created_at DESC, id DESC";
    }

    private boolean updateForOwner(int emailId, String username, String column, boolean value) throws SQLException {
        String sql = "UPDATE emails SET " + column + " = ? WHERE id = ? AND ((recipient = ? AND permanently_deleted = FALSE) OR sender = ?)";
        return update(sql, value, emailId, username, username + "@localhost");
    }

    private boolean update(String sql, Object... values) throws SQLException {
        try (Connection connection = DatabaseConnection.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, values);
            return statement.executeUpdate() > 0;
        }
    }

    private List<EmailMessage> queryMany(String sql, Object... values) throws SQLException {
        List<EmailMessage> emails = new ArrayList<>();
        try (Connection connection = DatabaseConnection.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, values);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    emails.add(mapEmail(resultSet));
                }
            }
        }
        return emails;
    }

    private void bind(PreparedStatement statement, Object... values) throws SQLException {
        for (int i = 0; i < values.length; i++) {
            statement.setObject(i + 1, values[i]);
        }
    }

    private boolean readBooleanResult(CallableStatement statement) throws SQLException {
        boolean hasResultSet = statement.execute();
        if (!hasResultSet) {
            return false;
        }

        try (ResultSet resultSet = statement.getResultSet()) {
            return resultSet.next() && resultSet.getBoolean(1);
        }
    }

    private String baseSelect() {
        return "SELECT id, sender, recipient, subject, body, is_seen, deleted, sender_deleted, "
                + "permanently_deleted, is_starred, is_important, is_spam, category, created_at FROM emails";
    }

    private EmailMessage mapEmail(ResultSet resultSet) throws SQLException {
        Timestamp createdAt = resultSet.getTimestamp("created_at");
        return new EmailMessage(
                resultSet.getInt("id"),
                resultSet.getString("sender"),
                resultSet.getString("recipient"),
                resultSet.getString("subject"),
                resultSet.getString("body"),
                resultSet.getBoolean("is_seen"),
                resultSet.getBoolean("deleted"),
                resultSet.getBoolean("sender_deleted"),
                resultSet.getBoolean("permanently_deleted"),
                resultSet.getBoolean("is_starred"),
                resultSet.getBoolean("is_important"),
                resultSet.getBoolean("is_spam"),
                resultSet.getString("category"),
                createdAt == null ? null : new Date(createdAt.getTime()));
    }

    private String normalizeFolder(String folder) {
        return folder == null || folder.isBlank() ? "inbox" : folder.trim().toLowerCase(Locale.ROOT);
    }

    private String titleCase(String value) {
        if (value == null || value.isBlank()) {
            return "Primary";
        }
        return value.substring(0, 1).toUpperCase(Locale.ROOT) + value.substring(1).toLowerCase(Locale.ROOT);
    }

    public static final class EmailMessage {
        private final int id;
        private final String sender;
        private final String recipient;
        private final String subject;
        private final String body;
        private final boolean seen;
        private final boolean deleted;
        private final boolean senderDeleted;
        private final boolean permanentlyDeleted;
        private final boolean starred;
        private final boolean important;
        private final boolean spam;
        private final String category;
        private final Date createdAt;

        public EmailMessage(int id, String sender, String recipient, String subject, String body,
                            boolean seen, boolean deleted, boolean senderDeleted, boolean permanentlyDeleted,
                            boolean starred, boolean important, boolean spam, String category, Date createdAt) {
            this.id = id;
            this.sender = sender;
            this.recipient = recipient;
            this.subject = subject == null ? "" : subject;
            this.body = body == null ? "" : body;
            this.seen = seen;
            this.deleted = deleted;
            this.senderDeleted = senderDeleted;
            this.permanentlyDeleted = permanentlyDeleted;
            this.starred = starred;
            this.important = important;
            this.spam = spam;
            this.category = category == null || category.isBlank() ? "Primary" : category;
            this.createdAt = createdAt == null ? new Date() : new Date(createdAt.getTime());
        }

        public int getId() {
            return id;
        }

        public String getSender() {
            return sender;
        }

        public String getRecipient() {
            return recipient;
        }

        public String getSubject() {
            return subject;
        }

        public String getBody() {
            return body;
        }

        public boolean isSeen() {
            return seen;
        }

        public boolean isDeleted() {
            return deleted;
        }

        public boolean isSenderDeleted() {
            return senderDeleted;
        }

        public boolean isPermanentlyDeleted() {
            return permanentlyDeleted;
        }

        public boolean isStarred() {
            return starred;
        }

        public boolean isImportant() {
            return important;
        }

        public boolean isSpam() {
            return spam;
        }

        public String getCategory() {
            return category;
        }

        public Date getCreatedAt() {
            return new Date(createdAt.getTime());
        }

        public String toRfcMessage() {
            return toHeader() + "\r\n" + crlf(body);
        }

        public String toHeader() {
            String date = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.US).format(createdAt);
            return "From: " + sender + "\r\n"
                    + "To: " + recipient + "\r\n"
                    + "Date: " + date + "\r\n"
                    + "Subject: " + subject + "\r\n";
        }

        public long size() {
            return toRfcMessage().getBytes(StandardCharsets.UTF_8).length;
        }

        private String crlf(String value) {
            return value == null ? "" : value.replace("\r\n", "\n").replace('\r', '\n').replace("\n", "\r\n");
        }
    }
}
