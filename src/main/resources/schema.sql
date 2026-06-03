-- LightAnalytics schema. Statements are idempotent (IF NOT EXISTS) so this file
-- doubles as a naive migration: it is executed on every startup.

CREATE TABLE IF NOT EXISTS players (
    uuid           TEXT    PRIMARY KEY,
    username       TEXT    NOT NULL,
    first_seen     INTEGER NOT NULL,
    last_seen      INTEGER NOT NULL,
    total_sessions INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS sessions (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    uuid        TEXT    NOT NULL,
    username    TEXT    NOT NULL,
    server      TEXT,
    login_time  INTEGER NOT NULL,
    logout_time INTEGER
);

CREATE INDEX IF NOT EXISTS idx_sessions_login_time ON sessions (login_time);
CREATE INDEX IF NOT EXISTS idx_sessions_uuid       ON sessions (uuid);

CREATE TABLE IF NOT EXISTS snapshots (
    timestamp    INTEGER PRIMARY KEY,
    player_count INTEGER NOT NULL,
    cpu_process  REAL    NOT NULL,
    cpu_system   REAL    NOT NULL,
    heap_used    INTEGER NOT NULL,
    heap_max     INTEGER NOT NULL
);

-- Downsampled history. Full-resolution snapshots are kept for a recent window
-- (see RetentionTask), then older ones are rolled up into one row per UTC hour so
-- long-range population peaks and resource trends survive without the row count
-- growing without bound. bucket_start is the hour-aligned epoch-ms boundary,
-- timestamp minus (timestamp modulo 3,600,000). Averages are stored as REAL so a
-- window spanning both resolutions can be recombined with sample-count weighting.
-- NOTE the loader splits this file on the semicolon character, so comments here
-- must not contain one (a stray one would be parsed as a statement terminator).
CREATE TABLE IF NOT EXISTS snapshots_hourly (
    bucket_start     INTEGER PRIMARY KEY,
    sample_count     INTEGER NOT NULL,
    player_count_min INTEGER NOT NULL,
    player_count_max INTEGER NOT NULL,
    player_count_avg REAL    NOT NULL,
    cpu_process_avg  REAL    NOT NULL,
    cpu_process_max  REAL    NOT NULL,
    cpu_system_avg   REAL    NOT NULL,
    cpu_system_max   REAL    NOT NULL,
    heap_used_avg    REAL    NOT NULL,
    heap_used_max    INTEGER NOT NULL,
    heap_max         INTEGER NOT NULL
);
