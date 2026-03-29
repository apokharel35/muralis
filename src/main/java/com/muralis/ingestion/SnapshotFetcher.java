package com.muralis.ingestion;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.muralis.model.InstrumentSpec;
import com.muralis.model.OrderBookSnapshot;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Optional;

class SnapshotFetcher {

    private static final String DEPTH_URL_TEMPLATE =
        "https://api.binance.com/api/v3/depth?symbol=%s&limit=1000";

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

        // Determine exchangeTs from the HTTP Date response header.
        // The Binance REST snapshot body contains no server-side timestamp field,
        // so the response Date header is the closest proxy for exchange time.
        // If the header is absent or unparseable, we fall back to
        // System.currentTimeMillis() at body receipt — a known approximation
        // documented in SPEC-ingestion.md §9.2. The Date header has only
        // second-level precision (RFC 7231), so sub-second error is expected.
        long exchangeTs = parseHttpDate(response.headers().firstValue("Date"));

        try {
            JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
            OrderBookSnapshot raw = BinanceMessageParser.parseSnapshot(json, spec);
            // BinanceMessageParser sets exchangeTs = receivedTs (no header knowledge).
            // Replace it with the HTTP-header-derived value computed above.
            return new OrderBookSnapshot(
                raw.symbol(), raw.lastUpdateId(), exchangeTs, raw.receivedTs(),
                raw.bidPrices(), raw.bidQtys(), raw.askPrices(), raw.askQtys()
            );
        } catch (Exception e) {
            throw new SnapshotFetchException(e);
        }
    }

    /**
     * Parses an HTTP {@code Date} header (RFC 1123) to epoch milliseconds.
     * Returns {@link System#currentTimeMillis()} if the header is absent or
     * cannot be parsed. Note: RFC 1123 Date has 1-second resolution only.
     */
    private static long parseHttpDate(Optional<String> dateHeader) {
        if (dateHeader.isEmpty()) {
            return System.currentTimeMillis();
        }
        try {
            ZonedDateTime zdt = ZonedDateTime.parse(
                dateHeader.get(), DateTimeFormatter.RFC_1123_DATE_TIME);
            return zdt.toInstant().toEpochMilli();
        } catch (DateTimeParseException e) {
            return System.currentTimeMillis();
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
