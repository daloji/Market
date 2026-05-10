package com.market.service;

import io.quarkus.mailer.Mail;
import io.quarkus.mailer.Mailer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import com.market.model.StockRecommendation;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

@ApplicationScoped
public class AlertService {

    private static final Logger LOG = Logger.getLogger(AlertService.class);

    @Inject
    Mailer mailer;

    @ConfigProperty(name = "market.alert.email", defaultValue = "")
    Optional<String> alertEmail;

    /**
     * Sends a BUY alert email. Does nothing if {@code market.alert.email} is not set.
     */
    public void sendBuyAlert(StockRecommendation rec) {
        if (alertEmail.isEmpty()) {
            LOG.debugf("Alert email not configured — skipping BUY alert for %s", rec.symbol);
            return;
        }
        String email = alertEmail.get();
        try {
            String subject = String.format("🚀 BUY Signal: %s (score %d/100)", rec.symbol, rec.score);
            String body = buildBody(rec);
            mailer.send(Mail.withText(email, subject, body));
            LOG.infof("BUY alert sent for %s to %s", rec.symbol, email);
        } catch (Exception e) {
            LOG.errorf(e, "Failed to send alert for %s", rec.symbol);
        }
    }

    private String buildBody(StockRecommendation rec) {
        StringBuilder sb = new StringBuilder();
        sb.append("═══════════════════════════════\n");
        sb.append("  🚀 SIGNAL D'ACHAT DÉTECTÉ\n");
        sb.append("═══════════════════════════════\n\n");
        sb.append(String.format("Action  : %s\n", rec.symbol));
        sb.append(String.format("Prix    : %.2f\n", rec.currentPrice));
        sb.append(String.format("Score   : %d / 100\n", rec.score));
        sb.append(String.format("RSI     : %.1f\n", rec.rsi));
        sb.append(String.format("SMA20   : %.2f\n", rec.sma20));
        sb.append(String.format("SMA50   : %.2f\n", rec.sma50));
        if (rec.macdHistogram != null) {
            sb.append(String.format("MACD    : %.4f (histogramme)\n", rec.macdHistogram));
        }
        sb.append(String.format("\nAnalyse : %s\n", rec.reasons));
        sb.append(String.format("\nHorodatage : %s\n", rec.timestamp));
        sb.append("\n⚠️  Ceci n'est pas un conseil financier.\n");
        return sb.toString();
    }
}
