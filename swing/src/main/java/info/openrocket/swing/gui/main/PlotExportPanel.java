package info.openrocket.swing.gui.main;

import info.openrocket.core.document.OpenRocketDocument;
import info.openrocket.core.document.Simulation;
import info.openrocket.core.file.CSVExport;
import info.openrocket.core.l10n.Translator;
import info.openrocket.core.preferences.ApplicationPreferences;
import info.openrocket.core.simulation.FlightData;
import info.openrocket.core.simulation.FlightDataBranch;
import info.openrocket.core.simulation.FlightDataType;
import info.openrocket.core.simulation.FlightEvent;
import info.openrocket.core.startup.Application;
import info.openrocket.core.unit.Unit;
import info.openrocket.core.util.TextUtil;
import info.openrocket.swing.gui.plot.ChartStyle;
import info.openrocket.swing.gui.plot.SimulationChart;
import info.openrocket.swing.gui.plot.SimulationPlot;
import info.openrocket.swing.gui.plot.SimulationPlotConfiguration;
import info.openrocket.swing.gui.simulation.SimulationPlotPanel;
import info.openrocket.swing.gui.theme.UITheme;
import info.openrocket.swing.gui.util.FileHelper;
import info.openrocket.swing.gui.util.Icons;
import info.openrocket.swing.gui.widgets.SaveFileChooser;
import net.miginfocom.swing.MigLayout;
import org.jfree.chart.JFreeChart;

import com.formdev.flatlaf.util.SystemFileChooser.FileFilter;

import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.RenderingHints;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The Plot / Export tab: several plots of one simulation on one page, ready to use. It starts with the eight most
 * useful plots, each with one unit on its vertical axis; plots can be added, edited (data, axes, units, colors)
 * and removed. All plots can be exported at once, as one image or as the data in one CSV file.
 */
public class PlotExportPanel extends JPanel {
	private static final Translator trans = Application.getTranslator();

	static final int COLUMNS = 2;
	private static final int CHART_HEIGHT = 360;
	/** Size of one plot in the exported image, before it is drawn at twice the resolution */
	static final int EXPORT_CHART_WIDTH = 600;
	static final int EXPORT_CHART_HEIGHT = 400;
	static final int EXPORT_SCALE = 2;

	private final OpenRocketDocument document;
	private final List<SimulationPlotConfiguration> plots = new ArrayList<>(mainPlots());

	private final JComboBox<Simulation> simulationSelector = new JComboBox<>();
	private final JButton addButton;
	private final JButton exportImageButton;
	private final JButton exportDataButton;
	private final JPanel content = new JPanel(new BorderLayout());
	private final JPanel chartGrid = new JPanel(new GridLayout(0, COLUMNS, 8, 8));
	private final JScrollPane scroll = new JScrollPane(content);
	private int updating = 0;

	public PlotExportPanel(OpenRocketDocument document) {
		super(new MigLayout("fill, ins n", "", "[][grow]"));
		this.document = document;

		add(new JLabel(trans.get("PlotExportPanel.lbl.Simulation")), "split, gapright rel");
		simulationSelector.setRenderer(new DefaultListCellRenderer() {
			@Override
			public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected,
														  boolean cellHasFocus) {
				Object name = value instanceof Simulation simulation ? simulation.getName() : value;
				return super.getListCellRendererComponent(list, name, index, isSelected, cellHasFocus);
			}
		});
		simulationSelector.addActionListener(e -> {
			if (updating == 0) {
				updateCharts();
			}
		});
		add(simulationSelector, "wmin 200lp, gapright para");

		addButton = new JButton(trans.get("PlotExportPanel.but.AddPlot"));
		addButton.setToolTipText(trans.get("PlotExportPanel.but.AddPlot.ttip"));
		addButton.addActionListener(e -> showAddMenu());
		add(addButton);

		exportImageButton = new JButton(trans.get("PlotExportPanel.but.ExportImage"), Icons.FILE_EXPORT);
		exportImageButton.setToolTipText(trans.get("PlotExportPanel.but.ExportImage.ttip"));
		exportImageButton.addActionListener(e -> export(FileHelper.PNG_FILTER, "png"));
		add(exportImageButton, "gapleft push");

		exportDataButton = new JButton(trans.get("PlotExportPanel.but.ExportData"), Icons.FILE_EXPORT);
		exportDataButton.setToolTipText(trans.get("PlotExportPanel.but.ExportData.ttip"));
		exportDataButton.addActionListener(e -> export(FileHelper.CSV_FILTER, "csv"));
		add(exportDataButton, "wrap");

		scroll.setBorder(null);

		scroll.getVerticalScrollBar().setUnitIncrement(16);
		add(scroll, "grow");

		activating();
	}

	/**
	 * Updates the list of simulations and the plots. Called when the tab is selected, so new simulation results
	 * are shown.
	 */
	public void activating() {
		Object selected = simulationSelector.getSelectedItem();
		updating++;
		simulationSelector.removeAllItems();
		for (Simulation simulation : document.getSimulations()) {
			if (hasData(simulation)) {
				simulationSelector.addItem(simulation);
			}
		}
		if (selected != null) {
			simulationSelector.setSelectedItem(selected);
		}
		updating--;
		updateCharts();
	}

	/**
	 * @return the simulation whose data is plotted, or null if no simulation has data
	 */
	Simulation getSimulation() {
		return (Simulation) simulationSelector.getSelectedItem();
	}

	/**
	 * @return the configurations of the plots, in the order in which they are shown
	 */
	List<SimulationPlotConfiguration> getPlots() {
		return plots;
	}

	void addPlot(SimulationPlotConfiguration configuration) {
		plots.add(configuration);
		updateCharts();
	}

	void removePlot(int index) {
		plots.remove(index);
		updateCharts();
	}

	/**
	 * @return the eight plots that are shown at first. Each plot has only quantities with the same unit, all on the
	 * left axis, so they can be compared directly.
	 */
	static List<SimulationPlotConfiguration> mainPlots() {
		List<SimulationPlotConfiguration> plots = new ArrayList<>();
		plots.add(plot("Altitude", FlightDataType.TYPE_TIME, FlightDataType.TYPE_ALTITUDE));
		plots.add(plot("Velocity", FlightDataType.TYPE_TIME, FlightDataType.TYPE_VELOCITY_Z,
				FlightDataType.TYPE_VELOCITY_TOTAL));
		plots.add(plot("Acceleration", FlightDataType.TYPE_TIME, FlightDataType.TYPE_ACCELERATION_Z,
				FlightDataType.TYPE_ACCELERATION_TOTAL));
		plots.add(plot("Stability", FlightDataType.TYPE_TIME, FlightDataType.TYPE_STABILITY));
		plots.add(plot("Forces", FlightDataType.TYPE_TIME, FlightDataType.TYPE_THRUST_FORCE,
				FlightDataType.TYPE_DRAG_FORCE));
		plots.add(plot("DragCoefficient", FlightDataType.TYPE_TIME, FlightDataType.TYPE_DRAG_COEFF));
		plots.add(plot("Mach", FlightDataType.TYPE_TIME, FlightDataType.TYPE_MACH_NUMBER));
		plots.add(plot("FlightPath", FlightDataType.TYPE_POSITION_X, FlightDataType.TYPE_ALTITUDE));
		return plots;
	}

	private static SimulationPlotConfiguration plot(String key, FlightDataType domain, FlightDataType... types) {
		SimulationPlotConfiguration plot = new SimulationPlotConfiguration(trans.get("PlotExportPanel.plot." + key),
				domain);
		for (FlightDataType type : types) {
			plot.addPlotDataType(type, 0);
		}
		// The same flight events as the preset plots
		for (FlightEvent.Type event : SimulationPlotConfiguration.DEFAULT_CONFIGURATIONS[0].getActiveEvents()) {
			plot.setEvent(event, true);
		}
		return plot.resetUnits();
	}

	private static boolean hasData(Simulation simulation) {
		FlightData data = simulation.getSimulatedData();
		return data != null && data.getBranchCount() > 0;
	}

	private void updateCharts() {
		Simulation simulation = getSimulation();
		addButton.setEnabled(simulation != null);
		exportImageButton.setEnabled(simulation != null && !plots.isEmpty());
		exportDataButton.setEnabled(simulation != null && !plots.isEmpty());

		content.removeAll();
		chartGrid.removeAll();
		if (simulation == null) {
			content.add(new JLabel(trans.get("PlotExportPanel.lbl.NoData"), SwingConstants.CENTER), BorderLayout.CENTER);
		} else {
			for (int i = 0; i < plots.size(); i++) {
				chartGrid.add(createCard(simulation, i));
			}
			content.add(chartGrid, BorderLayout.NORTH);
		}
		content.revalidate();
		content.repaint();
	}

	private static JFreeChart createChart(Simulation simulation, SimulationPlotConfiguration configuration) {
		JFreeChart chart = SimulationPlot.create(simulation, configuration, false).getJFreeChart();
		ChartStyle.apply(chart);
		return chart;
	}

	private JPanel createCard(Simulation simulation, int index) {
		SimulationChart chartPanel = new SimulationChart(createChart(simulation, plots.get(index)));
		chartPanel.setPreferredSize(new Dimension(400, CHART_HEIGHT));

		JButton edit = toolButton(Icons.EDIT_EDIT, trans.get("PlotExportPanel.but.Edit.ttip"));
		edit.addActionListener(e -> editPlot(index));
		JButton remove = toolButton(Icons.EDIT_DELETE, trans.get("PlotExportPanel.but.Remove.ttip"));
		remove.addActionListener(e -> removePlot(index));
		JPanel tools = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 0));
		tools.setOpaque(false);
		tools.add(edit);
		tools.add(remove);

		JPanel card = new JPanel(new BorderLayout());
		card.setBorder(BorderFactory.createLineBorder(UITheme.getColor(UITheme.Keys.BORDER)));
		card.add(tools, BorderLayout.NORTH);
		card.add(chartPanel, BorderLayout.CENTER);
		return card;
	}

	private static JButton toolButton(Icon icon, String toolTip) {
		JButton button = new JButton(icon);
		button.setToolTipText(toolTip);
		button.putClientProperty("JButton.buttonType", "toolBarButton");
		return button;
	}

	private void showAddMenu() {
		JPopupMenu menu = new JPopupMenu();
		for (SimulationPlotConfiguration plot : mainPlots()) {
			JMenuItem item = new JMenuItem(plot.getName());
			item.addActionListener(e -> addPlot(plot));
			menu.add(item);
		}
		menu.addSeparator();
		for (SimulationPlotConfiguration preset : SimulationPlotConfiguration.DEFAULT_CONFIGURATIONS) {
			JMenuItem item = new JMenuItem(preset.getName());
			item.addActionListener(e -> addPlot(preset.clone().resetUnits()));
			menu.add(item);
		}
		menu.addSeparator();
		JMenuItem custom = new JMenuItem(trans.get("PlotExportPanel.menu.Custom"));
		custom.addActionListener(e -> {
			SimulationPlotConfiguration configuration =
					showEditor(SimulationPlotConfiguration.DEFAULT_CONFIGURATIONS[0].clone().resetUnits());
			if (configuration != null) {
				addPlot(configuration);
			}
		});
		menu.add(custom);
		menu.show(addButton, 0, addButton.getHeight());
	}

	private void editPlot(int index) {
		SimulationPlotConfiguration configuration = showEditor(plots.get(index));
		if (configuration != null) {
			plots.set(index, configuration);
			updateCharts();
		}
	}

	/**
	 * Edits a copy of the configuration in the plot editor of the simulation dialog.
	 *
	 * @return the edited configuration, or null if the editor was cancelled or nothing is plotted
	 */
	private SimulationPlotConfiguration showEditor(SimulationPlotConfiguration configuration) {
		SimulationPlotPanel editor = SimulationPlotPanel.create(getSimulation());
		editor.setPlotConfiguration(configuration);
		int result = JOptionPane.showConfirmDialog(SwingUtilities.getWindowAncestor(this), editor,
				trans.get("PlotExportPanel.dlg.EditPlot"), JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
		if (result != JOptionPane.OK_OPTION || editor.getConfiguration().getDataCount() == 0) {
			return null;
		}
		return editor.getConfiguration().clone();
	}

	private void export(FileFilter filter, String extension) {
		SaveFileChooser chooser = new SaveFileChooser();
		chooser.setFileFilter(filter);
		chooser.setCurrentDirectory(Application.getPreferences().getDefaultDirectory());
		chooser.setSelectedFile(new File(getSimulation().getName() + "." + extension));
		if (chooser.showSaveDialog(this) != SaveFileChooser.APPROVE_OPTION || chooser.getSelectedFile() == null) {
			return;
		}
		File file = FileHelper.forceExtension(chooser.getSelectedFile(), extension);
		if (!FileHelper.confirmWrite(file, chooser.getSelectedFile(), this)) {
			return;
		}
		Application.getPreferences().setDefaultDirectory(chooser.getCurrentDirectory());

		try {
			if (extension.equals("png")) {
				ImageIO.write(renderImage(), "png", file);
			} else {
				writeData(file);
			}
		} catch (IOException e) {
			JOptionPane.showMessageDialog(this, String.format(trans.get("PlotExportPanel.dlg.ExportError"),
					e.getMessage()), trans.get("PlotExportPanel.dlg.ExportError.title"), JOptionPane.ERROR_MESSAGE);
		}
	}

	/**
	 * @return all plots in one image, {@link #COLUMNS} per row, drawn at {@link #EXPORT_SCALE} times the resolution
	 * so that the text is sharp
	 */
	BufferedImage renderImage() {
		List<JFreeChart> charts = new ArrayList<>();
		for (SimulationPlotConfiguration plot : plots) {
			charts.add(createChart(getSimulation(), plot));
		}
		int columns = Math.min(COLUMNS, charts.size());
		int rows = (charts.size() + COLUMNS - 1) / COLUMNS;
		BufferedImage image = new BufferedImage(columns * EXPORT_CHART_WIDTH * EXPORT_SCALE,
				rows * EXPORT_CHART_HEIGHT * EXPORT_SCALE, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = image.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		g.setColor(UITheme.getColor(UITheme.Keys.BACKGROUND));
		g.fillRect(0, 0, image.getWidth(), image.getHeight());
		g.scale(EXPORT_SCALE, EXPORT_SCALE);
		for (int i = 0; i < charts.size(); i++) {
			charts.get(i).draw(g, new Rectangle2D.Double((i % COLUMNS) * EXPORT_CHART_WIDTH,
					(i / COLUMNS) * EXPORT_CHART_HEIGHT, EXPORT_CHART_WIDTH, EXPORT_CHART_HEIGHT));
		}
		g.dispose();
		return image;
	}

	/**
	 * Writes the data of all plots (time and every plotted quantity, in the default units) to one CSV file, with the
	 * CSV settings that were last used for exporting.
	 */
	void writeData(File file) throws IOException {
		Simulation simulation = getSimulation();
		FlightDataBranch branch = simulation.getSimulatedData().getBranch(0);
		Set<FlightDataType> types = new LinkedHashSet<>();
		types.add(FlightDataType.TYPE_TIME);
		for (SimulationPlotConfiguration plot : plots) {
			types.add(plot.getDomainAxisType());
			for (int i = 0; i < plot.getDataCount(); i++) {
				types.add(plot.getType(i));
			}
		}
		types.retainAll(Arrays.asList(branch.getTypes()));
		FlightDataType[] fields = types.toArray(new FlightDataType[0]);
		Unit[] units = new Unit[fields.length];
		for (int i = 0; i < fields.length; i++) {
			units[i] = fields[i].getUnitGroup().getDefaultUnit();
		}

		ApplicationPreferences prefs = Application.getPreferences();
		try (OutputStream out = new BufferedOutputStream(new FileOutputStream(file))) {
			CSVExport.exportCSV(out, simulation, branch, fields, units,
					prefs.getString(ApplicationPreferences.EXPORT_FIELD_SEPARATOR, ","),
					prefs.getInt(ApplicationPreferences.EXPORT_DECIMAL_PLACES, TextUtil.DEFAULT_DECIMAL_PLACES),
					prefs.getBoolean(ApplicationPreferences.EXPORT_EXPONENTIAL_NOTATION, true),
					prefs.getString(ApplicationPreferences.EXPORT_COMMENT_CHARACTER, "#"), true, true, true);
		}
	}
}
