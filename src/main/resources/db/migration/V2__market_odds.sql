-- Historical market prices, stored so the walk-forward backtest can score the model
-- against the bookmakers on the same matches without re-hitting the provider each run.

create table market_odds (
    id            bigserial primary key,
    fixture_id    bigint      not null references fixture (id) on delete cascade,
    provider      varchar(32) not null default 'allsports',
    median_home   double precision not null,
    median_draw   double precision,
    median_away   double precision not null,
    best_home     double precision,
    best_draw     double precision,
    best_away     double precision,
    bookmakers    int         not null default 0,
    fetched_at    timestamptz not null default now(),
    constraint ux_market_odds unique (fixture_id, provider)
);

create index ix_market_odds_fixture on market_odds (fixture_id);
