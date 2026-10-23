package info.openrocket.swing.gui.simulation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

public class MonteCarloProgressStatisticsTest {
	@Test
	public void testDurationsAreShownAsMinutesAndSecondsAndHoursFromAnHour() {
		assertEquals("0:00", MonteCarloProgressStatistics.formatDuration(0));
		assertEquals("0:00", MonteCarloProgressStatistics.formatDuration(999));
		assertEquals("1:05", MonteCarloProgressStatistics.formatDuration(65_400));
		assertEquals("59:59", MonteCarloProgressStatistics.formatDuration(3_599_999));
		assertEquals("1:02:05", MonteCarloProgressStatistics.formatDuration(3_725_000));
		assertEquals("0:00", MonteCarloProgressStatistics.formatDuration(-5));
	}

	/**
	 * The nominal trajectory runs alone and finished after 4 s. The other 99 trajectories then took 1 s each in
	 * parallel: after 10 trajectories (9 of them since the first) at 13 s, 90 are left at 1 s.
	 */
	@Test
	public void testTheEstimateIgnoresTheSlowFirstTrajectory() {
		assertEquals(90_000, MonteCarloProgressStatistics.estimateRemainingMillis(13_000, 4_000, 10, 100));
		assertEquals(1.0, MonteCarloProgressStatistics.trajectoriesPerSecond(13_000, 4_000, 10), 1e-9);
		assertEquals(0, MonteCarloProgressStatistics.estimateRemainingMillis(103_000, 4_000, 100, 100));
	}

	@Test
	public void testNothingIsEstimatedBeforeThereIsProgress() {
		assertEquals(-1, MonteCarloProgressStatistics.estimateRemainingMillis(500, -1, 0, 100));
		assertEquals(-1, MonteCarloProgressStatistics.estimateRemainingMillis(4_000, 4_000, 1, 100));
		assertEquals(-1, MonteCarloProgressStatistics.estimateRemainingMillis(4_000, 4_000, 5, 100));
		assertEquals(-1.0, MonteCarloProgressStatistics.trajectoriesPerSecond(4_000, 4_000, 1), 0);
	}
}
