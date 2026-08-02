-- SportPredict initial schema (PostgreSQL)

create table competition (
    id              bigserial primary key,
    sport           varchar(16)  not null,
    name            varchar(200) not null,
    country         varchar(100),
    external_ref    varchar(64),
    constraint ux_competition unique (sport, name, country)
);

create table team (
    id               bigserial primary key,
    sport            varchar(16)  not null,
    name             varchar(200) not null,
    normalized_name  varchar(200) not null,
    country          varchar(100),
    constraint ux_team unique (sport, normalized_name)
);

create table team_alias (
    id               bigserial primary key,
    team_id          bigint       not null references team (id) on delete cascade,
    provider         varchar(32)  not null,
    external_id      varchar(64),
    raw_name         varchar(240) not null,
    normalized_name  varchar(240) not null
);
create unique index ux_alias_ext on team_alias (provider, external_id) where external_id is not null;
create index ix_alias_norm on team_alias (provider, normalized_name);

create table fixture (
    id               bigserial primary key,
    sport            varchar(16) not null,
    competition_id   bigint references competition (id),
    home_team_id     bigint      not null references team (id),
    away_team_id     bigint      not null references team (id),
    kickoff          timestamptz not null,
    status           varchar(16) not null,
    season           varchar(16),
    home_score       int,
    away_score       int,
    home_score_ht    int,
    away_score_ht    int,
    learned          boolean     not null default false,
    created_at       timestamptz not null default now(),
    updated_at       timestamptz not null default now(),
    constraint ux_fixture unique (sport, home_team_id, away_team_id, kickoff)
);
create index ix_fixture_when   on fixture (sport, kickoff);
create index ix_fixture_learn  on fixture (sport, status, learned);

create table fixture_source (
    id           bigserial primary key,
    fixture_id   bigint      not null references fixture (id) on delete cascade,
    provider     varchar(32) not null,
    sport        varchar(16) not null,
    external_id  varchar(64) not null,
    payload      jsonb,
    fetched_at   timestamptz not null default now(),
    constraint ux_fixture_source unique (provider, sport, external_id)
);

create table team_rating (
    id              bigserial primary key,
    team_id         bigint      not null references team (id) on delete cascade,
    sport           varchar(16) not null,
    elo             double precision not null default 1500,
    matches         int              not null default 0,
    scored          double precision not null default 0,
    conceded        double precision not null default 0,
    updated_at      timestamptz      not null default now(),
    constraint ux_team_rating unique (team_id, sport)
);

create table fighter (
    id               bigserial primary key,
    name             varchar(200) not null,
    normalized_name  varchar(200) not null unique,
    nickname         varchar(160),
    height_cm        double precision,
    reach_cm         double precision,
    weight_kg        double precision,
    stance           varchar(32),
    date_of_birth    date,
    wins             int not null default 0,
    losses           int not null default 0,
    draws            int not null default 0,
    win_streak       int not null default 0,
    elo              double precision not null default 1500,
    strikes_per_min  double precision,
    strike_accuracy  double precision,
    takedowns_avg    double precision,
    submissions_avg  double precision,
    updated_at       timestamptz not null default now()
);

create table fight (
    id                bigserial primary key,
    event_name        varchar(240),
    fight_date        timestamptz not null,
    fighter_a_id      bigint      not null references fighter (id),
    fighter_b_id      bigint      not null references fighter (id),
    weight_class      varchar(80),
    rounds_scheduled  int,
    title_fight       boolean     not null default false,
    status            varchar(16) not null,
    winner_id         bigint references fighter (id),
    method            varchar(80),
    end_round         int,
    learned           boolean     not null default false,
    created_at        timestamptz not null default now(),
    updated_at        timestamptz not null default now(),
    constraint ux_fight unique (fighter_a_id, fighter_b_id, fight_date)
);
create index ix_fight_when on fight (fight_date);

create table fight_source (
    id           bigserial primary key,
    fight_id     bigint      not null references fight (id) on delete cascade,
    provider     varchar(32) not null,
    external_id  varchar(64) not null,
    payload      jsonb,
    fetched_at   timestamptz not null default now(),
    constraint ux_fight_source unique (provider, external_id)
);

create table prediction (
    id             bigserial primary key,
    sport          varchar(16) not null,
    fixture_id     bigint references fixture (id) on delete cascade,
    fight_id       bigint references fight (id) on delete cascade,
    model          varchar(64) not null,
    model_version  varchar(40),
    p_home         double precision,
    p_draw         double precision,
    p_away         double precision,
    expected_home  double precision,
    expected_away  double precision,
    ou_line        double precision,
    p_over         double precision,
    p_btts         double precision,
    top_score      varchar(16),
    confidence     double precision,
    detail         jsonb,
    created_at     timestamptz not null default now(),
    settled        boolean     not null default false,
    outcome        varchar(8),
    log_loss       double precision,
    brier          double precision
);
create index ix_prediction_fixture on prediction (fixture_id);
create index ix_prediction_fight   on prediction (fight_id);

create table model_state (
    id           bigserial primary key,
    model_key    varchar(80) not null unique,
    version      varchar(40),
    payload      jsonb       not null,
    sample_size  int,
    trained_at   timestamptz not null default now()
);

create table betting_round (
    id            bigserial primary key,
    provider      varchar(32) not null default 'winner',
    round_code    varchar(80),
    form_name     varchar(200),
    source_url    text        not null,
    fetch_method  varchar(32),
    fetched_at    timestamptz not null default now()
);

create table betting_selection (
    id                 bigserial primary key,
    round_id           bigint      not null references betting_round (id) on delete cascade,
    line_no            int         not null,
    raw_text           text,
    competition_raw    varchar(240),
    home_raw           varchar(240),
    away_raw           varchar(240),
    kickoff            timestamptz,
    odds_home          double precision,
    odds_draw          double precision,
    odds_away          double precision,
    fixture_id         bigint references fixture (id),
    match_confidence   double precision
);
create index ix_selection_round on betting_selection (round_id, line_no);

create table ingest_run (
    id           bigserial primary key,
    provider     varchar(32) not null,
    sport        varchar(16) not null,
    from_date    date,
    to_date      date,
    requests     int not null default 0,
    records      int not null default 0,
    created      int not null default 0,
    updated      int not null default 0,
    started_at   timestamptz not null default now(),
    finished_at  timestamptz,
    error        text
);
create index ix_ingest_run_time on ingest_run (provider, sport, started_at desc);

-- Cursor for the nightly history backfill: how far back each provider/sport got.
create table ingest_cursor (
    id             bigserial primary key,
    provider       varchar(32) not null,
    sport          varchar(16) not null,
    oldest_pulled  date,
    newest_pulled  date,
    updated_at     timestamptz not null default now(),
    constraint ux_ingest_cursor unique (provider, sport)
);
