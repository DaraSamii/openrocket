package info.openrocket.swing.gui.simulation;

import java.awt.CardLayout;
import java.awt.Cursor;
import java.awt.Window;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutionException;

import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTable;
import javax.swing.SpinnerDateModel;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingWorker;
import javax.swing.table.DefaultTableModel;

import info.openrocket.core.l10n.Translator;
import info.openrocket.core.models.wind.OpenMeteoWindProfile;
import info.openrocket.core.models.wind.OpenMeteoWindProfile.Level;
import info.openrocket.core.models.wind.OpenMeteoWindProfile.Model;
import info.openrocket.core.models.wind.OpenMeteoWindProfile.Profile;
import info.openrocket.core.startup.Application;
import info.openrocket.core.unit.UnitGroup;
import info.openrocket.swing.gui.theme.UITheme;
import info.openrocket.swing.gui.util.GUIUtil;
import info.openrocket.swing.gui.util.URLUtil;
import net.miginfocom.swing.MigLayout;

/**
 * Asks for the place, the local time and the weather model of a wind profile, downloads it from Open-Meteo, and shows
 * exactly which place, time and levels were downloaded before they are used.
 */
public class WindProfileDownloadDialog extends JDialog {
	private static final Translator trans = Application.getTranslator();
	private static final String REQUEST = "request";
	private static final String RESULT = "result";
	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

	private final JComboBox<Model> modelCombo = new JComboBox<>(Model.values());
	private final JSpinner latitudeSpinner;
	private final JSpinner longitudeSpinner;
	private final JSpinner timeSpinner;
	private final JButton downloadButton = new JButton(trans.get("WindProfileDownloadDlg.but.download"));
	private final JLabel statusLabel = new JLabel(" ");
	private final CardLayout cardLayout = new CardLayout();
	private final JPanel cards = new JPanel(cardLayout);
	private final JPanel resultPanel = new JPanel(new MigLayout("fill, wrap 2", "[][grow, fill]"));
	private List<Level> levels;

	/**
	 * @param latitude latitude of the launch site, in degrees
	 * @param longitude longitude of the launch site, in degrees
	 */
	public WindProfileDownloadDialog(Window owner, double latitude, double longitude) {
		super(owner, trans.get("WindProfileDownloadDlg.title"), ModalityType.APPLICATION_MODAL);
		latitudeSpinner = coordinateSpinner(latitude, 90);
		longitudeSpinner = coordinateSpinner(longitude, 180);
		Date nextHour = Date.from(LocalDateTime.now().truncatedTo(ChronoUnit.HOURS).plusHours(1)
				.atZone(ZoneId.systemDefault()).toInstant());
		timeSpinner = new JSpinner(new SpinnerDateModel(nextHour, null, null, java.util.Calendar.HOUR_OF_DAY));
		timeSpinner.setEditor(new JSpinner.DateEditor(timeSpinner, "yyyy-MM-dd HH:00"));

		modelCombo.setRenderer(new DefaultListCellRenderer() {
			@Override
			public java.awt.Component getListCellRendererComponent(JList<?> list, Object value, int index,
					boolean isSelected, boolean cellHasFocus) {
				super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
				if (value instanceof Model model) {
					setText(modelName(model));
				}
				return this;
			}
		});

		JPanel panel = new JPanel(new MigLayout("fill, wrap 2", "[][grow, fill]"));
		panel.add(new JLabel(trans.get("WindProfileDownloadDlg.lbl.model")));
		panel.add(modelCombo);
		panel.add(new JLabel(trans.get("WindProfileDownloadDlg.lbl.latitude")));
		panel.add(latitudeSpinner);
		panel.add(new JLabel(trans.get("WindProfileDownloadDlg.lbl.longitude")));
		panel.add(longitudeSpinner);
		JLabel timeLabel = new JLabel(trans.get("WindProfileDownloadDlg.lbl.time"));
		timeLabel.setToolTipText(trans.get("WindProfileDownloadDlg.lbl.time.ttip"));
		timeSpinner.setToolTipText(trans.get("WindProfileDownloadDlg.lbl.time.ttip"));
		panel.add(timeLabel);
		panel.add(timeSpinner);
		// A fixed HTML width makes the label wrap before the dialog is packed, so its height fits the text
		panel.add(wrapped(trans.get("WindProfileDownloadDlg.lbl.source")), "spanx, gaptop para");
		panel.add(statusLabel, "spanx, growx, pushy, aligny top");

		JButton cancelButton = new JButton(trans.get("dlg.but.cancel"));
		cancelButton.addActionListener(e -> dispose());
		downloadButton.addActionListener(e -> download());
		panel.add(downloadButton, "spanx, split 2, right, tag ok");
		panel.add(cancelButton, "tag cancel");

		cards.add(panel, REQUEST);
		cards.add(resultPanel, RESULT);
		setContentPane(cards);
		getRootPane().setDefaultButton(downloadButton);
		GUIUtil.setDisposableDialogOptions(this, null);
		GUIUtil.installEscapeCloseButtonOperation(this, cancelButton);
		pack();
		setLocationRelativeTo(owner);
	}

	private static JLabel wrapped(String text) {
		return new JLabel("<html><div style=\"width: 360px\">" + text + "</div></html>");
	}

	private static String modelName(Model model) {
		return trans.get("WindProfileDownloadDlg.model." + model.name());
	}

	private static JSpinner coordinateSpinner(double value, double limit) {
		JSpinner spinner = new JSpinner(new SpinnerNumberModel(value, -limit, limit, 0.0001));
		spinner.setEditor(new JSpinner.NumberEditor(spinner, "0.0000"));
		return spinner;
	}

	/**
	 * @return the place as text, like "32.9903° N, 106.9750° W"
	 */
	static String formatPlace(double latitude, double longitude) {
		return String.format("%.4f° %s, %.4f° %s", Math.abs(latitude), latitude < 0 ? "S" : "N",
				Math.abs(longitude), longitude < 0 ? "W" : "E");
	}

	private void download() {
		Model model = (Model) modelCombo.getSelectedItem();
		double latitude = ((Number) latitudeSpinner.getValue()).doubleValue();
		double longitude = ((Number) longitudeSpinner.getValue()).doubleValue();
		LocalDateTime time = LocalDateTime.ofInstant(((Date) timeSpinner.getValue()).toInstant(),
				ZoneId.systemDefault());
		downloadButton.setEnabled(false);
		statusLabel.setText(trans.get("WindProfileDownloadDlg.lbl.downloading"));

		new SwingWorker<Profile, Void>() {
			@Override
			protected Profile doInBackground() throws IOException {
				return OpenMeteoWindProfile.download(latitude, longitude, time, model);
			}

			@Override
			protected void done() {
				downloadButton.setEnabled(true);
				statusLabel.setText(" ");
				try {
					showResult(get(), latitude, longitude);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				} catch (ExecutionException e) {
					String reason = e.getCause() == null ? e.getMessage() : e.getCause().getMessage();
					JOptionPane.showMessageDialog(WindProfileDownloadDialog.this,
							new String[]{trans.get("WindProfileDownloadDlg.msg.failed"), reason},
							trans.get("WindProfileDownloadDlg.msg.failed.title"), JOptionPane.ERROR_MESSAGE);
				}
			}
		}.execute();
	}

	/**
	 * Shows which place, time and levels were downloaded, with a link to the downloaded data, and lets the user use
	 * them or go back.
	 */
	private void showResult(Profile profile, double requestedLatitude, double requestedLongitude) {
		resultPanel.removeAll();
		JLabel heading = new JLabel(trans.get("WindProfileDownloadDlg.result.heading"));
		heading.setFont(heading.getFont().deriveFont(java.awt.Font.BOLD));
		resultPanel.add(heading, "spanx, wrap para");

		String distance = UnitGroup.UNITS_DISTANCE.getDefaultUnit().toStringUnit(
				profile.distanceFrom(requestedLatitude, requestedLongitude));
		addRow("WindProfileDownloadDlg.result.model", profile.model() == null ? "" : modelName(profile.model()));
		addRow("WindProfileDownloadDlg.result.requested", formatPlace(requestedLatitude, requestedLongitude));
		addRow("WindProfileDownloadDlg.result.place", String.format(trans.get("WindProfileDownloadDlg.result.placeValue"),
				formatPlace(profile.latitude(), profile.longitude()), distance));
		addRow("WindProfileDownloadDlg.result.elevation",
				UnitGroup.UNITS_DISTANCE.getDefaultUnit().toStringUnit(profile.elevation()));
		addRow("WindProfileDownloadDlg.result.time", String.format("%s (%s)", TIME.format(profile.time()),
				profile.timezone()));
		List<Level> profileLevels = profile.levels();
		Level ground = profileLevels.get(0);
		addRow("WindProfileDownloadDlg.result.ground", String.format(trans.get("WindProfileDownloadDlg.result.windValue"),
				UnitGroup.UNITS_WINDSPEED.getDefaultUnit().toStringUnit(ground.speed()),
				Math.round(Math.toDegrees(ground.direction()))));

		DefaultTableModel tableModel = new DefaultTableModel(new Object[]{
				trans.get("WindProfileDownloadDlg.result.col.altitude"),
				trans.get("WindProfileDownloadDlg.result.col.speed"),
				trans.get("WindProfileDownloadDlg.result.col.direction")}, 0) {
			@Override
			public boolean isCellEditable(int row, int column) {
				return false;
			}
		};
		for (Level level : profileLevels) {
			tableModel.addRow(new Object[]{UnitGroup.UNITS_DISTANCE.getDefaultUnit().toStringUnit(level.altitude()),
					UnitGroup.UNITS_WINDSPEED.getDefaultUnit().toStringUnit(level.speed()),
					Math.round(Math.toDegrees(level.direction())) + "°"});
		}
		JTable table = new JTable(tableModel);
		table.setFillsViewportHeight(true);
		JScrollPane tableScroll = new JScrollPane(table);
		resultPanel.add(tableScroll, "spanx, grow, push, h 160lp::, gaptop para");

		// Opens the same address the data were downloaded from, so the data can be checked in the browser
		JButton link = new JButton("<html><u>" + trans.get("WindProfileDownloadDlg.result.link") + "</u></html>");
		link.setBorderPainted(false);
		link.setContentAreaFilled(false);
		link.setForeground(UITheme.getColor(UITheme.Keys.URL, java.awt.Color.BLUE));
		link.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		link.setToolTipText(profile.source() == null ? null : profile.source().toString());
		link.setEnabled(profile.source() != null);
		link.addActionListener(e -> URLUtil.openWebpage(profile.source()));
		resultPanel.add(link, "spanx, left, gapleft 0");
		resultPanel.add(wrapped(trans.get("WindProfileDownloadDlg.result.replaces")), "spanx, gaptop para");

		JButton backButton = new JButton(trans.get("WindProfileDownloadDlg.but.back"));
		backButton.addActionListener(e -> {
			cardLayout.show(cards, REQUEST);
			getRootPane().setDefaultButton(downloadButton);
		});
		JButton useButton = new JButton(trans.get("WindProfileDownloadDlg.but.use"));
		useButton.addActionListener(e -> {
			levels = profileLevels;
			dispose();
		});
		JButton cancelButton = new JButton(trans.get("dlg.but.cancel"));
		cancelButton.addActionListener(e -> dispose());
		resultPanel.add(backButton, "spanx, split 3, right, gaptop para");
		resultPanel.add(useButton, "tag ok");
		resultPanel.add(cancelButton, "tag cancel");

		cardLayout.show(cards, RESULT);
		getRootPane().setDefaultButton(useButton);
		pack();
	}

	private void addRow(String labelKey, String value) {
		resultPanel.add(new JLabel(trans.get(labelKey)));
		resultPanel.add(new JLabel(value));
	}

	/**
	 * @return the downloaded levels if the user chose to use them, or null
	 */
	public List<Level> getLevels() {
		return levels;
	}
}
