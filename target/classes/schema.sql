CREATE TABLE IF NOT EXISTS lockers (
    id       INTEGER PRIMARY KEY AUTOINCREMENT,
    building TEXT NOT NULL,
    in_use   INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS items (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    description  TEXT NOT NULL,
    category     TEXT NOT NULL,
    building     TEXT NOT NULL,
    finder_email TEXT NOT NULL,
    locker_id    INTEGER,
    pin          TEXT,
    status       TEXT NOT NULL DEFAULT 'stored',
    photo        BLOB,
    photo_type   TEXT,
    created_at   TEXT NOT NULL DEFAULT (datetime('now')),
    FOREIGN KEY (locker_id) REFERENCES lockers(id)
);

CREATE TABLE IF NOT EXISTS claims (
    id             INTEGER PRIMARY KEY AUTOINCREMENT,
    description    TEXT NOT NULL,
    category       TEXT NOT NULL,
    building       TEXT NOT NULL,
    claimant_email TEXT NOT NULL,
    status         TEXT NOT NULL DEFAULT 'pending',
    matched_item   INTEGER,
    lost_on        TEXT,
    photo          BLOB,
    photo_type     TEXT,
    created_at     TEXT NOT NULL DEFAULT (datetime('now')),
    FOREIGN KEY (matched_item) REFERENCES items(id)
);

CREATE TABLE IF NOT EXISTS users (
    id               INTEGER PRIMARY KEY AUTOINCREMENT,
    username         TEXT NOT NULL,
    email            TEXT UNIQUE NOT NULL,
    password_hash    TEXT NOT NULL,
    role             TEXT NOT NULL DEFAULT 'user',
    points           INTEGER NOT NULL DEFAULT 0
    );

CREATE TABLE IF NOT EXISTS rewards (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    name        TEXT NOT NULL,
    description TEXT,
    cost        INTEGER NOT NULL,
    stock       INTEGER,               -- NULL = unlimited
    active      INTEGER NOT NULL DEFAULT 1
);

CREATE TABLE IF NOT EXISTS redemptions (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id      INTEGER NOT NULL,
    reward_id    INTEGER NOT NULL,
    redeemed_at  TEXT NOT NULL DEFAULT (datetime('now')),
    FOREIGN KEY (user_id) REFERENCES users(id),
    FOREIGN KEY (reward_id) REFERENCES rewards(id)
);
