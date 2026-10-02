package mys.zstdnet.reborn.core.stats;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class TrafficStatsTest {
    @Test
    void tracksActiveAndTotalConnectionsSeparately() {
        var stats = new TrafficStats();

        stats.addConnection(1);
        stats.addConnection(1);
        stats.addConnection(-1);

        var snapshot = stats.snapshot();
        assertEquals(1, snapshot.connections());
        assertEquals(2L, snapshot.totalConnections());
    }
}
