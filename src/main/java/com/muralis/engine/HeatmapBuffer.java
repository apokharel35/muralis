package com.muralis.engine;

import java.util.Arrays;

/**
 * Fixed-capacity ring buffer of HeatmapColumn objects.
 *
 * Thread safety: single writer (engine thread), single reader (UI thread).
 * writeIndex is volatile — the UI's volatile read establishes happens-before
 * with the engine's volatile write, ensuring all columns written before
 * writeIndex was updated are visible to the UI (JSR-133 transitivity).
 *
 * writeIndex is monotonically increasing; modulo is applied only at access.
 */
class HeatmapBuffer {

    private final HeatmapColumn[] columns;
    private volatile int writeIndex;
    private final int capacity;

    HeatmapBuffer(int capacity) {
        this.columns    = new HeatmapColumn[capacity];
        this.capacity   = capacity;
        this.writeIndex = 0;
    }

    /** Engine thread only. */
    void writeColumn(HeatmapColumn column) {
        columns[writeIndex % capacity] = column;
        writeIndex++;   // volatile write last — ensures visibility to UI thread
    }

    /**
     * UI thread. Returns null if index is out of the live window:
     * index < 0, or index < (writeIndex - capacity) (already overwritten).
     */
    HeatmapColumn getColumn(int index) {
        if (index < 0 || index < writeIndex - capacity) return null;
        return columns[index % capacity];
    }

    int getWriteIndex() { return writeIndex; }

    int getCapacity() { return capacity; }

    /** Called on CONNECTING / RECONNECTING to discard stale history. */
    void clear() {
        Arrays.fill(columns, null);
        writeIndex = 0;
    }
}
