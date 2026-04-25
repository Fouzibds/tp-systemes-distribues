CREATE DATABASE IF NOT EXISTS mailserver
    CHARACTER SET utf8mb4
    COLLATE utf8mb4_unicode_ci;

USE mailserver;

CREATE TABLE IF NOT EXISTS users (
    id INT AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(64) NOT NULL UNIQUE,
    password VARCHAR(255) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    active BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE TABLE IF NOT EXISTS emails (
    id INT AUTO_INCREMENT PRIMARY KEY,
    sender VARCHAR(255) NOT NULL,
    recipient VARCHAR(255) NOT NULL,
    subject VARCHAR(255) NOT NULL DEFAULT '',
    body TEXT NOT NULL,
    is_seen BOOLEAN NOT NULL DEFAULT FALSE,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    sender_deleted BOOLEAN NOT NULL DEFAULT FALSE,
    permanently_deleted BOOLEAN NOT NULL DEFAULT FALSE,
    is_starred BOOLEAN NOT NULL DEFAULT FALSE,
    is_important BOOLEAN NOT NULL DEFAULT FALSE,
    is_spam BOOLEAN NOT NULL DEFAULT FALSE,
    category VARCHAR(20) NOT NULL DEFAULT 'Primary',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_emails_recipient_deleted (recipient, deleted),
    INDEX idx_emails_sender_deleted (sender, sender_deleted),
    INDEX idx_emails_category (category),
    INDEX idx_emails_sender (sender),
    INDEX idx_emails_subject (subject)
);
