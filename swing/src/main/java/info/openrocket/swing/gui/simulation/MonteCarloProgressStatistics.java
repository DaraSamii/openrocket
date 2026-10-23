package info.openrocket.swing.gui.simulation;

/**
 * The numbers shown while a Monte Carlo analysis runs: the time and the estimate of the time left.
 */
final class MonteCarloProgressStatistics {
	private MonteCarloProgressStatistics() {
	}

	/**
	 * @return the duration as m:ss, or h:mm:ss from an hour on, rounded down to the second
	 */
	static String formatDuration(long millis) {
		long seconds = Math.max(0, millis) / 1000;
		long hours = seconds / 3600;
		long minutes = seconds / 60 % 60;
		if (hours > 0) {
			return String.format("%d:%02d:%02d", hours, minutes, seconds % 60);
		}
		return String.format("%d:%02d", minutes, seconds % 60);
	}

	/**
	 * Estimates the time left from the time the trajectories after the first took. The first trajectory, the nominal
	 * one, runs alone and takes much longer per trajectory than the parallel dispersed ones, so it is not counted.
	 *
	 * @param elapsedMillis time since the analysis started
	 * @param firstCompletionMillis time since the analysis started at which the first trajectory completed
	 * @param completed number of completed trajectories
	 * @param total number of trajectories
	 * @return the estimated time left, or -1 while there is not enough progress to estimate it
	 */
	static long estimateRemainingMillis(long elapsedMillis, long firstCompletionMillis, int completed, int total) {
		if (completed <= 1 || elapsedMillis <= firstCompletionMillis) {
			return -1;
		}
		double millisPerTrajectory = (double) (elapsedMillis - firstCompletionMillis) / (completed - 1);
		return Math.round(millisPerTrajectory * Math.max(0, total - completed));
	}

	/**
	 * @return the trajectories per second since the first one completed, or -1 while it is not known
	 */
	static double trajectoriesPerSecond(long elapsedMillis, long firstCompletionMillis, int completed) {
		if (completed <= 1 || elapsedMillis <= firstCompletionMillis) {
			return -1;
		}
		return (completed - 1) * 1000.0 / (elapsedMillis - firstCompletionMillis);
	}
}
