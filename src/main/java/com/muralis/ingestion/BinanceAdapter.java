package com.muralis.ingestion;

import com.google.gson.JsonObject;
import com.muralis.model.ConnectionEvent;
import com.muralis.model.ConnectionState;
import com.muralis.model.InstrumentSpec;
import com.muralis.model.MarketEvent;
import com.muralis.model.NormalizedTrade;
import com.muralis.model.OrderBookDelta;
import com.muralis.model.OrderBookSnapshot;
import com.muralis.provider.MarketDataListener;
import com.muralis.provider.MarketDataProvider;
import com.muralis.provider.ProviderConfig;
import com.muralis.provider.ProviderType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedTransferQueue;
import java.util.concurrent.atomic.AtomicReference;

public class BinanceAdapter implements MarketDataProvider {

    private static final Logger log = LoggerFactory.getLogger(BinanceAdapter.class);
    private static final String DEFAULT_WS_BASE = "wss://fstream.binance.com/stream?streams=";
    /** Backoff durations indexed by attempt number (1-based); index 0 unused. */
    private static final long[] BACKOFF_MS = {0L, 1_000L, 2_000L, 5_000L, 10_000L, 30_000L};

    private final LinkedTransferQueue<MarketEvent> queue;
    private final InstrumentSpec spec;
    private final List<MarketDataListener> listeners;
    private final AtomicReference<ConnectionState> state;
    private volatile BinanceWebSocketClient wsClient;
    private volatile long lastPublishedFinalUpdateId = -1L;
    /** True after the @depth20 snapshot is published but before the first live diff is accepted. */
    private boolean awaitingFirstDiff = false;
    private final Object reconnectLock = new Object();
    private int reconnectCount = 0;
    private long disconnectTs = -1L;   // Phase 2 placeholder: record on RECONNECTING
    private volatile String lastWsUrl;

    public BinanceAdapter(LinkedTransferQueue<MarketEvent> queue, InstrumentSpec spec) {
        this.queue = queue;
        this.spec = spec;
        this.listeners = new ArrayList<>();
        this.state = new AtomicReference<>(ConnectionState.DISCONNECTED);
    }

    @Override
    public String getName() {
        return null;
    }

    @Override
    public ProviderType getType() {
        return null;
    }

    @Override
    public void connect(ProviderConfig config) {
        if (!state.compareAndSet(ConnectionState.DISCONNECTED, ConnectionState.CONNECTING)) {
            throw new IllegalStateException("connect() already called");
        }
        if (!spec.symbol().equals(config.symbol())) {
            throw new IllegalArgumentException(
                "config.symbol() '" + config.symbol() + "' does not match spec.symbol() '" + spec.symbol() + "'");
        }

        ConnectionEvent connecting = new ConnectionEvent(
            spec.symbol(), ConnectionState.CONNECTING, System.currentTimeMillis(), "Initiating connection");
        for (MarketDataListener l : listeners) {
            l.onConnectionEvent(connecting);
        }

        // Symbol must be lowercase per Binance combined-stream URL requirement
        String symbolLower = spec.symbol().toLowerCase();
        String streams = symbolLower + "@depth20@100ms/" + symbolLower + "@depth@100ms/" + symbolLower + "@aggTrade";
        lastWsUrl = (config.wsUrlOverride() != null)
            ? config.wsUrlOverride()
            : DEFAULT_WS_BASE + streams;

        try {
            wsClient = new BinanceWebSocketClient(new URI(lastWsUrl), this, spec);
            wsClient.connectBlocking();
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Invalid WebSocket URL: " + lastWsUrl, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while connecting", e);
        }
    }

    @Override
    public void disconnect() {
        if (state.getAndSet(ConnectionState.DISCONNECTED) == ConnectionState.DISCONNECTED) {
            return; // idempotent
        }
        log.info("[{}] Disconnect requested. Closing.", spec.symbol());
        synchronized (reconnectLock) {
            reconnectLock.notifyAll(); // unblock any waiting reconnect backoff
        }
        BinanceWebSocketClient client = wsClient;
        if (client != null) {
            try {
                client.closeBlocking();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        ConnectionEvent evt = new ConnectionEvent(
            spec.symbol(), ConnectionState.DISCONNECTED,
            System.currentTimeMillis(), "Disconnect requested");
        for (MarketDataListener l : listeners) {
            l.onConnectionEvent(evt);
        }
    }

    @Override
    public void addListener(MarketDataListener listener) {
    }

    @Override
    public InstrumentSpec getInstrumentSpec() {
        return null;
    }

    @Override
    public ConnectionState getConnectionState() {
        return state.get();
    }

    void onWebSocketOpen() {
        state.set(ConnectionState.CONNECTED);
        // CONNECTED listener event is deferred until the @depth20 snapshot is accepted
    }

    void handleDepth20Message(JsonObject json) {
        // Once the snapshot has been published, ignore all subsequent @depth20 messages
        if (lastPublishedFinalUpdateId != -1L) {
            return;
        }

        // Accept the first @depth20 immediately — no pu-chain validation needed
        OrderBookSnapshot snapshot = BinanceMessageParser.parseDepth20Snapshot(json, spec);
        queue.offer(snapshot);
        lastPublishedFinalUpdateId = snapshot.lastUpdateId();
        awaitingFirstDiff = true;

        log.info("[{}] @depth20 snapshot accepted. lastUpdateId={}", spec.symbol(), snapshot.lastUpdateId());

        ConnectionEvent connected = new ConnectionEvent(
            spec.symbol(), ConnectionState.CONNECTED, System.currentTimeMillis(), "Order book synced");
        for (MarketDataListener l : listeners) {
            l.onConnectionEvent(connected);
        }
    }

    void handleDepthMessage(JsonObject json) {
        // Discard all @depth diffs received before the @depth20 snapshot is accepted
        if (lastPublishedFinalUpdateId == -1L) {
            return;
        }

        OrderBookDelta delta = BinanceMessageParser.parseDelta(json, spec);

        // First diff after snapshot: accept unconditionally to anchor the pu-chain.
        // The @depth20 and @depth streams use different update ID sequences, so the
        // first diff's pu will not match the snapshot's lastUpdateId — skip the check.
        if (awaitingFirstDiff) {
            awaitingFirstDiff = false;
            lastPublishedFinalUpdateId = delta.finalUpdateId();
            queue.offer(delta);
            log.info("[{}] First diff anchored. finalUpdateId={}", spec.symbol(), delta.finalUpdateId());
            return;
        }

        // Stale: diff is fully covered by already-published state
        if (delta.finalUpdateId() <= lastPublishedFinalUpdateId) {
            log.debug("[{}] Stale diff discarded. finalUpdateId={} lastPublished={}",
                      spec.symbol(), delta.finalUpdateId(), lastPublishedFinalUpdateId);
            return;
        }

        long pu = BinanceMessageParser.parsePu(json);
        if (pu == lastPublishedFinalUpdateId) {
            // Valid next diff in the pu-chain — publish
            if (!queue.offer(delta)) {
                log.warn("Queue rejected event — type={}", delta.getClass().getSimpleName());
            } else {
                lastPublishedFinalUpdateId = delta.finalUpdateId();
            }
        } else {
            // pu does not match — real gap in the diff stream
            log.warn("[{}] Gap detected: pu={}, lastPublished={}", spec.symbol(), pu, lastPublishedFinalUpdateId);
            ConnectionEvent reconnecting = new ConnectionEvent(
                spec.symbol(), ConnectionState.RECONNECTING, System.currentTimeMillis(),
                "Gap detected: pu=" + pu + ", lastPublished=" + lastPublishedFinalUpdateId);
            for (MarketDataListener l : listeners) {
                l.onConnectionEvent(reconnecting);
            }
            state.set(ConnectionState.RECONNECTING);
        }
    }

    void handleAggTradeMessage(JsonObject json) {
        // Suppress trades during bootstrap — engine has no book to correlate against yet
        if (lastPublishedFinalUpdateId == -1L) {
            return;
        }
        NormalizedTrade trade = BinanceMessageParser.parseAggTrade(json, spec);
        if (!queue.offer(trade)) {
            log.warn("Queue rejected event — type={}", trade.getClass().getSimpleName());
        }
    }

    void onWebSocketClose(int code, String reason) {
        if (state.get() == ConnectionState.DISCONNECTED) return;

        String closeReason = (reason != null && !reason.isEmpty())
            ? reason
            : "WebSocket closed, code=" + code;

        while (state.get() != ConnectionState.DISCONNECTED) {
            reconnectCount++;

            if (reconnectCount > 5) {
                state.set(ConnectionState.DISCONNECTED);
                ConnectionEvent evt = new ConnectionEvent(
                    spec.symbol(), ConnectionState.DISCONNECTED,
                    System.currentTimeMillis(), "Max reconnect attempts exceeded");
                for (MarketDataListener l : listeners) {
                    l.onConnectionEvent(evt);
                }
                return;
            }

            long waitMs = BACKOFF_MS[Math.min(reconnectCount, BACKOFF_MS.length - 1)];
            if (waitMs > 0) {
                synchronized (reconnectLock) {
                    try {
                        reconnectLock.wait(waitMs);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }

            if (state.get() == ConnectionState.DISCONNECTED) return;

            log.info("[{}] Reconnecting, attempt {}. Reason: {}", spec.symbol(), reconnectCount, closeReason);

            disconnectTs = System.currentTimeMillis();
            lastPublishedFinalUpdateId = -1L;
            awaitingFirstDiff = false;
            state.set(ConnectionState.RECONNECTING);

            ConnectionEvent reconnecting = new ConnectionEvent(
                spec.symbol(), ConnectionState.RECONNECTING,
                System.currentTimeMillis(), closeReason);
            for (MarketDataListener l : listeners) {
                l.onConnectionEvent(reconnecting);
            }

            try {
                wsClient = new BinanceWebSocketClient(new URI(lastWsUrl), this, spec);
                wsClient.connectBlocking();
                return; // connection established; onWebSocketOpen() completes bootstrap
            } catch (URISyntaxException e) {
                log.error("[{}] WebSocket error: {}", spec.symbol(), e.getMessage(), e);
                return; // bad URL is unrecoverable
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                log.error("[{}] WebSocket error: {}", spec.symbol(), e.getMessage(), e);
                // loop to next attempt
            }
        }
    }

    void onWebSocketError(Exception ex) {
        log.error("[{}] WebSocket error: {}", spec.symbol(), ex.getMessage(), ex);
        // onWebSocketClose() follows automatically from the Java-WebSocket library
    }
}
