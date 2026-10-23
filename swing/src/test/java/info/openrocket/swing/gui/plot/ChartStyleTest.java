package info.openrocket.swing.gui.plot;

import org.jfree.chart.ChartFactory;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.plot.PlotOrientation;
import org.jfree.chart.plot.XYPlot;
import org.jfree.chart.title.TextTitle;
import org.jfree.data.xy.XYSeries;
import org.jfree.data.xy.XYSeriesCollection;
import org.junit.jupiter.api.Test;

import java.awt.BasicStroke;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

class ChartStyleTest {
	/**
	 * A chart like the ones made by {@link Plot}: a main title, the configuration name as subtitle, and a dashed series.
	 */
	private static JFreeChart createChart() {
		XYSeries series = new XYSeries("Altitude");
		series.add(0, 0);
		series.add(1, 10);
		JFreeChart chart = ChartFactory.createXYLineChart("Simulation 1", "Time", "Altitude",
				new XYSeriesCollection(series), PlotOrientation.VERTICAL, false, false, false);
		chart.addSubtitle(new TextTitle("Vertical motion"));
		chart.getXYPlot().getRenderer().setSeriesStroke(0, new BasicStroke(1f, BasicStroke.CAP_BUTT,
				BasicStroke.JOIN_MITER, 10f, new float[]{6f, 3f}, 0f));
		return chart;
	}

	@Test
	void configurationNameBecomesTheTitle() {
		JFreeChart chart = createChart();

		ChartStyle.apply(chart);

		assertNull(chart.getTitle());
		TextTitle title = (TextTitle) chart.getSubtitle(0);
		assertEquals("Vertical motion", title.getText());
		assertEquals(ChartStyle.TITLE_SIZE, title.getFont().getSize2D(), 0.01);
	}

	@Test
	void linesAreThickerAndKeepTheirDashes() {
		JFreeChart chart = createChart();

		ChartStyle.apply(chart);

		BasicStroke stroke = (BasicStroke) chart.getXYPlot().getRenderer().getSeriesStroke(0);
		assertEquals(ChartStyle.LINE_WIDTH, stroke.getLineWidth(), 0.01);
		assertArrayEquals(new float[]{6f, 3f}, stroke.getDashArray());
	}

	@Test
	void axesUseReadableFontSizesAndThePlotHasNoOutline() {
		JFreeChart chart = createChart();

		ChartStyle.apply(chart);

		XYPlot plot = chart.getXYPlot();
		assertFalse(plot.isOutlineVisible());
		assertEquals(ChartStyle.LABEL_SIZE, plot.getDomainAxis().getLabelFont().getSize2D(), 0.01);
		assertEquals(ChartStyle.TICK_SIZE, plot.getRangeAxis().getTickLabelFont().getSize2D(), 0.01);
	}
}
