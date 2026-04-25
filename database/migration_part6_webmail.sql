USE mailserver;

SET @add_sender_deleted = (
    SELECT IF(
        COUNT(*) = 0,
        'ALTER TABLE emails ADD COLUMN sender_deleted BOOLEAN NOT NULL DEFAULT FALSE',
        'SELECT 1'
    )
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'emails'
      AND column_name = 'sender_deleted'
);
PREPARE stmt FROM @add_sender_deleted;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @add_permanently_deleted = (
    SELECT IF(
        COUNT(*) = 0,
        'ALTER TABLE emails ADD COLUMN permanently_deleted BOOLEAN NOT NULL DEFAULT FALSE',
        'SELECT 1'
    )
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'emails'
      AND column_name = 'permanently_deleted'
);
PREPARE stmt FROM @add_permanently_deleted;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @add_is_starred = (
    SELECT IF(
        COUNT(*) = 0,
        'ALTER TABLE emails ADD COLUMN is_starred BOOLEAN NOT NULL DEFAULT FALSE',
        'SELECT 1'
    )
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'emails'
      AND column_name = 'is_starred'
);
PREPARE stmt FROM @add_is_starred;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @add_is_important = (
    SELECT IF(
        COUNT(*) = 0,
        'ALTER TABLE emails ADD COLUMN is_important BOOLEAN NOT NULL DEFAULT FALSE',
        'SELECT 1'
    )
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'emails'
      AND column_name = 'is_important'
);
PREPARE stmt FROM @add_is_important;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @add_is_spam = (
    SELECT IF(
        COUNT(*) = 0,
        'ALTER TABLE emails ADD COLUMN is_spam BOOLEAN NOT NULL DEFAULT FALSE',
        'SELECT 1'
    )
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'emails'
      AND column_name = 'is_spam'
);
PREPARE stmt FROM @add_is_spam;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @add_category = (
    SELECT IF(
        COUNT(*) = 0,
        'ALTER TABLE emails ADD COLUMN category VARCHAR(20) NOT NULL DEFAULT ''Primary''',
        'SELECT 1'
    )
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'emails'
      AND column_name = 'category'
);
PREPARE stmt FROM @add_category;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @idx_sender_deleted = (
    SELECT IF(
        COUNT(*) = 0,
        'CREATE INDEX idx_emails_sender_deleted ON emails(sender, sender_deleted)',
        'SELECT 1'
    )
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'emails'
      AND index_name = 'idx_emails_sender_deleted'
);
PREPARE stmt FROM @idx_sender_deleted;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @idx_category = (
    SELECT IF(
        COUNT(*) = 0,
        'CREATE INDEX idx_emails_category ON emails(category)',
        'SELECT 1'
    )
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'emails'
      AND index_name = 'idx_emails_category'
);
PREPARE stmt FROM @idx_category;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
