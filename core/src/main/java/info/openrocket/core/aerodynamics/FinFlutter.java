package info.openrocket.core.aerodynamics;

import info.openrocket.core.rocketcomponent.FinSet;
import info.openrocket.core.util.CoordinateIF;

/**
 * Bending-torsion flutter velocity of a fin by Martin's simplified criterion (NACA TN 4197, 1958, equation 16), with
 * the center of gravity term evaluated for the fin shape as described by Bennett (Peak of Flight newsletter 615):
 * <pre>
 *   (Vf / a)^2 = G / ( 24 epsilon gamma p / pi * A^3 / ((t/c)^3 (A + 2)) * (lambda + 1) / 2 )
 * </pre>
 * where a is the speed of sound, G the shear modulus of the fin material, p the air pressure, gamma = 1.4,
 * A = span^2 / area the aspect ratio of one fin, t/c the thickness over the root chord, lambda the taper ratio and epsilon the
 * distance of the fin centroid behind the quarter root chord, as a fraction of the root chord.
 * <p>
 * Martin intended the criterion as a guide for preliminary design; it was checked against tests up to about
 * Mach 1.5, so its results call for a generous safety margin.
 */
public final class FinFlutter {
	/** Ratio of specific heats of air */
	private static final double GAMMA = 1.4;

	/**
	 * The shape terms of the criterion.
	 *
	 * @param aspectRatio span squared over the area of one fin
	 * @param thicknessRatio thickness over root chord
	 * @param taperRatio tip chord over root chord, for other shapes that of a trapezoid with the same area
	 * @param epsilon distance of the fin centroid behind the quarter root chord, as a fraction of the root chord
	 */
	public record Geometry(double aspectRatio, double thicknessRatio, double taperRatio, double epsilon) {
	}

	private FinFlutter() {
	}

	/**
	 * The shape terms of a fin set.
	 *
	 * @param fins the fin set
	 * @return the shape terms, or null if the fins have no area, span or thickness
	 */
	public static Geometry geometry(FinSet fins) {
		final double rootChord = fins.getLength();
		final double span = fins.getSpan();
		final double thickness = fins.getThickness();
		CoordinateIF[] points = fins.getFinPoints();

		// Area and centroid of the outline, closed along the root
		double twiceArea = 0;
		double xMoment = 0;
		for (int i = 0; i < points.length; i++) {
			CoordinateIF p = points[i];
			CoordinateIF q = points[(i + 1) % points.length];
			double cross = p.getX() * q.getY() - q.getX() * p.getY();
			twiceArea += cross;
			xMoment += (p.getX() + q.getX()) * cross;
		}
		final double area = Math.abs(twiceArea) / 2;
		if (rootChord <= 0 || span <= 0 || thickness <= 0 || area <= 0) {
			return null;
		}
		final double centroidX = xMoment / (3 * twiceArea) - points[0].getX();

		final double tipChord = Math.max(0, 2 * area / span - rootChord);
		return new Geometry(span * span / area, thickness / rootChord, tipChord / rootChord,
				centroidX / rootChord - 0.25);
	}

	/**
	 * The flutter velocity of a fin set in the given air.
	 *
	 * @param fins the fin set
	 * @param speedOfSound the speed of sound, in m/s
	 * @param pressure the air pressure, in Pa
	 * @return the flutter velocity in m/s, or NaN if it cannot be determined, e.g. because the fin material has no
	 * shear modulus or the fin centroid lies ahead of the quarter root chord
	 */
	public static double flutterVelocity(FinSet fins, double speedOfSound, double pressure) {
		Geometry geometry = geometry(fins);
		if (geometry == null) {
			return Double.NaN;
		}
		return flutterVelocity(fins.getMaterial().getInPlaneShearModulus(), geometry, speedOfSound, pressure);
	}

	/**
	 * The flutter velocity for the given shear modulus, shape and air.
	 *
	 * @param shearModulus the shear modulus of the fin material, in the same unit as the pressure
	 * @param geometry the shape terms
	 * @param speedOfSound the speed of sound; the flutter velocity is in the same unit
	 * @param pressure the air pressure
	 * @return the flutter velocity, or NaN if the shear modulus or epsilon is not positive
	 */
	public static double flutterVelocity(double shearModulus, Geometry geometry, double speedOfSound, double pressure) {
		if (!(shearModulus > 0) || !(geometry.epsilon() > 0) || !(pressure > 0)) {
			return Double.NaN;
		}
		final double a = geometry.aspectRatio();
		final double denominator = 24 * geometry.epsilon() * GAMMA * pressure / Math.PI
				* a * a * a / (Math.pow(geometry.thicknessRatio(), 3) * (a + 2))
				* (geometry.taperRatio() + 1) / 2;
		return speedOfSound * Math.sqrt(shearModulus / denominator);
	}
}
