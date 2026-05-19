'use strict';

// ── State ──────────────────────────────────────────────────────────────────────
let chart           = null;
let candleSeries    = null;
let priceLines      = [];
let chartSimu       = null;
let candleSeriesSimu = null;
let priceLinesSimu  = [];
let currentInterval = '1h';
let lastSignal      = null;
let highWaterMark   = null;
let refreshTimer    = null;

const REFRESH_INTERVAL_MS = 5000;

// ── Real trade state ───────────────────────────────────────────────────────────
let realTrades      = [];   // active REAL trades
let realTimer       = null;
let realDir         = 'LONG';
let realClosingId   = null; // id of trade being closed via modal

// ── Bootstrap ──────────────────────────────────────────────────────────────────
document.addEventListener('DOMContentLoaded', () => {
  initChart();
  initChartSimu();
  setupTabs();
  setupIntervalButtons();
  loadSignal();
  loadActiveTrade();
  loadRealTrades();
  startAutoRefresh();
});

// ── Auto-refresh every 5 s ────────────────────────────────────────────────────
function startAutoRefresh() {
  if (refreshTimer) clearInterval(refreshTimer);
  refreshTimer = setInterval(async () => {
    await loadSignal();
    await refreshTradeFromBackend();
    await loadRealTrades();
  }, REFRESH_INTERVAL_MS);
}

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
    renderTradeProposals(s);
    if (s.candles?.length) {
      renderCandles(s.candles);
      addChartAnnotations(s, s.candles);
    }

    // Pré-remplir la simulation avec les niveaux du signal
    if (!activeTrade) simuPrefillLevels(s);

    // Mettre à jour le tracker live si un trade est ouvert
    if (activeTrade) updateLiveSimu();

    document.getElementById('ts').textContent =
      new Date(s.timestamp).toLocaleTimeString('fr-FR');

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
  const cur   = s.currentPrice || s.entryPrice;
  const lev   = s.leverage || 10;

  // Update levier label in header
  const levLabel = document.getElementById('lev-label');
  if (levLabel) levLabel.textContent = lev;

  setText('entry-price', '$' + fmt(s.entryPrice));
  setText('tp1',         '$' + fmt(s.tp1));
  setText('tp2',         '$' + fmt(s.tp2));
  setText('tp3',         '$' + fmt(s.tp3));
  setText('stop-loss',   '$' + fmt(s.stopLoss));
  setText('liquidation', '$' + fmt(s.liquidationPrice));

  // Distance from current price (independent of leverage)
  setDist('tp1-dist', s.tp1,              cur);
  setDist('tp2-dist', s.tp2,              cur);
  setDist('tp3-dist', s.tp3,              cur);
  setDist('sl-dist',  s.stopLoss,         cur);
  setDist('liq-dist', s.liquidationPrice, cur);

  setPnl('tp1-pnl', s.tp1PnlPct);
  setPnl('tp2-pnl', s.tp2PnlPct);
  setPnl('tp3-pnl', s.tp3PnlPct);
  setPnl('sl-pnl',  s.slPnlPct);

  setText('atr-val', fmt2(s.atr) + ' $');
}

/** Distance % entre prix courant et un niveau cible */
function setDist(id, target, current) {
  const el = document.getElementById(id);
  if (!el || !target || !current) return;
  const pct  = (target - current) / current * 100;
  const sign = pct >= 0 ? '+' : '';
  el.textContent = sign + pct.toFixed(2) + '%';
  el.className   = pct >= 0 ? 'green' : 'red';
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

  // ADX
  const adxPct = Math.min(100, (s.adx / 60) * 100);
  document.getElementById('adx-needle').style.left = adxPct + '%';
  let adxLabel, adxClass;
  if      (s.adx > 40) { adxLabel = `ADX ${s.adx.toFixed(1)} — Tendance forte`;   adxClass = 'ind-value trend-bull'; }
  else if (s.adx > 22) { adxLabel = `ADX ${s.adx.toFixed(1)} — Tendance modérée`; adxClass = 'ind-value trend-neutral'; }
  else                  { adxLabel = `ADX ${s.adx.toFixed(1)} — Marché en range`;  adxClass = 'ind-value trend-bear'; }
  const adxEl = document.getElementById('adx-value');
  adxEl.textContent = adxLabel;
  adxEl.className   = adxClass;
  setText('plus-di',  s.plusDI  != null ? s.plusDI.toFixed(1)  : '—');
  setText('minus-di', s.minusDI != null ? s.minusDI.toFixed(1) : '—');

  // Stochastic
  const stochPct = Math.min(100, Math.max(0, s.stochK));
  document.getElementById('stoch-needle').style.left = stochPct + '%';
  setText('stoch-k', s.stochK != null ? s.stochK.toFixed(1) : '—');
  setText('stoch-d', s.stochD != null ? s.stochD.toFixed(1) : '—');
  let stochLabel, stochClass;
  if      (s.stochK < 20) { stochLabel = '🟢 Survendu — rebond possible';   stochClass = 'ind-value trend-bull'; }
  else if (s.stochK > 80) { stochLabel = '🔴 Suracheté — correction possible'; stochClass = 'ind-value trend-bear'; }
  else if (s.stochK > s.stochD) { stochLabel = '↗ %K > %D (momentum haussier)'; stochClass = 'ind-value trend-bull'; }
  else                    { stochLabel = '↘ %K < %D (momentum baissier)';    stochClass = 'ind-value trend-bear'; }
  const stochEl = document.getElementById('stoch-signal');
  stochEl.textContent = stochLabel;
  stochEl.className   = stochClass;

  // OBV
  const obvEl    = document.getElementById('obv-direction');
  const obvLabel = document.getElementById('obv-label');
  if (s.obvSlope > 0) {
    obvEl.textContent = '↑ Haussier';
    obvEl.className   = 'ind-value trend-bull';
    obvLabel.textContent = 'Volume confirme la hausse — acheteurs actifs';
  } else if (s.obvSlope < 0) {
    obvEl.textContent = '↓ Baissier';
    obvEl.className   = 'ind-value trend-bear';
    obvLabel.textContent = 'Volume confirme la baisse — vendeurs actifs';
  } else {
    obvEl.textContent = '→ Neutre';
    obvEl.className   = 'ind-value trend-neutral';
    obvLabel.textContent = 'Pas de pression de volume dominante';
  }

  // ── Multi-Timeframe panel ────────────────────────────────────────────────
  // 4h
  const bias4h = s.tf4hBias || 'NEUTRAL';
  const el4h   = document.getElementById('mtf-4h-bias');
  if (el4h) {
    if (bias4h === 'BULL') { el4h.textContent = '▲ BULL'; el4h.style.color = '#4caf50'; }
    else if (bias4h === 'BEAR') { el4h.textContent = '▼ BEAR'; el4h.style.color = '#f44336'; }
    else { el4h.textContent = '→ NEUTRE'; el4h.style.color = '#9e9e9e'; }
    setText('mtf-4h-ema9',  s.tf4hEma9  ? s.tf4hEma9.toFixed(0)  : '—');
    setText('mtf-4h-ema21', s.tf4hEma21 ? s.tf4hEma21.toFixed(0) : '—');
    setText('mtf-4h-rsi',   s.tf4hRsi   ? s.tf4hRsi.toFixed(1)   : '—');
    const sc4h = s.tf4hScore || 0;
    const el4hSc = document.getElementById('mtf-4h-score');
    el4hSc.textContent = (sc4h >= 0 ? '+' : '') + sc4h + ' pts';
    el4hSc.style.color = sc4h > 0 ? '#4caf50' : sc4h < 0 ? '#f44336' : '#9e9e9e';
  }

  // 1h (current signal)
  const el1h = document.getElementById('mtf-1h-signal');
  if (el1h) {
    const dir = s.direction || 'WAIT';
    if (dir === 'LONG')  { el1h.textContent = '▲ LONG';  el1h.style.color = '#4caf50'; }
    else if (dir === 'SHORT') { el1h.textContent = '▼ SHORT'; el1h.style.color = '#f44336'; }
    else { el1h.textContent = '⏳ WAIT'; el1h.style.color = '#9e9e9e'; }
    const confEl = document.getElementById('mtf-1h-conf');
    if (confEl) {
      confEl.textContent = (s.confidence || 0) + '%';
      confEl.style.color = dir === 'LONG' ? '#4caf50' : dir === 'SHORT' ? '#f44336' : '#9e9e9e';
    }
  }

  // 5m
  const mom5m = s.tf5mMomentum || 'NEUTRAL';
  const el5m  = document.getElementById('mtf-5m-momentum');
  if (el5m) {
    if (mom5m === 'UP')   { el5m.textContent = '▲ UP';   el5m.style.color = '#4caf50'; }
    else if (mom5m === 'DOWN') { el5m.textContent = '▼ DOWN'; el5m.style.color = '#f44336'; }
    else { el5m.textContent = '→ NEUTRE'; el5m.style.color = '#9e9e9e'; }
    const hist5m = s.tf5mMacdHist || 0;
    setText('mtf-5m-macd', hist5m.toFixed(2));
    const sc5m = s.tf5mScore || 0;
    const el5mSc = document.getElementById('mtf-5m-score');
    el5mSc.textContent = (sc5m >= 0 ? '+' : '') + sc5m + ' pts';
    el5mSc.style.color = sc5m > 0 ? '#4caf50' : sc5m < 0 ? '#f44336' : '#9e9e9e';
  }

  // Confluence indicator
  const confEl = document.getElementById('mtf-confluence');
  if (confEl && s.tf4hBias) {
    const dir = s.direction || 'WAIT';
    const longConf  = dir === 'LONG'  && bias4h === 'BULL' && mom5m === 'UP';
    const shortConf = dir === 'SHORT' && bias4h === 'BEAR' && mom5m === 'DOWN';
    const partialL  = dir === 'LONG'  && (bias4h === 'BULL' || mom5m === 'UP');
    const partialS  = dir === 'SHORT' && (bias4h === 'BEAR' || mom5m === 'DOWN');
    if (longConf || shortConf) {
      confEl.textContent = '✅ Confluence forte — les 3 TF sont alignés';
      confEl.style.color = '#4caf50';
    } else if (partialL || partialS) {
      confEl.textContent = '⚠️ Confluence partielle — 2 TF sur 3 alignés';
      confEl.style.color = '#f7c948';
    } else {
      confEl.textContent = '❌ Pas de confluence — TF divergents, signal non confirmé';
      confEl.style.color = '#f44336';
    }
  }

  // ── Market Structure panel ───────────────────────────────────────────────
  if (s.marketStructure) {
    const ms = s.marketStructure;
    const badge = document.getElementById('ms-badge');
    if (badge) {
      const labels = {
        BULL_TREND:    { text: '🔵 BULL TREND',    color: '#4caf50', bg: 'rgba(76,175,80,0.15)'  },
        BEAR_TREND:    { text: '🔴 BEAR TREND',    color: '#f44336', bg: 'rgba(244,67,54,0.15)'  },
        BREAKOUT_UP:   { text: '🚀 BREAKOUT ↑',   color: '#4caf50', bg: 'rgba(76,175,80,0.10)'  },
        BREAKOUT_DOWN: { text: '💥 BREAKOUT ↓',   color: '#f44336', bg: 'rgba(244,67,54,0.10)'  },
        CONSOLIDATION: { text: '⚪ CONSOLIDATION', color: '#9e9e9e', bg: 'rgba(158,158,158,0.10)' }
      };
      const lbl = labels[ms] || { text: ms, color: '#9e9e9e', bg: 'transparent' };
      badge.textContent        = lbl.text;
      badge.style.color        = lbl.color;
      badge.style.background   = lbl.bg;
    }
    const descEl = document.getElementById('ms-desc');
    if (descEl) descEl.textContent = s.msDescription || '';

    const scEl = document.getElementById('ms-score');
    if (scEl) {
      const sc = s.msScore || 0;
      scEl.textContent = (sc >= 0 ? '+' : '') + sc + ' pts';
      scEl.style.color = sc > 0 ? '#4caf50' : sc < 0 ? '#f44336' : '#9e9e9e';
    }

    // HH/HL/LH/LL flags
    const flagActive = { background: 'rgba(76,175,80,0.25)',  color: '#4caf50' };
    const flagBear   = { background: 'rgba(244,67,54,0.25)',  color: '#f44336' };
    const flagOff    = { background: 'rgba(255,255,255,0.05)', color: '#555' };
    const setFlag = (id, active, bearStyle) => {
      const el = document.getElementById(id);
      if (!el) return;
      const style = active ? (bearStyle ? flagBear : flagActive) : flagOff;
      el.style.background = style.background;
      el.style.color      = style.color;
    };
    setFlag('ms-hh', s.msHH, false);
    setFlag('ms-hl', s.msHL, false);
    setFlag('ms-lh', s.msLH, true);
    setFlag('ms-ll', s.msLL, true);

    // Support / Resistance
    const resEl = document.getElementById('ms-resistance');
    if (resEl) resEl.textContent = s.msResistance ? '$' + s.msResistance.toLocaleString() : '—';
    const supEl = document.getElementById('ms-support');
    if (supEl) supEl.textContent = s.msSupport ? '$' + s.msSupport.toLocaleString() : '—';
  }

  // ── Futures Volumetrics panel ────────────────────────────────────────────
  // Volume delta
  const vdBadge = document.getElementById('vol-delta-badge');
  if (vdBadge) {
    const vd = s.volumeDeltaTrend || 'NEUTRAL';
    if      (vd === 'POSITIVE') { vdBadge.textContent = '↑ Acheteurs'; vdBadge.style.color = '#4caf50'; }
    else if (vd === 'NEGATIVE') { vdBadge.textContent = '↓ Vendeurs';  vdBadge.style.color = '#f44336'; }
    else                        { vdBadge.textContent = '→ Neutre';     vdBadge.style.color = '#9e9e9e'; }
    const vdVal = document.getElementById('vol-delta-val');
    if (vdVal) vdVal.textContent = s.volumeDelta != null
      ? (s.volumeDelta >= 0 ? '+' : '') + s.volumeDelta.toFixed(2) + ' BTC net'
      : '—';
    const vdSc = document.getElementById('vol-delta-score');
    if (vdSc) {
      const sc = s.volScore != null ? (s.volScore > 0 ? s.volScore : 0) : 0;
      // We show only volume delta contribution (approx)
      vdSc.textContent = vd === 'POSITIVE' ? '+8 pts' : vd === 'NEGATIVE' ? '-8 pts' : '0 pts';
      vdSc.style.color = vd === 'POSITIVE' ? '#4caf50' : vd === 'NEGATIVE' ? '#f44336' : '#9e9e9e';
    }
  }

  // Funding rate
  const frBadge = document.getElementById('funding-badge');
  if (frBadge && s.fundingBias != null) {
    const fb = s.fundingBias;
    const frMap = {
      EXTREME_LONG:    { text: '🔴 Extrême LONG',   color: '#f44336' },
      MODERATE_LONG:   { text: '🟠 Mod. LONG',      color: '#ff9800' },
      NEUTRAL:         { text: '⚪ Neutre',          color: '#9e9e9e' },
      MODERATE_SHORT:  { text: '🟢 Mod. SHORT',     color: '#8bc34a' },
      EXTREME_SHORT:   { text: '🟢 Extrême SHORT',  color: '#4caf50' }
    };
    const frLbl = frMap[fb] || { text: fb, color: '#9e9e9e' };
    frBadge.textContent = frLbl.text;
    frBadge.style.color = frLbl.color;
    const frVal = document.getElementById('funding-val');
    if (frVal) frVal.textContent = s.fundingRate != null
      ? (s.fundingRate >= 0 ? '+' : '') + (s.fundingRate * 100).toFixed(4) + '%'
      : '—';
    const frSc = document.getElementById('funding-score');
    if (frSc) {
      const sc = fb === 'EXTREME_SHORT' ? '+7' : fb === 'MODERATE_SHORT' ? '+3'
               : fb === 'EXTREME_LONG'  ? '-7' : fb === 'MODERATE_LONG'  ? '-3' : '0';
      frSc.textContent = sc + ' pts';
      frSc.style.color = parseInt(sc) > 0 ? '#4caf50' : parseInt(sc) < 0 ? '#f44336' : '#9e9e9e';
    }
  }

  // OI
  const oiEl = document.getElementById('oi-val');
  if (oiEl && s.openInterest) {
    oiEl.textContent = s.openInterest > 1000
      ? (s.openInterest / 1000).toFixed(1) + 'k BTC'
      : s.openInterest.toFixed(0) + ' BTC';
  }

  // Vol total score
  const vtEl = document.getElementById('vol-total');
  if (vtEl && s.volScore != null) {
    const vs = s.volScore;
    vtEl.textContent = 'Score volumétrie total : ' + (vs >= 0 ? '+' : '') + vs + ' pts';
    vtEl.style.color = vs > 0 ? '#4caf50' : vs < 0 ? '#f44336' : '#9e9e9e';
  }

  // ── Volatility Filter panel ──────────────────────────────────────────────
  if (s.volatilityRegime) {
    const regEl = document.getElementById('vf-regime');
    if (regEl) {
      const regMap = {
        EXTREME: { text: '🔴 EXTREME', color: '#f44336' },
        HIGH:    { text: '🟠 HIGH',    color: '#ff9800' },
        NORMAL:  { text: '🟢 NORMAL',  color: '#4caf50' },
        LOW:     { text: '⚪ LOW',     color: '#9e9e9e' }
      };
      const rm = regMap[s.volatilityRegime] || { text: s.volatilityRegime, color: '#9e9e9e' };
      regEl.textContent = rm.text;
      regEl.style.color = rm.color;
    }
    const atrPctEl = document.getElementById('vf-atr-pct');
    if (atrPctEl) atrPctEl.textContent = 'ATR = ' + (s.atrPct || 0).toFixed(2) + '% du prix';

    const vfScEl = document.getElementById('vf-score');
    if (vfScEl) {
      const sc = s.volFilterScore || 0;
      vfScEl.textContent = (sc >= 0 ? '+' : '') + sc + ' pts';
      vfScEl.style.color = sc < 0 ? '#f44336' : '#4caf50';
    }

    // BB state
    const bbEl = document.getElementById('vf-bb-state');
    if (bbEl) {
      const bbMap = {
        SQUEEZE:   { text: '⚠️ SQUEEZE',   color: '#f7c948' },
        NORMAL:    { text: '✅ NORMAL',     color: '#4caf50' },
        EXPANSION: { text: '🔴 EXPANSION',  color: '#f44336' }
      };
      const bm = bbMap[s.bbState] || { text: s.bbState || '—', color: '#9e9e9e' };
      bbEl.textContent = bm.text;
      bbEl.style.color = bm.color;
    }
    const bbWEl = document.getElementById('vf-bb-width');
    if (bbWEl) bbWEl.textContent = 'Width : ' + (s.bbWidth || 0).toFixed(1) + '%';

    // Extreme candle
    const canEl = document.getElementById('vf-candle');
    if (canEl) {
      if (s.extremeCandle) { canEl.textContent = '🚨 EXTRÊME'; canEl.style.color = '#f44336'; }
      else                 { canEl.textContent = '✅ Normale'; canEl.style.color = '#4caf50'; }
    }

    // Filter verdict
    const vdEl = document.getElementById('vf-verdict');
    if (vdEl) {
      const blocked = s.volatilityRegime === 'EXTREME' || s.extremeCandle || s.bbState === 'SQUEEZE';
      if (blocked) {
        const why = s.volatilityRegime === 'EXTREME' ? 'ATR extrême — event violent'
                  : s.extremeCandle ? 'Bougie extrême détectée'
                  : 'Bollinger Squeeze — direction inconnue';
        vdEl.textContent = '🚫 Auto-trade bloqué : ' + why;
        vdEl.style.color = '#f44336';
      } else {
        vdEl.textContent = '✅ Volatilité acceptable — trading autorisé';
        vdEl.style.color = '#4caf50';
      }
    }
  }
}

// ── Trade Proposals ────────────────────────────────────────────────────────────
function renderTradeProposals(s) {
  const entry = s.currentPrice;
  const atr   = s.atr;
  const lev   = s.leverage || 10;

  const p2 = v => Math.round(v * 100) / 100;
  const pnlPct = (e, t, dir) => {
    const move = (t - e) / e;
    return ((dir === 'SHORT' ? -move : move) * lev * 100).toFixed(2);
  };
  const fmtPnl = (e, t, dir) => {
    const v = pnlPct(e, t, dir);
    return (v >= 0 ? '+' : '') + v + '%';
  };

  // ── Indicator synthesis ────────────────────────────────────────────────────
  const indicators = [
    { name: 'RSI',      bull: s.rsi < 40,       bear: s.rsi > 65,           val: s.rsi?.toFixed(1) },
    { name: 'EMA',      bull: s.ema9 > s.ema21,  bear: s.ema9 < s.ema21,    val: s.ema9 > s.ema21 ? '9>21' : '9<21' },
    { name: 'MACD',     bull: s.macdHistogram > 0, bear: s.macdHistogram < 0, val: s.macdHistogram >= 0 ? '+' : '−' },
    { name: 'Bollinger',bull: s.bollingerPosition < 0.30, bear: s.bollingerPosition > 0.70, val: (s.bollingerPosition*100).toFixed(0)+'%' },
    { name: 'Stoch',    bull: s.stochK < 20,     bear: s.stochK > 80,        val: s.stochK?.toFixed(1) },
    { name: 'OBV',      bull: s.obvSlope > 0,    bear: s.obvSlope < 0,       val: s.obvSlope > 0 ? '↑' : '↓' },
    { name: 'ADX',      bull: s.adx > 25 && s.plusDI > s.minusDI, bear: s.adx > 25 && s.minusDI > s.plusDI, val: s.adx?.toFixed(1) },
  ];

  let bullCount = 0, bearCount = 0;
  const grid = document.getElementById('synthesis-grid');
  grid.innerHTML = indicators.map(ind => {
    let cls, arrow;
    if      (ind.bull) { cls = 'syn-bull'; arrow = '▲'; bullCount++; }
    else if (ind.bear) { cls = 'syn-bear'; arrow = '▼'; bearCount++; }
    else               { cls = 'syn-neut'; arrow = '→'; }
    return `<div class="syn-item">
      <span class="syn-name">${ind.name}</span>
      <span class="syn-arrow ${cls}">${arrow}</span>
      <span class="${cls}" style="font-size:11px">${ind.val ?? '—'}</span>
    </div>`;
  }).join('');

  const verdict = document.getElementById('synthesis-verdict');
  const total = bullCount + bearCount;
  if (bullCount >= 5)                   { verdict.textContent = `✅ ${bullCount}/7 indicateurs haussiers — terrain favorable au LONG`;  verdict.className = 'synthesis-verdict verdict-bull'; }
  else if (bearCount >= 5)              { verdict.textContent = `🔻 ${bearCount}/7 indicateurs baissiers — terrain favorable au SHORT`; verdict.className = 'synthesis-verdict verdict-bear'; }
  else if (bullCount > bearCount)       { verdict.textContent = `↗ Légère majorité haussière (${bullCount} vs ${bearCount}) — LONG avec prudence`; verdict.className = 'synthesis-verdict verdict-bull'; }
  else if (bearCount > bullCount)       { verdict.textContent = `↘ Légère majorité baissière (${bearCount} vs ${bullCount}) — SHORT avec prudence`; verdict.className = 'synthesis-verdict verdict-bear'; }
  else                                  { verdict.textContent = `→ Signaux mixtes (${bullCount} haussiers / ${bearCount} baissiers) — Attendre confirmation`; verdict.className = 'synthesis-verdict verdict-neut'; }

  // ── LONG scenario ─────────────────────────────────────────────────────────
  const lLevels = {
    entry, liq: p2(entry * (1 - 1/lev)),
    sl:  p2(entry - atr),
    tp1: p2(entry + atr),
    tp2: p2(entry + 2*atr),
    tp3: p2(entry + 3*atr),
  };
  setText('l-entry', '$' + fmt(lLevels.entry));
  setText('l-tp1',   '$' + fmt(lLevels.tp1));
  setText('l-tp2',   '$' + fmt(lLevels.tp2));
  setText('l-tp3',   '$' + fmt(lLevels.tp3));
  setText('l-sl',    '$' + fmt(lLevels.sl));
  setText('l-liq',   '$' + fmt(lLevels.liq));
  setDist('l-tp1-dist', lLevels.tp1,  entry);
  setDist('l-tp2-dist', lLevels.tp2,  entry);
  setDist('l-tp3-dist', lLevels.tp3,  entry);
  setDist('l-sl-dist',  lLevels.sl,   entry);
  setDist('l-liq-dist', lLevels.liq,  entry);
  setText('l-tp1-pnl', fmtPnl(entry, lLevels.tp1, 'LONG'));
  setText('l-tp2-pnl', fmtPnl(entry, lLevels.tp2, 'LONG'));
  setText('l-tp3-pnl', fmtPnl(entry, lLevels.tp3, 'LONG'));
  setText('l-sl-pnl',  fmtPnl(entry, lLevels.sl,  'LONG'));

  // ── SHORT scenario ────────────────────────────────────────────────────────
  const sLevels = {
    entry, liq: p2(entry * (1 + 1/lev)),
    sl:  p2(entry + atr),
    tp1: p2(entry - atr),
    tp2: p2(entry - 2*atr),
    tp3: p2(entry - 3*atr),
  };
  setText('s-entry', '$' + fmt(sLevels.entry));
  setText('s-tp1',   '$' + fmt(sLevels.tp1));
  setText('s-tp2',   '$' + fmt(sLevels.tp2));
  setText('s-tp3',   '$' + fmt(sLevels.tp3));
  setText('s-sl',    '$' + fmt(sLevels.sl));
  setText('s-liq',   '$' + fmt(sLevels.liq));
  setDist('s-tp1-dist', sLevels.tp1,  entry);
  setDist('s-tp2-dist', sLevels.tp2,  entry);
  setDist('s-tp3-dist', sLevels.tp3,  entry);
  setDist('s-sl-dist',  sLevels.sl,   entry);
  setDist('s-liq-dist', sLevels.liq,  entry);
  setText('s-tp1-pnl', fmtPnl(entry, sLevels.tp1, 'SHORT'));
  setText('s-tp2-pnl', fmtPnl(entry, sLevels.tp2, 'SHORT'));
  setText('s-tp3-pnl', fmtPnl(entry, sLevels.tp3, 'SHORT'));
  setText('s-sl-pnl',  fmtPnl(entry, sLevels.sl,  'SHORT'));


  // ── Badges & highlights ───────────────────────────────────────────────────
  const longCard  = document.getElementById('proposal-long');
  const shortCard = document.getElementById('proposal-short');
  const longBadge = document.getElementById('long-badge');
  const shortBadge= document.getElementById('short-badge');

  longCard.className  = 'proposal-card' + (s.direction === 'LONG'  ? ' recommended-long'  : '');
  shortCard.className = 'proposal-card' + (s.direction === 'SHORT' ? ' recommended-short' : '');

  if (s.direction === 'LONG') {
    longBadge.textContent  = '✅ Recommandé'; longBadge.className  = 'prop-badge badge-recommended';
    shortBadge.textContent = '⛔ Déconseillé'; shortBadge.className = 'prop-badge badge-avoid';
  } else if (s.direction === 'SHORT') {
    shortBadge.textContent = '✅ Recommandé'; shortBadge.className = 'prop-badge badge-recommended';
    longBadge.textContent  = '⛔ Déconseillé'; longBadge.className = 'prop-badge badge-avoid';
  } else {
    longBadge.textContent  = '⏳ Attendre'; longBadge.className  = 'prop-badge badge-wait';
    shortBadge.textContent = '⏳ Attendre'; shortBadge.className = 'prop-badge badge-wait';
  }

  // ── Reason summaries ──────────────────────────────────────────────────────
  const longReasons  = [];
  const shortReasons = [];

  if (s.ema9 > s.ema21)         longReasons.push('EMA haussière'); else shortReasons.push('EMA baissière');
  if (s.rsi < 40)                longReasons.push('RSI survendu');  else if (s.rsi > 65) shortReasons.push('RSI suracheté');
  if (s.macdHistogram > 0)       longReasons.push('MACD positif');  else shortReasons.push('MACD négatif');
  if (s.stochK < 20)             longReasons.push('Stoch survendu'); else if (s.stochK > 80) shortReasons.push('Stoch suracheté');
  if (s.bollingerPosition < 0.3) longReasons.push('Près bande basse'); else if (s.bollingerPosition > 0.7) shortReasons.push('Près bande haute');
  if (s.obvSlope > 0)            longReasons.push('OBV haussier'); else shortReasons.push('OBV baissier');
  if (s.adx < 20)               { longReasons.push('⚠ ADX faible (range)'); shortReasons.push('⚠ ADX faible (range)'); }
  else if (s.adx > 25)          { if (s.plusDI > s.minusDI) longReasons.push(`+DI>${s.minusDI?.toFixed(0)}`); else shortReasons.push(`-DI>${s.plusDI?.toFixed(0)}`); }

  setText('long-reason',  longReasons.length  ? '📌 ' + longReasons.join(' · ')  : 'Peu de signaux haussiers actuellement');
  setText('short-reason', shortReasons.length ? '📌 ' + shortReasons.join(' · ') : 'Peu de signaux baissiers actuellement');
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
      if (btn.dataset.tab === 'scalping') {
        startScalpingRefresh();
      } else {
        stopScalpingRefresh();
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

// ── Auto pre-fill SL / TP from signal analysis ────────────────────────────────
function simuPrefillLevels(s) {
  const price = s.currentPrice;
  if (!price) return;

  const atr = s.atr || price * 0.01;
  const r2  = v => Math.round(v * 100) / 100;

  // Determine direction: use signal, or EMA trend when WAIT
  const dir = s.direction === 'LONG'  ? 'LONG'
            : s.direction === 'SHORT' ? 'SHORT'
            : (s.ema9 > s.ema21 ? 'LONG' : 'SHORT');

  // Always update entry + direction
  document.getElementById('simu-entry').value = price;
  simuSetDir(dir);

  let sl, tp, slHint, tpHint;

  if (dir === 'LONG') {
    // ── SL : EMA21 if it's a close dynamic support, else 1.5×ATR ──
    const slEma  = r2(s.ema21 * 0.9995);   // just below EMA21
    const slBase = r2(price - 1.5 * atr);
    if (s.ema21 < price && s.ema21 > slBase) {
      sl = slEma;
      slHint = 'EMA21 — support dynamique';
    } else {
      sl = slBase;
      slHint = '1.5× ATR sous le prix';
    }

    // ── TP : Bollinger Upper if far enough, else 2×ATR ──
    const distUp = s.bollingerUpper - price;
    if (distUp > 0.8 * atr) {
      tp = r2(s.bollingerUpper);
      tpHint = 'Bollinger Upper — résistance naturelle';
    } else {
      tp = r2(price + 2 * atr);
      tpHint = '2× ATR au-dessus du prix';
    }
  } else { // SHORT
    // ── SL : EMA21 if it's a close dynamic resistance, else 1.5×ATR ──
    const slEma  = r2(s.ema21 * 1.0005);   // just above EMA21
    const slBase = r2(price + 1.5 * atr);
    if (s.ema21 > price && s.ema21 < slBase) {
      sl = slEma;
      slHint = 'EMA21 — résistance dynamique';
    } else {
      sl = slBase;
      slHint = '1.5× ATR au-dessus du prix';
    }

    // ── TP : Bollinger Lower if far enough, else 2×ATR ──
    const distDn = price - s.bollingerLower;
    if (distDn > 0.8 * atr) {
      tp = r2(s.bollingerLower);
      tpHint = 'Bollinger Lower — support naturel';
    } else {
      tp = r2(price - 2 * atr);
      tpHint = '2× ATR sous le prix';
    }
  }

  document.getElementById('simu-sl').value = sl;
  document.getElementById('simu-tp').value = tp;

  // Show reasoning hints
  const slEl = document.getElementById('simu-sl-hint');
  const tpEl = document.getElementById('simu-tp-hint');
  if (slEl) slEl.textContent = '→ ' + slHint;
  if (tpEl) tpEl.textContent = '→ ' + tpHint;

  // R/R ratio
  const rrEl = document.getElementById('simu-rr-hint');
  if (rrEl && sl && tp) {
    const risk   = Math.abs(price - sl);
    const reward = Math.abs(tp - price);
    const rr     = risk > 0 ? (reward / risk).toFixed(2) : '—';
    rrEl.textContent = `R/R : 1 : ${rr}`;
    rrEl.style.color = parseFloat(rr) >= 1.5 ? 'var(--buy)' : parseFloat(rr) >= 1 ? 'var(--hold)' : 'var(--sell)';
  }
}

async function simuOpen() {
  const amount   = parseFloat(document.getElementById('simu-amount').value) || 0;
  const entry    = parseFloat(document.getElementById('simu-entry').value)  || 0;
  const leverage = parseInt(document.getElementById('simu-leverage').value) || 10;
  const customSl = parseFloat(document.getElementById('simu-sl').value) || 0;
  const customTp = parseFloat(document.getElementById('simu-tp').value) || 0;
  if (!amount || !entry) { alert('Renseigne la mise et le prix d\'entrée.'); return; }

  // Validate custom SL/TP direction consistency
  if (customSl > 0 && simuDir === 'LONG'  && customSl >= entry) { alert('SL doit être INFÉRIEUR au prix d\'entrée pour un LONG.'); return; }
  if (customSl > 0 && simuDir === 'SHORT' && customSl <= entry) { alert('SL doit être SUPÉRIEUR au prix d\'entrée pour un SHORT.'); return; }
  if (customTp > 0 && simuDir === 'LONG'  && customTp <= entry) { alert('TP doit être SUPÉRIEUR au prix d\'entrée pour un LONG.'); return; }
  if (customTp > 0 && simuDir === 'SHORT' && customTp >= entry) { alert('TP doit être INFÉRIEUR au prix d\'entrée pour un SHORT.'); return; }

  const atr = lastSignal?.atr || entry * 0.01;

  try {
    const res = await fetch('/api/trades', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ amount, direction: simuDir, leverage, entryPrice: entry,
                             feeRate: simuFeeRate, atr, sl: customSl, tp: customTp })
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
      stopTradeTimers();
      showClosureBanner(updated);
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

function showClosureBanner(trade) {
  const reason    = trade.closeReason || 'CLOSED';
  const isProfit  = trade.pnlNet >= 0;
  const isTp      = reason === 'TP_HIT';
  const isSl      = reason === 'SL_HIT';
  const isLiq     = reason === 'LIQUIDATION';

  const el = document.getElementById('exit-alert');
  el.style.display = 'flex';

  if (isTp) {
    el.className = 'exit-alert success';
    setText('exit-alert-icon',  '🎯');
    setText('exit-alert-title', 'TAKE PROFIT ATTEINT — Position fermée automatiquement');
    document.getElementById('exit-alert-reasons').innerHTML =
      `<div>• Prix de Take-Profit : ${fmt(trade.tp1)} $</div>` +
      `<div>• Gain net : +${fmt(trade.pnlNet)} $  (${trade.pnlPct.toFixed(2)}%)</div>`;
  } else if (isSl) {
    el.className = 'exit-alert warning';
    setText('exit-alert-icon',  '🛑');
    setText('exit-alert-title', 'STOP-LOSS ATTEINT — Position fermée automatiquement');
    document.getElementById('exit-alert-reasons').innerHTML =
      `<div>• Prix de Stop-Loss : ${fmt(trade.sl)} $</div>` +
      `<div>• Perte nette : ${fmt(trade.pnlNet)} $  (${trade.pnlPct.toFixed(2)}%)</div>`;
  } else if (isLiq) {
    el.className = 'exit-alert critical';
    setText('exit-alert-icon',  '💀');
    setText('exit-alert-title', 'LIQUIDATION — Position fermée automatiquement');
    document.getElementById('exit-alert-reasons').innerHTML =
      `<div>• Prix de liquidation atteint : ${fmt(trade.liq)} $</div>` +
      `<div>• Perte nette : ${fmt(trade.pnlNet)} $</div>`;
  } else {
    el.className = isProfit ? 'exit-alert success' : 'exit-alert';
    setText('exit-alert-icon',  isProfit ? '✅' : '⚠️');
    setText('exit-alert-title', 'Position fermée manuellement');
    document.getElementById('exit-alert-reasons').innerHTML =
      `<div>• P&L net : ${fmt(trade.pnlNet)} $ (${trade.pnlPct.toFixed(2)}%)</div>`;
  }

  setText('exit-price-val', fmt(trade.currentPrice) + ' $');
  const exitPnlEl = document.getElementById('exit-pnl-val');
  exitPnlEl.textContent = fmt(trade.pnlNet) + ' $';
  exitPnlEl.style.color = isProfit ? 'var(--buy)' : 'var(--sell)';

  // Show history link in banner
  const existing = document.getElementById('history-link-btn');
  if (!existing) {
    const btn = document.createElement('a');
    btn.id = 'history-link-btn';
    btn.href = 'trade-history.html';
    btn.textContent = '📋 Voir l\'historique';
    btn.style.cssText = 'display:block;margin-top:10px;color:var(--muted);font-size:13px;text-decoration:none';
    document.getElementById('exit-alert-reasons').appendChild(btn);
  }
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
  setText('pnl-entry-price',   fmt(entry)   + ' $');

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


// ════════════════════════  REAL TRADES  ════════════════════════════════════════

const fmtR   = v  => Number(v).toLocaleString('fr-FR', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
const fmtPR  = v  => (v >= 0 ? '+' : '') + fmtR(v);
const dur    = (openMs) => {
  if (!openMs) return '—';
  const s = Math.floor((Date.now() - openMs) / 1000);
  if (s < 60)   return s + 's';
  if (s < 3600) return Math.floor(s/60) + 'min';
  return Math.floor(s/3600) + 'h ' + Math.floor((s%3600)/60) + 'min';
};

// ── Direction buttons ─────────────────────────────────────────────────────────
function realSetDir(d) {
  realDir = d;
  document.getElementById('real-long').classList.toggle('active', d === 'LONG');
  document.getElementById('real-short').classList.toggle('active', d === 'SHORT');
}

function realFillPrice() {
  if (lastSignal?.currentPrice)
    document.getElementById('real-entry').value = lastSignal.currentPrice;
}

// ── Pre-fill SL/TP from signal (same logic as simulation) ─────────────────────
function realPrefillLevels(s) {
  const entry = parseFloat(document.getElementById('real-entry').value) || s.currentPrice;
  const atr   = s.atr || entry * 0.01;
  const r2    = v => Math.round(v * 100) / 100;
  const dir   = realDir;

  let sl, tp, slHint, tpHint;
  if (dir === 'LONG') {
    const slEma = r2(s.ema21 * 0.9995), slBase = r2(entry - 1.5 * atr);
    sl = (s.ema21 < entry && s.ema21 > slBase) ? slEma : slBase;
    slHint = sl === slEma ? 'EMA21 — support dynamique' : '1.5× ATR sous le prix';
    tp = (s.bollingerUpper - entry) > 0.8 * atr ? r2(s.bollingerUpper) : r2(entry + 2 * atr);
    tpHint = (s.bollingerUpper - entry) > 0.8 * atr ? 'Bollinger Upper' : '2× ATR';
  } else {
    const slEma = r2(s.ema21 * 1.0005), slBase = r2(entry + 1.5 * atr);
    sl = (s.ema21 > entry && s.ema21 < slBase) ? slEma : slBase;
    slHint = sl === slEma ? 'EMA21 — résistance dynamique' : '1.5× ATR au-dessus';
    tp = (entry - s.bollingerLower) > 0.8 * atr ? r2(s.bollingerLower) : r2(entry - 2 * atr);
    tpHint = (entry - s.bollingerLower) > 0.8 * atr ? 'Bollinger Lower' : '2× ATR';
  }

  document.getElementById('real-sl').value = sl;
  document.getElementById('real-tp').value = tp;
  const slH = document.getElementById('real-sl-hint'); if (slH) slH.textContent = '→ ' + slHint;
  const tpH = document.getElementById('real-tp-hint'); if (tpH) tpH.textContent = '→ ' + tpHint;
  const rrH = document.getElementById('real-rr-hint');
  if (rrH && sl && tp) {
    const rr = (Math.abs(tp - entry) / Math.abs(entry - sl)).toFixed(2);
    rrH.textContent = `R/R : 1:${rr}`;
    rrH.style.color = parseFloat(rr) >= 1.5 ? 'var(--buy)' : parseFloat(rr) >= 1 ? 'var(--hold)' : 'var(--sell)';
  }
}

// ── Form show/hide ─────────────────────────────────────────────────────────────
function realShowForm() {
  document.getElementById('real-form-wrap').style.display = 'block';
  if (lastSignal) {
    document.getElementById('real-entry').value = lastSignal.currentPrice;
    realSetDir(lastSignal.direction !== 'WAIT' ? lastSignal.direction : (lastSignal.ema9 > lastSignal.ema21 ? 'LONG' : 'SHORT'));
    realPrefillLevels(lastSignal);
  }
}
function realHideForm() {
  document.getElementById('real-form-wrap').style.display = 'none';
}

// ── Open a real trade ──────────────────────────────────────────────────────────
async function realOpen() {
  const amount   = parseFloat(document.getElementById('real-amount').value) || 0;
  const entry    = parseFloat(document.getElementById('real-entry').value)  || 0;
  const leverage = parseInt(document.getElementById('real-leverage').value) || 1;
  const sl       = parseFloat(document.getElementById('real-sl').value) || 0;
  const tp       = parseFloat(document.getElementById('real-tp').value) || 0;
  const broker   = document.getElementById('real-broker').value;
  const symbol   = document.getElementById('real-symbol').value || 'BTC/USDT';
  const note     = document.getElementById('real-note').value;

  if (!amount || !entry) { alert('Renseigne la mise et le prix d\'entrée.'); return; }

  if (sl > 0 && realDir === 'LONG'  && sl >= entry) { alert('SL doit être inférieur au prix d\'entrée (LONG)'); return; }
  if (sl > 0 && realDir === 'SHORT' && sl <= entry) { alert('SL doit être supérieur au prix d\'entrée (SHORT)'); return; }
  if (tp > 0 && realDir === 'LONG'  && tp <= entry) { alert('TP doit être supérieur au prix d\'entrée (LONG)'); return; }
  if (tp > 0 && realDir === 'SHORT' && tp >= entry) { alert('TP doit être inférieur au prix d\'entrée (SHORT)'); return; }

  const atr = lastSignal?.atr || entry * 0.01;
  try {
    const res = await fetch('/api/trades/real', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ amount, direction: realDir, leverage, entryPrice: entry,
                             feeRate: 0.0004, atr, sl, tp, broker, symbol, note })
    });
    if (!res.ok) throw new Error(await res.text());
    realHideForm();
    await loadRealTrades();
  } catch(e) { alert('Erreur: ' + e.message); }
}

// ── Load & render active real trades ──────────────────────────────────────────
async function loadRealTrades() {
  try {
    const res = await fetch('/api/trades/real/active');
    if (!res.ok) return;
    realTrades = await res.json();
    renderRealTrades();
  } catch(e) { /* ignore */ }
}

// ── Render positions table ────────────────────────────────────────────────────
function renderRealTrades() {
  const tbody    = document.getElementById('real-positions-tbody');
  const emptyEl  = document.getElementById('real-empty');
  const tableWrap= document.getElementById('real-positions-table-wrap');
  const countEl  = document.getElementById('real-count');
  const pnlEl    = document.getElementById('real-total-pnl');
  const expEl    = document.getElementById('real-exposure');

  countEl.textContent = realTrades.length;

  if (realTrades.length === 0) {
    emptyEl.style.display   = 'block';
    tableWrap.style.display = 'none';
    pnlEl.textContent = '—'; pnlEl.style.color = '';
    expEl.textContent = '—';
    return;
  }
  emptyEl.style.display   = 'none';
  tableWrap.style.display = 'block';

  let totalPnl = 0, totalExp = 0;
  let rows = '';
  for (const t of realTrades) {
    totalPnl += t.pnlNet || 0;
    totalExp += (t.amount || 0) * (t.leverage || 1);

    const dir    = (t.direction || '').toLowerCase();
    const pnlCls = t.pnlNet >= 0 ? 'pnl-pos' : 'pnl-neg';
    const cp     = t.currentPrice || t.entryPrice;

    // SL/TP proximity alerts (within 0.5%)
    const slNear = t.sl > 0 && Math.abs(cp - t.sl) / cp < 0.005;
    const tpNear = t.tp1 > 0 && Math.abs(cp - t.tp1) / cp < 0.005;

    rows += `<tr>
      <td><span class="broker-badge">${t.broker || '—'}</span></td>
      <td>${t.symbol || 'BTC/USDT'}</td>
      <td><span class="dir-badge ${dir}">${dir === 'long' ? '▲ LONG' : '▼ SHORT'}</span></td>
      <td>${fmtR(t.entryPrice)} $</td>
      <td>${fmtR(cp)} $</td>
      <td style="color:var(--muted)">×${t.leverage}</td>
      <td>${fmtR(t.amount)} $</td>
      <td class="${pnlCls}">${fmtPR(t.pnlNet)} $</td>
      <td class="${pnlCls}">${fmtPR(t.pnlPct)}%</td>
      <td class="${slNear ? 'sl-near' : ''}">${t.sl > 0 ? fmtR(t.sl) + ' $' : '—'}</td>
      <td class="${tpNear ? 'tp-near' : ''}">${t.tp1 > 0 ? fmtR(t.tp1) + ' $' : '—'}</td>
      <td style="color:var(--muted)">${dur(t.openedAt)}</td>
      <td style="color:var(--muted);max-width:120px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap"
          title="${t.note || ''}">${t.note || '—'}</td>
      <td>
        <button onclick="realOpenCloseModal(${t.id})"
                style="background:#3d1a1a;border:1px solid var(--sell);color:var(--sell);padding:4px 10px;border-radius:5px;cursor:pointer;font-size:12px">
          ✖ Clôturer
        </button>
      </td>
    </tr>`;
  }
  tbody.innerHTML = rows;

  pnlEl.textContent = fmtPR(totalPnl) + ' $';
  pnlEl.style.color = totalPnl >= 0 ? 'var(--buy)' : 'var(--sell)';
  expEl.textContent = fmtR(totalExp) + ' $';
}

// ── Close modal ───────────────────────────────────────────────────────────────
function realOpenCloseModal(id) {
  const t = realTrades.find(x => x.id === id);
  if (!t) return;
  realClosingId = id;
  const cp = t.currentPrice || t.entryPrice;
  document.getElementById('real-close-price').value = cp;
  document.getElementById('real-modal-info').innerHTML =
    `<strong>${t.direction}</strong> ${t.symbol} — Entrée : ${fmtR(t.entryPrice)} $ — Mise : ${fmtR(t.amount)} $ ×${t.leverage}`;
  realUpdateModalPnl();
  document.getElementById('real-close-modal').style.display = 'flex';
  document.getElementById('real-close-price').addEventListener('input', realUpdateModalPnl);
}

function realUpdateModalPnl() {
  const t  = realTrades.find(x => x.id === realClosingId);
  if (!t) return;
  const cp = parseFloat(document.getElementById('real-close-price').value) || t.currentPrice;
  const mv = t.direction === 'LONG' ? cp - t.entryPrice : t.entryPrice - cp;
  const pnlUsd  = t.amount * (mv / t.entryPrice) * t.leverage;
  const fees    = t.amount * t.leverage * (t.feeRate || 0.0004) * 2;
  const pnlNet  = pnlUsd - fees;
  const pnlPct  = pnlNet / t.amount * 100;
  const el = document.getElementById('real-modal-pnl');
  el.textContent = `${fmtPR(pnlNet)} $ (${fmtPR(pnlPct)}%)`;
  el.style.color = pnlNet >= 0 ? 'var(--buy)' : 'var(--sell)';
}

async function realConfirmClose() {
  if (!realClosingId) return;
  const t = realTrades.find(x => x.id === realClosingId);
  if (!t) return;

  let cp = parseFloat(document.getElementById('real-close-price').value) || 0;
  // Normalize symbol: "BTC/USDT" → "BTCUSDT"
  const symbol = (t.symbol || 'BTCUSDT').replace('/', '');

  // Step 1: close on Binance (market order + cancel SL/TP)
  try {
    const binResp = await fetch(`/api/futures/close-position?symbol=${symbol}`, { method: 'POST' });
    if (binResp.ok) {
      const binData = await binResp.json();
      // Use actual Binance fill price if available
      if (binData.price && binData.price > 0) cp = binData.price;
    } else {
      const err = await binResp.json().catch(() => ({}));
      const msg = err.error || err.message || 'Erreur serveur';
      // "Aucune position" means already closed on Binance — still close local record
      if (!msg.toLowerCase().includes('aucune position') && !msg.toLowerCase().includes('no position')) {
        if (!confirm(`⚠️ Binance : ${msg}\n\nContinuer la clôture locale uniquement ?`)) return;
      }
    }
  } catch (e) {
    if (!confirm(`⚠️ Erreur Binance : ${e.message}\n\nContinuer la clôture locale uniquement ?`)) return;
  }

  // Step 2: close in local database with actual (or entered) price
  try {
    const res = await fetch(`/api/trades/${realClosingId}?reason=USER_CLOSED&closePrice=${cp}`, { method: 'DELETE' });
    if (!res.ok) throw new Error(await res.text());
    realCloseModal();
    await loadRealTrades();
    atLoadPositions(); // refresh live positions panel
  } catch(e) { alert('Erreur clôture locale: ' + e.message); }
}

function realCloseModal() {
  document.getElementById('real-close-modal').style.display = 'none';
  realClosingId = null;
}
function realModalBgClick(e) {
  if (e.target === document.getElementById('real-close-modal')) realCloseModal();
}

// ════════════════════════════════════════════════════════════════════════════
// SCALPING TAB — isolated, zero impact on existing code
// ════════════════════════════════════════════════════════════════════════════

let scalpingChart        = null;
let scalpingCandles      = null;
let scalpingRefreshTimer = null;
let lastScalpingPrice    = 0;
const SCALPING_REFRESH_MS = 30_000;

// ── Load & render ─────────────────────────────────────────────────────────

async function loadScalpingSignal() {
  try {
    const s = await fetch('/api/crypto/btc/scalping').then(r => r.json());
    renderScalpingSignal(s);
    document.getElementById('scalping-last-update').textContent =
      'Mis à jour : ' + new Date().toLocaleTimeString('fr-FR');
  } catch (e) {
    console.error('[Scalping]', e);
  }
}

function renderScalpingSignal(s) {
  if (s.error) {
    document.getElementById('scalping-direction').textContent = 'ERR';
    document.getElementById('scalping-reasoning').textContent = s.error;
    return;
  }

  const GREEN = 'var(--green)', RED = 'var(--red)', MUTED = 'var(--muted)', ACC = 'var(--accent)';
  const fmtP  = v => v ? '$' + v.toLocaleString('fr-FR', {minimumFractionDigits:1, maximumFractionDigits:1}) : '—';
  const fmtN  = v => v != null ? v.toFixed(2) : '—';
  const sign  = v => v > 0 ? '+' + v.toFixed(2) + '%' : v.toFixed(2) + '%';
  const scoreColor = v => v > 0 ? GREEN : v < 0 ? RED : MUTED;

  // Direction card
  const card = document.getElementById('scalping-card');
  const dir  = s.direction;
  card.className = 'signal-card ' + (dir === 'LONG' ? 'long' : dir === 'SHORT' ? 'short' : '');
  document.getElementById('scalping-direction').textContent   = dir;
  document.getElementById('scalping-confidence').textContent  = s.confidence + ' / 100';

  // Levels
  if (s.currentPrice) lastScalpingPrice = s.currentPrice;
  document.getElementById('scalping-price').textContent   = fmtP(s.currentPrice);
  document.getElementById('scalping-tp1').textContent     = fmtP(s.tp1);
  document.getElementById('scalping-tp2').textContent     = fmtP(s.tp2);
  document.getElementById('scalping-sl').textContent      = fmtP(s.stopLoss);
  document.getElementById('scalping-atr').textContent     = fmtN(s.atr);
  document.getElementById('scalping-tp1-pnl').textContent = s.tp1PnlPct != null ? sign(s.tp1PnlPct) : '';
  document.getElementById('scalping-tp2-pnl').textContent = s.tp2PnlPct != null ? sign(s.tp2PnlPct) : '';
  document.getElementById('scalping-sl-pnl').textContent  = s.slPnlPct  != null ? sign(s.slPnlPct)  : '';
  document.getElementById('scalping-atr-pct').textContent = s.atrPct != null ? s.atrPct.toFixed(2) + '%' : '';

  // RSI(7)
  const rsi = s.rsi7;
  const rsiEl = document.getElementById('scalping-rsi');
  rsiEl.textContent = rsi != null ? rsi.toFixed(1) : '—';
  rsiEl.style.color = rsi < 30 ? GREEN : rsi > 70 ? RED : 'var(--text)';
  const fill = document.getElementById('scalping-rsi-fill');
  fill.style.width = (rsi != null ? Math.min(100, rsi) : 0) + '%';
  fill.style.background = rsi < 30 ? GREEN : rsi > 70 ? RED : ACC;

  // RSI dynamics: slope / acceleration / divergence
  const rsiDynEl = document.getElementById('scalping-rsi-dyn');
  if (rsiDynEl) {
    const slope = s.rsiSlope;
    const accel = s.rsiAcceleration;
    const div   = s.rsiDivergence;
    const slopeArrow = slope > 0.5 ? `<span style="color:${GREEN}">↑${slope.toFixed(1)}</span>`
                     : slope < -0.5 ? `<span style="color:${RED}">↓${slope.toFixed(1)}</span>`
                     : `<span style="color:var(--muted)">→${slope != null ? slope.toFixed(1) : '—'}</span>`;
    const accelIcon = accel > 0.3 ? `<span style="color:${GREEN}" title="Accélération">⚡</span>`
                    : accel < -0.3 ? `<span style="color:${RED}" title="Décélération">⚡</span>`
                    : '';
    const divBadge = div === 'BULLISH' ? `<span style="color:${GREEN};font-size:10px;font-weight:700">DIV↑</span>`
                   : div === 'BEARISH' ? `<span style="color:${RED};font-size:10px;font-weight:700">DIV↓</span>`
                   : '';
    rsiDynEl.innerHTML = `${slopeArrow} ${accelIcon} ${divBadge}`;
  }

  // EMA 5/13 + SMA50 trend
  document.getElementById('scalping-ema').textContent =
    (s.ema5 ? s.ema5.toFixed(0) : '—') + ' / ' + (s.ema13 ? s.ema13.toFixed(0) : '—');
  const emaStatus = document.getElementById('scalping-ema-status');
  const p = s.currentPrice;
  if (p && s.ema5 && s.ema13) {
    if (p > s.ema5 && s.ema5 > s.ema13) {
      emaStatus.textContent = '▲ Bullish (prix > EMA5 > EMA13)'; emaStatus.style.color = GREEN;
    } else if (p < s.ema5 && s.ema5 < s.ema13) {
      emaStatus.textContent = '▼ Bearish (prix < EMA5 < EMA13)'; emaStatus.style.color = RED;
    } else {
      emaStatus.textContent = '— Mixte'; emaStatus.style.color = MUTED;
    }
  }
  const sma50El = document.getElementById('scalping-sma50-trend');
  if (sma50El && s.sma50_1m && p) {
    const upTrend = p > s.sma50_1m;
    sma50El.textContent = `SMA50: ${s.sma50_1m.toFixed(0)} — ${upTrend ? '↑ seuil LONG 72' : '↓ seuil SHORT 72'} (contre-tendance: 85)`;
    sma50El.style.color = upTrend ? GREEN : RED;
  }

  // MACD
  const hist = s.macdHistogram;
  const histEl = document.getElementById('scalping-macd-hist');
  histEl.textContent = hist != null ? (hist > 0 ? '+' : '') + hist.toFixed(2) : '—';
  histEl.style.color = hist > 0 ? GREEN : hist < 0 ? RED : MUTED;
  document.getElementById('scalping-macd-line').textContent   = s.macdLine   != null ? s.macdLine.toFixed(2)   : '—';
  document.getElementById('scalping-macd-signal').textContent = s.macdSignal != null ? s.macdSignal.toFixed(2) : '—';

  // Volume Delta
  const vd = s.volumeDeltaPct;
  const vdEl = document.getElementById('scalping-vol-pct');
  vdEl.textContent  = vd != null ? vd.toFixed(1) + '%' : '—';
  vdEl.style.color  = vd > 52 ? GREEN : vd < 48 ? RED : MUTED;
  const vFill = document.getElementById('scalping-vol-fill');
  vFill.style.width      = (vd != null ? Math.min(100, vd) : 50) + '%';
  vFill.style.background = vd > 52 ? GREEN : vd < 48 ? RED : ACC;
  const volTrendLabels = { STRONG_BUY:'🟢 Fort achat', BUY:'🟢 Achat', NEUTRAL:'⚪ Neutre', SELL:'🔴 Vente', STRONG_SELL:'🔴 Forte vente' };
  document.getElementById('scalping-vol-trend').textContent = volTrendLabels[s.volumeDeltaTrend] || s.volumeDeltaTrend;

  // Stochastic
  const stEl = document.getElementById('scalping-stoch');
  stEl.textContent = (s.stochK != null ? s.stochK.toFixed(1) : '—') + ' / ' + (s.stochD != null ? s.stochD.toFixed(1) : '—');
  const stStatus = document.getElementById('scalping-stoch-status');
  if (s.stochK < 20) { stStatus.textContent = '▲ Oversold'; stStatus.style.color = GREEN; }
  else if (s.stochK > 80) { stStatus.textContent = '▼ Overbought'; stStatus.style.color = RED; }
  else { stStatus.textContent = '— Neutre'; stStatus.style.color = MUTED; }

  // Bollinger
  const bbEl = document.getElementById('scalping-bb-width');
  bbEl.textContent = s.bbWidth != null ? s.bbWidth.toFixed(2) + '%' : '—';
  const bbStateEl = document.getElementById('scalping-bb-state');
  const bbColors = { SQUEEZE:'#f0b429', NORMAL: MUTED, EXPANSION: ACC };
  const bbLabels = { SQUEEZE:'🟡 SQUEEZE — faible volatilité', NORMAL:'⚪ Normal', EXPANSION:'🔵 Expansion' };
  bbStateEl.textContent = bbLabels[s.bbState] || s.bbState;
  bbStateEl.style.color = bbColors[s.bbState] || MUTED;

  // Score breakdown
  const scoreEl = (id, v) => {
    const el = document.getElementById(id);
    if (!el) return;
    el.textContent  = v != null ? (v > 0 ? '+' : '') + v : '—';
    el.style.color  = scoreColor(v);
  };
  scoreEl('sc-rsi',     s.rsiScore);
  scoreEl('sc-rsi-dyn', s.rsiDynScore);
  scoreEl('sc-ema',  s.emaScore);
  scoreEl('sc-macd', s.macdScore);
  scoreEl('sc-vol',  s.volScore);
  document.getElementById('scalping-reasoning').textContent = s.reasoning || '';

  // 1m chart
  if (s.candles && s.candles.length) renderScalpingChart(s);
}

// ── 1m chart ──────────────────────────────────────────────────────────────

function initScalpingChart() {
  const container = document.getElementById('scalping-chart');
  if (!container || scalpingChart) return;
  scalpingChart = LightweightCharts.createChart(container, {
    autoSize:   true,
    layout:     { background: { color: '#161b22' }, textColor: '#c9d1d9' },
    grid:       { vertLines: { color: '#21262d' }, horzLines: { color: '#21262d' } },
    crosshair:  { mode: LightweightCharts.CrosshairMode.Normal },
    timeScale:  { timeVisible: true, secondsVisible: false, borderColor: '#30363d' },
    rightPriceScale: { borderColor: '#30363d' },
  });
  scalpingCandles = scalpingChart.addCandlestickSeries({
    upColor: '#3fb950', downColor: '#f85149',
    borderUpColor: '#3fb950', borderDownColor: '#f85149',
    wickUpColor: '#3fb950', wickDownColor: '#f85149',
  });
}

function renderScalpingChart(s) {
  if (!scalpingChart) initScalpingChart();
  if (!scalpingChart) return;
  const data = s.candles.map(c => ({
    time: c.time, open: c.open, high: c.high, low: c.low, close: c.close
  })).sort((a, b) => a.time - b.time);
  scalpingCandles.setData(data);

  // Price lines for SL / TP1 / TP2
  scalpingChart.priceScale('right');
  [
    { price: s.tp1,      color: '#3fb950', title: 'TP1', lineWidth: 1 },
    { price: s.tp2,      color: '#3fb950', title: 'TP2', lineWidth: 1 },
    { price: s.stopLoss, color: '#f85149', title: 'SL',  lineWidth: 1 },
  ].forEach(({ price, color, title, lineWidth }) => {
    if (!price) return;
    scalpingCandles.createPriceLine({ price, color, title, lineWidth, lineStyle: 2, axisLabelVisible: true });
  });
  scalpingChart.timeScale().fitContent();
}

// ── Auto-refresh when scalping tab is visible ─────────────────────────────

function startScalpingRefresh() {
  if (scalpingRefreshTimer) clearInterval(scalpingRefreshTimer);
  loadScalpingSignal();
  loadScalpingStatus();
  loadScalpingDiagnose();
  loadScalpingHistory();
  scalpingRefreshTimer = setInterval(() => {
    loadScalpingSignal();
    loadScalpingStatus();
    loadScalpingDiagnose();
    loadScalpingHistory();
  }, SCALPING_REFRESH_MS);
}

function stopScalpingRefresh() {
  if (scalpingRefreshTimer) { clearInterval(scalpingRefreshTimer); scalpingRefreshTimer = null; }
}

// ── Scalping auto-trade activation ────────────────────────────────────────

let scalpingEnabled = false;

async function loadScalpingStatus() {
  try {
    const s = await fetch('/api/scalping/status').then(r => r.json());
    renderScalpingStatus(s);
  } catch (e) { console.warn('[Scalping status]', e); }
}

function renderScalpingStatus(s) {
  scalpingEnabled = !!s.enabled;

  const statusEl = document.getElementById('scalping-bot-status');
  const btnEl    = document.getElementById('scalping-toggle-btn');
  if (s.enabled) {
    statusEl.textContent = '● Activé';
    statusEl.style.color = 'var(--green)';
    btnEl.textContent    = '⏹ Désactiver auto-scalping';
    btnEl.style.background = '#da3633';
  } else {
    statusEl.textContent = '● Désactivé';
    statusEl.style.color = 'var(--muted)';
    btnEl.textContent    = '▶ Activer auto-scalping';
    btnEl.style.background = '#238636';
  }

  // Wallet balance
  const w = s.wallet;
  if (w && w.walletBalance != null) {
    const total = w.walletBalance;
    const avail = w.availableBalance;
    const unreal = w.unrealizedProfit || 0;
    const totalEl = document.getElementById('scalping-wallet-total');
    const availEl = document.getElementById('scalping-wallet-avail');
    totalEl.textContent = `$${total.toFixed(2)}`;
    totalEl.style.color = unreal >= 0 ? 'var(--text)' : 'var(--red)';
    availEl.textContent = `dispo: $${avail.toFixed(2)}` + (unreal !== 0 ? ` · PnL: ${unreal >= 0 ? '+' : ''}${unreal.toFixed(2)}$` : '');
  }

  // Consecutive loss streak
  const streakEl = document.getElementById('scalping-streak');
  const coolEl   = document.getElementById('scalping-streak-cool');
  if (streakEl) {
    const losses = s.consecutiveLosses ?? 0;
    streakEl.textContent = losses;
    streakEl.style.color = losses >= 2 ? 'var(--red)' : losses === 1 ? '#f0b429' : 'var(--text)';
  }
  if (coolEl) {
    const cd = s.lossStreakCooldown;
    if (cd && cd !== 'none') {
      coolEl.textContent = `⏸ pause ${cd}`;
      coolEl.style.display = 'block';
    } else {
      coolEl.style.display = 'none';
    }
  }

  // Sync config inputs
  if (s.amountUsdt)    setVal('scalping-cfg-amount', s.amountUsdt);
  if (s.leverage)      setVal('scalping-cfg-lev',    s.leverage);
  if (s.tpPct)         setVal('scalping-cfg-tp',     s.tpPct);
  if (s.slPct)         setVal('scalping-cfg-sl',     s.slPct);
  if (s.minConfidence) setVal('scalping-cfg-conf',   s.minConfidence);

  // Last result
  const resultEl = document.getElementById('scalping-last-result');
  if (s.lastResult && s.lastResult.status) {
    const r = s.lastResult;
    const badge = {
      placed:  `<span style="color:var(--green);font-weight:700">✅ TRADE PLACÉ</span>`,
      closed:  `<span style="color:var(--accent);font-weight:700">🔒 FERMÉ</span>`,
      skipped: `<span style="color:var(--muted);font-weight:700">⏭ SKIP</span>`,
      error:   `<span style="color:var(--red);font-weight:700">❌ ERREUR</span>`
    }[r.status] || `<span>${r.status}</span>`;

    if (r.status === 'placed') {
      const dir = r.direction === 'LONG'
        ? `<span style="color:var(--green);font-weight:700">▲ LONG</span>`
        : `<span style="color:var(--red);font-weight:700">▼ SHORT</span>`;
      const msg = r.message ?? '';
      const hasWarning = msg.includes('⚠');
      const msgColor = hasWarning ? 'var(--red)' : 'var(--muted)';
      resultEl.innerHTML =
        `${badge} ${dir} · conf <strong>${r.confidence}%</strong> · entrée <strong>$${r.entryPrice?.toFixed(1) ?? '—'}</strong>` +
        `<br><span style="color:${msgColor};font-size:11px">${msg}</span>`;
    } else if (r.status === 'closed') {
      const pnlColor = (r.pnl ?? 0) >= 0 ? 'var(--green)' : 'var(--red)';
      resultEl.innerHTML =
        `${badge} ${r.message ?? ''} · prix <strong>$${r.entryPrice?.toFixed(1) ?? '—'}</strong>` +
        (r.pnl != null ? ` · <span style="color:${pnlColor};font-weight:700">${r.pnl >= 0 ? '+' : ''}${r.pnl.toFixed(2)} USDT</span>` : '');
    } else {
      resultEl.innerHTML = `${badge} <span style="color:var(--muted)">${r.message ?? ''}</span>`;
    }

    if (s.cooldownRemaining > 0) {
      resultEl.innerHTML += ` · <span style="color:var(--muted)">⏳ ${Math.ceil(s.cooldownRemaining / 60)} min cooldown</span>`;
    }
  }

  // Active position
  const posEl = document.getElementById('scalping-active-pos');
  if (s.activeDir) {
    posEl.style.display = 'block';
    const isLong = s.activeDir === 'LONG';
    const col    = isLong ? 'var(--green)' : 'var(--red)';
    const dir    = isLong ? '▲ LONG' : '▼ SHORT';

    // Direction badge
    const dirEl = document.getElementById('scalping-pos-dir');
    dirEl.textContent   = dir;
    dirEl.style.color   = col;
    dirEl.style.border  = `1px solid ${col}`;

    // Confidence
    document.getElementById('scalping-pos-conf').textContent =
      s.activeConf ? `Conf: ${s.activeConf}%` : '';

    // Duration
    const durEl = document.getElementById('scalping-pos-duration');
    if (s.activeOpenedAt) {
      const sec = Math.floor((Date.now() - new Date(s.activeOpenedAt).getTime()) / 1000);
      const h   = Math.floor(sec / 3600);
      const m   = Math.floor((sec % 3600) / 60);
      const ss  = sec % 60;
      durEl.textContent = h > 0 ? `⏱ ${h}h ${m}m` : m > 0 ? `⏱ ${m}m ${ss}s` : `⏱ ${ss}s`;
    } else {
      durEl.textContent = '';
    }

    // Entry
    document.getElementById('scalping-pos-entry').textContent =
      s.activeEntry ? `$${s.activeEntry.toFixed(1)}` : '—';

    // Live unrealized P&L
    const pnlCard = document.getElementById('scalping-pos-pnl-card');
    const pnlEl   = document.getElementById('scalping-pos-pnl');
    const pnlPct  = document.getElementById('scalping-pos-pnl-pct');
    if (s.activeEntry && s.activeQty && lastScalpingPrice) {
      const rawPnl = isLong
        ? (lastScalpingPrice - s.activeEntry) * s.activeQty
        : (s.activeEntry - lastScalpingPrice) * s.activeQty;
      const pnlPctVal = isLong
        ? (lastScalpingPrice - s.activeEntry) / s.activeEntry * 100
        : (s.activeEntry - lastScalpingPrice) / s.activeEntry * 100;
      const pnlColor  = rawPnl >= 0 ? 'var(--green)' : 'var(--red)';
      pnlEl.textContent  = `${rawPnl >= 0 ? '+' : ''}${rawPnl.toFixed(2)} $`;
      pnlEl.style.color  = pnlColor;
      pnlPct.textContent = `${pnlPctVal >= 0 ? '+' : ''}${pnlPctVal.toFixed(3)}%`;
      pnlPct.style.color = pnlColor;
      pnlCard.style.borderTop = `2px solid ${pnlColor}`;
    } else {
      pnlEl.textContent  = 'En attente prix';
      pnlEl.style.color  = 'var(--muted)';
      pnlPct.textContent = '';
      pnlCard.style.borderTop = '';
    }

    // TP + distance
    const tpEl   = document.getElementById('scalping-pos-tp');
    const tpDist = document.getElementById('scalping-pos-tp-dist');
    if (s.activeTp) {
      tpEl.textContent = `$${s.activeTp.toFixed(1)}`;
      if (s.activeEntry) {
        const d = isLong
          ? (s.activeTp - s.activeEntry) / s.activeEntry * 100
          : (s.activeEntry - s.activeTp) / s.activeEntry * 100;
        tpDist.textContent = `+${d.toFixed(3)}%`;
      }
    }

    // SL + distance
    const slEl   = document.getElementById('scalping-pos-sl');
    const slDist = document.getElementById('scalping-pos-sl-dist');
    if (s.activeSl) {
      slEl.textContent = `$${s.activeSl.toFixed(1)}`;
      if (s.activeEntry) {
        const d = isLong
          ? (s.activeEntry - s.activeSl) / s.activeEntry * 100
          : (s.activeSl - s.activeEntry) / s.activeEntry * 100;
        slDist.textContent = `-${d.toFixed(3)}%`;
      }
    }
  } else {
    posEl.style.display = 'none';
  }
}

async function toggleScalping() {
  const url = scalpingEnabled ? '/api/scalping/disable' : '/api/scalping/enable';
  const msgEl = document.getElementById('scalping-bot-msg');
  try {
    msgEl.textContent = '…';
    const r = await fetch(url, { method: 'POST' }).then(res => res.json());
    if (r.error) { msgEl.textContent = '❌ ' + r.error; return; }
    msgEl.textContent = r.message || '';
    await loadScalpingStatus();
    if (r.firstCheck) renderScalpingStatus({ ...await fetch('/api/scalping/status').then(x => x.json()) });
  } catch (e) {
    msgEl.textContent = '❌ ' + e.message;
  }
}

async function triggerScalping() {
  const msgEl = document.getElementById('scalping-bot-msg');
  try {
    msgEl.textContent = '…';
    const r = await fetch('/api/scalping/trigger', { method: 'POST' }).then(res => res.json());
    msgEl.textContent = (r.status || '') + ': ' + (r.message || '');
    await loadScalpingStatus();
  } catch (e) {
    msgEl.textContent = '❌ ' + e.message;
  }
}

async function checkBinanceOrders() {
  const msgEl = document.getElementById('scalping-bot-msg');
  try {
    msgEl.textContent = '⏳ Récupération ordres…';
    const resp = await fetch('/api/scalping/orders');
    const text = await resp.text();
    let orders;
    try { orders = JSON.parse(text); } catch(e) { orders = text; }
    if (Array.isArray(orders)) {
      if (orders.length === 0) {
        msgEl.textContent = '📋 Aucun ordre ouvert sur Binance';
      } else {
        const lines = orders.map(o =>
          `[${o.type}] ${o.side} @ ${o.stopPrice} (${o.status})`
        ).join(' | ');
        msgEl.textContent = `📋 ${orders.length} ordre(s): ${lines}`;
      }
    } else {
      msgEl.textContent = '📋 ' + (typeof orders === 'object' ? JSON.stringify(orders) : text).slice(0, 200);
    }
  } catch (e) {
    msgEl.textContent = '❌ ' + e.message;
  }
}

async function saveScalpingConfig() {
  const body = {
    amountUsdt:    parseFloat(document.getElementById('scalping-cfg-amount').value) || 0,
    leverage:      parseInt(document.getElementById('scalping-cfg-lev').value)      || 0,
    tpPct:         parseFloat(document.getElementById('scalping-cfg-tp').value)     || 0,
    slPct:         parseFloat(document.getElementById('scalping-cfg-sl').value)     || 0,
    minConfidence: parseInt(document.getElementById('scalping-cfg-conf').value)     || 0,
  };
  try {
    await fetch('/api/scalping/config', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body)
    });
    document.getElementById('scalping-bot-msg').textContent = '✅ Config sauvegardée';
  } catch (e) {
    document.getElementById('scalping-bot-msg').textContent = '❌ ' + e.message;
  }
}

function setVal(id, v) { const el = document.getElementById(id); if (el) el.value = v; }

// ── Pre-trade checklist ───────────────────────────────────────────────────────

async function loadScalpingDiagnose() {
  try {
    const d = await fetch('/api/scalping/diagnose').then(r => r.json());
    renderScalpingDiagnose(d);
  } catch (e) { console.warn('[Scalping diagnose]', e); }
}

function renderScalpingDiagnose(d) {
  // ── Would-trade banner ────────────────────────────────────────────────────
  const banner = document.getElementById('scalping-would-trade');
  if (banner) {
    if (d.wouldTrade) {
      banner.textContent = '✅ PRÊT À TRADER';
      banner.style.color = 'var(--green)';
      banner.style.border = '1px solid var(--green)';
    } else {
      banner.textContent = '🚫 BLOQUÉ';
      banner.style.color = 'var(--red)';
      banner.style.border = '1px solid var(--red)';
    }
  }

  // ── Gate conditions ───────────────────────────────────────────────────────
  const gates = [
    { label: 'Auto-scalping activé',     ok: d.enabled,
      detail: d.enabled ? 'ON' : 'OFF — cliquer Activer' },
    { label: 'API Binance configurée',   ok: d.configured,
      detail: d.configured ? 'OK' : 'Clé manquante' },
    { label: 'Pas de position interne',  ok: !d.hasActivePosition,
      detail: d.hasActivePosition ? `Position ${d.activeDir} en cours` : 'Libre' },
    { label: 'Cooldown écoulé',          ok: d.cooldownOk,
      detail: d.cooldownOk ? 'OK' : `Encore ${d.cooldownRemainMin} min` },
    { label: 'Direction signal',         ok: d.directionOk,
      detail: d.directionOk ? (d.signalDirection || '?') : `WAIT (conf=${d.signalConfidence ?? '?'}%)` },
    { label: `Confiance ≥ ${d.minConfidence}%`, ok: d.confidenceOk,
      detail: d.signalError ? '⚠ Erreur signal' : `${d.signalConfidence ?? '?'}%` },
    { label: 'Pas de position Binance',  ok: d.binancePosOk,
      detail: d.binancePosDetail || '—' },
  ];

  const gatesEl = document.getElementById('scalping-checks-gates');
  if (gatesEl) gatesEl.innerHTML = gates.map(c => checkRow(c)).join('');

  // ── Indicator conditions ──────────────────────────────────────────────────
  const indicators = d.signalError ? [] : [
    { label: 'RSI(7)',          ok: d.rsiOk,      detail: d.rsiDetail      || '—' },
    { label: 'EMA 5/13',       ok: d.emaOk,      detail: d.emaDetail      || '—' },
    { label: 'MACD histo',     ok: d.macdOk,     detail: d.macdDetail     || '—' },
    { label: 'Volume delta',   ok: d.volDeltaOk, detail: d.volDeltaDetail || '—' },
    { label: 'Stoch(5,3)',     ok: d.stochOk,    detail: d.stochDetail    || '—' },
    { label: 'BB non-squeeze', ok: d.bbOk,       detail: d.bbDetail       || '—' },
  ];

  const indEl = document.getElementById('scalping-checks-indicators');
  if (indEl) {
    if (d.signalError) {
      indEl.innerHTML = `<div style="color:var(--red);font-size:13px">⚠ ${d.signalError}</div>`;
    } else {
      indEl.innerHTML = indicators.map(c => checkRow(c, true)).join('');
    }
  }

  // ── Score detail ──────────────────────────────────────────────────────────
  const scoreEl = document.getElementById('scalping-score-detail');
  if (scoreEl) scoreEl.textContent = d.scoreDetail || '—';

  // ── Blocking reason ───────────────────────────────────────────────────────
  const blockEl = document.getElementById('scalping-blocking');
  if (blockEl) {
    if (d.blockingReason) {
      blockEl.style.display = 'block';
      blockEl.textContent = '🔴 ' + d.blockingReason;
    } else {
      blockEl.style.display = 'none';
    }
  }
}

function checkRow(c, informative = false) {
  const icon  = c.ok ? '✅' : (informative ? '⬜' : '❌');
  const color = c.ok ? 'var(--green)' : (informative ? 'var(--muted)' : 'var(--red)');
  const tip   = c.detail && c.detail !== '—' ? `data-tooltip="${c.label}\n${c.detail}"` : `data-tooltip="${c.label}"`;
  return `<div style="display:flex;align-items:center;gap:6px;padding:5px 8px;
                       border-radius:6px;background:var(--bg);font-size:12px;overflow:hidden" ${tip}>
    <span>${icon}</span>
    <span style="flex:1;color:var(--text);font-size:11px;white-space:nowrap;
                 overflow:hidden;text-overflow:ellipsis">${c.label}</span>
    <span style="color:${color};font-size:11px;font-weight:600;white-space:nowrap">${c.detail}</span>
  </div>`;
}

// ── Scalping history ──────────────────────────────────────────────────────────

async function loadScalpingHistory() {
  try {
    const r = await fetch('/api/scalping/history');
    if (!r.ok) return;
    renderScalpingHistory(await r.json());
  } catch(e) {}
}

function renderScalpingHistory(trades) {
  const body     = document.getElementById('scalping-history-body');
  const pnlEl    = document.getElementById('scalping-history-pnl');
  const statsWR  = document.getElementById('scalping-stats-winrate');
  const statsTR  = document.getElementById('scalping-stats-trades');
  if (!body) return;

  if (!trades || trades.length === 0) {
    body.innerHTML = `<div style="color:var(--muted);font-size:13px;text-align:center;padding:20px">Aucun trade pour l'instant</div>`;
    if (pnlEl)   pnlEl.textContent   = '';
    if (statsWR) statsWR.textContent = '';
    if (statsTR) statsTR.textContent = '';
    return;
  }

  // Compute stats
  let totalPnl = 0, wins = 0, losses = 0, opens = 0;
  trades.forEach(t => {
    if (t.status === 'OPEN') { opens++; return; }
    totalPnl += (t.pnl || 0);
    if (t.status === 'TP') wins++;
    else if (t.status === 'SL') losses++;
  });
  const closed   = wins + losses;
  const winRate  = closed > 0 ? (wins / closed * 100) : null;

  if (pnlEl) {
    pnlEl.textContent = `${totalPnl >= 0 ? '+' : ''}${totalPnl.toFixed(2)} $`;
    pnlEl.style.color = totalPnl >= 0 ? 'var(--green)' : 'var(--red)';
  }
  if (statsTR) {
    statsTR.textContent = `${closed} trades · ${opens > 0 ? opens + ' ouvert' : ''}`;
    statsTR.style.color = 'var(--muted)';
  }
  if (statsWR && winRate !== null) {
    statsWR.textContent = `Win: ${winRate.toFixed(0)}% (${wins}W/${losses}L)`;
    statsWR.style.color = winRate >= 50 ? 'var(--green)' : 'var(--red)';
  }

  const STATUS_BADGE = {
    'TP':     `<span style="background:#1a3a1a;color:var(--green);padding:2px 8px;border-radius:4px;font-size:11px;font-weight:700">🟢 TP</span>`,
    'SL':     `<span style="background:#3a1a1a;color:var(--red);padding:2px 8px;border-radius:4px;font-size:11px;font-weight:700">🔴 SL</span>`,
    'OPEN':   `<span style="background:#2a2400;color:var(--accent);padding:2px 8px;border-radius:4px;font-size:11px;font-weight:700">⏳ OUVERT</span>`,
    'MANUAL': `<span style="background:#1e1e2a;color:#8b8bcc;padding:2px 8px;border-radius:4px;font-size:11px">✋ MANUEL</span>`,
  };

  let html = `<table style="width:100%;border-collapse:collapse;font-size:12px">
    <thead>
      <tr style="color:var(--muted);text-align:left;border-bottom:1px solid var(--border)">
        <th style="padding:4px 8px" data-tooltip="Date et heure d'ouverture du trade">Date</th>
        <th style="padding:4px 8px" data-tooltip="Direction du trade\nLONG = pari à la hausse\nSHORT = pari à la baisse">Dir</th>
        <th style="padding:4px 8px" data-tooltip="Score de confiance du signal\n0–100 · seuil minimum configurable">Conf</th>
        <th style="padding:4px 8px" data-tooltip="Prix d'entrée du marché au moment de l'ordre">Entrée</th>
        <th style="padding:4px 8px;color:var(--green)" data-tooltip="Take Profit — niveau de clôture gagnante\nOrdre algo Binance">TP</th>
        <th style="padding:4px 8px;color:var(--red)" data-tooltip="Stop Loss — niveau de clôture perdante\nOrdre algo Binance">SL</th>
        <th style="padding:4px 8px" data-tooltip="Prix effectif de clôture du trade">Sortie</th>
        <th style="padding:4px 8px" data-tooltip="Durée totale du trade (de l'ouverture à la clôture)">Durée</th>
        <th style="padding:4px 8px" data-tooltip="Résultat final du trade\n🟢 TP = objectif atteint\n🔴 SL = stop touché\n✋ Manuel = clôture manuelle\n⏳ Ouvert = en cours">Statut</th>
        <th style="padding:4px 8px;text-align:right" data-tooltip="Profit ou perte réalisé(e) en USDT\n(hors frais Binance)">P&L</th>
      </tr>
    </thead>
    <tbody>`;

  for (const t of trades) {
    const isOpen   = t.status === 'OPEN';
    const isTp     = t.status === 'TP';
    const dirColor = t.direction === 'LONG' ? 'var(--green)' : 'var(--red)';
    const pnlColor = (t.pnl || 0) >= 0 ? 'var(--green)' : 'var(--red)';
    const dateFmt  = t.openedAt ? new Date(t.openedAt).toLocaleString('fr-FR', {dateStyle:'short', timeStyle:'short'}) : '—';
    const exitFmt  = isOpen ? '—' : (t.exitPrice ? '$' + t.exitPrice.toFixed(1) : '—');
    const pnlFmt   = isOpen
      ? '<span style="color:var(--muted)">—</span>'
      : `<span style="color:${pnlColor};font-weight:700">${(t.pnl||0) >= 0 ? '+' : ''}${(t.pnl||0).toFixed(2)} $</span>`;

    // Duration
    let dur = '—';
    if (t.openedAt && !isOpen && t.closedAt) {
      const sec = Math.floor((new Date(t.closedAt) - new Date(t.openedAt)) / 1000);
      const h   = Math.floor(sec / 3600);
      const m   = Math.floor((sec % 3600) / 60);
      const s   = sec % 60;
      dur = h > 0 ? `${h}h${m}m` : m > 0 ? `${m}m${s}s` : `${s}s`;
    } else if (isOpen && t.openedAt) {
      const sec = Math.floor((Date.now() - new Date(t.openedAt)) / 1000);
      const h   = Math.floor(sec / 3600);
      const m   = Math.floor((sec % 3600) / 60);
      const s   = sec % 60;
      dur = `⏱ ${h > 0 ? h + 'h' : ''}${m}m${h > 0 ? '' : s + 's'}`;
    }

    const rowBg = isOpen ? 'background:rgba(240,180,41,0.05)'
                : isTp   ? 'background:rgba(46,160,67,0.04)'
                :          '';
    const badge = STATUS_BADGE[t.status] || `<span>${t.status}</span>`;
    html += `<tr style="border-bottom:1px solid var(--border);${rowBg}">
      <td style="padding:5px 8px;color:var(--muted)">${dateFmt}</td>
      <td style="padding:5px 8px;font-weight:700;color:${dirColor}">${t.direction === 'LONG' ? '▲ LONG' : '▼ SHORT'}</td>
      <td style="padding:5px 8px;color:var(--muted)">${t.confidence != null ? t.confidence + '%' : '—'}</td>
      <td style="padding:5px 8px">$${(t.entryPrice||0).toFixed(1)}</td>
      <td style="padding:5px 8px;color:var(--green)">${t.tpPrice ? '$' + t.tpPrice.toFixed(1) : '—'}</td>
      <td style="padding:5px 8px;color:var(--red)">${t.slPrice ? '$' + t.slPrice.toFixed(1) : '—'}</td>
      <td style="padding:5px 8px">${exitFmt}</td>
      <td style="padding:5px 8px;color:var(--muted)">${dur}</td>
      <td style="padding:5px 8px">${badge}</td>
      <td style="padding:5px 8px;text-align:right">${pnlFmt}</td>
    </tr>`;
  }

  html += `</tbody></table>`;
  body.innerHTML = html;
}
