package com.market.service;

import com.market.client.dto.YahooQuoteSummaryResponse;
import com.market.client.dto.YahooQuoteSummaryResponse.QuoteSummaryResult;
import com.market.model.FundamentalData;
import com.market.model.ValuationVerdict;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Fetches fundamental data from Yahoo Finance quoteSummary (no API key needed),
 * computes a valuation score (0–100) and a verdict:
 *   ≥ 65 → UNDERVALUED · 40–64 → FAIRLY_VALUED · < 40 → OVERVALUED
 *
 * Scoring breakdown (100 pts max):
 *   Forward P/E  : 20 pts
 *   PEG ratio    : 25 pts
 *   Price/Book   : 15 pts
 *   Profit margin: 15 pts
 *   ROE          : 10 pts
 *   Growth       : 15 pts  (earnings + revenue)
 */
@ApplicationScoped
public class FundamentalAnalysisService {

    private static final Logger LOG = Logger.getLogger(FundamentalAnalysisService.class);

    @Inject
    YahooCrumbService crumbService;

    /** In-memory cache: symbol → today's FundamentalData (cleared next day). */
    private final ConcurrentMap<String, FundamentalData> memCache = new ConcurrentHashMap<>();

    /** Load all today's results from DB into the memory cache at startup. */
    @PostConstruct
    @Transactional
    void initCache() {
        LocalDate today = LocalDate.now();
        FundamentalData.findAllLatest().stream()
            .filter(fd -> fd.fetchedAt.toLocalDate().equals(today))
            .forEach(fd -> memCache.put(fd.symbol, fd));
        LOG.infof("Fundamental cache initialized with %d entries from DB", memCache.size());
    }

    /** Always enabled — Yahoo Finance needs no API key. */
    public boolean isEnabled() {
        return true;
    }

    /**
     * Returns cached fundamental data if already fetched today,
     * otherwise fetches from Yahoo Finance, persists, and caches.
     */
    @Transactional
    public FundamentalData analyzeAndStore(String symbol) {
        String key = symbol.toUpperCase();

        // 1. Memory cache hit (fast path — no DB round-trip)
        FundamentalData cached = memCache.get(key);
        if (cached != null && cached.fetchedAt.toLocalDate().equals(LocalDate.now())) {
            LOG.debugf("Fundamental cache hit (memory) for %s", symbol);
            return cached;
        }

        // 2. DB cache hit (app restart case)
        FundamentalData existing = FundamentalData.findLatestBySymbol(symbol);
        if (existing != null && existing.fetchedAt.toLocalDate().equals(LocalDate.now())) {
            LOG.debugf("Fundamental cache hit (DB) for %s", symbol);
            memCache.put(key, existing);
            return existing;
        }

        // 3. Fetch from Yahoo Finance
        try {
            YahooQuoteSummaryResponse resp = crumbService.fetchSummary(symbol);
            if (resp == null) {
                LOG.warnf("No quoteSummary data for %s", symbol);
                return null;
            }
            QuoteSummaryResult result = resp.firstResult();
            if (result == null) {
                LOG.warnf("Empty quoteSummary result for %s", symbol);
                return null;
            }
            FundamentalData fd = buildAndPersist(symbol, result);
            if (fd != null) memCache.put(key, fd);
            return fd;
        } catch (Exception e) {
            LOG.errorf("Error fetching fundamental data for %s: %s", symbol, e.getMessage());
            return null;
        }
    }

    /**
     * Returns true if the symbol already has fresh data in the memory cache for today.
     * Used by callers to skip unnecessary analyzeAndStore calls.
     */
    public boolean isCachedToday(String symbol) {
        FundamentalData fd = memCache.get(symbol.toUpperCase());
        return fd != null && fd.fetchedAt.toLocalDate().equals(LocalDate.now());
    }

    /** Runs a quick Yahoo Finance connectivity test and returns a human-readable report. */
    public String diagnoseYahoo() {
        return crumbService.diagnose();
    }

    private FundamentalData buildAndPersist(String symbol, QuoteSummaryResult r) {
        var fin = r.financialData;
        var ks  = r.defaultKeyStatistics;
        var sd  = r.summaryDetail;
        var ap  = r.assetProfile;

        FundamentalData fd = new FundamentalData();
        fd.symbol    = symbol.toUpperCase();
        fd.fetchedAt = LocalDateTime.now();

        if (ap != null) {
            fd.sector   = ap.sector;
            fd.industry = ap.industry;
        }

        fd.peRatio      = raw(sd != null ? sd.trailingPE : null);
        fd.forwardPE    = raw(sd != null ? sd.forwardPE : null);
        fd.pegRatio     = raw(ks != null ? ks.pegRatio : null);
        fd.priceToBook  = raw(ks != null ? ks.priceToBook : null);
        fd.priceToSales = raw(sd != null ? sd.priceToSalesTrailing12Months : null);
        fd.evToEbitda   = raw(ks != null ? ks.enterpriseToEbitda : null);

        fd.profitMargin    = raw(fin != null ? fin.profitMargins : null);
        fd.operatingMargin = raw(fin != null ? fin.operatingMargins : null);
        fd.returnOnEquity  = raw(fin != null ? fin.returnOnEquity : null);
        fd.returnOnAssets  = raw(fin != null ? fin.returnOnAssets : null);

        fd.earningsGrowth = raw(fin != null ? fin.earningsGrowth : null);
        fd.revenueGrowth  = raw(fin != null ? fin.revenueGrowth : null);

        fd.beta               = raw(sd != null ? sd.beta : null);
        fd.dividendYield      = raw(sd != null ? sd.dividendYield : null);
        fd.analystTargetPrice = raw(fin != null ? fin.targetMeanPrice : null);

        fd.weekHigh52 = raw(sd != null ? sd.fiftyTwoWeekHigh : null);
        fd.weekLow52  = raw(sd != null ? sd.fiftyTwoWeekLow : null);

        // ── Score computation ────────────────────────────────────────────────
        List<String> reasons = new ArrayList<>();
        int score = 0;

        // Forward P/E (20 pts)
        double pe = fd.forwardPE != null ? fd.forwardPE : (fd.peRatio != null ? fd.peRatio : -1);
        if (pe > 0) {
            if      (pe < 10)  { score += 20; reasons.add("P/E forward très bas (<10) : forte décote"); }
            else if (pe < 15)  { score += 17; reasons.add("P/E forward bas (10-15) : décote modérée"); }
            else if (pe < 20)  { score += 13; reasons.add("P/E forward raisonnable (15-20)"); }
            else if (pe < 30)  { score += 7;  reasons.add("P/E forward élevé (20-30) : prime de valorisation"); }
            else               { score += 0;  reasons.add("P/E forward très élevé (>30) : fortement surévalué"); }
        }

        // PEG (25 pts)
        if (fd.pegRatio != null && fd.pegRatio > 0) {
            if      (fd.pegRatio < 1.0) { score += 25; reasons.add("PEG < 1 : croissance non valorisée → sous-évalué"); }
            else if (fd.pegRatio < 1.5) { score += 18; reasons.add("PEG 1-1.5 : valorisation attractive"); }
            else if (fd.pegRatio < 2.0) { score += 10; reasons.add("PEG 1.5-2 : valorisation neutre"); }
            else                        { score += 0;  reasons.add("PEG > 2 : croissance trop chère"); }
        }

        // Price/Book (15 pts)
        if (fd.priceToBook != null && fd.priceToBook > 0) {
            if      (fd.priceToBook < 1.0) { score += 15; reasons.add("P/B < 1 : actifs nets non valorisés"); }
            else if (fd.priceToBook < 2.0) { score += 12; reasons.add("P/B 1-2 : valorisation raisonnable"); }
            else if (fd.priceToBook < 4.0) { score += 6;  reasons.add("P/B 2-4 : prime sur actifs"); }
            else                           { score += 0;  reasons.add("P/B > 4 : prime élevée sur actifs"); }
        }

        // Profit margin (15 pts)
        if (fd.profitMargin != null) {
            double pct = fd.profitMargin * 100;
            if      (pct > 20) { score += 15; reasons.add(fmt("Marge nette élevée (%.1f%%)", pct)); }
            else if (pct > 10) { score += 10; reasons.add(fmt("Marge nette correcte (%.1f%%)", pct)); }
            else if (pct > 0)  { score += 5;  reasons.add(fmt("Marge nette faible (%.1f%%)", pct)); }
            else               { score += 0;  reasons.add("Marge nette négative"); }
        }

        // ROE (10 pts)
        if (fd.returnOnEquity != null) {
            double pct = fd.returnOnEquity * 100;
            if      (pct > 20) { score += 10; reasons.add(fmt("ROE élevé (%.1f%%)", pct)); }
            else if (pct > 10) { score += 7;  reasons.add(fmt("ROE correct (%.1f%%)", pct)); }
            else if (pct > 0)  { score += 3;  reasons.add(fmt("ROE faible (%.1f%%)", pct)); }
            else               { score += 0;  reasons.add("ROE négatif"); }
        }

        // Growth (15 pts = 8 earnings + 7 revenue)
        if (fd.earningsGrowth != null) {
            double pct = fd.earningsGrowth * 100;
            if      (pct > 20) { score += 8; reasons.add(fmt("Croissance bénéfices forte (+%.1f%%)", pct)); }
            else if (pct > 5)  { score += 5; reasons.add(fmt("Croissance bénéfices modérée (+%.1f%%)", pct)); }
            else if (pct > 0)  { score += 2; reasons.add(fmt("Croissance bénéfices faible (+%.1f%%)", pct)); }
            else               { score += 0; reasons.add(fmt("Bénéfices en baisse (%.1f%%)", pct)); }
        }
        if (fd.revenueGrowth != null) {
            double pct = fd.revenueGrowth * 100;
            if      (pct > 10) { score += 7; reasons.add(fmt("Croissance CA forte (+%.1f%%)", pct)); }
            else if (pct > 5)  { score += 4; reasons.add(fmt("Croissance CA modérée (+%.1f%%)", pct)); }
            else if (pct > 0)  { score += 1; reasons.add(fmt("Croissance CA faible (+%.1f%%)", pct)); }
            else               { score += 0; reasons.add(fmt("CA en baisse (%.1f%%)", pct)); }
        }

        fd.valuationScore = Math.min(100, score);
        fd.verdict = fd.valuationScore >= 65 ? ValuationVerdict.UNDERVALUED
                   : fd.valuationScore >= 40 ? ValuationVerdict.FAIRLY_VALUED
                   : ValuationVerdict.OVERVALUED;
        fd.reasons = String.join(";", reasons);

        fd.persist();
        LOG.infof("Fundamental analysis for %s: score=%d verdict=%s", symbol, fd.valuationScore, fd.verdict);
        return fd;
    }

    private Double raw(YahooQuoteSummaryResponse.V v) {
        return v != null ? v.raw : null;
    }

    private String fmt(String pattern, Object... args) {
        return String.format(java.util.Locale.US, pattern, args);
    }
}
