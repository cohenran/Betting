# SportPredict

Java 21 / Spring Boot prediction engine for international football and basketball, Israeli
football, and UFC/MMA. It ingests historical results from two sports data APIs in parallel,
learns from every new result, serves predictions over a web UI, and reads a Winner.co.il
round to tell you what the model makes of each line.

Deploys onto a bare Ubuntu server with one script.

---

## What it actually does

| Sport | Model | Learns |
|---|---|---|
| Football (world + Israel) | Dixon-Coles: time-weighted Poisson attack/defence per team plus a low-score correlation term. Produces 1X2, over/under, BTTS and a scoreline distribution. | Elo after every result; full MLE refit nightly |
| Basketball | Elo → expected margin → normal distribution. Produces two-way probability, spread and total. | Elo + exponentially weighted scoring after every result |
| UFC / MMA | Logistic regression over fighter differentials (Elo, reach, height, age, record, streak, striking and grappling rates). | One SGD step per new fight (true online learning) |

There is no LLM in the prediction path — every number comes from the statistical models
above, which is what makes the backtest meaningful.

### Honest limits

- The model outputs **probabilities, not results**. A 62% home win loses four times in ten.
- Automated access to Winner.co.il is **against their terms of use**, their markup changes
  without notice, and the IP can be blocked. `/api/winner/debug` and manual paste exist
  because of that.
- Bookmaker margins are 5–12%. Beating the closing price consistently is hard; check the
  backtest and calibration numbers before believing an "edge".

---

## Quick start (local)

```bash
docker compose -f deploy/docker-compose.yml up -d
export APISPORTS_KEY=your-key
export ALLSPORTS_KEY=your-key
mvn spring-boot:run
```

Open <http://localhost:8090>.

Without API keys the app still starts — ingest is skipped and you can exercise the Winner
parser via manual paste.

## Deploy to Ubuntu

```bash
git clone <your-repo> sportpredict && cd sportpredict
sudo bash deploy/install-ubuntu.sh
```

The script installs JDK 21, Maven, PostgreSQL and nginx, creates the database and a
`sportpredict` service user, builds the jar, installs headless Chromium for the scraper,
writes `/etc/sportpredict/sportpredict.env`, and enables the systemd unit behind nginx on
port 80. It prints the generated admin token.

Note: the jar is ~260 MB because Playwright bundles its browser driver. Setting
`PLAYWRIGHT_ENABLED=false` disables the browser fallback (manual paste still works) but does
not shrink the jar — remove the `playwright` dependency from `pom.xml` for that.

Then put your API keys in `/etc/sportpredict/sportpredict.env` and:

```bash
sudo systemctl restart sportpredict
```

Useful commands:

```bash
journalctl -u sportpredict -f
systemctl status sportpredict
```

---

## Getting data in

Two providers on separate quotas, fetched concurrently on virtual threads, each behind its
own token-bucket limiter. Throughput is the **sum** of both budgets rather than the slower
one, which is the whole point of using both.

- `api-sports.io` is queried **by date** (`/fixtures?date=…`) so one request returns every
  league that day — far cheaper on a 10 req/min, 100 req/day free tier. Configured league
  ids filter the response client-side.
- `allsportsapi.com` accepts a **from..to range**, so a 10-day chunk costs one request.

Records from both land on the same `fixture` row: teams are resolved through
`team_alias` (provider id → exact name → alias → fuzzy Jaro-Winkler → create), and a
pairing within ±6h of an existing kickoff is treated as the same match. Fetching is
parallel; merging is serialized so two providers cannot race into duplicate rows.

Three timers:

| Job | Default | What |
|---|---|---|
| recent | every 15 min | yesterday → +14 days, then incremental learning |
| history | 03:20 daily | one 10-day chunk further back, until `history-days` is covered |
| retrain | 04:00 daily | chronological replay of all results + Dixon-Coles refit |

The nightly replay is not optional: the history backfill walks *backwards* in time, and
Elo only means anything when results are applied in order.

Kick it off by hand:

```bash
curl -X POST -H "X-Admin-Token: $TOKEN" \
  "http://localhost:8090/api/admin/ingest?from=2025-08-01&to=2026-07-01&sports=FOOTBALL"
```

League ids in `application.yml` are the ones to check first if a competition is missing —
`383` is Ligat ha'Al and `384` Liga Leumit on api-sports; the allsportsapi ids are
different and should be verified against their own `/Leagues` endpoint.

---

## Reading a Winner round

`POST /api/winner/analyze {"url": "..."}` — or the **טופס וינר** tab.

1. Plain HTTP + Jsoup first, including any JSON embedded in the page.
2. If the page turns out to be client-rendered, headless Chromium loads it and records
   every JSON response it fetches — the internal API payload is usually much easier to
   parse than the DOM.
3. Lines are parsed by *shape* (two team names, two or three decimal odds, an optional
   date/time) rather than by fixed CSS selectors, so a redesign does not necessarily break it.

Hebrew names are mapped to the English names the APIs use via
`src/main/resources/aliases/hebrew-teams.csv` (~150 clubs seeded), then matched to a stored
fixture. Anything unrecognised comes back in `unmatchedNames`, and one call teaches it
permanently:

```bash
curl -X POST -H "X-Admin-Token: $TOKEN" -H 'Content-Type: application/json' \
  -d '{"rawName":"מכבי בני ריינה","teamName":"Maccabi Bnei Reineh","sport":"FOOTBALL"}' \
  http://localhost:8090/api/admin/alias
```

For each line you get the model's 1/X/2 probabilities, its pick, the most likely scoreline,
and the **edge** against the printed odds (`p × odds − 1`; positive means the model thinks
the price is too long). If a line has no fixture in the database, it is still predicted from
current ratings and flagged.

When a parse comes back empty, `GET /api/winner/debug?url=…` shows exactly what was fetched
and how far the parser got. Pasting the lines into the UI textarea always works:

```
1. מכבי חיפה - הפועל באר שבע 2.10 3.25 3.40
2. 15/08 21:00 ריאל מדריד - ברצלונה 1.95 3.60 3.70
```

---

## Is the model any good?

`GET /api/backtest/football?historyDays=540&stepDays=7` runs a walk-forward test: refit on
everything before a cutoff, predict the next week, advance, repeat. Nothing it scores was
ever in its own training set.

It reports log-loss, Brier score and hit rate against a baseline of the training window's
own outcome frequencies, plus a calibration table (when the model says 60%, does it happen
60% of the time?). **If the model does not beat the baseline log-loss, it is not adding
anything** — more history is the usual fix.

`GET /api/stats` shows the same metrics over predictions that were actually stored before
kickoff and settled afterwards. That number is the one that counts.

---

## API

| Method | Path | Purpose |
|---|---|---|
| GET | `/api/predictions/upcoming?sport=FOOTBALL&days=7` | upcoming events with predictions |
| GET | `/api/predictions/fixture/{id}` | one fixture |
| GET | `/api/predictions/fight/{id}` | one fight |
| GET | `/api/ratings?sport=FOOTBALL` | Elo table |
| GET | `/api/backtest/football` | walk-forward evaluation |
| GET | `/api/stats` | counters + live calibration |
| POST | `/api/winner/analyze` | analyze a round by URL |
| POST | `/api/winner/analyze-text` | analyze pasted lines |
| GET | `/api/winner/debug?url=` | what the fetcher actually got |
| POST | `/api/admin/ingest` | manual ingest |
| POST | `/api/admin/learn` | apply new results now |
| POST | `/api/admin/retrain` | full replay + refit |
| POST | `/api/admin/alias` | teach a Hebrew team name |
| GET | `/api/admin/runs` | ingest history |

`/api/admin/**` requires `X-Admin-Token`.

---

## Layout

```
ingest/    provider clients, rate limiters, team resolution, upsert/merge, schedulers
model/     elo · football (Dixon-Coles) · basketball · ufc · learning · backtest
winner/    fetcher (Jsoup → Playwright), shape-based parser, Hebrew aliases, analysis
web/       REST controllers + error handling
domain/    JPA entities        repo/  Spring Data repositories
resources/db/migration  Flyway schema
resources/static        the UI (vanilla JS, RTL)
deploy/    install-ubuntu.sh, systemd unit, nginx config, dev docker-compose
```

## Tests

```bash
mvn test
```

Covers the Dixon-Coles fit against a simulated league with known strengths (it recovers the
ordering, the intercept and the home advantage), the Winner parser on Hebrew text/JSON/HTML,
and name normalization. They need no database.
