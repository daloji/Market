package com.market.service;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for BinanceFuturesService.
 *
 * - isConfigured / isTestnet: tested on locally-created instances so we can
 *   control the field values without wrestling with SmallRye Config's empty-string
 *   rejection in the test profile.
 * - sign(): tested on the CDI-injected bean (which has a non-empty secret from
 *   application.properties, so HMAC-SHA256 works).
 */
@QuarkusTest
class BinanceFuturesServiceTest {

    /** Injected real bean – has non-empty secret/api-key from application.properties. */
    @Inject
    BinanceFuturesService svc;

    // ── isConfigured ─────────────────────────────────────────────────────────

    @Test
    void isConfigured_noKeys_returnsFalse() {
        // Create a plain instance and set fields directly (same package → accessible)
        BinanceFuturesService s = new BinanceFuturesService();
        s.apiKey = "";
        s.secret = "";
        assertFalse(s.isConfigured());
    }

    @Test
    void isConfigured_bothKeysPresent_returnsTrue() {
        BinanceFuturesService s = new BinanceFuturesService();
        s.apiKey = "my-api-key";
        s.secret = "my-secret";
        assertTrue(s.isConfigured());
    }

    // ── isTestnet ─────────────────────────────────────────────────────────────

    @Test
    void isTestnet_defaultFalse() {
        BinanceFuturesService s = new BinanceFuturesService();
        s.testnet = false;
        assertFalse(s.isTestnet());
    }

    @Test
    void isTestnet_whenTrue_returnsTrue() {
        BinanceFuturesService s = new BinanceFuturesService();
        s.testnet = true;
        assertTrue(s.isTestnet());
    }

    // ── sign() ────────────────────────────────────────────────────────────────

    @Test
    void sign_returns64HexChars() throws Exception {
        String result = svc.sign("symbol=BTCUSDT&timestamp=1234567890");
        assertEquals(64, result.length(), "HMAC-SHA256 hex output must be 64 characters");
        assertTrue(result.matches("[0-9a-f]{64}"), "Output must match lowercase hex pattern");
    }

    @Test
    void sign_deterministicForSameInput() throws Exception {
        String input = "symbol=BTCUSDT&timestamp=1234567890";
        String first  = svc.sign(input);
        String second = svc.sign(input);
        assertEquals(first, second, "HMAC is deterministic for the same key and input");
    }

    @Test
    void sign_differentInputsProduceDifferentOutputs() throws Exception {
        String s1 = svc.sign("data=one");
        String s2 = svc.sign("data=two");
        assertNotEquals(s1, s2, "Different inputs must produce different HMAC outputs");
    }

    @Test
    void sign_doesNotThrow() {
        // The injected bean has a non-empty secret from application.properties → must not throw
        assertDoesNotThrow(() -> svc.sign("hello world"));
    }

    // ── getOpenInterestHistory ────────────────────────────────────────────────

    @Test
    void getOpenInterestHistory_methodExists() {
        // Verify the method is declared with the expected signature
        assertDoesNotThrow(() -> {
            BinanceFuturesService.class.getMethod("getOpenInterestHistory",
                    String.class, String.class, int.class);
        });
    }
}

