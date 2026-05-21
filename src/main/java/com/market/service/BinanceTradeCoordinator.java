package com.market.service;

import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

/**
 * Shared lock between BinanceScalpingTradeService and BinanceAutoTradeService.
 *
 * Only one service can hold an active Binance position at a time (One-Way mode,
 * single BTCUSDT symbol). The service that opens a trade acquires the lock;
 * it is released when the trade closes. The other service skips until the lock
 * is free, preventing cancelAllOrders from wiping the other service's SL/TP.
 */
@ApplicationScoped
public class BinanceTradeCoordinator {

    private static final Logger LOG = Logger.getLogger(BinanceTradeCoordinator.class);

    public enum Owner { SCALPING, AUTO_TRADE }

    private volatile Owner owner = null;

    /** Acquires the lock for {@code requester}. Returns true if acquired (or already held). */
    public synchronized boolean tryAcquire(Owner requester) {
        if (owner == null || owner == requester) {
            if (owner == null) LOG.infof("[Coordinator] Verrou acquis par %s", requester);
            owner = requester;
            return true;
        }
        return false;
    }

    /** Forces acquisition regardless of current owner. Use on startup to restore state. */
    public synchronized void forceAcquire(Owner requester) {
        LOG.infof("[Coordinator] Verrou forcé → %s (était: %s)", requester, owner);
        owner = requester;
    }

    /** Releases the lock if held by {@code service}. */
    public synchronized void release(Owner service) {
        if (owner == service) {
            LOG.infof("[Coordinator] Verrou relâché par %s", service);
            owner = null;
        }
    }

    public Owner getOwner()                    { return owner; }
    public boolean isFree()                    { return owner == null; }
    public boolean isOwnedBy(Owner service)    { return owner == service; }
    public boolean isFreeOrOwnedBy(Owner s)    { return owner == null || owner == s; }
}
