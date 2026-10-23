package info.openrocket.swing.gui.plot;

import info.openrocket.swing.gui.theme.UITheme;
import info.openrocket.swing.gui.util.GUIUtil;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.axis.Axis;
import org.jfree.chart.renderer.xy.XYItemRenderer;
import org.jfree.chart.title.LegendTitle;
import org.jfree.chart.title.TextTitle;
import org.jfree.chart.plot.XYPlot;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Stroke;

/**
 * A clean style for the charts of simulations: theme colors (so dark themes work too), the user interface font in
 * moderate sizes, light dashed grid lines and thicker lines.
 */
public final class ChartStyle {
	/** Width of the data lines, in pixels */
	static final float LINE_WIDTH = 2.0f;
	static final float TITLE_SIZE = 15f;
	static final float LABEL_SIZE = 12f;
	static final float TICK_SIZE = 11f;

	private ChartStyle() {
	}

	/**
	 * Applies the style to a chart made by {@link Plot}. The main title (the name of the simulation) is removed, so
	 * the name of the plot configuration is the title.
	 */
	public static void apply(JFreeChart chart) {
		// The fallbacks are the colors that the plots had before, for themes that do not define these colors
		Color background = UITheme.getColor(UITheme.Keys.BACKGROUND, new Color(240, 240, 240));
		Color plotBackground = UITheme.getColor(UITheme.Keys.MONTE_CARLO_PLOT_BACKGROUND, Color.WHITE);
		Color gridLine = UITheme.getColor(UITheme.Keys.MONTE_CARLO_GRID, Color.LIGHT_GRAY);
		// Half transparent, so the grid stays in the background on light and dark themes
		Color grid = new Color(gridLine.getRed(), gridLine.getGreen(), gridLine.getBlue(), 110);
		Color text = UITheme.getColor(UITheme.Keys.TEXT, Color.BLACK);
		Color textDim = UITheme.getColor(UITheme.Keys.TEXT_DIM, Color.DARK_GRAY);
		Font titleFont = GUIUtil.createUIFont(GUIUtil.UI_FONT_STYLE_MEDIUM, TITLE_SIZE, 0f);
		Font labelFont = GUIUtil.createUIFont(GUIUtil.UI_FONT_STYLE_REGULAR, LABEL_SIZE, 0f);
		Font tickFont = GUIUtil.createUIFont(GUIUtil.UI_FONT_STYLE_REGULAR, TICK_SIZE, 0f);

		chart.setTitle((TextTitle) null);
		chart.setBackgroundPaint(background);
		boolean first = true;
		for (Object subtitle : chart.getSubtitles()) {
			if (subtitle instanceof LegendTitle legend) {
				legend.setItemFont(labelFont);
				legend.setItemPaint(text);
				legend.setBackgroundPaint(background);
			} else if (subtitle instanceof TextTitle title && first) {
				// The name of the plot configuration
				title.setFont(titleFont);
				title.setPaint(text);
				first = false;
			}
		}

		XYPlot plot = chart.getXYPlot();
		plot.setBackgroundPaint(plotBackground);
		plot.setOutlineVisible(false);
		Stroke gridStroke = new BasicStroke(0.8f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f,
				new float[]{3f, 3f}, 0f);
		plot.setDomainGridlinePaint(grid);
		plot.setRangeGridlinePaint(grid);
		plot.setDomainGridlineStroke(gridStroke);
		plot.setRangeGridlineStroke(gridStroke);

		for (int i = 0; i < plot.getDomainAxisCount(); i++) {
			styleAxis(plot.getDomainAxis(i), labelFont, tickFont, text, textDim, grid);
		}
		for (int i = 0; i < plot.getRangeAxisCount(); i++) {
			styleAxis(plot.getRangeAxis(i), labelFont, tickFont, text, textDim, grid);
		}

		for (int i = 0; i < plot.getRendererCount(); i++) {
			XYItemRenderer renderer = plot.getRenderer(i);
			if (renderer == null || plot.getDataset(i) == null) {
				continue;
			}
			for (int series = 0; series < plot.getDataset(i).getSeriesCount(); series++) {
				if (renderer.getSeriesStroke(series) instanceof BasicStroke stroke) {
					renderer.setSeriesStroke(series, new BasicStroke(LINE_WIDTH, BasicStroke.CAP_ROUND,
							BasicStroke.JOIN_ROUND, 10f, stroke.getDashArray(), stroke.getDashPhase()));
				}
			}
		}
	}

	private static void styleAxis(Axis axis, Font labelFont, Font tickFont, Color text, Color textDim, Color line) {
		if (axis == null) {
			return;
		}
		axis.setLabelFont(labelFont);
		axis.setLabelPaint(text);
		axis.setTickLabelFont(tickFont);
		axis.setTickLabelPaint(textDim);
		axis.setAxisLinePaint(line);
		axis.setTickMarkPaint(line);
	}
}
