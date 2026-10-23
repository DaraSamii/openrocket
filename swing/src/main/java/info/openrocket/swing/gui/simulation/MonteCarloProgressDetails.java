package info.openrocket.swing.gui.simulation;

import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.Timer;

import info.openrocket.core.l10n.Translator;
import info.openrocket.core.startup.Application;
import net.miginfocom.swing.MigLayout;

/**
 * The details under the progress bar of a running Monte Carlo analysis: the time that has passed, the estimated time
 * left and total time, the speed, and the number of threads. It is refreshed twice a second while it runs.
 */
final class MonteCarloProgressDetails extends JPanel {
	private static final Translator trans = Application.getTranslator();
	private static final int REFRESH_MILLIS = 500;

	private final JLabel elapsedLabel = new JLabel();
	private final JLabel remainingLabel = new JLabel();
	private final JLabel totalLabel = new JLabel();
	private final JLabel speedLabel = new JLabel();
	private final JLabel threadsLabel = new JLabel();
	private final Timer timer = new Timer(REFRESH_MILLIS, event -> refresh());

	private long startNanos;
	private long firstCompletionMillis = -1;
	private int completed;
	private int total;

	MonteCarloProgressDetails() {
		super(new MigLayout("ins 0, gapy 3, wrap 2", "[]para[grow]"));
		add(new JLabel(trans.get("LandingDispersionDlg.progress.elapsed")));
		add(elapsedLabel);
		add(new JLabel(trans.get("LandingDispersionDlg.progress.remaining")));
		add(remainingLabel);
		add(new JLabel(trans.get("LandingDispersionDlg.progress.estimatedTotal")));
		add(totalLabel);
		add(new JLabel(trans.get("LandingDispersionDlg.progress.speed")));
		add(speedLabel);
		add(new JLabel(trans.get("LandingDispersionDlg.progress.threads")));
		add(threadsLabel);
	}

	/**
	 * Starts timing and refreshing.
	 *
	 * @param threads number of threads that run the trajectories
	 * @param totalTrajectories number of trajectories to run
	 */
	void start(int threads, int totalTrajectories) {
		startNanos = System.nanoTime();
		firstCompletionMillis = -1;
		completed = 0;
		total = totalTrajectories;
		threadsLabel.setText(String.format(trans.get("LandingDispersionDlg.progress.threadsValue"), threads,
				Runtime.getRuntime().availableProcessors()));
		refresh();
		timer.start();
	}

	void stop() {
		timer.stop();
	}

	/**
	 * @param completedTrajectories number of trajectories that have completed
	 * @param totalTrajectories number of trajectories to run
	 */
	void setProgress(int completedTrajectories, int totalTrajectories) {
		if (firstCompletionMillis < 0 && completedTrajectories > 0) {
			firstCompletionMillis = elapsedMillis();
		}
		completed = completedTrajectories;
		total = totalTrajectories;
		refresh();
	}

	private long elapsedMillis() {
		return (System.nanoTime() - startNanos) / 1_000_000;
	}

	private void refresh() {
		long elapsed = elapsedMillis();
		elapsedLabel.setText(MonteCarloProgressStatistics.formatDuration(elapsed));

		long remaining = MonteCarloProgressStatistics.estimateRemainingMillis(elapsed, firstCompletionMillis,
				completed, total);
		String unknown = trans.get("LandingDispersionDlg.progress.estimating");
		remainingLabel.setText(remaining < 0 ? unknown : MonteCarloProgressStatistics.formatDuration(remaining));
		totalLabel.setText(remaining < 0 ? unknown
				: MonteCarloProgressStatistics.formatDuration(elapsed + remaining));
		double speed = MonteCarloProgressStatistics.trajectoriesPerSecond(elapsed, firstCompletionMillis, completed);
		speedLabel.setText(speed < 0 ? unknown
				: String.format(trans.get("LandingDispersionDlg.progress.speedValue"), speed));
	}
}
