package com.muralis.engine;

import com.muralis.model.ConnectionEvent;
import com.muralis.model.ConnectionState;
import com.muralis.model.InstrumentSpec;
import com.muralis.model.MarketEvent;
import com.muralis.model.NormalizedTrade;
import com.muralis.model.OrderBookDelta;
import com.muralis.model.OrderBookSnapshot;
import com.muralis.provider.MarketDataListener;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.LinkedTransferQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Engine thread loop. Consumes the MarketEvent queue, maintains order book
 * and trade buffer state, and writes immutable RenderSnapshots for the UI.
 *
 * Also implements MarketDataListener as a pure forwarding listener: the
 * ingestion thread calls on*() methods, which enqueue events without any
 * inline processing.
 */
public class OrderBookEngine implements MarketDataListener {

    private static final System.Logger LOG =
            System.getLogger(OrderBookEngine.class.getName());

    // ── Wired dependencies ─────────────────────────────────────────────

    private final LinkedTransferQueue<MarketEvent> queue;
    private final AtomicReference<RenderSnapshot>  snapshotRef;
    private final InstrumentSpec                   instrumentSpec;
    private final RenderConfig                     renderConfig;

    // ── Mutable engine-thread state ────────────────────────────────────

    private final OrderBook   orderBook   = new OrderBook();
    private final TradeBuffer tradeBuffer = new TradeBuffer();

    private volatile boolean running        = false;
    private long             lastSnapshotTs = 0L;
    private ConnectionState  connectionState = ConnectionState.CONNECTING;

    // ── Constructor ────────────────────────────────────────────────────

    public OrderBookEngine(
            LinkedTransferQueue<MarketEvent> queue,
            AtomicReference<RenderSnapshot>  snapshotRef,
            InstrumentSpec                   instrumentSpec,
            RenderConfig                     renderConfig) {
        this.queue          = queue;
        this.snapshotRef    = snapshotRef;
        this.instrumentSpec = instrumentSpec;
        this.renderConfig   = renderConfig;
    }

    // ── Lifecycle ──────────────────────────────────────────────────────

    public void start() {
        running = true;
        Thread thread = new Thread(this::runLoop, "muralis-engine");
        thread.setDaemon(false);
        thread.start();
    }

    public void stop() {
        running = false;
    }

    // ── MarketDataListener — forwarding only (ingestion thread) ────────

    @Override public void onSnapshot(OrderBookSnapshot s)    { queue.offer(s); }
    @Override public void onDelta(OrderBookDelta d)          { queue.offer(d); }
    @Override public void onTrade(NormalizedTrade t)         { queue.offer(t); }
    @Override public void onConnectionEvent(ConnectionEvent c) { queue.offer(c); }

    // ── Engine thread loop ─────────────────────────────────────────────

    private void runLoop() {
        while (running) {
            MarketEvent event;
            try {
                event = queue.poll(100, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            if (event == null) continue;  // timeout — loop and re-check running flag

            switch (event) {
                case OrderBookSnapshot s -> applySnapshot(s);
                case OrderBookDelta    d -> applyDelta(d);
                case NormalizedTrade   t -> applyTrade(t);
                case ConnectionEvent   c -> applyConnectionEvent(c);
            }

            snapshotRef.set(buildSnapshot());
        }
    }

    // ── Event handlers (engine thread only) ───────────────────────────

    private void applySnapshot(OrderBookSnapshot s) {
        orderBook.clear();
        for (int i = 0; i < s.bidPrices().length; i++) {
            if (s.bidQtys()[i] > 0L) {
                orderBook.setBid(s.bidPrices()[i], s.bidQtys()[i]);
            }
        }
        for (int i = 0; i < s.askPrices().length; i++) {
            if (s.askQtys()[i] > 0L) {
                orderBook.setAsk(s.askPrices()[i], s.askQtys()[i]);
            }
        }
        lastSnapshotTs = s.exchangeTs();
        LOG.log(System.Logger.Level.DEBUG,
                "[{0}] Snapshot applied. levels=bid:{1} ask:{2}",
                instrumentSpec.symbol(), orderBook.bidDepth(), orderBook.askDepth());
    }

    private void applyDelta(OrderBookDelta d) {
        for (int i = 0; i < d.bidPrices().length; i++) {
            if (d.bidQtys()[i] == 0L) {
                orderBook.removeBid(d.bidPrices()[i]);
            } else {
                orderBook.setBid(d.bidPrices()[i], d.bidQtys()[i]);
            }
        }
        for (int i = 0; i < d.askPrices().length; i++) {
            if (d.askQtys()[i] == 0L) {
                orderBook.removeAsk(d.askPrices()[i]);
            } else {
                orderBook.setAsk(d.askPrices()[i], d.askQtys()[i]);
            }
        }
        lastSnapshotTs = d.exchangeTs();
    }

    private void applyTrade(NormalizedTrade t) {
        if (tradeBuffer.containsTradeId(t.tradeId())) {
            LOG.log(System.Logger.Level.WARNING,
                    "[{0}] Duplicate tradeId={1} discarded",
                    instrumentSpec.symbol(), t.tradeId());
            return;
        }
        TradeBlip blip = new TradeBlip(
                t.tradeId(), t.price(), t.qty(),
                t.aggressorSide(), t.exchangeTs(), t.receivedTs());
        tradeBuffer.add(blip);
    }

    private void applyConnectionEvent(ConnectionEvent c) {
        switch (c.state()) {
            case CONNECTING -> {
                connectionState = ConnectionState.CONNECTING;
                orderBook.clear();
                tradeBuffer.clear();
                LOG.log(System.Logger.Level.INFO,
                        "[{0}] Connecting...", instrumentSpec.symbol());
            }
            case CONNECTED -> {
                connectionState = ConnectionState.CONNECTED;
                LOG.log(System.Logger.Level.INFO,
                        "[{0}] Connected and synced.", instrumentSpec.symbol());
            }
            case RECONNECTING -> {
                connectionState = ConnectionState.RECONNECTING;
                orderBook.clear();
                tradeBuffer.clear();
                LOG.log(System.Logger.Level.WARNING,
                        "[{0}] Reconnecting. Reason: {1}",
                        instrumentSpec.symbol(), c.reason());
            }
            case DISCONNECTED -> {
                connectionState = ConnectionState.DISCONNECTED;
                running = false;
                LOG.log(System.Logger.Level.INFO,
                        "[{0}] Disconnected.", instrumentSpec.symbol());
            }
        }
    }

    // ── Snapshot construction (engine thread only) ─────────────────────

    private RenderSnapshot buildSnapshot() {
        Set<Map.Entry<Long, Long>> bidEntries = orderBook.getBidsDescending();
        Set<Map.Entry<Long, Long>> askEntries = orderBook.getAsksAscending();
        long[][] bidArrays = toArrays(bidEntries);
        long[][] askArrays = toArrays(askEntries);
        List<TradeBlip> activeBlips = tradeBuffer.getActive(renderConfig.bubbleDecayMs());

        // Crossed-book sanity check — log WARN, never throw (spec §3.3)
        long bestBid = orderBook.bestBid();
        long bestAsk = orderBook.bestAsk();
        if (bestBid != -1L && bestAsk != -1L && bestBid >= bestAsk) {
            LOG.log(System.Logger.Level.WARNING,
                    "[{0}] Crossed book: bestBid={1} >= bestAsk={2}",
                    instrumentSpec.symbol(), bestBid, bestAsk);
        }

        return new RenderSnapshot(
                instrumentSpec.symbol(),
                lastSnapshotTs,
                bidArrays[0], bidArrays[1],
                askArrays[0], askArrays[1],
                activeBlips,
                connectionState,
                instrumentSpec
        );
    }

    private static long[][] toArrays(Set<Map.Entry<Long, Long>> entries) {
        int size = entries.size();
        long[] prices = new long[size];
        long[] qtys   = new long[size];
        int i = 0;
        for (Map.Entry<Long, Long> entry : entries) {
            prices[i] = entry.getKey();
            qtys[i]   = entry.getValue();
            i++;
        }
        return new long[][]{ prices, qtys };
    }
}
