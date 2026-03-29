package com.muralis.ingestion;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.muralis.model.InstrumentSpec;
import com.muralis.model.NormalizedTrade;
import com.muralis.model.OrderBookDelta;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;

/**
 * Pure routing shim between the Java-WebSocket library and BinanceAdapter.
 * Contains no business logic — it parses the stream envelope, routes to
 * BinanceMessageParser, and forwards typed events to BinanceAdapter callbacks.
 */
class BinanceWebSocketClient extends WebSocketClient {

    private static final Logger log = LoggerFactory.getLogger(BinanceWebSocketClient.class);
    private static final Gson GSON = new Gson();

    private final BinanceAdapter adapter;
    private final InstrumentSpec spec;

    BinanceWebSocketClient(URI uri, BinanceAdapter adapter, InstrumentSpec spec) {
        super(uri);
        this.adapter = adapter;
        this.spec = spec;
    }

    @Override
    public void onOpen(ServerHandshake handshake) {
        log.info("[{}] WebSocket connected", spec.symbol());
        adapter.onWebSocketOpen();
    }

    @Override
    public void onMessage(String message) {
        try {
            JsonObject envelope = GSON.fromJson(message, JsonObject.class);
            String stream = envelope.get("stream").getAsString();
            JsonObject data = envelope.getAsJsonObject("data");

            if (stream.endsWith("@depth@100ms")) {
                OrderBookDelta delta = BinanceMessageParser.parseDelta(data, spec);
                adapter.onDepthMessage(delta);
            } else if (stream.endsWith("@trade")) {
                NormalizedTrade trade = BinanceMessageParser.parseTrade(data, spec);
                adapter.onTradeMessage(trade);
            } else {
                log.warn("[{}] Unknown stream type received: {}", spec.symbol(), stream);
            }
        } catch (Exception ex) {
            log.error("[{}] Parse error — triggering reconnect. Cause: {}", spec.symbol(), ex.getMessage(), ex);
            adapter.onWebSocketError(ex);
        }
    }

    @Override
    public void onClose(int code, String reason, boolean remote) {
        adapter.onWebSocketClose(code, reason);
    }

    @Override
    public void onError(Exception ex) {
        log.error("[{}] WebSocket error: {}", spec.symbol(), ex.getMessage(), ex);
        adapter.onWebSocketError(ex);
    }
}
