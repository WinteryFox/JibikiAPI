CREATE TABLE IF NOT EXISTS users
(
    snowflake TEXT NOT NULL PRIMARY KEY,
    email     TEXT NOT NULL,
    hash      TEXT NOT NULL,
    salt      TEXT NOT NULL,
    username  TEXT NOT NULL
);