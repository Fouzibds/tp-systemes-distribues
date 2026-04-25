USE mailserver;

CALL create_user('user1', 'test');
CALL create_user('user2', 'test');

CALL store_email(
    'user1@localhost',
    'user2',
    'Welcome to MySQL storage',
    'This message is stored in the centralized MySQL database.'
);

CALL store_email(
    'user2@localhost',
    'user1',
    'Part 5 test email',
    'POP3 and IMAP should read this message from MySQL.'
);
