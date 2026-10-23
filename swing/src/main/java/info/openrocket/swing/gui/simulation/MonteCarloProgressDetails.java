package info.openrocket.swing.gui.simulation;

import java.time.Duration;
import java.util.Optional;

import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.Timer;

import info.openrocket.core.l10n.Translator;
import info.openrocket.core.startup.Application;
import net.miginfocom.swing.MigLayout;

/**
 * The details under the progress bar of a running Monte Carlo analysis: the time that has passed, the estimated time
 * left, the speed, the processor and memory in use, and the number of threads. It is refreshed twice a second while
 * it runs.
 */
final class MonteCarloProgressDetails extends JPanel {
	private static final Translator trans = Application.getTranslator();
	private static final int REFRESH_MILLIS = 500;
	private static final double MEGABYTE = 1024.0 * 1024.0;

	private final JLabel elapsedLabel = new JLabel();
	private final JLabel remainingLabel = new JLabel();
	private final JLabel totalLabel = new JLabel();
	private final JLabel speedLabel = new JLabel();
	private final JLabel threadsLabel = new JLabel();
	private final JProgressBar cpuBar = new JProgressBar(0, 100);
	private final JProgressBar memoryBar = new JProgressBar(0, 100);
	private final Timer timer = new Timer(REFRESH_MILLIS, event -> refresh());

	private long startNanos;
	private long firstCompletionMillis = -1;
	private int completed;
	private int total;
	private long lastSampleNanos;
	private long lastCpuNanos;

	MonteCarloProgressDetails() {
		super(new MigLayout("fillx, ins 0, gapy 3", "[][grow, fill]para[][grow, fill]"));
		add(new JLabel(trans.get("LandingDispersionDlg.progress.elapsed")));
		add(elapsedLabel);
		add(new JLabel(trans.get("LandingDispersionDlg.progress.remaining")));
		add(remainingLabel, "wrap");
		add(new JLabel(trans.get("LandingDispersionDlg.progress.estimatedTotal")));
		add(totalLabel);
		add(new JLabel(trans.get("LandingDispersionDlg.progress.speed")));
		add(speedLabel, "wrap");
		add(new JLabel(trans.get("LandingDispersionDlg.progress.threads")));
		add(threadsLabel, "wrap para");
		cpuBar.setStringPainted(true);
		memoryBar.setStringPainted(true);
		add(new JLabel(trans.get("LandingDispersionDlg.progress.cpu")));
		add(cpuBar, "span 3, wrap");
		add(new JLabel(trans.get("LandingDispersionDlg.progress.memory")));
		add(memoryBar, "span 3");
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
		lastSampleNanos = startNanos;
		lastCpuNanos = processCpuNanos();
		threadsLabel.setText(String.format(trans.get("LandingDispersionDlg.progress.threadsValue"), threads,
				Runtime.getRuntime().availableProcessors()));
		cpuBar.setValue(0);
		cpuBar.setString("");
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

		updateCpu();
		Runtime runtime = Runtime.getRuntime();
		long used = runtime.totalMemory() - runtime.freeMemory();
		long max = runtime.maxMemory();
		memoryBar.setValue((int) Math.round(100.0 * used / max));
		memoryBar.setString(String.format(trans.get("LandingDispersionDlg.progress.memoryValue"),
				used / MEGABYTE, max / MEGABYTE));
	}

	private void updateCpu() {
		long now = System.nanoTime();
		long cpu = processCpuNanos();
		if (cpu < 0 || lastCpuNanos < 0) {
			cpuBar.setString(trans.get("LandingDispersionDlg.progress.unavailable"));
		} else if (now - lastSampleNanos >= REFRESH_MILLIS * 800_000L) {
			double fraction = MonteCarloProgressStatistics.cpuFraction(cpu - lastCpuNanos, now - lastSampleNanos,
					Runtime.getRuntime().availableProcessors());
			cpuBar.setValue((int) Math.round(fraction * 100));
			cpuBar.setString(String.format("%d %%", Math.round(fraction * 100)));
		}
		if (now - lastSampleNanos >= REFRESH_MILLIS * 800_000L) {
			lastSampleNanos = now;
			lastCpuNanos = cpu;
		}
	}

	/**
	 * @return the processor time that the program has used so far, in ns, or -1 if the system does not tell
	 */
	private static long processCpuNanos() {
		Optional<Duration> cpu = ProcessHandle.current().info().totalCpuDuration();
		return cpu.map(Duration::toNanos).orElse(-1L);
	}
}
