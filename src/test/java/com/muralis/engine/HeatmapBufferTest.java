package com.muralis.engine;

import com.muralis.model.AggressorSide;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HeatmapBufferTest {

    private static HeatmapColumn col(long timestamp) {
        return new HeatmapColumn(
                timestamp, 0L, 0L,
                new long[0], new long[0], new TradeBlip[0]);
    }

    // --- sequential write and read-back ---

    @Test
    void writeAndReadBack_fiveColumns() {
        HeatmapBuffer buf = new HeatmapBuffer(10);

        for (int i = 0; i < 5; i++) {
            buf.writeColumn(col(1000L + i));
        }

        assertThat(buf.getWriteIndex()).isEqualTo(5);
        for (int i = 0; i < 5; i++) {
            HeatmapColumn c = buf.getColumn(i);
            assertThat(c).isNotNull();
            assertThat(c.timestamp()).isEqualTo(1000L + i);
        }
    }

    // --- writeIndex increments correctly after each write ---

    @Test
    void writeIndex_incrementsAfterEachWrite() {
        HeatmapBuffer buf = new HeatmapBuffer(10);

        assertThat(buf.getWriteIndex()).isEqualTo(0);
        buf.writeColumn(col(1L));
        assertThat(buf.getWriteIndex()).isEqualTo(1);
        buf.writeColumn(col(2L));
        assertThat(buf.getWriteIndex()).isEqualTo(2);
        buf.writeColumn(col(3L));
        assertThat(buf.getWriteIndex()).isEqualTo(3);
    }

    // --- write beyond capacity: oldest overwritten, newest 3 accessible ---

    @Test
    void writesBeyondCapacity_overwritesOldest() {
        HeatmapBuffer buf = new HeatmapBuffer(3);

        // write 5 columns into capacity-3 buffer
        for (int i = 0; i < 5; i++) {
            buf.writeColumn(col(100L + i));
        }

        assertThat(buf.getWriteIndex()).isEqualTo(5);

        // indices 0 and 1 are beyond the live window (writeIndex-capacity = 5-3 = 2)
        assertThat(buf.getColumn(0)).isNull();
        assertThat(buf.getColumn(1)).isNull();

        // indices 2, 3, 4 are the newest three and must be readable
        assertThat(buf.getColumn(2)).isNotNull();
        assertThat(buf.getColumn(2).timestamp()).isEqualTo(102L);
        assertThat(buf.getColumn(3)).isNotNull();
        assertThat(buf.getColumn(3).timestamp()).isEqualTo(103L);
        assertThat(buf.getColumn(4)).isNotNull();
        assertThat(buf.getColumn(4).timestamp()).isEqualTo(104L);
    }

    // --- getColumn returns null for index below (writeIndex - capacity) ---

    @Test
    void getColumn_returnsNull_forExpiredIndex() {
        HeatmapBuffer buf = new HeatmapBuffer(5);

        for (int i = 0; i < 7; i++) {
            buf.writeColumn(col(200L + i));
        }
        // writeIndex = 7, capacity = 5, live window = [2, 6]
        assertThat(buf.getColumn(-1)).isNull();
        assertThat(buf.getColumn(0)).isNull();
        assertThat(buf.getColumn(1)).isNull();
        assertThat(buf.getColumn(2)).isNotNull();
    }

    // --- getColumn returns null for negative index regardless of state ---

    @Test
    void getColumn_returnsNull_forNegativeIndex() {
        HeatmapBuffer buf = new HeatmapBuffer(10);
        buf.writeColumn(col(1L));
        assertThat(buf.getColumn(-1)).isNull();
        assertThat(buf.getColumn(-100)).isNull();
    }

    // --- clear resets writeIndex to 0 and makes all getColumn calls return null ---

    @Test
    void clear_resetsStateCompletely() {
        HeatmapBuffer buf = new HeatmapBuffer(5);

        for (int i = 0; i < 5; i++) {
            buf.writeColumn(col(300L + i));
        }
        assertThat(buf.getWriteIndex()).isEqualTo(5);

        buf.clear();

        assertThat(buf.getWriteIndex()).isEqualTo(0);
        for (int i = 0; i < 5; i++) {
            assertThat(buf.getColumn(i)).isNull();
        }
    }

    // --- HeatmapColumn defensive copy: mutations to source array don't affect record ---

    @Test
    void heatmapColumn_defensiveCopy_prices() {
        long[] prices = {100L, 200L, 300L};
        long[] qtys   = {10L,  20L,  30L};
        HeatmapColumn col = new HeatmapColumn(1L, 0L, 0L, prices, qtys, new TradeBlip[0]);

        prices[0] = 999L;

        assertThat(col.prices()[0]).isEqualTo(100L);
    }

    @Test
    void heatmapColumn_defensiveCopy_quantities() {
        long[] prices = {100L};
        long[] qtys   = {42L};
        HeatmapColumn col = new HeatmapColumn(1L, 0L, 0L, prices, qtys, new TradeBlip[0]);

        qtys[0] = 0L;

        assertThat(col.quantities()[0]).isEqualTo(42L);
    }

    @Test
    void heatmapColumn_defensiveCopy_trades() {
        TradeBlip blip = new TradeBlip(1L, 100L, 5L, AggressorSide.BUY, 0L, 0L);
        TradeBlip[] trades = {blip};
        HeatmapColumn col = new HeatmapColumn(1L, 0L, 0L, new long[0], new long[0], trades);

        trades[0] = null;

        assertThat(col.trades()[0]).isSameAs(blip);
    }
}
