package com.muralis.engine;

import com.muralis.model.AggressorSide;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TradeBufferTest {

    private static final int MAX_BLIPS = 500;

    private TradeBuffer buffer;

    @BeforeEach
    void setUp() {
        buffer = new TradeBuffer();
    }

    private TradeBlip blip(long tradeId, long receivedTs) {
        return new TradeBlip(tradeId, 6708340L, 41L, AggressorSide.BUY, 1_000_000L, receivedTs);
    }

    // ── add() tests ───────────────────────────────────────────────────────────

    @Test
    void add_storesBlipCorrectly() {
        long now = System.currentTimeMillis();
        TradeBlip blip = blip(1L, now);
        buffer.add(blip);

        assertThat(buffer.containsTradeId(1L)).isTrue();
        assertThat(buffer.getActive(Long.MAX_VALUE)).containsExactly(blip);
    }

    @Test
    void add_beyondMaxBlips_evictsOldestBlip() {
        long now = System.currentTimeMillis();
        for (int i = 1; i <= MAX_BLIPS; i++) {
            buffer.add(blip((long) i, now));
        }
        // tradeId=1 is the oldest; adding 501 should evict it
        buffer.add(blip(501L, now));

        assertThat(buffer.containsTradeId(1L)).isFalse();
        assertThat(buffer.containsTradeId(501L)).isTrue();
    }

    // ── containsTradeId() tests ───────────────────────────────────────────────

    @Test
    void containsTradeId_returnsTrueForAddedTradeId() {
        buffer.add(blip(42L, System.currentTimeMillis()));

        assertThat(buffer.containsTradeId(42L)).isTrue();
    }

    @Test
    void containsTradeId_returnsFalseAfterEviction() {
        long now = System.currentTimeMillis();
        for (int i = 1; i <= MAX_BLIPS; i++) {
            buffer.add(blip((long) i, now));
        }
        buffer.add(blip(501L, now)); // evicts tradeId=1

        assertThat(buffer.containsTradeId(1L)).isFalse();
    }

    // ── getActive() tests ─────────────────────────────────────────────────────

    @Test
    void getActive_returnsAllBlipsWithinDecayWindow() {
        long now = System.currentTimeMillis();
        buffer.add(blip(1L, now));
        buffer.add(blip(2L, now));

        List<TradeBlip> active = buffer.getActive(5_000L);
        assertThat(active).hasSize(2);
    }

    @Test
    void getActive_excludesBlipsOlderThanDecayMs() {
        long old = System.currentTimeMillis() - 10_000L; // 10s ago — outside 5s window
        long now = System.currentTimeMillis();
        buffer.add(blip(1L, old));
        buffer.add(blip(2L, now));

        List<TradeBlip> active = buffer.getActive(5_000L);
        assertThat(active).hasSize(1);
        assertThat(active.get(0).tradeId()).isEqualTo(2L);
    }

    @Test
    void getActive_usesReceivedTs_notExchangeTs() {
        long now = System.currentTimeMillis();
        // exchangeTs is an ancient value; receivedTs is current
        TradeBlip blip = new TradeBlip(99L, 6708340L, 41L, AggressorSide.BUY, 1L, now);
        buffer.add(blip);

        // Should be returned because receivedTs is within the 5s window
        List<TradeBlip> active = buffer.getActive(5_000L);
        assertThat(active).hasSize(1);
        assertThat(active.get(0).tradeId()).isEqualTo(99L);
    }

    // ── Duplicate tradeId test ────────────────────────────────────────────────

    @Test
    void duplicateTradeId_rejectedNotAddedTwice() {
        long now = System.currentTimeMillis();
        buffer.add(blip(7L, now));

        // Simulate the OrderBookEngine.applyTrade() duplicate-check pattern:
        // caller must check containsTradeId before calling add().
        assertThat(buffer.containsTradeId(7L)).isTrue();
        // Because containsTradeId returns true, add() is NOT called again.
        // Buffer should still hold exactly one blip.
        assertThat(buffer.getActive(Long.MAX_VALUE)).hasSize(1);
    }

    // ── clear() test ──────────────────────────────────────────────────────────

    @Test
    void clear_emptiesBufferAndSeenTradeIds() {
        long now = System.currentTimeMillis();
        buffer.add(blip(1L, now));
        buffer.add(blip(2L, now));
        buffer.clear();

        assertThat(buffer.containsTradeId(1L)).isFalse();
        assertThat(buffer.containsTradeId(2L)).isFalse();
        assertThat(buffer.getActive(Long.MAX_VALUE)).isEmpty();
    }
}
