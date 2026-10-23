package info.openrocket.swing.gui.simulation;

import java.awt.Window;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.ZoneId;
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
import javax.swing.JSpinner;
import javax.swing.SpinnerDateModel;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingWorker;

import info.openrocket.core.l10n.Translator;
import info.openrocket.core.models.wind.OpenMeteoWindProfile;
import info.openrocket.core.models.wind.OpenMeteoWindProfile.Level;
import info.openrocket.core.models.wind.OpenMeteoWindProfile.Model;
import info.openrocket.core.startup.Application;
import info.openrocket.swing.gui.util.GUIUtil;
import net.miginfocom.swing.MigLayout;

/**
 * Asks for the place, the local time and the weather model of a wind profile, and downloads it from Open-Meteo.
 */
public class WindProfileDownloadDialog extends JDialog {
	private static final Translator trans = Application.getTranslator();

	private final JComboBox<Model> modelCombo = new JComboBox<>(Model.values());
	private final JSpinner latitudeSpinner;
	private final JSpinner longitudeSpinner;
	private final JSpinner timeSpinner;
	private final JButton downloadButton = new JButton(trans.get("WindProfileDownloadDlg.but.download"));
	private final JLabel statusLabel = new JLabel(" ");
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
					setText(trans.get("WindProfileDownloadDlg.model." + model.name()));
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
		panel.add(new JLabel("<html><div style=\"width: 330px\">" + trans.get("WindProfileDownloadDlg.lbl.source")
				+ "</div></html>"), "spanx, gaptop para");
		panel.add(statusLabel, "spanx, growx");

		JButton cancelButton = new JButton(trans.get("dlg.but.cancel"));
		cancelButton.addActionListener(e -> dispose());
		downloadButton.addActionListener(e -> download());
		panel.add(downloadButton, "spanx, split 2, right, tag ok");
		panel.add(cancelButton, "tag cancel");

		setContentPane(panel);
		getRootPane().setDefaultButton(downloadButton);
		GUIUtil.setDisposableDialogOptions(this, null);
		GUIUtil.installEscapeCloseButtonOperation(this, cancelButton);
		pack();
		setLocationRelativeTo(owner);
	}

	private static JSpinner coordinateSpinner(double value, double limit) {
		JSpinner spinner = new JSpinner(new SpinnerNumberModel(value, -limit, limit, 0.0001));
		spinner.setEditor(new JSpinner.NumberEditor(spinner, "0.0000"));
		return spinner;
	}

	private void download() {
		Model model = (Model) modelCombo.getSelectedItem();
		double latitude = ((Number) latitudeSpinner.getValue()).doubleValue();
		double longitude = ((Number) longitudeSpinner.getValue()).doubleValue();
		LocalDateTime time = LocalDateTime.ofInstant(((Date) timeSpinner.getValue()).toInstant(),
				ZoneId.systemDefault());
		downloadButton.setEnabled(false);
		statusLabel.setText(trans.get("WindProfileDownloadDlg.lbl.downloading"));

		new SwingWorker<List<Level>, Void>() {
			@Override
			protected List<Level> doInBackground() throws IOException {
				return OpenMeteoWindProfile.download(latitude, longitude, time, model);
			}

			@Override
			protected void done() {
				downloadButton.setEnabled(true);
				statusLabel.setText(" ");
				try {
					levels = get();
					dispose();
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
	 * @return the downloaded levels, or null if nothing was downloaded
	 */
	public List<Level> getLevels() {
		return levels;
	}
}
