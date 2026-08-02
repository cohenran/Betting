'use strict';

const $ = (id) => document.getElementById(id);

document.querySelectorAll('.tab').forEach((tab) => {
    tab.addEventListener('click', () => {
        document.querySelectorAll('.tab').forEach((t) => t.classList.remove('active'));
        document.querySelectorAll('.panel').forEach((p) => p.classList.remove('active'));
        tab.classList.add('active');
        $(tab.dataset.tab).classList.add('active');
    });
});

async function api(path, options = {}) {
    const headers = Object.assign({'Content-Type': 'application/json'}, options.headers || {});
    const token = $('adminToken').value || localStorage.getItem('adminToken');
    if (token) {
        headers['X-Admin-Token'] = token;
    }
    const response = await fetch(path, Object.assign({}, options, {headers}));
    const body = await response.json().catch(() => ({error: 'תשובה לא תקינה מהשרת'}));
    if (!response.ok) {
        throw new Error(body.error || response.status);
    }
    return body;
}

function pct(v) {
    return v == null ? '—' : (v * 100).toFixed(1) + '%';
}

function num(v, digits = 2) {
    return v == null ? '—' : Number(v).toFixed(digits);
}

function when(iso) {
    if (!iso) {
        return '—';
    }
    return new Date(iso).toLocaleString('he-IL', {day: '2-digit', month: '2-digit', hour: '2-digit', minute: '2-digit'});
}

function edgeCell(edge) {
    if (edge == null) {
        return '<td>—</td>';
    }
    const cls = edge > 0 ? 'pos' : 'neg';
    return `<td class="${cls}">${(edge * 100).toFixed(1)}%</td>`;
}

const PICK_LABEL = {HOME: '1', DRAW: 'X', AWAY: '2'};

function table(headers, rows) {
    return `<table><thead><tr>${headers.map((h) => `<th>${h}</th>`).join('')}</tr></thead>`
        + `<tbody>${rows.join('')}</tbody></table>`;
}

function busy(button, running) {
    button.disabled = running;
    button.textContent = running ? '...' : button.dataset.label;
}

document.querySelectorAll('button').forEach((b) => {
    b.dataset.label = b.textContent;
});

async function run(button, target, fn) {
    busy(button, true);
    try {
        await fn();
    } catch (e) {
        $(target).innerHTML = `<div class="error">שגיאה: ${e.message}</div>`;
    } finally {
        busy(button, false);
    }
}

// ---------- Winner ----------

function renderRound(round) {
    $('winnerMeta').textContent =
        `שורות: ${round.lines} · נחזו: ${round.predicted} · שליפה: ${round.fetchMethod}`
        + (round.note ? ` · ${round.note}` : '');

    $('winnerUnmatched').innerHTML = round.unmatchedNames.length
        ? `שמות שלא זוהו (הוסף מיפוי בלשונית ניהול): ${round.unmatchedNames.join(', ')}`
        : '';

    if (!round.results.length) {
        $('winnerResults').innerHTML =
            '<div class="warn">לא נמצאו שורות. בדוק ב"בדיקת שליפה" מה הוחזר, או הדבק את השורות ידנית.</div>';
        return;
    }

    const rows = round.results.map((r) => {
        const p = r.prediction;
        const recommendation = r.recommendation
            ? `<span class="pick">${PICK_LABEL[r.recommendation]}</span> (${pct(r.recommendationProbability)})`
            : '—';
        return `<tr>
            <td>${r.lineNo}</td>
            <td>${r.homeRaw} – ${r.awayRaw}</td>
            <td>${when(r.kickoff)}</td>
            <td>${num(r.oddsHome)} / ${num(r.oddsDraw)} / ${num(r.oddsAway)}</td>
            <td>${p ? pct(p.pHome) : '—'}</td>
            <td>${p ? pct(p.pDraw) : '—'}</td>
            <td>${p ? pct(p.pAway) : '—'}</td>
            <td>${recommendation}</td>
            ${edgeCell(r.recommendationEdge)}
            <td>${p && p.topScore ? p.topScore : '—'}</td>
            <td class="warn">${r.note || ''}</td>
        </tr>`;
    });

    $('winnerResults').innerHTML = table(
        ['#', 'משחק', 'מועד', 'יחסים 1/X/2', '1', 'X', '2', 'המלצה', 'יתרון', 'תוצאה סבירה', 'הערות'],
        rows);
}

$('analyzeUrl').addEventListener('click', () => run($('analyzeUrl'), 'winnerResults', async () => {
    const url = $('winnerUrl').value.trim();
    if (!url) {
        throw new Error('הזן כתובת של טופס');
    }
    renderRound(await api('/api/winner/analyze', {method: 'POST', body: JSON.stringify({url})}));
}));

$('analyzeText').addEventListener('click', () => run($('analyzeText'), 'winnerResults', async () => {
    const text = $('winnerText').value.trim();
    if (!text) {
        throw new Error('הדבק שורות של טופס');
    }
    renderRound(await api('/api/winner/analyze-text', {method: 'POST', body: JSON.stringify({text})}));
}));

$('debugUrl').addEventListener('click', () => run($('debugUrl'), 'winnerResults', async () => {
    const url = $('winnerUrl').value.trim();
    if (!url) {
        throw new Error('הזן כתובת של טופס');
    }
    const debug = await api('/api/winner/debug?url=' + encodeURIComponent(url));
    $('winnerResults').innerHTML = `<pre>${JSON.stringify(debug, null, 2)
        .replace(/</g, '&lt;')}</pre>`;
}));

// ---------- Upcoming ----------

$('loadUpcoming').addEventListener('click', () => run($('loadUpcoming'), 'upcomingResults', async () => {
    const sport = $('upcomingSport').value;
    const days = $('upcomingDays').value;
    const list = await api(`/api/predictions/upcoming?sport=${sport}&days=${days}`);
    if (!list.length) {
        $('upcomingResults').innerHTML = '<div class="warn">אין משחקים בטווח. הרץ שאיבת נתונים בלשונית ניהול.</div>';
        return;
    }
    const rows = list.map((v) => {
        const p = v.prediction;
        const score = v.homeScore == null ? '' : `${v.homeScore}:${v.awayScore}`;
        return `<tr>
            <td>${when(v.startsAt)}</td>
            <td>${v.competition || '—'}</td>
            <td>${v.home} – ${v.away}</td>
            <td>${score || v.status}</td>
            <td>${pct(p.pHome)}</td>
            <td>${pct(p.pDraw)}</td>
            <td>${pct(p.pAway)}</td>
            <td class="pick">${PICK_LABEL[pickOf(p)]}</td>
            <td>${p.expectedHome == null ? '—' : num(p.expectedHome) + ' : ' + num(p.expectedAway)}</td>
            <td>${p.pOver == null ? '—' : pct(p.pOver) + ' (' + p.ouLine + ')'}</td>
            <td>${pct(p.confidence)}</td>
        </tr>`;
    });
    $('upcomingResults').innerHTML = table(
        ['מועד', 'מפעל', 'משחק', 'תוצאה', '1', 'X', '2', 'המלצה', 'צפי', 'מעל', 'ביטחון'], rows);
}));

function pickOf(p) {
    if (p.pHome >= p.pDraw && p.pHome >= p.pAway) {
        return 'HOME';
    }
    return p.pAway >= p.pDraw ? 'AWAY' : 'DRAW';
}

// ---------- Ratings ----------

$('loadRatings').addEventListener('click', () => run($('loadRatings'), 'ratingsResults', async () => {
    const list = await api('/api/ratings?sport=' + $('ratingsSport').value);
    const max = list.length ? Math.max(...list.map((r) => r.elo)) : 1;
    const rows = list.map((r, i) => `<tr>
        <td>${i + 1}</td>
        <td>${r.team}</td>
        <td>${r.elo}</td>
        <td><span class="bar" style="width:${Math.max(4, (r.elo / max) * 120)}px"></span></td>
        <td>${r.matches}</td>
        <td>${num(r.scored)}</td>
        <td>${num(r.conceded)}</td>
    </tr>`);
    $('ratingsResults').innerHTML = table(['#', 'קבוצה', 'Elo', '', 'משחקים', 'כבש', 'ספג'], rows);
}));

// ---------- Backtest ----------

$('runBacktest').addEventListener('click', () => run($('runBacktest'), 'backtestResults', async () => {
    $('backtestResults').innerHTML = '<div class="meta">מריץ אימונים חוזרים על ההיסטוריה, זה לוקח זמן...</div>';
    const r = await api(`/api/backtest/football?historyDays=${$('btHistory').value}&stepDays=${$('btStep').value}`);
    const metrics = table(['מדד', 'מודל', 'בסיס'], [
        `<tr><td>log-loss (נמוך = טוב)</td><td>${num(r.logLoss, 4)}</td><td>${num(r.baselineLogLoss, 4)}</td></tr>`,
        `<tr><td>Brier</td><td>${num(r.brier, 4)}</td><td>${num(r.baselineBrier, 4)}</td></tr>`,
        `<tr><td>אחוז פגיעה</td><td>${pct(r.accuracy)}</td><td>${pct(r.baselineAccuracy)}</td></tr>`
    ]);
    const calibration = table(['טווח חיזוי', 'מספר משחקים', 'חיזוי ממוצע', 'פגיעה בפועל'],
        r.calibration.map((b) => `<tr>
            <td>${pct(b.predictedLow)}–${pct(b.predictedHigh)}</td>
            <td>${b.count}</td>
            <td>${pct(b.predicted)}</td>
            <td>${pct(b.actual)}</td>
        </tr>`));
    $('backtestResults').innerHTML =
        `<div class="meta">אומן על ${r.trainMatches} משחקים, נבדק על ${r.testMatches} (${r.refits} אימונים חוזרים)</div>`
        + metrics + '<h3>כיול (על ההמלצה של המודל)</h3>' + calibration;
}));

// ---------- Admin ----------

$('adminToken').value = localStorage.getItem('adminToken') || '';

$('saveToken').addEventListener('click', () => {
    localStorage.setItem('adminToken', $('adminToken').value);
    $('adminResults').innerHTML = '<div class="meta">נשמר בדפדפן.</div>';
});

function showJson(data) {
    $('adminResults').innerHTML = `<pre>${JSON.stringify(data, null, 2)}</pre>`;
}

$('runIngest').addEventListener('click', () => run($('runIngest'), 'adminResults', async () => {
    showJson(await api('/api/admin/ingest', {method: 'POST'}));
}));

$('runLearn').addEventListener('click', () => run($('runLearn'), 'adminResults', async () => {
    showJson(await api('/api/admin/learn', {method: 'POST'}));
}));

$('runRetrain').addEventListener('click', () => run($('runRetrain'), 'adminResults', async () => {
    showJson(await api('/api/admin/retrain', {method: 'POST'}));
}));

$('loadRuns').addEventListener('click', () => run($('loadRuns'), 'adminResults', async () => {
    const runs = await api('/api/admin/runs');
    $('adminResults').innerHTML = table(
        ['ספק', 'ספורט', 'מ־', 'עד', 'בקשות', 'רשומות', 'חדש', 'עודכן', 'מתי', 'שגיאה'],
        runs.map((r) => `<tr>
            <td>${r.provider}</td><td>${r.sport}</td><td>${r.from}</td><td>${r.to}</td>
            <td>${r.requests}</td><td>${r.records}</td><td>${r.created}</td><td>${r.updated}</td>
            <td>${when(r.startedAt)}</td><td class="warn">${r.error || ''}</td>
        </tr>`));
}));

$('saveAlias').addEventListener('click', () => run($('saveAlias'), 'adminResults', async () => {
    const body = {
        rawName: $('aliasRaw').value.trim(),
        teamName: $('aliasTeam').value.trim(),
        sport: $('aliasSport').value
    };
    if (!body.rawName || !body.teamName) {
        throw new Error('מלא את שני השדות');
    }
    showJson(await api('/api/admin/alias', {method: 'POST', body: JSON.stringify(body)}));
}));
