'use strict';

// ─── CAC 40 constituents ──────────────────────────────────────────────────────
const CAC40 = new Set([
  'AI.PA','AIR.PA','ALO.PA','CS.PA','BNP.PA','EN.PA',
  'CAP.PA','CA.PA','ACA.PA','BN.PA','DSY.PA','EDEN.PA',
  'EL.PA','RMS.PA','KER.PA','OR.PA','LR.PA','MC.PA',
  'ML.PA','ORA.PA','RI.PA','PUB.PA','RNO.PA','SAF.PA',
  'SGO.PA','SAN.PA','SU.PA','GLE.PA','STM.PA','HO.PA',
  'TTE.PA','VIE.PA','DG.PA','URW.AS'
]);

// ─── State ────────────────────────────────────────────────────────────────────
let allRecs        = [];
let fundamentalMap = {};   // symbol → FundamentalData (cached)
let currentFilter      = 'ALL';
let currentFundFilter  = 'ALL';  // ALL | UNDERVALUED | FAIRLY_VALUED | OVERVALUED
let chartInstance = null;
let countdownSec  = 300;

// ─── Init ─────────────────────────────────────────────────────────────────────
document.addEventListener('DOMContentLoaded', () => {
  setupFilters();
  setupFundFilters();
  refresh();
  startCountdown();
  // Auto-refresh every 5 minutes
  setInterval(refresh, 5 * 60 * 1000);
});

// ─── Data fetching ────────────────────────────────────────────────────────────
async function refresh() {
  countdownSec = 300;
  try {
    const [recsRes, fundRes] = await Promise.all([
      fetch('/api/recommendations'),
      fetch('/api/fundamentals')
    ]);
    allRecs = await recsRes.json();
    const fundList = await fundRes.json();
    fundamentalMap = {};
    fundList.forEach(f => { fundamentalMap[f.symbol] = f; });
    renderCards();
    updateFundCounts();
    if (currentFilter === 'CAC40') fetchCAC40Index();
    document.getElementById('last-update').textContent =
      'MAJ: ' + new Date().toLocaleTimeString('fr-FR');
  } catch (e) {
    console.error('Erreur fetch recommendations:', e);
  }
}

// ─── Rendering ────────────────────────────────────────────────────────────────
function renderCards() {
  const grid    = document.getElementById('grid');
  const loading = document.getElementById('loading');
  const empty   = document.getElementById('empty');
  const banner  = document.getElementById('cac40-banner');

  loading.style.display = 'none';

  // Technical signal filter
  let filtered;
  if      (currentFilter === 'ALL')   filtered = allRecs;
  else if (currentFilter === 'CAC40') filtered = allRecs.filter(r => CAC40.has(r.symbol));
  else                                filtered = allRecs.filter(r => r.signal === currentFilter);

  // Fundamental valuation filter
  if (currentFundFilter !== 'ALL') {
    filtered = filtered.filter(r => {
      const fd = fundamentalMap[r.symbol];
      return fd && fd.verdict === currentFundFilter;
    });
  }

  // CAC 40 banner
  banner.style.display = currentFilter === 'CAC40' ? 'block' : 'none';
  if (currentFilter === 'CAC40') renderCAC40Stats(filtered);

  empty.style.display = filtered.length === 0 ? 'block' : 'none';
  grid.innerHTML = '';

  const order = { BUY: 0, HOLD: 1, SELL: 2 };
  filtered
    .sort((a, b) => (order[a.signal] - order[b.signal]) || (b.score - a.score))
    .forEach(rec => grid.appendChild(buildCard(rec)));
}

function reasonIcon(reason) {
  const r = reason.toLowerCase();
  if (r.includes('rsi') && r.includes('survendu')) return '🟢';
  if (r.includes('rsi') && r.includes('suracheté')) return '🔴';
  if (r.includes('rsi') && r.includes('neutre')) return '🟡';
  if (r.includes('macd') && (r.includes('haussier') || r.includes('> 0') || r.includes('positif'))) return '📈';
  if (r.includes('macd') && (r.includes('baissier') || r.includes('< 0') || r.includes('négatif'))) return '📉';
  if (r.includes('au-dessus') || r.includes('au dessus') || r.includes('sma') && r.includes('positif')) return '📈';
  if (r.includes('sous') && r.includes('sma')) return '⚠️';
  if (r.includes('bande basse')) return '⬇️';
  if (r.includes('bande haute')) return '⬆️';
  if (r.includes('volume') && r.includes('fort')) return '📊';
  if (r.includes('volume') && r.includes('faible')) return '😴';
  if (r.includes('croisement') || r.includes('golden')) return '✨';
  return '•';
}

function buildCard(rec) {
  const el = document.createElement('div');
  el.className = `card ${rec.signal}`;

  const scoreColor = rec.score >= 65 ? 'var(--buy)'
                   : rec.score <= 35 ? 'var(--sell)'
                   : 'var(--hold)';

  const rsiClass = rec.rsi < 30 ? 'green' : rec.rsi > 70 ? 'red' : 'muted';
  const macdArrow = rec.macdHistogram > 0 ? '▲' : rec.macdHistogram < 0 ? '▼' : '—';
  const macdClass = rec.macdHistogram > 0 ? 'green' : rec.macdHistogram < 0 ? 'red' : 'muted';
  const bollPos   = Math.round((rec.bollingerPosition ?? 0.5) * 100);
  const bollClass = bollPos < 25 ? 'green' : bollPos > 75 ? 'red' : 'muted';
  const isCac     = CAC40.has(rec.symbol);

  // Trend: 3 signals — price vs SMA20, SMA20 vs SMA50, MACD histogram
  const trendBull = (rec.currentPrice > rec.sma20 ? 1 : 0)
                  + (rec.sma20 > rec.sma50 ? 1 : 0)
                  + (rec.macdHistogram > 0 ? 1 : 0);
  const trendBear = (rec.currentPrice < rec.sma20 ? 1 : 0)
                  + (rec.sma20 < rec.sma50 ? 1 : 0)
                  + (rec.macdHistogram < 0 ? 1 : 0);
  let trendLabel, trendClass, trendTip;
  if (trendBull === 3)      { trendLabel = '📈 Haussière forte';  trendClass = 'trend-bull-strong'; trendTip = 'Prix > SMA20 > SMA50\nMACD positif\n3/3 signaux haussiers'; }
  else if (trendBull === 2) { trendLabel = '↗ Haussière';         trendClass = 'trend-bull';        trendTip = '2/3 signaux haussiers'; }
  else if (trendBear === 3) { trendLabel = '📉 Baissière forte';  trendClass = 'trend-bear-strong'; trendTip = 'Prix < SMA20 < SMA50\nMACD négatif\n3/3 signaux baissiers'; }
  else if (trendBear === 2) { trendLabel = '↘ Baissière';         trendClass = 'trend-bear';        trendTip = '2/3 signaux baissiers'; }
  else                      { trendLabel = '➡ Neutre';            trendClass = 'trend-neutral';     trendTip = 'Signaux mixtes\nPas de direction claire'; }

  // Fundamental badge
  const fd = fundamentalMap[rec.symbol];
  const verdictBadge = fd ? {
    UNDERVALUED:  `<span class="valuation-badge undervalued">🟢 Sous-évalué</span>`,
    FAIRLY_VALUED:`<span class="valuation-badge fairly">🟡 Juste valeur</span>`,
    OVERVALUED:   `<span class="valuation-badge overvalued">🔴 Sur-évalué</span>`,
  }[fd.verdict] || '' : '';

  const time = rec.timestamp
    ? new Date(rec.timestamp).toLocaleTimeString('fr-FR', { hour: '2-digit', minute: '2-digit' })
    : '—';

  // ADX regime badge
  let adxBadge = '';
  if (rec.adx != null) {
    const diInfo = rec.plusDI != null && rec.minusDI != null
      ? `\n+DI ${fmt1(rec.plusDI)} / -DI ${fmt1(rec.minusDI)}`
      : '';
    if      (rec.adx < 15) adxBadge = `<span class="adx-badge adx-range"    data-tooltip="ADX ${fmt1(rec.adx)} — Marché en range\nSignaux techniques peu fiables${diInfo}">➡ Range (ADX ${fmt1(rec.adx)})</span>`;
    else if (rec.adx < 25) adxBadge = `<span class="adx-badge adx-weak"     data-tooltip="ADX ${fmt1(rec.adx)} — Tendance faible ou émergente\nAttendre confirmation${diInfo}">〰 Tendance faible (ADX ${fmt1(rec.adx)})</span>`;
    else if (rec.adx < 35) adxBadge = `<span class="adx-badge adx-moderate" data-tooltip="ADX ${fmt1(rec.adx)} — Tendance modérée confirmée\nSignaux fiables${diInfo}">📶 Tendance modérée (ADX ${fmt1(rec.adx)})</span>`;
    else if (rec.adx < 50) adxBadge = `<span class="adx-badge adx-strong"   data-tooltip="ADX ${fmt1(rec.adx)} — Tendance forte\nMomentum solide${diInfo}">🔥 Tendance forte (ADX ${fmt1(rec.adx)})</span>`;
    else                   adxBadge = `<span class="adx-badge adx-extreme"  data-tooltip="ADX ${fmt1(rec.adx)} — Tendance extrême\nPossible retournement imminent${diInfo}">💥 Tendance extrême (ADX ${fmt1(rec.adx)})</span>`;
  }
  let changeHtml = '';
  if (rec.dailyChangePercent != null) {
    const pct   = rec.dailyChangePercent;
    const abs   = rec.previousClose != null ? rec.currentPrice - rec.previousClose : null;
    const sign  = pct >= 0 ? '+' : '';
    const cls   = pct >= 0 ? 'change-up' : 'change-down';
    const arrow = pct >= 0 ? '▲' : '▼';
    const absStr = abs != null ? ` (${sign}${formatPrice(abs)})` : '';
    changeHtml = `<span class="daily-change ${cls}">${arrow} ${sign}${fmt2(pct)}%${absStr}</span>`;
  }

  el.innerHTML = `
    <div class="card-header">
      <div>
        <div class="card-symbol">${rec.symbol}${isCac ? '<span class="cac40-flag" title="CAC 40">🇫🇷</span>' : ''}</div>
        ${rec.stockName ? `<div class="card-name">${rec.stockName}</div>` : ''}
      </div>
      <div class="signal-badge ${rec.signal}">${rec.signal}</div>
    </div>

    <div class="card-badges-row">
      ${verdictBadge ? verdictBadge : ''}
      <span class="trend-badge ${trendClass}" data-tooltip="${trendTip}">${trendLabel}</span>
      ${adxBadge}
    </div>

    <div class="card-price">${formatPrice(rec.currentPrice)} ${changeHtml}</div>

    <div class="score-row">
      <div class="score-bar-bg">
        <div class="score-bar" style="width:${rec.score}%;background:${scoreColor}"></div>
      </div>
      <div class="score-label">${rec.score}/100</div>
    </div>

    <div class="metrics">
      <div class="metric">
        <div class="metric-label">RSI (14)</div>
        <div class="metric-value ${rsiClass}" data-tooltip="${rec.rsi < 30 ? 'Survente — signal potentiel BUY' : rec.rsi > 70 ? 'Surachat — signal potentiel SELL' : 'Zone neutre (30–70)'}">${fmt1(rec.rsi)}</div>
      </div>
      <div class="metric">
        <div class="metric-label">SMA20</div>
        <div class="metric-value" data-tooltip="Moyenne mobile 20 jours\nSupport/résistance court terme">${formatPrice(rec.sma20)}</div>
      </div>
      <div class="metric">
        <div class="metric-label">MACD</div>
        <div class="metric-value ${macdClass}" data-tooltip="Histogramme MACD (12,26,9)\n${rec.macdHistogram > 0 ? 'Positif → momentum haussier' : rec.macdHistogram < 0 ? 'Négatif → momentum baissier' : 'Neutre'}">${macdArrow} ${fmt4(rec.macdHistogram)}</div>
      </div>
      <div class="metric">
        <div class="metric-label">SMA50</div>
        <div class="metric-value" data-tooltip="Moyenne mobile 50 jours\nTendance long terme">${formatPrice(rec.sma50)}</div>
      </div>
    </div>

    <div class="bollinger-row">
      <div class="bollinger-label" data-tooltip="Bandes de Bollinger (20j, ±2σ)\n0% = bande basse | 100% = bande haute\n${bollPos < 25 ? 'Prix proche de la bande basse → survente' : bollPos > 75 ? 'Prix proche de la bande haute → surachat' : 'Prix en zone centrale'}">Bollinger — position <span class="${bollClass}">${bollPos}%</span></div>
      <div class="bollinger-bar-bg">
        <div class="bollinger-marker" style="left:${bollPos}%"></div>
      </div>
    </div>

    ${rec.reasons ? `
    <div class="card-reasons">
      <div class="reasons-title">📋 Analyse</div>
      ${rec.reasons.split(';').map(r => r.trim()).filter(Boolean).map(r => `
        <div class="reason-line">${reasonIcon(r)} ${r}</div>
      `).join('')}
    </div>` : ''}

    <div class="card-footer">
      <span class="card-time">🕐 ${time}</span>
      <button class="btn-chart" onclick="openChart('${rec.symbol}')">📊 Graphique</button>
      <button class="btn-fundamental" onclick="openFundamental('${rec.symbol}')">🔍 Valorisation</button>
    </div>
  `;
  return el;
}

// ─── Filters (signal technique) ───────────────────────────────────────────────
function setupFilters() {
  document.querySelectorAll('.filter-btn').forEach(btn => {
    btn.addEventListener('click', () => {
      document.querySelectorAll('.filter-btn').forEach(b => b.classList.remove('active'));
      btn.classList.add('active');
      currentFilter = btn.dataset.filter;
      renderCards();
      if (currentFilter === 'CAC40') fetchCAC40Index();
    });
  });
}

// ─── Filters (valorisation fondamentale) ──────────────────────────────────────
function setupFundFilters() {
  document.querySelectorAll('.fund-filter-btn').forEach(btn => {
    btn.addEventListener('click', () => {
      document.querySelectorAll('.fund-filter-btn').forEach(b => b.classList.remove('active'));
      btn.classList.add('active');
      currentFundFilter = btn.dataset.fund;
      renderCards();
    });
  });
}

function updateFundCounts() {
  const counts = { UNDERVALUED: 0, FAIRLY_VALUED: 0, OVERVALUED: 0 };
  Object.values(fundamentalMap).forEach(f => { if (counts[f.verdict] !== undefined) counts[f.verdict]++; });
  const el = id => document.getElementById(id);
  if (el('cnt-under'))  el('cnt-under').textContent  = counts.UNDERVALUED;
  if (el('cnt-fairly')) el('cnt-fairly').textContent = counts.FAIRLY_VALUED;
  if (el('cnt-over'))   el('cnt-over').textContent   = counts.OVERVALUED;
}

// ─── CAC 40 banner ────────────────────────────────────────────────────────────
function renderCAC40Stats(recs) {
  const total = recs.length || 1;
  const buy   = recs.filter(r => r.signal === 'BUY').length;
  const hold  = recs.filter(r => r.signal === 'HOLD').length;
  const sell  = recs.filter(r => r.signal === 'SELL').length;
  const avg   = total > 0 ? Math.round(recs.reduce((s, r) => s + r.score, 0) / total) : 0;

  document.getElementById('cnt-buy').textContent  = buy;
  document.getElementById('cnt-hold').textContent = hold;
  document.getElementById('cnt-sell').textContent = sell;
  document.getElementById('bar-buy').style.width   = `${buy  / total * 100}%`;
  document.getElementById('bar-hold').style.width  = `${hold / total * 100}%`;
  document.getElementById('bar-sell').style.width  = `${sell / total * 100}%`;
  document.getElementById('avg-score').textContent = avg + '/100';
  document.getElementById('avg-label').textContent = `sur ${recs.length} valeurs`;
}

async function fetchCAC40Index() {
  try {
    const res  = await fetch('/api/indices/FCHI');
    if (!res.ok) return;
    const data = await res.json();

    const price  = data.price?.toLocaleString('fr-FR', { minimumFractionDigits: 2 }) ?? '—';
    const pct    = data.changePercent;

    document.getElementById('idx-price').textContent = price;
    const chEl = document.getElementById('idx-change');
    if (pct == null) {
      chEl.textContent = '—';
      chEl.className   = 'index-change';
    } else {
      const sign  = pct >= 0 ? '+' : '';
      const arrow = pct >= 0 ? '▲' : '▼';
      chEl.textContent = `${arrow} ${sign}${pct.toFixed(2)}%`;
      chEl.className   = `index-change ${pct >= 0 ? 'up' : 'down'}`;
    }
  } catch (e) {
    console.warn('CAC 40 index fetch failed:', e);
  }
}

// ─── Add stock ────────────────────────────────────────────────────────────────
async function addStock() {
  const symbol = document.getElementById('inp-symbol').value.trim().toUpperCase();
  const name   = document.getElementById('inp-name').value.trim();
  if (!symbol) return alert('Veuillez saisir un symbole.');

  try {
    const res = await fetch('/api/stocks', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ symbol, name })
    });
    if (res.status === 201) {
      document.getElementById('inp-symbol').value = '';
      document.getElementById('inp-name').value   = '';
      await refresh();
    } else if (res.status === 409) {
      alert(`${symbol} est déjà dans la liste.`);
    } else {
      alert('Erreur lors de l\'ajout.');
    }
  } catch (e) {
    alert('Impossible de contacter le serveur.');
  }
}

// ─── Chart modal ──────────────────────────────────────────────────────────────
async function openChart(symbol) {
  const modal = document.getElementById('modal');
  document.getElementById('modal-symbol').textContent = symbol;
  document.getElementById('modal-name').textContent   = '';
  document.getElementById('modal-metrics').innerHTML  = '<span style="color:var(--muted)">Chargement…</span>';

  const rec = allRecs.find(r => r.symbol === symbol);
  if (rec) {
    document.getElementById('modal-badge').className   = `signal-badge ${rec.signal}`;
    document.getElementById('modal-badge').textContent = rec.signal;
  }

  modal.classList.add('open');

  try {
    const res    = await fetch(`/api/stocks/${symbol}/quotes?limit=60`);
    const quotes = (await res.json()).reverse(); // chronological

    const dates  = quotes.map(q => q.date);
    const closes = quotes.map(q => q.close);

    const sma20 = computeSMA(closes, 20);
    const sma50 = computeSMA(closes, 50);
    const { upper, lower } = computeBollinger(closes, 20);

    renderChart(dates, closes, sma20, sma50, upper, lower);
    renderModalMetrics(rec);
  } catch (e) {
    console.error('Erreur chargement graphique:', e);
  }
}

function renderChart(dates, closes, sma20, sma50, upper, lower) {
  const canvas = document.getElementById('price-chart');
  if (chartInstance) { chartInstance.destroy(); chartInstance = null; }

  chartInstance = new Chart(canvas, {
    type: 'line',
    data: {
      labels: dates,
      datasets: [
        {
          label: 'Bollinger Sup',
          data: upper,
          borderColor: 'rgba(34,197,94,.4)',
          borderDash: [4, 4],
          pointRadius: 0,
          fill: '+1',
          backgroundColor: 'rgba(34,197,94,.07)',
          borderWidth: 1,
        },
        {
          label: 'Bollinger Inf',
          data: lower,
          borderColor: 'rgba(34,197,94,.4)',
          borderDash: [4, 4],
          pointRadius: 0,
          fill: false,
          borderWidth: 1,
        },
        {
          label: 'SMA50',
          data: sma50,
          borderColor: '#f85149',
          borderWidth: 1.5,
          pointRadius: 0,
          fill: false,
        },
        {
          label: 'SMA20',
          data: sma20,
          borderColor: '#f0883e',
          borderWidth: 1.5,
          pointRadius: 0,
          fill: false,
        },
        {
          label: 'Clôture',
          data: closes,
          borderColor: '#58a6ff',
          borderWidth: 2,
          pointRadius: 0,
          fill: false,
        },
      ]
    },
    options: {
      responsive: true,
      interaction: { mode: 'index', intersect: false },
      plugins: {
        legend: { labels: { color: '#8b949e', boxWidth: 14, font: { size: 11 } } },
        tooltip: { backgroundColor: '#161b22', borderColor: '#30363d', borderWidth: 1 }
      },
      scales: {
        x: { ticks: { color: '#8b949e', maxTicksLimit: 8 }, grid: { color: '#21262d' } },
        y: { ticks: { color: '#8b949e' }, grid: { color: '#21262d' } }
      }
    }
  });
}

function renderModalMetrics(rec) {
  if (!rec) { document.getElementById('modal-metrics').innerHTML = ''; return; }
  const items = [
    { label: 'Prix',        value: formatPrice(rec.currentPrice) },
    { label: 'Score',       value: `${rec.score}/100` },
    { label: 'RSI (14)',    value: fmt1(rec.rsi),         cls: rec.rsi < 30 ? 'green' : rec.rsi > 70 ? 'red' : '' },
    { label: 'SMA 20',      value: formatPrice(rec.sma20) },
    { label: 'SMA 50',      value: formatPrice(rec.sma50) },
    { label: 'MACD ligne',  value: fmt4(rec.macdLine),    cls: rec.macdLine > 0 ? 'green' : 'red' },
    { label: 'MACD signal', value: fmt4(rec.macdSignal) },
    { label: 'Histogramme', value: fmt4(rec.macdHistogram), cls: rec.macdHistogram > 0 ? 'green' : 'red' },
    { label: 'Boll. sup.',  value: formatPrice(rec.bollingerUpper) },
    { label: 'Boll. inf.',  value: formatPrice(rec.bollingerLower) },
  ];
  document.getElementById('modal-metrics').innerHTML = items.map(it => `
    <div class="metric">
      <div class="metric-label">${it.label}</div>
      <div class="metric-value ${it.cls || ''}">${it.value ?? '—'}</div>
    </div>
  `).join('');
  if (rec.reasons) {
    document.getElementById('modal-metrics').innerHTML +=
      `<div class="metric" style="grid-column:1/-1">
        <div class="metric-label">Analyse</div>
        <div style="font-size:12px;color:var(--muted);margin-top:4px">${rec.reasons}</div>
      </div>`;
  }
}

function closeChart() {
  document.getElementById('modal').classList.remove('open');
  if (chartInstance) { chartInstance.destroy(); chartInstance = null; }
}
function closeModal(e) {
  if (e.target.id === 'modal') closeChart();
}
document.addEventListener('keydown', e => { if (e.key === 'Escape') { closeChart(); closeFundamental(); } });

// ─── Fundamental modal ────────────────────────────────────────────────────────
async function openFundamental(symbol) {
  const modal = document.getElementById('fundamental-modal');
  const body  = document.getElementById('fundamental-body');
  document.getElementById('fundamental-title').textContent = symbol;
  body.innerHTML = '<div class="fund-loading">⏳ Chargement…</div>';
  modal.classList.add('open');

  try {
    const res = await fetch(`/api/fundamentals/${symbol}`);
    if (res.status === 503) {
      body.innerHTML = `<div class="fund-error">🔑 Clé Alpha Vantage non configurée.<br>
        Ajoutez <code>market.datasource.alphavantage.api-key=VOTRE_CLE</code> dans <code>application.properties</code>.<br>
        <a href="https://www.alphavantage.co/support/#api-key" target="_blank">Obtenir une clé gratuite</a></div>`;
      return;
    }
    if (!res.ok) {
      body.innerHTML = `<div class="fund-error">❌ Données indisponibles — Yahoo Finance inaccessible depuis ce serveur.<br>
        <small>Vérifiez la connectivité : <a href="/api/fundamentals/health" target="_blank">/api/fundamentals/health</a></small></div>`;
      return;
    }
    const fd = await res.json();
    const staleHeader = res.headers.get('X-Data-Stale');
    const staleDate   = res.headers.get('X-Data-FetchedAt');
    let banner = '';
    if (staleHeader === 'true' && staleDate) {
      const d = new Date(staleDate);
      banner = `<div class="fund-stale">⚠️ Données du ${d.toLocaleDateString('fr-FR')} (Yahoo Finance indisponible — données en cache)</div>`;
    } else if (fd.dataSource === 'YAHOO_QUOTE') {
      banner = `<div class="fund-stale" style="color:#a3c9f0">ℹ️ Source : Yahoo Finance (données partielles — P/E, P/B, Beta, dividende)</div>`;
    } else if (fd.dataSource === 'ALPHAVANTAGE') {
      banner = `<div class="fund-stale" style="color:#7ec8e3">ℹ️ Source : Alpha Vantage (Yahoo Finance inaccessible depuis ce serveur)</div>`;
    }
    body.innerHTML = banner + buildFundamentalHTML(fd);
  } catch(e) {
    body.innerHTML = `<div class="fund-error">❌ Erreur: ${e.message}</div>`;
  }
}

function closeFundamental() {
  document.getElementById('fundamental-modal').classList.remove('open');
}

function buildFundamentalHTML(fd) {
  const verdictLabel = { UNDERVALUED: '🟢 SOUS-ÉVALUÉ', FAIRLY_VALUED: '🟡 JUSTE VALEUR', OVERVALUED: '🔴 SUR-ÉVALUÉ' };
  const verdictClass = { UNDERVALUED: 'buy', FAIRLY_VALUED: 'hold', OVERVALUED: 'sell' };
  const vc = verdictClass[fd.verdict] || 'hold';
  const vl = verdictLabel[fd.verdict] || fd.verdict;

  const scoreColor = fd.valuationScore >= 65 ? 'var(--buy)' : fd.valuationScore >= 40 ? 'var(--hold)' : 'var(--sell)';

  const fmtPct  = v => v != null ? (v * 100).toFixed(1) + '%' : '—';
  const fmtNum  = v => v != null ? v.toFixed(2) : '—';
  const fmtBig  = v => v != null ? (v >= 1e9 ? (v/1e9).toFixed(1)+'B' : (v/1e6).toFixed(0)+'M') : '—';

  // Analyst consensus
  const totalA = (fd.analystStrongBuy||0) + (fd.analystBuy||0) + (fd.analystHold||0) + (fd.analystSell||0) + (fd.analystStrongSell||0);
  const bullish = (fd.analystStrongBuy||0) + (fd.analystBuy||0);
  const consensusBar = totalA > 0
    ? `<div class="analyst-bar">
        <div class="analyst-seg buy"   style="width:${(bullish/totalA*100).toFixed(0)}%">${bullish > 0 ? bullish+'✓' : ''}</div>
        <div class="analyst-seg hold"  style="width:${((fd.analystHold||0)/totalA*100).toFixed(0)}%">${fd.analystHold > 0 ? fd.analystHold : ''}</div>
        <div class="analyst-seg sell"  style="width:${(((fd.analystSell||0)+(fd.analystStrongSell||0))/totalA*100).toFixed(0)}%">${(fd.analystSell||0)+(fd.analystStrongSell||0) > 0 ? (fd.analystSell||0)+(fd.analystStrongSell||0)+'✗' : ''}</div>
      </div>` : '';

  const reasons = fd.reasons ? fd.reasons.split(';').map(r => r.trim()).filter(Boolean)
    .map(r => `<div class="reason-line">• ${r}</div>`).join('') : '';

  const fetchDate = fd.fetchedAt ? new Date(fd.fetchedAt).toLocaleDateString('fr-FR') : '—';

  return `
    <div class="fund-verdict-row">
      <div class="signal-badge ${vc}">${vl}</div>
      <div class="fund-score-wrap">
        <div class="score-bar-bg" style="width:160px">
          <div class="score-bar" style="width:${fd.valuationScore}%;background:${scoreColor}"></div>
        </div>
        <span class="score-label">${fd.valuationScore}/100</span>
      </div>
    </div>

    ${fd.sector ? `<div class="fund-sector">${fd.sector} · ${fd.industry || ''}</div>` : ''}

    <div class="fund-grid">
      <div class="fund-section">
        <div class="fund-section-title">📊 Valorisation</div>
        <div class="fund-row"><span>P/E (trailing)</span><strong>${fmtNum(fd.peRatio)}</strong></div>
        <div class="fund-row"><span>P/E (forward)</span><strong>${fmtNum(fd.forwardPE)}</strong></div>
        <div class="fund-row"><span>PEG ratio</span><strong>${fmtNum(fd.pegRatio)}</strong></div>
        <div class="fund-row"><span>Prix / Valeur comptable</span><strong>${fmtNum(fd.priceToBook)}</strong></div>
        <div class="fund-row"><span>Prix / Ventes</span><strong>${fmtNum(fd.priceToSales)}</strong></div>
        <div class="fund-row"><span>VE / EBITDA</span><strong>${fmtNum(fd.evToEbitda)}</strong></div>
      </div>
      <div class="fund-section">
        <div class="fund-section-title">💹 Rentabilité</div>
        <div class="fund-row"><span>Marge nette</span><strong>${fmtPct(fd.profitMargin)}</strong></div>
        <div class="fund-row"><span>Marge opérationnelle</span><strong>${fmtPct(fd.operatingMargin)}</strong></div>
        <div class="fund-row"><span>ROE</span><strong>${fmtPct(fd.returnOnEquity)}</strong></div>
        <div class="fund-row"><span>ROA</span><strong>${fmtPct(fd.returnOnAssets)}</strong></div>
      </div>
      <div class="fund-section">
        <div class="fund-section-title">🚀 Croissance (YoY)</div>
        <div class="fund-row"><span>Bénéfices</span><strong>${fmtPct(fd.earningsGrowth)}</strong></div>
        <div class="fund-row"><span>Chiffre d'affaires</span><strong>${fmtPct(fd.revenueGrowth)}</strong></div>
      </div>
      <div class="fund-section">
        <div class="fund-section-title">📌 Autres</div>
        <div class="fund-row"><span>Bêta</span><strong>${fmtNum(fd.beta)}</strong></div>
        <div class="fund-row"><span>Rendement dividende</span><strong>${fmtPct(fd.dividendYield)}</strong></div>
        <div class="fund-row"><span>Objectif analystes</span><strong>${fd.analystTargetPrice ? fd.analystTargetPrice + ' $' : '—'}</strong></div>
        <div class="fund-row"><span>52 sem. haut</span><strong>${fmtNum(fd.weekHigh52)}</strong></div>
        <div class="fund-row"><span>52 sem. bas</span><strong>${fmtNum(fd.weekLow52)}</strong></div>
      </div>
    </div>

    ${totalA > 0 ? `
    <div class="fund-section" style="margin-top:12px">
      <div class="fund-section-title">🏦 Consensus analystes (${totalA} avis)</div>
      ${consensusBar}
      <div class="analyst-labels">
        <span class="green">Achat fort: ${fd.analystStrongBuy||0} · Achat: ${fd.analystBuy||0}</span>
        <span class="muted">Neutre: ${fd.analystHold||0}</span>
        <span class="red">Vente: ${(fd.analystSell||0)+(fd.analystStrongSell||0)}</span>
      </div>
    </div>` : ''}

    ${reasons ? `
    <div class="card-reasons" style="margin-top:12px">
      <div class="reasons-title">📋 Analyse fondamentale</div>
      ${reasons}
    </div>` : ''}

    <div class="fund-footer">Données : Alpha Vantage · ${fetchDate}</div>
  `;
}


// ─── Countdown ────────────────────────────────────────────────────────────────
function startCountdown() {
  setInterval(() => {
    countdownSec = Math.max(0, countdownSec - 1);
    const m = String(Math.floor(countdownSec / 60)).padStart(1, '0');
    const s = String(countdownSec % 60).padStart(2, '0');
    document.getElementById('countdown').textContent = `${m}:${s}`;
  }, 1000);
}

// ─── Technical indicators (client-side for chart) ─────────────────────────────
function computeSMA(closes, period) {
  return closes.map((_, i) => {
    if (i < period - 1) return null;
    const slice = closes.slice(i - period + 1, i + 1);
    return slice.reduce((a, b) => a + b, 0) / period;
  });
}

function computeBollinger(closes, period = 20) {
  const sma = computeSMA(closes, period);
  const upper = [], lower = [];
  closes.forEach((_, i) => {
    if (i < period - 1) { upper.push(null); lower.push(null); return; }
    const slice  = closes.slice(i - period + 1, i + 1);
    const mean   = sma[i];
    const stddev = Math.sqrt(slice.reduce((s, v) => s + (v - mean) ** 2, 0) / period);
    upper.push(mean + 2 * stddev);
    lower.push(mean - 2 * stddev);
  });
  return { upper, lower };
}

// ─── Formatters ───────────────────────────────────────────────────────────────
function formatPrice(v) {
  if (v == null) return '—';
  return v >= 1000 ? v.toFixed(0) : v.toFixed(2);
}
function fmt1(v) { return v != null ? v.toFixed(1) : '—'; }
function fmt2(v) { return v != null ? v.toFixed(2) : '—'; }
function fmt4(v) { return v != null ? v.toFixed(4) : '—'; }
