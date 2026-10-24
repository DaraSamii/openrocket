package info.openrocket.swing.gui.simulation;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Rectangle2D;

import org.jfree.chart.annotations.AbstractXYAnnotation;
import org.jfree.chart.axis.ValueAxis;
import org.jfree.chart.plot.Plot;
import org.jfree.chart.plot.PlotRenderingInfo;
import org.jfree.chart.plot.XYPlot;
import org.jfree.chart.ui.RectangleEdge;

import info.openrocket.core.unit.Unit;

/**
 * Draws a scale bar in the corner of the landing plot, and a measured distance between two places with its length and
 * bearing.
 */
class PlotOverlayAnnotation extends AbstractXYAnnotation {
	/** Length of the scale bar is about this many pixels */
	private static final int SCALE_BAR_PIXELS = 110;

	private final Unit distanceUnit;
	/** Start and end of the measurement, as east and north in m, or null */
	private double[] measureStart;
	private double[] measureEnd;

	/**
	 * @param distanceUnit unit of the plot axes
	 */
	PlotOverlayAnnotation(Unit distanceUnit) {
		this.distanceUnit = distanceUnit;
	}

	/**
	 * @param start start of the measurement, as east and north in m, or null for no measurement
	 * @param end end of the measurement, or null
	 */
	void setMeasurement(double[] start, double[] end) {
		this.measureStart = start;
		this.measureEnd = end;
	}

	/**
	 * @return a round length for a scale bar about the given length: 1, 2 or 5 times a power of ten
	 */
	static double roundLength(double length) {
		double power = Math.pow(10, Math.floor(Math.log10(length)));
		for (double step : new double[]{5, 2, 1}) {
			if (step * power <= length) {
				return step * power;
			}
		}
		return power;
	}

	@Override
	public void draw(Graphics2D g2, XYPlot plot, Rectangle2D dataArea, ValueAxis domainAxis, ValueAxis rangeAxis,
			int rendererIndex, PlotRenderingInfo info) {
		RectangleEdge domainEdge = Plot.resolveDomainAxisLocation(plot.getDomainAxisLocation(), plot.getOrientation());
		RectangleEdge rangeEdge = Plot.resolveRangeAxisLocation(plot.getRangeAxisLocation(), plot.getOrientation());
		g2.setFont(g2.getFont().deriveFont(Font.PLAIN, 11f));
		FontMetrics metrics = g2.getFontMetrics();

		// Scale bar in the lower left corner
		double metersPerPixel = distanceUnit.fromUnit(domainAxis.getRange().getLength()) / dataArea.getWidth();
		double meters = roundLength(SCALE_BAR_PIXELS * metersPerPixel);
		int width = (int) Math.round(meters / metersPerPixel);
		int x = (int) dataArea.getMinX() + 10;
		int y = (int) dataArea.getMaxY() - 12;
		String label = distanceUnit.toStringUnit(meters);
		g2.setColor(new Color(255, 255, 255, 190));
		g2.fillRect(x - 4, y - metrics.getHeight() - 4, Math.max(width, metrics.stringWidth(label)) + 8,
				metrics.getHeight() + 12);
		g2.setColor(Color.DARK_GRAY);
		g2.setStroke(new BasicStroke(2f));
		g2.draw(new Line2D.Double(x, y, x + width, y));
		g2.draw(new Line2D.Double(x, y - 4, x, y + 3));
		g2.draw(new Line2D.Double(x + width, y - 4, x + width, y + 3));
		g2.drawString(label, x, y - 5);

		// Measured distance
		if (measureStart != null && measureEnd != null) {
			double x0 = domainAxis.valueToJava2D(distanceUnit.toUnit(measureStart[0]), dataArea, domainEdge);
			double y0 = rangeAxis.valueToJava2D(distanceUnit.toUnit(measureStart[1]), dataArea, rangeEdge);
			double x1 = domainAxis.valueToJava2D(distanceUnit.toUnit(measureEnd[0]), dataArea, domainEdge);
			double y1 = rangeAxis.valueToJava2D(distanceUnit.toUnit(measureEnd[1]), dataArea, rangeEdge);
			Color line = new Color(200, 30, 30);
			g2.setColor(line);
			g2.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1f, new float[]{6, 4}, 0));
			g2.draw(new Line2D.Double(x0, y0, x1, y1));
			g2.fill(new Ellipse2D.Double(x0 - 4, y0 - 4, 8, 8));
			g2.fill(new Ellipse2D.Double(x1 - 4, y1 - 4, 8, 8));
			double distance = Math.hypot(measureEnd[0] - measureStart[0], measureEnd[1] - measureStart[1]);
			String text = String.format("%s, %.0f°", distanceUnit.toStringUnit(distance),
					MapGeometry.bearing(measureStart[0], measureStart[1], measureEnd[0], measureEnd[1]));
			int textWidth = metrics.stringWidth(text);
			int tx = (int) ((x0 + x1) / 2) + 8;
			int ty = (int) ((y0 + y1) / 2) - 8;
			g2.setColor(new Color(255, 255, 255, 220));
			g2.fillRect(tx - 3, ty - metrics.getAscent() - 2, textWidth + 6, metrics.getHeight() + 2);
			g2.setColor(line);
			g2.drawString(text, tx, ty);
		}
	}
}
