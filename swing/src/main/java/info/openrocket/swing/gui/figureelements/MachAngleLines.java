package info.openrocket.swing.gui.figureelements;

import info.openrocket.core.rocketcomponent.FinSet;
import info.openrocket.core.rocketcomponent.FlightConfiguration;
import info.openrocket.core.rocketcomponent.RocketComponent;
import info.openrocket.core.util.BoundingBox;
import info.openrocket.core.util.CoordinateIF;
import info.openrocket.swing.gui.theme.UITheme;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.geom.Line2D;
import java.util.ArrayList;
import java.util.List;

/**
 * Dotted lines at the Mach angle, asin(1 / Mach), in the side view of the rocket: the Mach cone from the nose tip, and
 * a Mach line outwards from the front of the root of every fin, where its leading edge meets the body. Nothing is
 * drawn below Mach 1. The lines run to a little behind the end of the rocket.
 */
public class MachAngleLines implements FigureElement {
	/** The lines run this part of the rocket length behind the end of the rocket. */
	private static final double OVERHANG = 0.2;
	private static final float LINE_WIDTH = 1.0f;
	private static final float[] DOTS = {2f, 4f};

	/** Lines as x1, y1, x2, y2, in m */
	private final List<double[]> lines = new ArrayList<>();

	/**
	 * @return the Mach angle in radians, or NaN at or below Mach 1, where there is none
	 */
	public static double machAngle(double mach) {
		return mach > 1 ? Math.asin(1 / mach) : Double.NaN;
	}

	/**
	 * Calculates the lines.
	 *
	 * @param configuration the flight configuration that is shown
	 * @param mach          the Mach number, or NaN to draw nothing
	 * @param rotation      the rotation of the view around the rocket axis, in radians
	 */
	public void update(FlightConfiguration configuration, double mach, double rotation) {
		lines.clear();
		double angle = machAngle(mach);
		BoundingBox bounds = configuration.getBoundingBox();
		if (Double.isNaN(angle) || bounds.isEmpty()) {
			return;
		}
		double slope = Math.tan(angle);
		double endX = bounds.max.getX() + OVERHANG * (bounds.max.getX() - bounds.min.getX());

		// The Mach cone of the nose tip
		double noseX = bounds.min.getX();
		lines.add(new double[]{noseX, 0, endX, slope * (endX - noseX)});
		lines.add(new double[]{noseX, 0, endX, -slope * (endX - noseX)});

		// A Mach line outwards from the front of the root of every fin
		for (RocketComponent component : configuration.getAllActiveComponents()) {
			if (!(component instanceof FinSet finSet) || !finSet.isVisible()) {
				continue;
			}
			// The first fin point is the front of the root, relative to the fin front on the body
			CoordinateIF rootFront = finSet.getFinPoints()[0];
			CoordinateIF[] roots = finSet.getComponentLocations();
			double[] angles = finSet.getInstanceAngles();
			for (int i = 0; i < roots.length; i++) {
				double finAngle = angles[i % angles.length];
				double x = roots[i].getX() + rootFront.getX();
				double y = roots[i].getY() + rootFront.getY() * Math.cos(finAngle);
				double z = roots[i].getZ() + rootFront.getY() * Math.sin(finAngle);
				// Map the 3D point into the view, like the CG and CP
				double viewY = y * Math.cos(rotation) + z * Math.sin(rotation);
				if (Math.abs(viewY) < 1e-9) {
					continue;		// The fin points at the viewer; its line would lie on the axis
				}
				double outwards = Math.signum(viewY);
				lines.add(new double[]{x, viewY, endX, viewY + outwards * slope * (endX - x)});
			}
		}
	}

	/**
	 * @return the lines as x1, y1, x2, y2, in m
	 */
	public List<double[]> getLines() {
		return lines;
	}

	@Override
	public void paint(Graphics2D g2, double scale) {
		paint(g2, scale, null);
	}

	@Override
	public void paint(Graphics2D g2, double scale, Rectangle visible) {
		if (lines.isEmpty()) {
			return;
		}
		float[] dots = {(float) (DOTS[0] / scale), (float) (DOTS[1] / scale)};
		g2.setStroke(new BasicStroke((float) (LINE_WIDTH / scale), BasicStroke.CAP_BUTT, BasicStroke.JOIN_BEVEL,
				10f, dots, 0f));
		g2.setColor(UITheme.getColor(UITheme.Keys.TEXT, Color.BLACK));
		for (double[] line : lines) {
			g2.draw(new Line2D.Double(line[0], line[1], line[2], line[3]));
		}
	}
}
