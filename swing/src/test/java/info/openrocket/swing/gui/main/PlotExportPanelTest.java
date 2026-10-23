package info.openrocket.swing.gui.main;

import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.util.Modules;
import info.openrocket.core.database.ComponentPresetDao;
import info.openrocket.core.database.ComponentPresetDatabase;
import info.openrocket.core.database.motor.MotorDatabase;
import info.openrocket.core.database.motor.ThrustCurveMotorSetDatabase;
import info.openrocket.core.document.OpenRocketDocument;
import info.openrocket.core.document.OpenRocketDocumentFactory;
import info.openrocket.core.document.Simulation;
import info.openrocket.core.simulation.FlightData;
import info.openrocket.core.simulation.FlightDataBranch;
import info.openrocket.core.simulation.SimulationOptions;
import info.openrocket.core.l10n.DebugTranslator;
import info.openrocket.core.l10n.Translator;
import info.openrocket.core.plugin.PluginModule;
import info.openrocket.core.simulation.FlightDataType;
import info.openrocket.core.startup.Application;
import info.openrocket.swing.ServicesForTesting;
import info.openrocket.swing.gui.plot.SimulationPlotConfiguration;
import info.openrocket.swing.gui.simulation.SimulationPlotPanel;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlotExportPanelTest {
	private static final SimulationPlotConfiguration[] PRESETS = SimulationPlotConfiguration.DEFAULT_CONFIGURATIONS;

	@TempDir
	Path tempDir;

	@BeforeAll
	static void setUp() {
		AbstractModule overrides = new AbstractModule() {
			@Override
			protected void configure() {
				bind(Translator.class).toInstance(new DebugTranslator(null));
				bind(MotorDatabase.class).toInstance(new ThrustCurveMotorSetDatabase());
				bind(ComponentPresetDao.class).toInstance(new ComponentPresetDatabase());
			}
		};
		Application.setInjector(Guice.createInjector(
				Modules.override(new ServicesForTesting()).with(overrides), new PluginModule()));
	}

	/** Number of data points of the test flight */
	private static final int POINTS = 50;

	/**
	 * A document with one simulation that has data for every built-in data type. The data is made here instead of
	 * loaded from a file, so the test does not depend on the names of the data types in the translator.
	 */
	private static OpenRocketDocument createDocument() {
		OpenRocketDocument document = OpenRocketDocumentFactory.createNewRocket();
		FlightDataBranch branch = new FlightDataBranch("Sustainer", FlightDataType.ALL_TYPES);
		for (int i = 0; i < POINTS; i++) {
			branch.addPoint();
			for (FlightDataType type : FlightDataType.ALL_TYPES) {
				branch.setValue(type, type == FlightDataType.TYPE_TIME ? i * 0.1 : Math.sin(i * 0.1) + 1);
			}
		}
		Simulation simulation = new Simulation(document, document.getRocket(), Simulation.Status.UPTODATE,
				"Test flight", new SimulationOptions(), List.of(), new FlightData(branch));
		document.addSimulation(simulation);
		return document;
	}

	@Test
	void startsWithTheEightMainPlotsOfTheFirstSimulation() {
		OpenRocketDocument document = createDocument();
		PlotExportPanel panel = new PlotExportPanel(document);

		assertEquals(document.getSimulations().get(0), panel.getSimulation());
		List<String> names = panel.getPlots().stream().map(SimulationPlotConfiguration::getName).toList();
		assertEquals(PlotExportPanel.mainPlots().stream().map(SimulationPlotConfiguration::getName).toList(), names);
		assertEquals(8, names.size());
	}

	/**
	 * Every main plot shows only quantities with the same unit, on one axis.
	 */
	@Test
	void mainPlotsHaveOneUnitOnOneAxis() {
		for (SimulationPlotConfiguration plot : PlotExportPanel.mainPlots()) {
			for (int i = 0; i < plot.getDataCount(); i++) {
				assertEquals(plot.getType(0).getUnitGroup(), plot.getType(i).getUnitGroup(), plot.getName());
				assertEquals(0, plot.getAxis(i), plot.getName());
			}
		}
	}

	@Test
	void documentWithoutSimulationDataShowsNoPlots() {
		PlotExportPanel panel = new PlotExportPanel(OpenRocketDocumentFactory.createNewRocket());

		assertNull(panel.getSimulation());
	}

	@Test
	void plotsCanBeAddedAndRemoved() throws Exception {
		PlotExportPanel panel = new PlotExportPanel(createDocument());

		panel.addPlot(PRESETS[7].clone());
		assertEquals(9, panel.getPlots().size());
		assertEquals(PRESETS[7].getName(), panel.getPlots().get(8).getName());

		String second = panel.getPlots().get(1).getName();
		panel.removePlot(0);
		assertEquals(8, panel.getPlots().size());
		assertEquals(second, panel.getPlots().get(0).getName());
	}

	/**
	 * Eight plots are four rows of two, each plot 600 x 400 drawn at twice the resolution.
	 */
	@Test
	void exportedImageHasAllPlotsAtTwiceTheResolution() throws Exception {
		PlotExportPanel panel = new PlotExportPanel(createDocument());

		BufferedImage image = panel.renderImage();
		assertEquals(2 * 600 * 2, image.getWidth());
		assertEquals(4 * 400 * 2, image.getHeight());
		assertTrue(countColors(image) > 50, "The image must contain the drawn plots");

		panel.addPlot(PRESETS[2].clone());
		assertEquals(5 * 400 * 2, panel.renderImage().getHeight());
	}

	@Test
	void exportedDataHasTimeAndEveryPlottedQuantity() throws Exception {
		PlotExportPanel panel = new PlotExportPanel(createDocument());
		File file = tempDir.resolve("data.csv").toFile();

		panel.writeData(file);

		List<String> lines = Files.readAllLines(file.toPath());
		String text = String.join("\n", lines);
		for (FlightDataType type : new FlightDataType[]{FlightDataType.TYPE_TIME, FlightDataType.TYPE_ALTITUDE,
				FlightDataType.TYPE_VELOCITY_Z, FlightDataType.TYPE_STABILITY, FlightDataType.TYPE_THRUST_FORCE,
				FlightDataType.TYPE_DRAG_COEFF, FlightDataType.TYPE_MACH_NUMBER, FlightDataType.TYPE_POSITION_X}) {
			assertTrue(text.contains(type.getName()), "Missing column " + type.getName());
		}
		long dataLines = lines.stream().filter(line -> !line.startsWith("#") && !line.isBlank()).count();
		assertEquals(POINTS, dataLines);
	}

	/**
	 * Editing a plot starts from that plot, not from the default plot of the editor.
	 */
	@Test
	void editorStartsFromThePlotBeingEdited() {
		OpenRocketDocument document = createDocument();
		SimulationPlotConfiguration stability = PRESETS[4].clone().resetUnits();

		SimulationPlotPanel editor = SimulationPlotPanel.create(document.getSimulations().get(0));
		editor.setPlotConfiguration(stability);

		SimulationPlotConfiguration edited = editor.getConfiguration();
		assertEquals(stability.getName(), edited.getName());
		assertEquals(stability.getDomainAxisType(), edited.getDomainAxisType());
		assertEquals(stability.getDataCount(), edited.getDataCount());
		for (int i = 0; i < stability.getDataCount(); i++) {
			assertEquals(stability.getType(i), edited.getType(i));
		}
	}

	private static int countColors(BufferedImage image) {
		Set<Integer> colors = new HashSet<>();
		for (int y = 0; y < image.getHeight(); y += 7) {
			for (int x = 0; x < image.getWidth(); x += 7) {
				colors.add(image.getRGB(x, y));
			}
		}
		return colors.size();
	}
}
