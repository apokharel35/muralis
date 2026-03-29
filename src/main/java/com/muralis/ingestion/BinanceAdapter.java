package com.muralis.ingestion;

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
    private static final String DEFAULT_WS_BASE = "wss://stream.binance.com:9443/stream?streams=";
    /** Backoff durations indexed by attempt number (1-based); index 0 unused. */
    private static final long[] BACKOFF_MS = {0L, 1_000L, 2_000L, 5_000L, 10_000L, 30_000L};

    private final LinkedTransferQueue<MarketEvent> queue;
    private final InstrumentSpec spec;
    private final List<MarketDataListener> listeners;
    private final AtomicReference<ConnectionState> state;
    private volatile BinanceWebSocketClient wsClient;
    private volatile long lastPublishedFinalUpdateId = -1L;
    private final List<OrderBookDelta> preBuffer;
    private final Object reconnectLock = new Object();
    private int reconnectCount = 0;
    private long disconnectTs = -1L;   // Phase 2 placeholder: record on RECONNECTING
    private volatile String lastWsUrl;

    public BinanceAdapter(LinkedTransferQueue<MarketEvent> queue, InstrumentSpec spec) {
        this.queue = queue;
        this.spec = spec;
        this.listeners = new ArrayList<>();
        this.state = new AtomicReference<>(ConnectionState.DISCONNECTED);
        this.preBuffer = new ArrayList<>();
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
        String streams = symbolLower + "@depth@100ms/" + symbolLower + "@trade";
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

        ConnectionEvent connected = new ConnectionEvent(
            spec.symbol(), ConnectionState.CONNECTED, System.currentTimeMillis(), "WebSocket open");
        for (MarketDataListener l : listeners) {
            l.onConnectionEvent(connected);
        }

        SnapshotFetcher fetcher = new SnapshotFetcher(spec);
        OrderBookSnapshot snapshot;
        try {
            snapshot = fetcher.fetch();
        } catch (SnapshotFetcher.SnapshotFetchException e) {
            log.error("[{}] Failed to fetch snapshot: {}", spec.symbol(), e.getMessage(), e);
            return;
        }

        queue.offer(snapshot);
        lastPublishedFinalUpdateId = snapshot.lastUpdateId();

        // Drain pre-buffer: publish all deltas that overlap or immediately follow the snapshot
        for (OrderBookDelta delta : preBuffer) {
            if (delta.firstUpdateId() <= lastPublishedFinalUpdateId + 1) {
                queue.offer(delta);
                lastPublishedFinalUpdateId = delta.finalUpdateId();
            }
        }
        preBuffer.clear();

        log.info("[{}] Order book synced. lastUpdateId={}", spec.symbol(), lastPublishedFinalUpdateId);
    }

    void onDepthMessage(OrderBookDelta delta) {
        // Pre-bootstrap: buffer all deltas until the snapshot has been published
        if (lastPublishedFinalUpdateId == -1L) {
            preBuffer.add(delta);
            return;
        }

        // Duplicate: delta is fully covered by already-published state
        if (delta.finalUpdateId() <= lastPublishedFinalUpdateId) {
            log.debug("[{}] Duplicate delta discarded. finalUpdateId={}", spec.symbol(), delta.finalUpdateId());
            return;
        }

        // Gap: sequence break — trigger reconnect
        long expected = lastPublishedFinalUpdateId + 1;
        if (delta.firstUpdateId() > expected) {
            log.warn("[{}] Gap detected: expected={}, got={}", spec.symbol(), expected, delta.firstUpdateId());
            ConnectionEvent reconnecting = new ConnectionEvent(
                spec.symbol(), ConnectionState.RECONNECTING, System.currentTimeMillis(),
                "Gap detected: expected=" + expected + ", got=" + delta.firstUpdateId());
            for (MarketDataListener l : listeners) {
                l.onConnectionEvent(reconnecting);
            }
            state.set(ConnectionState.RECONNECTING);
            return;
        }

        if (!queue.offer(delta)) {
            log.warn("Queue rejected event — type={}", delta.getClass().getSimpleName());
        } else {
            lastPublishedFinalUpdateId = delta.finalUpdateId();
        }
    }

    void onTradeMessage(NormalizedTrade trade) {
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
            preBuffer.clear();
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
