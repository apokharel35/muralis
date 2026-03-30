package com.muralis.engine;

import com.muralis.model.OrderBookDelta;
import com.muralis.model.OrderBookSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OrderBookTest {

    private OrderBook book;

    @BeforeEach
    void setUp() {
        book = new OrderBook();
    }

    private OrderBookSnapshot snapshot(long[] bidPrices, long[] bidQtys,
                                        long[] askPrices, long[] askQtys) {
        return new OrderBookSnapshot("BTCUSDT", 1L, 1_000_000L, 1_000_001L,
                bidPrices, bidQtys, askPrices, askQtys);
    }

    private OrderBookDelta delta(long[] bidPrices, long[] bidQtys,
                                  long[] askPrices, long[] askQtys) {
        return new OrderBookDelta("BTCUSDT", 2L, 3L, 1_000_000L, 1_000_001L,
                bidPrices, bidQtys, askPrices, askQtys);
    }

    // ── Snapshot tests ────────────────────────────────────────────────────────

    @Test
    void applySnapshot_populatesBidsAndAsksCorrectly() {
        book.applySnapshot(snapshot(
                new long[]{6708340L, 6708330L},
                new long[]{100L, 200L},
                new long[]{6708350L, 6708360L},
                new long[]{300L, 400L}
        ));

        assertThat(book.getBidQty(6708340L)).isEqualTo(100L);
        assertThat(book.getBidQty(6708330L)).isEqualTo(200L);
        assertThat(book.getAskQty(6708350L)).isEqualTo(300L);
        assertThat(book.getAskQty(6708360L)).isEqualTo(400L);
    }

    @Test
    void applySnapshot_clearsPreviousState() {
        book.applySnapshot(snapshot(
                new long[]{6708340L},
                new long[]{100L},
                new long[]{6708350L},
                new long[]{300L}
        ));
        book.applySnapshot(snapshot(
                new long[]{6700000L},
                new long[]{50L},
                new long[]{6710000L},
                new long[]{60L}
        ));

        assertThat(book.getBidQty(6708340L)).isEqualTo(0L);
        assertThat(book.getAskQty(6708350L)).isEqualTo(0L);
        assertThat(book.getBidQty(6700000L)).isEqualTo(50L);
        assertThat(book.getAskQty(6710000L)).isEqualTo(60L);
    }

    // ── Delta tests ───────────────────────────────────────────────────────────

    @Test
    void applyDelta_qty0_removesPriceLevel() {
        book.applySnapshot(snapshot(
                new long[]{6708340L},
                new long[]{100L},
                new long[]{6708350L},
                new long[]{300L}
        ));
        book.applyDelta(delta(
                new long[]{6708340L}, new long[]{0L},
                new long[]{6708350L}, new long[]{0L}
        ));

        assertThat(book.getBidQty(6708340L)).isEqualTo(0L);
        assertThat(book.getAskQty(6708350L)).isEqualTo(0L);
        assertThat(book.bidDepth()).isEqualTo(0);
        assertThat(book.askDepth()).isEqualTo(0);
    }

    @Test
    void applyDelta_qtyGt0_updatesPriceLevel() {
        book.applySnapshot(snapshot(
                new long[]{6708340L},
                new long[]{100L},
                new long[]{6708350L},
                new long[]{300L}
        ));
        book.applyDelta(delta(
                new long[]{6708340L}, new long[]{500L},
                new long[]{6708350L}, new long[]{600L}
        ));

        assertThat(book.getBidQty(6708340L)).isEqualTo(500L);
        assertThat(book.getAskQty(6708350L)).isEqualTo(600L);
    }

    // ── Best bid / best ask tests ─────────────────────────────────────────────

    @Test
    void bestBid_returnsHighestBidPrice() {
        book.applySnapshot(snapshot(
                new long[]{6708320L, 6708340L, 6708330L},
                new long[]{100L, 200L, 300L},
                new long[]{6708350L},
                new long[]{400L}
        ));

        assertThat(book.bestBid()).isEqualTo(6708340L);
    }

    @Test
    void bestAsk_returnsLowestAskPrice() {
        book.applySnapshot(snapshot(
                new long[]{6708340L},
                new long[]{100L},
                new long[]{6708380L, 6708350L, 6708360L},
                new long[]{100L, 300L, 200L}
        ));

        assertThat(book.bestAsk()).isEqualTo(6708350L);
    }

    @Test
    void bestBid_returnsMinusOne_whenEmpty() {
        assertThat(book.bestBid()).isEqualTo(-1L);
    }

    @Test
    void bestAsk_returnsMinusOne_whenEmpty() {
        assertThat(book.bestAsk()).isEqualTo(-1L);
    }

    // ── Clear test ────────────────────────────────────────────────────────────

    @Test
    void clear_emptiesBothSides() {
        book.applySnapshot(snapshot(
                new long[]{6708340L},
                new long[]{100L},
                new long[]{6708350L},
                new long[]{200L}
        ));
        book.clear();

        assertThat(book.bidDepth()).isEqualTo(0);
        assertThat(book.askDepth()).isEqualTo(0);
        assertThat(book.bestBid()).isEqualTo(-1L);
        assertThat(book.bestAsk()).isEqualTo(-1L);
    }

    // ── Sort-order tests ──────────────────────────────────────────────────────

    @Test
    void bids_sortedDescending_highestFirst() {
        book.applySnapshot(snapshot(
                new long[]{6708320L, 6708340L, 6708330L},
                new long[]{100L, 200L, 300L},
                new long[]{},
                new long[]{}
        ));

        long[] prices = book.bidPrices(3);
        assertThat(prices[0]).isEqualTo(6708340L);
        assertThat(prices[1]).isEqualTo(6708330L);
        assertThat(prices[2]).isEqualTo(6708320L);
    }

    @Test
    void asks_sortedAscending_lowestFirst() {
        book.applySnapshot(snapshot(
                new long[]{},
                new long[]{},
                new long[]{6708360L, 6708350L, 6708370L},
                new long[]{100L, 200L, 300L}
        ));

        long[] prices = book.askPrices(3);
        assertThat(prices[0]).isEqualTo(6708350L);
        assertThat(prices[1]).isEqualTo(6708360L);
        assertThat(prices[2]).isEqualTo(6708370L);
    }
}
