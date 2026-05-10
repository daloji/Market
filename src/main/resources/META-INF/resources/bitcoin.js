'use strict';

// ── State ──────────────────────────────────────────────────────────────────────
let chart           = null;
let candleSeries    = null;
let priceLines      = [];   // track price lines to remove on next update
let chartSimu       = null;
let candleSeriesSimu = null;
let priceLinesSimu  = [];   // trade level lines on the simu chart
let currentInterval = '1h';
let countdownSec    = 15;
let countdownTimer  = null;
let lastSignal      = null;
let highWaterMark   = null;  // prix max (LONG) ou min (SHORT) atteint pendant le trade

// ── Bootstrap ──────────────────────────────────────────────────────────────────
document.addEventListener('DOMContentLoaded', () => {
  initChart();
  initChartSimu();
  setupTabs();
  setupIntervalButtons();
  loadSignal();
  loadActiveTrade();  // Restore persisted trade on page load
  startCountdown();
});

// ── Chart init ─────────────────────────────────────────────────────────────────
function initChart() {
  const container = document.getElementById('chart-container');
  chart = LightweightCharts.createChart(container, {
    layout: {
      background: { color: '#161b22' },
      textColor:  '#8b949e',
    },
    grid: {
      vertLines: { color: '#21262d' },
      horzLines: { color: '#21262d' },
    },
    crosshair: { mode: LightweightCharts.CrosshairMode.Normal },
    rightPriceScale: {
      borderColor: '#30363d',
      scaleMargins: { top: 0.05, bottom: 0.1 },
    },
    timeScale: {
      borderColor: '#30363d',
      timeVisible: true,
      secondsVisible: false,
    },
    width:  container.clientWidth,
    height: 420,
  });

  candleSeries = chart.addCandlestickSeries({
    upColor:          '#3fb950',
    downColor:        '#f85149',
    borderUpColor:    '#3fb950',
    borderDownColor:  '#f85149',
    wickUpColor:      '#3fb950',
    wickDownColor:    '#f85149',
  });

  // Responsive resize
  const ro = new ResizeObserver(() => chart.applyOptions({ width: container.clientWidth }));
  ro.observe(container);
}

// ── Simu chart init ────────────────────────────────────────────────────────────
function initChartSimu() {
  const container = document.getElementById('chart-container-simu');
  chartSimu = LightweightCharts.createChart(container, {
    layout: {
      background: { color: '#161b22' },
      textColor:  '#8b949e',
    },
    grid: {
      vertLines: { color: '#21262d' },
      horzLines: { color: '#21262d' },
    },
    crosshair: { mode: LightweightCharts.CrosshairMode.Normal },
    rightPriceScale: {
      borderColor: '#30363d',
      scaleMargins: { top: 0.05, bottom: 0.1 },
    },
    timeScale: {
      borderColor: '#30363d',
      timeVisible: true,
      secondsVisible: false,
    },
    width:  container.clientWidth,
    height: 420,
  });

  candleSeriesSimu = chartSimu.addCandlestickSeries({
    upColor:          '#3fb950',
    downColor:        '#f85149',
    borderUpColor:    '#3fb950',
    borderDownColor:  '#f85149',
    wickUpColor:      '#3fb950',
    wickDownColor:    '#f85149',
  });

  const ro2 = new ResizeObserver(() => chartSimu.applyOptions({ width: container.clientWidth }));
  ro2.observe(container);
}

// ── Load signal ────────────────────────────────────────────────────────────────
async function loadSignal() {
  try {
    const res = await fetch('/api/crypto/btc/signal');
    if (!res.ok) throw new Error(res.statusText);
    const s = await res.json();
    lastSignal = s;

    if (s.error) {
      document.getElementById('signal-reasoning').textContent = '⚠️ ' + s.error;
      return;
    }

    updateHeader(s);
    updateSignalCard(s);
    updateLevels(s);
    updateIndicators(s);
    if (s.candles?.length) {
      renderCandles(s.candles);
      addChartAnnotations(s, s.candles);
    }

    // Pré-remplir la simulation avec le signal courant
    if (!document.getElementById('simu-entry').value) {
      document.getElementById('simu-entry').value = s.currentPrice;
    }
    if (s.direction !== 'WAIT') simuSetDir(s.direction);

    // Mettre à jour le tracker live si un trade est ouvert
    if (activeTrade) updateLiveSimu();

    document.getElementById('ts').textContent =
      new Date(s.timestamp).toLocaleTimeString('fr-FR');

    countdownSec = 15;
  } catch (e) {
    console.error('loadSignal error:', e);
  }
}

// ── Header price ───────────────────────────────────────────────────────────────
function updateHeader(s) {
  const el = document.getElementById('live-price');
  el.textContent = '$' + fmt(s.currentPrice);
}

// ── Signal card ────────────────────────────────────────────────────────────────
function updateSignalCard(s) {
  const card = document.getElementById('signal-card');
  const dir  = document.getElementById('signal-direction');
  const conf = document.getElementById('signal-confidence');
  const reas = document.getElementById('signal-reasoning');

  card.className = 'signal-card ' + s.direction;
  dir.className  = 'signal-direction ' + s.direction;
  dir.textContent = s.direction;
  conf.textContent = s.confidence + '%';

  const lines = s.reasoning ? s.reasoning.split(' · ') : [];
  reas.innerHTML = lines.map(l => `<div>• ${l}</div>`).join('');
}

// ── Levels table ───────────────────────────────────────────────────────────────
function updateLevels(s) {
  const isLong = s.direction === 'LONG';

  setText('entry-price', '$' + fmt(s.entryPrice));
  setText('tp1',         '$' + fmt(s.tp1));
  setText('tp2',         '$' + fmt(s.tp2));
  setText('tp3',         '$' + fmt(s.tp3));
  setText('stop-loss',   '$' + fmt(s.stopLoss));
  setText('liquidation', '$' + fmt(s.liquidationPrice));

  setPnl('tp1-pnl', s.tp1PnlPct);
  setPnl('tp2-pnl', s.tp2PnlPct);
  setPnl('tp3-pnl', s.tp3PnlPct);
  setPnl('sl-pnl',  s.slPnlPct);

  setText('atr-val', fmt2(s.atr) + ' $');
}

function setPnl(id, pct) {
  const el = document.getElementById(id);
  if (!el) return;
  const sign = pct >= 0 ? '+' : '';
  el.textContent = sign + pct.toFixed(2) + '%';
  el.className = 'pnl ' + (pct >= 0 ? 'green' : 'red');
}

// ── Indicators ─────────────────────────────────────────────────────────────────
function updateIndicators(s) {
  // RSI
  const rsiPct = Math.min(100, Math.max(0, s.rsi));
  document.getElementById('rsi-needle').style.left = rsiPct + '%';
  setText('rsi-value', s.rsi.toFixed(1));

  // EMA + tendance composite (3 signaux : EMA, MACD, Bollinger position)
  setText('ema9',  fmt(s.ema9));
  setText('ema21', fmt(s.ema21));
  const emaSig = document.getElementById('ema-signal');
  const trendBull = (s.ema9 > s.ema21 ? 1 : 0)
                  + (s.macdHistogram > 0 ? 1 : 0)
                  + (s.bollingerPosition > 0.5 ? 1 : 0);
  const trendBear = (s.ema9 < s.ema21 ? 1 : 0)
                  + (s.macdHistogram < 0 ? 1 : 0)
                  + (s.bollingerPosition < 0.5 ? 1 : 0);
  let trendText, trendClass;
  if      (trendBull === 3) { trendText = '📈 Haussière forte';  trendClass = 'ind-value trend-bull-strong'; }
  else if (trendBull === 2) { trendText = '↗ Haussière';         trendClass = 'ind-value trend-bull'; }
  else if (trendBear === 3) { trendText = '📉 Baissière forte';  trendClass = 'ind-value trend-bear-strong'; }
  else if (trendBear === 2) { trendText = '↘ Baissière';         trendClass = 'ind-value trend-bear'; }
  else                      { trendText = '➡ Neutre';            trendClass = 'ind-value trend-neutral'; }
  emaSig.textContent = trendText;
  emaSig.className   = trendClass;

  // MACD
  setText('macd-line', s.macdLine.toFixed(4));
  setText('macd-sig',  s.macdSignal.toFixed(4));
  const histEl = document.getElementById('macd-hist');
  histEl.textContent = (s.macdHistogram >= 0 ? '+' : '') + s.macdHistogram.toFixed(4);
  histEl.className   = 'bold ' + (s.macdHistogram >= 0 ? 'green' : 'red');

  // Bollinger
  setText('boll-upper', fmt(s.bollingerUpper));
  setText('boll-mid',   fmt(s.bollingerMid));
  setText('boll-lower', fmt(s.bollingerLower));
  const bollPct = Math.min(100, Math.max(0, s.bollingerPosition * 100));
  document.getElementById('boll-pos-needle').style.left = bollPct + '%';
  setText('boll-pos-val', 'Position dans les bandes : ' + bollPct.toFixed(0) + '%');
}

// ── Chart annotations (price lines + entry marker) ────────────────────────────

function clearPriceLines() {
  priceLines.forEach(pl => { try { candleSeries.removePriceLine(pl); } catch(_){} });
  priceLines = [];
}

function addChartAnnotations(s, candles) {
  clearPriceLines();

  const DASHED = 2;   // LightweightCharts.LineStyle.Dashed
  const DOTTED = 1;   // LightweightCharts.LineStyle.Dotted

  // ── Entry ──────────────────────────────────────────────────────────────────
  priceLines.push(candleSeries.createPriceLine({
    price:            s.entryPrice,
    color:            '#58a6ff',
    lineWidth:        2,
    lineStyle:        DASHED,
    axisLabelVisible: true,
    title:            '📌 Entrée',
  }));

  // ── Take Profit lines ──────────────────────────────────────────────────────
  const tpData = [
    [s.tp1, s.tp1PnlPct, 'TP1'],
    [s.tp2, s.tp2PnlPct, 'TP2'],
    [s.tp3, s.tp3PnlPct, 'TP3'],
  ];
  tpData.forEach(([price, pnl, label]) => {
    const sign = pnl >= 0 ? '+' : '';
    priceLines.push(candleSeries.createPriceLine({
      price,
      color:            '#3fb950',
      lineWidth:        1,
      lineStyle:        DASHED,
      axisLabelVisible: true,
      title:            `🎯 ${label}  ${sign}${pnl.toFixed(1)}%`,
    }));
  });

  // ── Stop Loss ──────────────────────────────────────────────────────────────
  priceLines.push(candleSeries.createPriceLine({
    price:            s.stopLoss,
    color:            '#f85149',
    lineWidth:        2,
    lineStyle:        DASHED,
    axisLabelVisible: true,
    title:            `🛑 SL  ${s.slPnlPct.toFixed(1)}%`,
  }));

  // ── Liquidation ────────────────────────────────────────────────────────────
  priceLines.push(candleSeries.createPriceLine({
    price:            s.liquidationPrice,
    color:            '#7d3f3f',
    lineWidth:        1,
    lineStyle:        DOTTED,
    axisLabelVisible: true,
    title:            '💀 Liquidation',
  }));

  // ── Entry arrow marker on last candle ──────────────────────────────────────
  if (s.direction !== 'WAIT' && candles?.length) {
    const last = candles[candles.length - 1];
    candleSeries.setMarkers([{
      time:     last.time,
      position: s.direction === 'LONG' ? 'belowBar' : 'aboveBar',
      color:    s.direction === 'LONG' ? '#3fb950' : '#f85149',
      shape:    s.direction === 'LONG' ? 'arrowUp'  : 'arrowDown',
      text:     `${s.direction}  ${s.confidence}%`,
    }]);
  } else {
    candleSeries.setMarkers([]);
  }
}

// ── Candles ────────────────────────────────────────────────────────────────────
function renderCandles(candles) {
  const data = candles.map(c => ({
    time:  c.time,
    open:  c.open,
    high:  c.high,
    low:   c.low,
    close: c.close,
  }));
  candleSeries.setData(data);
  chart.timeScale().fitContent();
  // Mirror candles on the simu chart
  candleSeriesSimu.setData(data);
  chartSimu.timeScale().fitContent();
  updateSimuChartAnnotations();
}

// ── Interval buttons ───────────────────────────────────────────────────────────
function setupIntervalButtons() {
  document.querySelectorAll('.itvl').forEach(btn => {
    btn.addEventListener('click', async () => {
      document.querySelectorAll('.itvl').forEach(b => b.classList.remove('active'));
      btn.classList.add('active');
      currentInterval = btn.dataset.itvl;
      // Sync simu buttons
      document.querySelectorAll('.itvl-simu').forEach(b => b.classList.toggle('active', b.dataset.itvl === currentInterval));
      await loadCandlesForInterval(currentInterval);
    });
  });

  document.querySelectorAll('.itvl-simu').forEach(btn => {
    btn.addEventListener('click', async () => {
      document.querySelectorAll('.itvl-simu').forEach(b => b.classList.remove('active'));
      btn.classList.add('active');
      currentInterval = btn.dataset.itvl;
      // Sync signal buttons
      document.querySelectorAll('.itvl').forEach(b => b.classList.toggle('active', b.dataset.itvl === currentInterval));
      await loadCandlesForInterval(currentInterval);
    });
  });
}

async function loadCandlesForInterval(interval) {
  try {
    const limit = interval === '1h' ? 100 : interval === '4h' ? 80 : 150;
    const res   = await fetch(`/api/crypto/btc/candles?interval=${interval}&limit=${limit}`);
    if (!res.ok) return;
    const candles = await res.json();
    renderCandles(candles);
    if (lastSignal) addChartAnnotations(lastSignal, candles);
  } catch (e) { console.error(e); }
}

// ── Simu chart annotations (trade levels) ─────────────────────────────────────
function clearSimuAnnotations() {
  priceLinesSimu.forEach(pl => { try { candleSeriesSimu.removePriceLine(pl); } catch(_){} });
  priceLinesSimu = [];
}

function updateSimuChartAnnotations() {
  clearSimuAnnotations();
  if (!activeTrade) return;
  const t = activeTrade;
  const isLong = t.direction === 'LONG';

  const addLine = (price, title, color, style = 0) => {
    priceLinesSimu.push(candleSeriesSimu.createPriceLine({
      price, color, lineWidth: 1, lineStyle: style,
      axisLabelVisible: true, title,
    }));
  };

  addLine(t.entryPrice, '📌 Entrée',     '#58a6ff', 0);
  addLine(t.tp1,        '🎯 TP1',        '#3fb950', 2);
  addLine(t.tp2,        '🎯 TP2',        '#3fb950', 2);
  addLine(t.tp3,        '🎯 TP3',        '#3fb950', 2);
  addLine(t.sl,         '🛑 SL',         '#f85149', 2);
  addLine(t.liq,        '💀 Liquidation','#8b1a1a', 1);
}


function startCountdown() {
  if (countdownTimer) clearInterval(countdownTimer);
  countdownTimer = setInterval(() => {
    countdownSec--;
    document.getElementById('countdown-label').textContent = `${countdownSec}s`;
    if (countdownSec <= 0) {
      countdownSec = 15;
      loadSignal();
      refreshTradeFromBackend();  // Sync P&L from backend every 15 s
    }
  }, 1000);
}

// ── Helpers ────────────────────────────────────────────────────────────────────
function setText(id, val) {
  const el = document.getElementById(id);
  if (el) el.textContent = val;
}

function fmt(n)  { return n?.toLocaleString('fr-FR', { minimumFractionDigits: 2, maximumFractionDigits: 2 }) ?? '—'; }
function fmt2(n) { return n?.toLocaleString('fr-FR', { minimumFractionDigits: 0, maximumFractionDigits: 0 }) ?? '—'; }

// ── Tabs ───────────────────────────────────────────────────────────────────────
function setupTabs() {
  document.querySelectorAll('.tab-btn').forEach(btn => {
    btn.addEventListener('click', () => {
      document.querySelectorAll('.tab-btn').forEach(b => b.classList.remove('active'));
      document.querySelectorAll('.tab-pane').forEach(p => p.classList.remove('active'));
      btn.classList.add('active');
      document.getElementById('tab-' + btn.dataset.tab).classList.add('active');
      // Force resize after tab becomes visible (was display:none → width was 0)
      if (btn.dataset.tab === 'simu') {
        const c = document.getElementById('chart-container-simu');
        chartSimu.applyOptions({ width: c.clientWidth });
        chartSimu.timeScale().fitContent();
      }
    });
  });
}

// ── Simulation (live tracker — persisted in backend) ──────────────────────────
let simuDir      = 'LONG';
let simuFeeRate  = 0.0004;  // 0.04% taker par défaut
let activeTrade  = null;   // TradeDTO from backend
let pnlHistory   = [];     // [{ time, pnl }]
let tradeTimer   = null;

function simuSetFee(rate, type) {
  if (!isNaN(rate) && rate >= 0) simuFeeRate = rate;
  document.getElementById('fee-taker').classList.toggle('active', type === 'taker');
  document.getElementById('fee-maker').classList.toggle('active', type === 'maker');
}

function simuSetDir(dir) {
  simuDir = dir;
  document.getElementById('simu-long').classList.toggle('active',  dir === 'LONG');
  document.getElementById('simu-short').classList.toggle('active', dir === 'SHORT');
}

function simuFillPrice() {
  if (lastSignal?.currentPrice)
    document.getElementById('simu-entry').value = lastSignal.currentPrice;
}

async function simuOpen() {
  const amount   = parseFloat(document.getElementById('simu-amount').value) || 0;
  const entry    = parseFloat(document.getElementById('simu-entry').value)  || 0;
  const leverage = parseInt(document.getElementById('simu-leverage').value) || 10;
  if (!amount || !entry) { alert('Renseigne la mise et le prix d\'entrée.'); return; }

  const atr = lastSignal?.atr || entry * 0.01;

  try {
    const res = await fetch('/api/trades', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ amount, direction: simuDir, leverage, entryPrice: entry, feeRate: simuFeeRate, atr })
    });
    if (!res.ok) throw new Error(await res.text());
    activeTrade = await res.json();
    pnlHistory  = [];
    highWaterMark = null;  // reset trailing stop tracker
    renderActiveTrade();
    startTradeTimers();
  } catch(e) {
    alert('Erreur ouverture trade: ' + e.message);
  }
}

async function simuClose() {
  if (!activeTrade) return;
  try {
    await fetch(`/api/trades/${activeTrade.id}?reason=USER_CLOSED`, { method: 'DELETE' });
  } catch(e) { /* ignore */ }
  stopTradeTimers();
  activeTrade   = null;
  pnlHistory    = [];
  highWaterMark = null;
  clearSimuAnnotations();
  document.getElementById('simu-live').style.display  = 'none';
  document.getElementById('simu-setup').style.display = 'block';
  document.getElementById('exit-alert').style.display  = 'none';
  document.getElementById('trail-alert').style.display = 'none';
}

async function loadActiveTrade() {
  try {
    const res  = await fetch('/api/trades/active');
    const list = await res.json();
    if (list.length > 0) {
      activeTrade = list[0];  // Display most recent open trade
      pnlHistory  = [];
      renderActiveTrade();
      startTradeTimers();
    }
  } catch(e) { /* no active trade */ }
}

async function refreshTradeFromBackend() {
  if (!activeTrade) return;
  try {
    const res = await fetch(`/api/trades/${activeTrade.id}`);
    if (!res.ok) return;
    const updated = await res.json();
    if (updated.status === 'CLOSED') {
      // Trade auto-closed (liquidation)
      stopTradeTimers();
      showLiquidationBanner(updated);
      activeTrade = null;
      pnlHistory  = [];
      document.getElementById('simu-live').style.display  = 'none';
      document.getElementById('simu-setup').style.display = 'block';
      return;
    }
    activeTrade = updated;
    updateLiveSimu();
  } catch(e) { /* ignore */ }
}

function showLiquidationBanner(trade) {
  const el = document.getElementById('exit-alert');
  el.className = 'exit-alert critical';
  el.style.display = 'flex';
  setText('exit-alert-icon',  '💀');
  setText('exit-alert-title', 'LIQUIDATION — Position fermée automatiquement');
  document.getElementById('exit-alert-reasons').innerHTML =
    `<div>• Prix de liquidation atteint : ${fmt(trade.liq)} $</div>` +
    `<div>• Perte nette : ${fmt(trade.pnlNet)} $</div>`;
  setText('exit-price-val', fmt(trade.currentPrice) + ' $');
  const exitPnlEl = document.getElementById('exit-pnl-val');
  exitPnlEl.textContent = fmt(trade.pnlNet) + ' $';
  exitPnlEl.style.color = 'var(--sell)';
}

function renderActiveTrade() {
  const t = activeTrade;
  document.getElementById('simu-setup').style.display = 'none';
  document.getElementById('simu-live').style.display  = 'block';

  const badge = document.getElementById('live-dir-badge');
  badge.textContent = t.direction;
  badge.className   = 'live-dir-badge ' + t.direction;
  setText('live-entry-lbl',  fmt(t.entryPrice) + ' $');
  setText('live-amount-lbl', fmt(t.amount) + ' $');
  setText('live-lev-lbl',    '×' + t.leverage);
  const feePct = (t.feeRate * 100).toFixed(3);
  setText('live-fee-lbl', feePct + '% × 2');
  setText('ll-entry-price',  fmt(t.entryPrice) + ' $');
  setText('ll-tp1-price',    fmt(t.tp1) + ' $');
  setText('ll-tp2-price',    fmt(t.tp2) + ' $');
  setText('ll-tp3-price',    fmt(t.tp3) + ' $');
  setText('ll-sl-price',     fmt(t.sl)  + ' $');
  setText('ll-liq-price',    fmt(t.liq) + ' $');

  updateSimuChartAnnotations();
  updateLiveSimu();
}

function startTradeTimers() {
  if (tradeTimer) clearInterval(tradeTimer);
  tradeTimer = setInterval(() => {
    if (!activeTrade?.openedAt) return;
    const sec = Math.floor((Date.now() - activeTrade.openedAt) / 1000);
    const h   = Math.floor(sec / 3600);
    const m   = Math.floor((sec % 3600) / 60);
    const s   = sec % 60;
    setText('live-duration', (h ? h + 'h ' : '') + (m ? m + 'm ' : '') + s + 's');
  }, 1000);
}

function stopTradeTimers() {
  if (tradeTimer) clearInterval(tradeTimer);
  tradeTimer = null;
}

function updateLiveSimu() {
  if (!activeTrade) return;
  const t = activeTrade;
  const current = t.currentPrice;
  if (!current) return;

  const { entryPrice: entry, amount, leverage, direction: dir,
          tp1, tp2, tp3, sl, liq, feeRate,
          pnlUsd, pnlNet, pnlPct, feesTotal } = t;

  const pnlNetPct = pnlNet / amount * 100;
  const isProfit  = pnlNet >= 0;

  // Big card
  const card = document.getElementById('pnl-big-card');
  card.className = 'pnl-big-card ' + (isProfit ? 'profit' : 'loss');
  const valEl = document.getElementById('pnl-big-value');
  const pctEl = document.getElementById('pnl-big-pct');
  valEl.textContent = (pnlUsd >= 0 ? '+' : '') + fmt(pnlUsd) + ' $ (brut)';
  pctEl.textContent = (pnlPct >= 0 ? '+' : '') + pnlPct.toFixed(2) + '%';
  valEl.className = 'pnl-big-value ' + (isProfit ? 'profit' : 'loss');
  pctEl.className = 'pnl-big-pct '   + (isProfit ? 'profit' : 'loss');
  setText('live-current-price', fmt(current) + ' $');

  // Frais + net
  document.getElementById('pnl-fees').textContent = '−' + fmt(feesTotal) + ' $';
  const netEl = document.getElementById('pnl-net');
  netEl.textContent  = (pnlNet >= 0 ? '+' : '') + fmt(pnlNet) + ' $ (' + (pnlNetPct >= 0 ? '+' : '') + pnlNetPct.toFixed(2) + '%)';
  netEl.style.color  = isProfit ? 'var(--buy)' : 'var(--sell)';

  // Progress bars
  function updateLevel(id, target, good) {
    const totalRange = Math.abs(target - entry);
    const progress   = Math.abs(current - entry);
    const pct        = totalRange > 0 ? Math.min(100, progress / totalRange * 100) : 0;
    const dist       = target - current;
    const distPct    = (dist / current * 100);
    const reached    = good
      ? (dir === 'LONG' ? current >= target : current <= target)
      : (dir === 'LONG' ? current <= target : current >= target);
    document.getElementById(`ll-${id}-bar`).style.width = pct + '%';
    const sign = distPct >= 0 ? '+' : '';
    setText(`ll-${id}-dist`, reached ? '✅ Atteint' : sign + distPct.toFixed(2) + '%');
    const row = document.getElementById(`ll-${id}`);
    if (row) row.classList.toggle(good ? 'hit-tp' : 'hit-sl', reached);
  }

  updateLevel('tp1', tp1, true);
  updateLevel('tp2', tp2, true);
  updateLevel('tp3', tp3, true);
  updateLevel('sl',  sl,  false);

  const liqDist = ((liq - current) / current * 100);
  setText('ll-liq-dist', (liqDist >= 0 ? '+' : '') + liqDist.toFixed(2) + '%');
  const liqProg = Math.min(100, Math.abs(current - entry) / Math.abs(liq - entry) * 100);
  document.getElementById('ll-liq-bar').style.width = liqProg + '%';

  // P&L history + sparkline
  pnlHistory.push({ time: new Date().toLocaleTimeString('fr-FR'), pnl: pnlNet });
  if (pnlHistory.length > 60) pnlHistory.shift();
  setText('sparkline-count', pnlHistory.length + ' pts · max ' + fmt(Math.max(...pnlHistory.map(p=>p.pnl))) + ' $ / min ' + fmt(Math.min(...pnlHistory.map(p=>p.pnl))) + ' $');
  drawSparkline();

  // Reversal check
  checkReversal(current, pnlNet, pnlNetPct);
  // Trailing stop suggestion
  checkTrailingStop(current, entry, tp1, tp2, tp3, sl, dir);
}

// ── Reversal detection ─────────────────────────────────────────────────────────
function checkReversal(currentPrice, pnlNet, pnlNetPct) {
  const alert = document.getElementById('exit-alert');
  if (!activeTrade || !lastSignal) { alert.style.display = 'none'; return; }

  const { dir, feeRate, amount, leverage } = activeTrade;
  const s = lastSignal;
  const reasons = [];
  let score = 0;

  if (dir === 'LONG') {
    // Signals suggesting the uptrend is ending
    if (s.rsi > 75)                        { score += 40; reasons.push(`RSI suracheté (${s.rsi.toFixed(1)}) — pression vendeuse forte`); }
    else if (s.rsi > 70)                   { score += 25; reasons.push(`RSI > 70 (${s.rsi.toFixed(1)}) — zone de surachat`); }
    else if (s.rsi > 65)                   { score += 10; reasons.push(`RSI proche surachat (${s.rsi.toFixed(1)})`); }

    if (s.ema9 < s.ema21)                  { score += 25; reasons.push(`EMA9 (${fmt(s.ema9)}) passe sous EMA21 (${fmt(s.ema21)}) — croisement baissier`); }

    if (s.macdHistogram < 0)               { score += 20; reasons.push(`MACD histogramme négatif (${s.macdHistogram.toFixed(4)}) — momentum baissier`); }
    else if (s.macdHistogram < s.macdLine * 0.1 && s.macdHistogram > 0)
                                           { score += 10; reasons.push(`MACD histogramme en chute — ralentissement haussier`); }

    if (s.bollingerPosition > 0.90)        { score += 20; reasons.push(`Prix en zone surachat Bollinger (${(s.bollingerPosition*100).toFixed(0)}%) — résistance`); }
    else if (s.bollingerPosition > 0.80)   { score += 10; reasons.push(`Prix proche bande haute Bollinger (${(s.bollingerPosition*100).toFixed(0)}%)`); }

    // P&L sparkline: 3 baisses consécutives
    if (pnlHistory.length >= 4) {
      const last4 = pnlHistory.slice(-4).map(p => p.pnl);
      if (last4[3] < last4[2] && last4[2] < last4[1] && last4[1] < last4[0]) {
        score += 15; reasons.push('P&L en baisse depuis 3 rafraîchissements consécutifs');
      }
    }
  } else {
    // SHORT — signals suggesting the downtrend is ending
    if (s.rsi < 25)                        { score += 40; reasons.push(`RSI survendu (${s.rsi.toFixed(1)}) — pression acheteuse forte`); }
    else if (s.rsi < 30)                   { score += 25; reasons.push(`RSI < 30 (${s.rsi.toFixed(1)}) — zone de survente`); }
    else if (s.rsi < 35)                   { score += 10; reasons.push(`RSI proche survente (${s.rsi.toFixed(1)})`); }

    if (s.ema9 > s.ema21)                  { score += 25; reasons.push(`EMA9 (${fmt(s.ema9)}) passe au-dessus de EMA21 (${fmt(s.ema21)}) — croisement haussier`); }

    if (s.macdHistogram > 0)               { score += 20; reasons.push(`MACD histogramme positif (${s.macdHistogram.toFixed(4)}) — momentum haussier`); }

    if (s.bollingerPosition < 0.10)        { score += 20; reasons.push(`Prix en zone survente Bollinger (${(s.bollingerPosition*100).toFixed(0)}%) — support`); }
    else if (s.bollingerPosition < 0.20)   { score += 10; reasons.push(`Prix proche bande basse Bollinger (${(s.bollingerPosition*100).toFixed(0)}%)`); }

    if (pnlHistory.length >= 4) {
      const last4 = pnlHistory.slice(-4).map(p => p.pnl);
      if (last4[3] < last4[2] && last4[2] < last4[1] && last4[1] < last4[0]) {
        score += 15; reasons.push('P&L en baisse depuis 3 rafraîchissements consécutifs');
      }
    }
  }

  // Threshold: 30 = modéré, 50 = élevé, 70 = critique
  if (score < 30) { alert.style.display = 'none'; return; }

  // Suggest exit price (current with 0.05% slippage buffer)
  const slippage  = currentPrice * 0.0005;
  const exitPrice = dir === 'LONG' ? currentPrice - slippage : currentPrice + slippage;

  // Estimated net P&L if exiting now
  const level = score >= 70 ? 'critical' : score >= 50 ? 'high' : 'moderate';
  const icon  = score >= 70 ? '🔴' : score >= 50 ? '🚨' : '⚠️';
  const title = score >= 70
    ? 'Retournement très probable — SORTIE RECOMMANDÉE'
    : score >= 50
    ? 'Fort signal de retournement — envisager la sortie'
    : 'Retournement possible — surveiller de près';

  alert.className = 'exit-alert ' + level;
  alert.style.display = 'flex';
  setText('exit-alert-icon',  icon);
  setText('exit-alert-title', title);
  document.getElementById('exit-alert-reasons').innerHTML =
    reasons.map(r => `<div>• ${r}</div>`).join('');
  setText('exit-price-val', fmt(exitPrice) + ' $');
  const exitPnlEl = document.getElementById('exit-pnl-val');
  exitPnlEl.textContent = (pnlNet >= 0 ? '+' : '') + fmt(pnlNet) + ' $ (' + (pnlNetPct >= 0 ? '+' : '') + pnlNetPct.toFixed(2) + '%)';
  exitPnlEl.style.color = pnlNet >= 0 ? 'var(--buy)' : 'var(--sell)';
}

// ── Trailing stop suggestion ────────────────────────────────────────────────────
function checkTrailingStop(current, entry, tp1, tp2, tp3, sl, dir) {
  const alertEl = document.getElementById('trail-alert');
  if (!activeTrade) { alertEl.style.display = 'none'; highWaterMark = null; return; }

  const { amount, leverage } = activeTrade;

  // Met à jour le prix extrême atteint depuis l'ouverture
  if (highWaterMark === null) highWaterMark = current;
  if (dir === 'LONG')  highWaterMark = Math.max(highWaterMark, current);
  else                 highWaterMark = Math.min(highWaterMark, current);

  // Détermine le meilleur SL suggéré selon le niveau atteint
  let suggestedSL = null;
  let milestone   = '';
  let reason      = '';

  if (dir === 'LONG') {
    if      (highWaterMark >= tp3) {
      suggestedSL = tp2;
      milestone   = '🎯 TP3 atteint';
      reason      = 'Le prix a touché TP3 — remontez le SL à TP2 pour verrouiller le gain maximum.';
    } else if (highWaterMark >= tp2) {
      suggestedSL = tp1;
      milestone   = '🎯 TP2 atteint';
      reason      = 'Le prix a touché TP2 — remontez le SL à TP1 pour sécuriser les profits.';
    } else if (highWaterMark >= tp1) {
      suggestedSL = entry;
      milestone   = '🎯 TP1 atteint';
      reason      = 'Le prix a touché TP1 — remontez le SL au prix d\'entrée (break-even).';
    } else if (highWaterMark >= entry + (tp1 - entry) * 0.60) {
      suggestedSL = entry * 0.9995;  // juste sous l'entrée
      milestone   = '↗ 60% vers TP1';
      reason      = 'Le prix progresse bien — envisagez de remonter le SL près de l\'entrée pour limiter le risque.';
    }
  } else { // SHORT
    if      (highWaterMark <= tp3) {
      suggestedSL = tp2;
      milestone   = '🎯 TP3 atteint';
      reason      = 'Le prix a touché TP3 — descendez le SL à TP2 pour verrouiller le gain maximum.';
    } else if (highWaterMark <= tp2) {
      suggestedSL = tp1;
      milestone   = '🎯 TP2 atteint';
      reason      = 'Le prix a touché TP2 — descendez le SL à TP1 pour sécuriser les profits.';
    } else if (highWaterMark <= tp1) {
      suggestedSL = entry;
      milestone   = '🎯 TP1 atteint';
      reason      = 'Le prix a touché TP1 — descendez le SL au prix d\'entrée (break-even).';
    } else if (highWaterMark <= entry - (entry - tp1) * 0.60) {
      suggestedSL = entry * 1.0005;
      milestone   = '↘ 60% vers TP1';
      reason      = 'Le prix progresse bien — envisagez de descendre le SL près de l\'entrée pour limiter le risque.';
    }
  }

  // N'affiche que si le nouveau SL est vraiment meilleur que l'actuel
  const slImproved = suggestedSL !== null && (dir === 'LONG' ? suggestedSL > sl : suggestedSL < sl);
  if (!slImproved) { alertEl.style.display = 'none'; return; }

  // Calcule le profit protégé si on place le SL suggéré
  const rawGainPct = dir === 'LONG'
    ? (suggestedSL - entry) / entry * 100
    : (entry - suggestedSL) / entry * 100;
  const protectedPct = rawGainPct * leverage;
  const protectedUsd = rawGainPct / 100 * leverage * amount;

  alertEl.style.display = 'flex';
  setText('trail-milestone', milestone);
  setText('trail-reason',    reason);
  setText('trail-sl-val',    fmt(suggestedSL) + ' $');

  const gainEl = document.getElementById('trail-gain-val');
  const sign   = protectedUsd >= 0 ? '+' : '';
  gainEl.textContent = sign + fmt(protectedUsd) + ' $ (' + sign + protectedPct.toFixed(2) + '%)';
  gainEl.style.color = protectedUsd >= 0 ? 'var(--buy)' : 'var(--sell)';
}

function drawSparkline() {
  const canvas = document.getElementById('sparkline');
  if (!canvas || pnlHistory.length < 2) return;
  const ctx  = canvas.getContext('2d');
  const W    = canvas.width  = canvas.offsetWidth;
  const H    = canvas.height = 80;
  ctx.clearRect(0, 0, W, H);

  const vals  = pnlHistory.map(p => p.pnl);
  const min   = Math.min(...vals);
  const max   = Math.max(...vals);
  const range = max - min || 1;

  const toX = i => i / (vals.length - 1) * W;
  const toY = v => H - ((v - min) / range * (H - 10)) - 5;

  // Fill
  ctx.beginPath();
  ctx.moveTo(toX(0), toY(vals[0]));
  vals.forEach((v, i) => ctx.lineTo(toX(i), toY(v)));
  ctx.lineTo(W, H); ctx.lineTo(0, H); ctx.closePath();
  const lastVal = vals[vals.length - 1];
  ctx.fillStyle = lastVal >= 0 ? '#3fb95022' : '#f8514922';
  ctx.fill();

  // Line
  ctx.beginPath();
  ctx.moveTo(toX(0), toY(vals[0]));
  vals.forEach((v, i) => ctx.lineTo(toX(i), toY(v)));
  ctx.strokeStyle = lastVal >= 0 ? '#3fb950' : '#f85149';
  ctx.lineWidth   = 2;
  ctx.stroke();

  // Zero line
  if (min < 0 && max > 0) {
    const y0 = toY(0);
    ctx.beginPath(); ctx.moveTo(0, y0); ctx.lineTo(W, y0);
    ctx.strokeStyle = '#8b949e44'; ctx.lineWidth = 1; ctx.setLineDash([4,4]);
    ctx.stroke(); ctx.setLineDash([]);
  }
}

