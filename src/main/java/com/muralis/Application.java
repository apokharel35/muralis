package com.muralis;

import com.muralis.engine.OrderBookEngine;
import com.muralis.engine.RenderConfig;
import com.muralis.engine.RenderSnapshot;
import com.muralis.ingestion.BinanceAdapter;
import com.muralis.model.InstrumentSpec;
import com.muralis.model.MarketEvent;
import com.muralis.provider.MarketDataProvider;
import com.muralis.provider.ProviderConfig;
import com.muralis.provider.ProviderType;
import com.muralis.ui.MuralisApp;

import java.util.concurrent.LinkedTransferQueue;
import java.util.concurrent.atomic.AtomicReference;

public class Application {

    public static void main(String[] args) {
        // Binance USDⓈ-M Futures — see ADR-001 in ARCHITECTURE.md Section 8
        // priceScale=1  → prices have 1 decimal place  (tick = 0.1 USDT)
        // qtyScale=3    → quantities have 3 decimal places (min = 0.001 BTC)
        InstrumentSpec instrumentSpec = new InstrumentSpec(
                "BTCUSDT",
                2,
                1L,
                3,
                1L,
                "USDT",
                ProviderType.BINANCE_FUTURES
        );

        LinkedTransferQueue<MarketEvent>   queue       = new LinkedTransferQueue<>();
        AtomicReference<RenderSnapshot>    snapshotRef = new AtomicReference<>();
        RenderConfig                       renderConfig = new RenderConfig();

        OrderBookEngine engine = new OrderBookEngine(queue, snapshotRef, instrumentSpec, renderConfig);

        // ── PROVIDER SEAM ─────────────────────────────────────────────────
        // Phase 1: BinanceAdapter targeting USDⓈ-M Futures is the only
        //          provider. Hardcoded here. (ADR-001: Spot geo-blocked in US)
        // Phase 2: Replace these two lines with ServiceLoader discovery
        //          (see SPEC-provider-spi.md Section 6 for upgrade path).
        // ──────────────────────────────────────────────────────────────────
        MarketDataProvider provider = new BinanceAdapter(queue, instrumentSpec);

        engine.start();

        provider.addListener(engine);

        MuralisApp.snapshotRef       = snapshotRef;
        MuralisApp.renderConfig      = renderConfig;
        MuralisApp.shutdownCallback  = provider::disconnect;
        MuralisApp.deltaResetCallback  = () -> engine.requestDeltaReset();
        MuralisApp.volumeResetCallback = () -> engine.requestVolumeReset();

        provider.connect(ProviderConfig.defaultFor("BTCUSDT"));

        javafx.application.Application.launch(MuralisApp.class, args);
    }
}
