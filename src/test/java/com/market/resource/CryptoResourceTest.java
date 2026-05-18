package com.market.resource;

import com.market.model.BitcoinSignal;
import com.market.model.ScalpingSignal;
import com.market.service.CryptoAnalysisService;
import com.market.service.ScalpingAnalysisService;
import com.market.service.TelegramAlertService;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectMock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.mockito.Mockito.*;

/**
 * Integration tests for CryptoResource (/api/crypto).
 * CryptoAnalysisService, ScalpingAnalysisService and TelegramAlertService are mocked.
 */
@QuarkusTest
class CryptoResourceTest {

    @InjectMock
    CryptoAnalysisService cryptoService;

    @InjectMock
    ScalpingAnalysisService scalpingService;

    @InjectMock
    TelegramAlertService telegramAlertService;

    @BeforeEach
    void setup() {
        when(cryptoService.getSignal()).thenReturn(btcSignal("LONG", 72, 95_000.0));
        when(scalpingService.getSignal()).thenReturn(scalpingSignal("WAIT", 50, 95_000.0));
        when(telegramAlertService.isEnabled()).thenReturn(false);
    }

    // ── GET /api/crypto/btc/signal ────────────────────────────────────────────

    @Test
    void getBtcSignal_returns200_withDirectionAndConfidence() {
        given()
            .when().get("/api/crypto/btc/signal")
            .then()
            .statusCode(200)
            .body("direction",    equalTo("LONG"))
            .body("confidence",   equalTo(72))
            .body("currentPrice", equalTo(95_000.0f));
    }

    @Test
    void getBtcSignal_short_returnsShort() {
        when(cryptoService.getSignal()).thenReturn(btcSignal("SHORT", 28, 94_000.0));

        given()
            .when().get("/api/crypto/btc/signal")
            .then()
            .statusCode(200)
            .body("direction",  equalTo("SHORT"))
            .body("confidence", equalTo(28));
    }

    @Test
    void getBtcSignal_withError_stillReturns200() {
        BitcoinSignal err = new BitcoinSignal();
        err.error = "Connection refused";
        when(cryptoService.getSignal()).thenReturn(err);

        given()
            .when().get("/api/crypto/btc/signal")
            .then()
            .statusCode(200)
            .body("error", equalTo("Connection refused"));
    }

    // ── GET /api/crypto/btc/scalping ─────────────────────────────────────────

    @Test
    void getScalpingSignal_returns200_withAllIndicators() {
        ScalpingSignal s = scalpingSignal("LONG", 78, 95_000.0);
        s.rsi7            = 28.5;
        s.ema5            = 94_950.0;
        s.ema13           = 94_800.0;
        s.macdHistogram   = 12.5;
        s.stochK          = 18.0;
        s.volumeDeltaPct  = 65.0;
        s.bbState         = "NORMAL";
        when(scalpingService.getSignal()).thenReturn(s);

        given()
            .when().get("/api/crypto/btc/scalping")
            .then()
            .statusCode(200)
            .body("direction",       equalTo("LONG"))
            .body("confidence",      equalTo(78))
            .body("rsi7",            equalTo(28.5f))
            .body("macdHistogram",   equalTo(12.5f))
            .body("volumeDeltaPct",  equalTo(65.0f))
            .body("bbState",         equalTo("NORMAL"));
    }

    @Test
    void getScalpingSignal_wait_returnsWait() {
        given()
            .when().get("/api/crypto/btc/scalping")
            .then()
            .statusCode(200)
            .body("direction",  equalTo("WAIT"))
            .body("confidence", equalTo(50));
    }

    @Test
    void getScalpingSignal_withError_stillReturns200() {
        ScalpingSignal err = new ScalpingSignal();
        err.error = "Not enough candle data";
        when(scalpingService.getSignal()).thenReturn(err);

        given()
            .when().get("/api/crypto/btc/scalping")
            .then()
            .statusCode(200)
            .body("error", equalTo("Not enough candle data"));
    }

    // ── POST /api/crypto/btc/telegram-test ───────────────────────────────────

    @Test
    void telegramTest_notEnabled_returns503() {
        when(telegramAlertService.isEnabled()).thenReturn(false);

        given()
            .contentType("application/json")
            .when().post("/api/crypto/btc/telegram-test")
            .then()
            .statusCode(503)
            .body("error", containsString("not configured"));
    }

    @Test
    void telegramTest_enabled_returns200_andSendsAlert() {
        when(telegramAlertService.isEnabled()).thenReturn(true);
        when(cryptoService.getSignal()).thenReturn(btcSignal("LONG", 72, 95_000.0));

        given()
            .contentType("application/json")
            .when().post("/api/crypto/btc/telegram-test")
            .then()
            .statusCode(200)
            .body("status",    equalTo("sent"))
            .body("direction", equalTo("LONG"))
            .body("price",     equalTo(95_000.0f));

        verify(telegramAlertService).sendTest(org.mockito.ArgumentMatchers.any(BitcoinSignal.class));
    }

    @Test
    void telegramTest_enabled_signalError_returns500() {
        when(telegramAlertService.isEnabled()).thenReturn(true);
        BitcoinSignal err = new BitcoinSignal();
        err.error = "API timeout";
        when(cryptoService.getSignal()).thenReturn(err);

        given()
            .contentType("application/json")
            .when().post("/api/crypto/btc/telegram-test")
            .then()
            .statusCode(500)
            .body("error", equalTo("API timeout"));

        verify(telegramAlertService, never()).sendTest(any());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private BitcoinSignal btcSignal(String dir, int conf, double price) {
        BitcoinSignal s = new BitcoinSignal();
        s.direction    = dir;
        s.confidence   = conf;
        s.currentPrice = price;
        return s;
    }

    private ScalpingSignal scalpingSignal(String dir, int conf, double price) {
        ScalpingSignal s = new ScalpingSignal();
        s.direction    = dir;
        s.confidence   = conf;
        s.currentPrice = price;
        return s;
    }
}
