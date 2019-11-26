CREATE TABLE IF NOT EXISTS users
(
    snowflake TEXT                        NOT NULL PRIMARY KEY,
    creation  TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT (now() AT TIME ZONE 'UTC'),
    email     TEXT                        NOT NULL,
    hash      TEXT                        NOT NULL,
    username  TEXT                        NOT NULL
);

CREATE TABLE IF NOT EXISTS userTokens
(
    snowflake TEXT                        NOT NULL PRIMARY KEY,
    token     TEXT                        NOT NULL,
    expiry    TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT (now() AT TIME ZONE 'UTC') + '7'
)