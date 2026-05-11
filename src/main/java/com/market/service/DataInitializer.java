package com.market.service;

import com.market.model.Stock;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;

/**
 * Seeds the default watchlist on first startup, then triggers an immediate
 * market analysis so recommendations are ready before the first scheduled run.
 */
@ApplicationScoped
public class DataInitializer {

    private static final Logger LOG = Logger.getLogger(DataInitializer.class);

    private static final String[][] DEFAULT_STOCKS = {
        // ── CAC 40 (Euronext Paris — suffixe .PA) ────────────────────────────
        {"AI.PA",    "Air Liquide"},
        {"AIR.PA",   "Airbus"},
        {"ALO.PA",   "Alstom"},
        {"CS.PA",    "AXA"},
        {"BNP.PA",   "BNP Paribas"},
        {"EN.PA",    "Bouygues"},
        {"CAP.PA",   "Capgemini"},
        {"CA.PA",    "Carrefour"},
        {"ACA.PA",   "Crédit Agricole"},
        {"BN.PA",    "Danone"},
        {"DSY.PA",   "Dassault Systèmes"},
        {"EDEN.PA",  "Edenred"},
        {"EL.PA",    "EssilorLuxottica"},
        {"RMS.PA",   "Hermès International"},
        {"KER.PA",   "Kering"},
        {"OR.PA",    "L'Oréal"},
        {"LR.PA",    "Legrand"},
        {"MC.PA",    "LVMH"},
        {"ML.PA",    "Michelin"},
        {"ORA.PA",   "Orange"},
        {"RI.PA",    "Pernod Ricard"},
        {"PUB.PA",   "Publicis Groupe"},
        {"RNO.PA",   "Renault"},
        {"SAF.PA",   "Safran"},
        {"SGO.PA",   "Saint-Gobain"},
        {"SAN.PA",   "Sanofi"},
        {"SU.PA",    "Schneider Electric"},
        {"GLE.PA",   "Société Générale"},
        {"STM.PA",   "STMicroelectronics"},
        {"HO.PA",    "Thales"},
        {"TTE.PA",   "TotalEnergies"},
        {"VIE.PA",   "Veolia"},
        {"DG.PA",    "Vinci"},
        {"URW.AS",   "Unibail-Rodamco-Westfield"},
        // ── Europe (hors CAC 40) ──────────────────────────────────────────────
        {"ASML.AS",  "ASML Holding (Amsterdam)"},
        {"MT.AS",    "ArcelorMittal (Amsterdam)"},
        {"SAP.DE",   "SAP (Francfort)"},
        {"SIE.DE",   "Siemens (Francfort)"},
        {"ALV.DE",   "Allianz (Francfort)"},
        {"BMW.DE",   "BMW (Francfort)"},
        {"VOW3.DE",  "Volkswagen (Francfort)"},
        {"BAYN.DE",  "Bayer (Francfort)"},
        {"DTE.DE",   "Deutsche Telekom (Francfort)"},
        {"NESN.SW",  "Nestlé (Zurich)"},
        {"NOVN.SW",  "Novartis (Zurich)"},
        {"ROG.SW",   "Roche (Zurich)"},
        // ── USA (tech & finance) ─────────────────────────────────────────────
        {"AAPL",     "Apple"},
        {"MSFT",     "Microsoft"},
        {"NVDA",     "NVIDIA"},
        {"GOOGL",    "Alphabet"},
        {"AMZN",     "Amazon"},
        {"META",     "Meta Platforms"},
        {"TSLA",     "Tesla"},
        {"JPM",      "JPMorgan Chase"},
    };

    @Inject StockDataService stockDataService;
    @Inject RecommendationService recommendationService;

    void onStart(@Observes StartupEvent event) {
        // Seed watchlist in its own transaction
        QuarkusTransaction.run(() -> {
            if (Stock.count() == 0) {
                LOG.info("Seeding default watchlist...");
                for (String[] s : DEFAULT_STOCKS) {
                    Stock stock = new Stock();
                    stock.symbol = s[0];
                    stock.name   = s[1];
                    stock.active = true;
                    stock.persist();
                }
                LOG.infof("Seeded %d stocks", DEFAULT_STOCKS.length);
            }
        });

        // Collect symbols in a short read-transaction, then fetch outside it
        List<String> symbols = QuarkusTransaction.call(
                () -> Stock.findActive().stream().map(s -> s.symbol).toList()
        );

        LOG.infof("Fetching initial data for %d stocks (this may take a moment)…", symbols.size());
        symbols.forEach(symbol -> {
            try {
                stockDataService.fetchAndStoreQuotes(symbol);
                recommendationService.generateRecommendation(symbol);
            } catch (Exception e) {
                LOG.warnf("Could not fetch data for %s at startup: %s", symbol, e.getMessage());
            }
        });
        LOG.info("Initial market analysis complete.");
    }
}
