USE mailserver;

DROP PROCEDURE IF EXISTS authenticate_user;
DROP PROCEDURE IF EXISTS user_exists;
DROP PROCEDURE IF EXISTS create_user;
DROP PROCEDURE IF EXISTS update_password;
DROP PROCEDURE IF EXISTS delete_user;
DROP PROCEDURE IF EXISTS store_email;
DROP PROCEDURE IF EXISTS fetch_emails;
DROP PROCEDURE IF EXISTS fetch_email_by_id;
DROP PROCEDURE IF EXISTS mark_email_seen;
DROP PROCEDURE IF EXISTS delete_email;
DROP PROCEDURE IF EXISTS search_emails;
DROP PROCEDURE IF EXISTS restore_email;
DROP PROCEDURE IF EXISTS permanently_delete_email;

DELIMITER //

CREATE PROCEDURE authenticate_user(
    IN p_username VARCHAR(64),
    IN p_password VARCHAR(255)
)
BEGIN
    SELECT COUNT(*) > 0 AS authenticated
    FROM users
    WHERE username = p_username
      AND password = p_password
      AND active = TRUE;
END //

CREATE PROCEDURE user_exists(
    IN p_username VARCHAR(64)
)
BEGIN
    SELECT COUNT(*) > 0 AS exists_user
    FROM users
    WHERE username = p_username
      AND active = TRUE;
END //

CREATE PROCEDURE create_user(
    IN p_username VARCHAR(64),
    IN p_password VARCHAR(255)
)
BEGIN
    IF NOT EXISTS (SELECT 1 FROM users WHERE username = p_username) THEN
        INSERT INTO users(username, password, active)
        VALUES (p_username, p_password, TRUE);
        SELECT TRUE AS created;
    ELSEIF EXISTS (SELECT 1 FROM users WHERE username = p_username AND active = FALSE) THEN
        UPDATE users
        SET password = p_password,
            active = TRUE
        WHERE username = p_username;
        SELECT TRUE AS created;
    ELSE
        SELECT FALSE AS created;
    END IF;
END //

CREATE PROCEDURE update_password(
    IN p_username VARCHAR(64),
    IN p_new_password VARCHAR(255)
)
BEGIN
    UPDATE users
    SET password = p_new_password
    WHERE username = p_username
      AND active = TRUE;

    SELECT ROW_COUNT() > 0 AS updated;
END //

CREATE PROCEDURE delete_user(
    IN p_username VARCHAR(64)
)
BEGIN
    UPDATE users
    SET active = FALSE
    WHERE username = p_username
      AND active = TRUE;

    SELECT ROW_COUNT() > 0 AS deleted_user;
END //

CREATE PROCEDURE store_email(
    IN p_sender VARCHAR(255),
    IN p_recipient VARCHAR(255),
    IN p_subject VARCHAR(255),
    IN p_body TEXT
)
BEGIN
    INSERT INTO emails(sender, recipient, subject, body, category)
    VALUES (
        p_sender,
        p_recipient,
        p_subject,
        p_body,
        CASE
            WHEN LOWER(CONCAT(p_subject, ' ', p_body)) REGEXP 'sale|offer|promo|discount' THEN 'Promotions'
            WHEN LOWER(CONCAT(p_subject, ' ', p_body)) REGEXP 'friend|follow|network|social' THEN 'Social'
            WHEN LOWER(CONCAT(p_subject, ' ', p_body)) REGEXP 'notification|update|alert|system' THEN 'Updates'
            ELSE 'Primary'
        END
    );

    SELECT LAST_INSERT_ID() AS email_id;
END //

CREATE PROCEDURE fetch_emails(
    IN p_username VARCHAR(64)
)
BEGIN
    SELECT id, sender, recipient, subject, body, is_seen, deleted, sender_deleted,
           permanently_deleted, is_starred, is_important, is_spam, category, created_at
    FROM emails
    WHERE recipient = p_username
      AND deleted = FALSE
      AND permanently_deleted = FALSE
      AND is_spam = FALSE
    ORDER BY created_at ASC, id ASC;
END //

CREATE PROCEDURE fetch_email_by_id(
    IN p_email_id INT,
    IN p_username VARCHAR(64)
)
BEGIN
    SELECT id, sender, recipient, subject, body, is_seen, deleted, sender_deleted,
           permanently_deleted, is_starred, is_important, is_spam, category, created_at
    FROM emails
    WHERE id = p_email_id
      AND permanently_deleted = FALSE
      AND (recipient = p_username OR sender = CONCAT(p_username, '@localhost'));
END //

CREATE PROCEDURE mark_email_seen(
    IN p_email_id INT,
    IN p_username VARCHAR(64),
    IN p_seen BOOLEAN
)
BEGIN
    UPDATE emails
    SET is_seen = p_seen
    WHERE id = p_email_id
      AND recipient = p_username
      AND permanently_deleted = FALSE;

    SELECT ROW_COUNT() > 0 AS updated;
END //

CREATE PROCEDURE delete_email(
    IN p_email_id INT,
    IN p_username VARCHAR(64)
)
BEGIN
    UPDATE emails
    SET deleted = TRUE
    WHERE id = p_email_id
      AND recipient = p_username
      AND deleted = FALSE;

    SELECT ROW_COUNT() > 0 AS deleted_email;
END //

CREATE PROCEDURE restore_email(
    IN p_email_id INT,
    IN p_username VARCHAR(64)
)
BEGIN
    UPDATE emails
    SET deleted = FALSE
    WHERE id = p_email_id
      AND recipient = p_username
      AND deleted = TRUE
      AND permanently_deleted = FALSE;

    SELECT ROW_COUNT() > 0 AS restored_email;
END //

CREATE PROCEDURE permanently_delete_email(
    IN p_email_id INT,
    IN p_username VARCHAR(64)
)
BEGIN
    UPDATE emails
    SET permanently_deleted = TRUE
    WHERE id = p_email_id
      AND recipient = p_username
      AND deleted = TRUE
      AND permanently_deleted = FALSE;

    SELECT ROW_COUNT() > 0 AS permanently_deleted_email;
END //

CREATE PROCEDURE search_emails(
    IN p_username VARCHAR(64),
    IN p_field VARCHAR(20),
    IN p_keyword VARCHAR(255)
)
BEGIN
    SELECT id, sender, recipient, subject, body, is_seen, deleted, sender_deleted,
           permanently_deleted, is_starred, is_important, is_spam, category, created_at
    FROM emails
    WHERE recipient = p_username
      AND deleted = FALSE
      AND permanently_deleted = FALSE
      AND (
          (UPPER(p_field) = 'FROM' AND sender LIKE CONCAT('%', p_keyword, '%'))
          OR (UPPER(p_field) = 'TO' AND recipient LIKE CONCAT('%', p_keyword, '%'))
          OR (UPPER(p_field) = 'SUBJECT' AND subject LIKE CONCAT('%', p_keyword, '%'))
          OR (UPPER(p_field) = 'BODY' AND body LIKE CONCAT('%', p_keyword, '%'))
      )
    ORDER BY created_at ASC, id ASC;
END //

DELIMITER ;
