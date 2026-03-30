package com.muralis.ingestion;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.muralis.model.InstrumentSpec;
import com.muralis.model.OrderBookSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

class SnapshotFetcher {

    private static final Logger log = LoggerFactory.getLogger(SnapshotFetcher.class);

    private static final String DEPTH_URL_TEMPLATE =
        "https://fapi.binance.com/fapi/v1/depth?symbol=%s&limit=1000";

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final InstrumentSpec spec;
    private final HttpClient     httpClient;
    private final URI            depthUri;

    SnapshotFetcher(InstrumentSpec spec) {
        this.spec       = spec;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            .build();
        this.depthUri   = URI.create(String.format(DEPTH_URL_TEMPLATE, spec.symbol()));
    }

    OrderBookSnapshot fetch() throws SnapshotFetchException {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(depthUri)
            .timeout(TIMEOUT)
            .GET()
            .build();

        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SnapshotFetchException(e);
        } catch (IOException e) {
            throw new SnapshotFetchException(e);
        }

        int status = response.statusCode();
        if (status != 200) {
            throw new SnapshotFetchException(status);
        }

        try {
            JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();

            // Section 9.2: Futures REST response includes "E" (event time ms) in the body.
            // Use it directly as exchangeTs — more precise than the HTTP Date header.
            // Fall back to System.currentTimeMillis() with WARN if the field is absent.
            long exchangeTs;
            if (json.has("E") && !json.get("E").isJsonNull()) {
                exchangeTs = json.get("E").getAsLong();
            } else {
                log.warn("[{}] Futures snapshot response missing 'E' field — falling back to System.currentTimeMillis()",
                         spec.symbol());
                exchangeTs = System.currentTimeMillis();
            }

            OrderBookSnapshot raw = BinanceMessageParser.parseSnapshot(json, spec);
            return new OrderBookSnapshot(
                raw.symbol(), raw.lastUpdateId(), exchangeTs, raw.receivedTs(),
                raw.bidPrices(), raw.bidQtys(), raw.askPrices(), raw.askQtys()
            );
        } catch (Exception e) {
            throw new SnapshotFetchException(e);
        }
    }

    // ── SnapshotFetchException ────────────────────────────────────────────────

    static final class SnapshotFetchException extends Exception {

        private final int statusCode;

        /** Thrown when the server responds with a non-200 HTTP status. */
        SnapshotFetchException(int statusCode) {
            super("HTTP " + statusCode);
            this.statusCode = statusCode;
        }

        /** Thrown on timeout, I/O error, or JSON parse failure. */
        SnapshotFetchException(Throwable cause) {
            super(cause.getMessage(), cause);
            this.statusCode = -1;
        }

        /** @return The HTTP status code, or -1 for non-HTTP failures. */
        int statusCode() {
            return statusCode;
        }
    }
}
