package info.openrocket.core.rocketcomponent;

import java.util.ArrayList;
import java.util.List;

import info.openrocket.core.util.Coordinate;
import info.openrocket.core.util.CoordinateIF;

/**
 * Shape tools for the outline of a free-form fin: round or chamfer a corner, replace a straight edge with a curve, or
 * smooth the whole outline. Each tool returns a new list of fin points and leaves the given one unchanged; curves are
 * sampled into short straight edges, which is how a free-form fin is stored and simulated.
 * <p>
 * The points are the free-form fin points: the first and the last point are on the body (y = 0), and the root, from
 * the last point back to the first, is implied. "Outwards" is away from the inside of the fin.
 */
public final class FinShapeTools {
	/** Number of straight edges a curve is made of, unless given */
	public static final int DEFAULT_SEGMENTS = 16;
	private static final double EPSILON = 1e-9;

	/** Why a tool cannot be applied */
	public enum Problem {
		/** The point is the first or the last point, which are on the body */
		NOT_A_CORNER,
		/** The edges at the point are in line, so there is no corner */
		STRAIGHT_CORNER,
		/** The radius or the distance is larger than the edges allow; see {@link FinShapeException#getLimit()} */
		TOO_LARGE,
		/** The value must be larger than zero */
		NOT_POSITIVE,
		/** The edge does not exist */
		NOT_AN_EDGE,
		/** The curve would go below the root, into the body */
		ENTERS_BODY,
		/** A quarter ellipse needs an edge that is neither horizontal nor vertical */
		NEEDS_SLANTED_EDGE
	}

	/** A tool cannot be applied to the fin points */
	public static class FinShapeException extends Exception {
		private final Problem problem;
		private final double limit;

		FinShapeException(Problem problem) {
			this(problem, Double.NaN);
		}

		FinShapeException(Problem problem, double limit) {
			super(problem.name());
			this.problem = problem;
			this.limit = limit;
		}

		public Problem getProblem() {
			return problem;
		}

		/**
		 * @return the largest allowed value, in m, for {@link Problem#TOO_LARGE}, otherwise NaN
		 */
		public double getLimit() {
			return limit;
		}
	}

	/** Curve types for an edge */
	public enum EdgeCurve {
		/** Circular arc, given by how far its middle is from the straight edge */
		ARC,
		/** Quadratic Bézier curve, given by one control point */
		QUADRATIC_BEZIER,
		/** Cubic Bézier curve, given by two control points */
		CUBIC_BEZIER,
		/** Quarter of an ellipse whose axes are along and across the root */
		QUARTER_ELLIPSE
	}

	private FinShapeTools() {
	}

	/**
	 * @return the largest radius with which the corner at the point can be rounded, in m
	 */
	public static double maxCornerRadius(List<CoordinateIF> points, int index) throws FinShapeException {
		Corner corner = corner(points, index);
		return Math.min(corner.lengthBefore, corner.lengthAfter) * Math.tan(corner.angle / 2);
	}

	/**
	 * @return the largest distance from the corner with which the corner at the point can be chamfered, in m
	 */
	public static double maxChamferDistance(List<CoordinateIF> points, int index) throws FinShapeException {
		Corner corner = corner(points, index);
		return Math.min(corner.lengthBefore, corner.lengthAfter);
	}

	/**
	 * @return the largest bulge of an arc on the edge, half the edge length (a half circle), in m
	 */
	public static double maxArcBulge(List<CoordinateIF> points, int edge) throws FinShapeException {
		double[][] ends = edge(points, edge);
		return length(subtract(ends[1], ends[0])) / 2;
	}

	/**
	 * Rounds a corner with a circular arc of the radius that is tangent to both edges at the point (a fillet).
	 *
	 * @param index index of the corner point; not the first or the last point
	 * @param radius radius of the arc, in m
	 * @param segments number of straight edges of the arc
	 */
	public static List<CoordinateIF> roundCorner(List<CoordinateIF> points, int index, double radius, int segments)
			throws FinShapeException {
		Corner corner = corner(points, index);
		requirePositive(radius);
		double max = Math.min(corner.lengthBefore, corner.lengthAfter) * Math.tan(corner.angle / 2);
		if (radius > max + EPSILON) {
			throw new FinShapeException(Problem.TOO_LARGE, max);
		}
		// The arc touches the edges at this distance from the corner
		double tangent = radius / Math.tan(corner.angle / 2);
		double[] start = add(corner.point, scale(corner.toBefore, tangent));
		double[] end = add(corner.point, scale(corner.toAfter, tangent));
		double[] bisector = normalize(add(corner.toBefore, corner.toAfter));
		double[] center = add(corner.point, scale(bisector, radius / Math.sin(corner.angle / 2)));

		double startAngle = Math.atan2(start[1] - center[1], start[0] - center[0]);
		double sweep = Math.atan2(end[1] - center[1], end[0] - center[0]) - startAngle;
		sweep = Math.atan2(Math.sin(sweep), Math.cos(sweep));	// the short way round
		List<double[]> curve = new ArrayList<>();
		for (int i = 0; i <= segments; i++) {
			double angle = startAngle + sweep * i / segments;
			curve.add(new double[]{center[0] + radius * Math.cos(angle), center[1] + radius * Math.sin(angle)});
		}
		return replacePoint(points, index, curve);
	}

	/**
	 * Cuts a corner with a straight edge between the points at the distance from the corner on both edges.
	 *
	 * @param index index of the corner point; not the first or the last point
	 * @param distance distance from the corner along both edges, in m
	 */
	public static List<CoordinateIF> chamferCorner(List<CoordinateIF> points, int index, double distance)
			throws FinShapeException {
		Corner corner = corner(points, index);
		requirePositive(distance);
		double max = maxChamferDistance(points, index);
		if (distance > max + EPSILON) {
			throw new FinShapeException(Problem.TOO_LARGE, max);
		}
		List<double[]> cut = List.of(add(corner.point, scale(corner.toBefore, distance)),
				add(corner.point, scale(corner.toAfter, distance)));
		return replacePoint(points, index, cut);
	}

	/**
	 * Replaces the straight edge from point edge - 1 to point edge with a circular arc.
	 *
	 * @param edge index of the end point of the edge
	 * @param bulge distance of the middle of the arc from the middle of the edge, in m; positive is outwards, and at
	 *              most half the edge length (a half circle)
	 */
	public static List<CoordinateIF> arcEdge(List<CoordinateIF> points, int edge, double bulge, int segments)
			throws FinShapeException {
		double[][] ends = edge(points, edge);
		double[] a = ends[0];
		double[] b = ends[1];
		double chord = length(subtract(b, a));
		if (Math.abs(bulge) > maxArcBulge(points, edge) + EPSILON) {
			throw new FinShapeException(Problem.TOO_LARGE, chord / 2);
		}
		if (Math.abs(bulge) < EPSILON) {
			return new ArrayList<>(points);
		}
		double[] along = normalize(subtract(b, a));
		double[] side = scale(outwardNormal(points, a, b), Math.signum(bulge));
		double sagitta = Math.abs(bulge);
		double radius = (chord * chord / 4 + sagitta * sagitta) / (2 * sagitta);
		double[] middle = scale(add(a, b), 0.5);
		double[] center = add(middle, scale(side, -(radius - sagitta)));
		double halfAngle = Math.asin(Math.min(1, chord / (2 * radius)));

		List<double[]> curve = new ArrayList<>();
		for (int i = 0; i <= segments; i++) {
			double angle = -halfAngle + 2 * halfAngle * i / segments;
			curve.add(add(center, add(scale(along, radius * Math.sin(angle)), scale(side, radius * Math.cos(angle)))));
		}
		return replaceEdge(points, edge, curve);
	}

	/**
	 * Replaces the straight edge from point edge - 1 to point edge with a Bézier curve.
	 *
	 * @param control1 first control point, as {x, y} in m
	 * @param control2 second control point for a cubic curve, or null for a quadratic curve
	 */
	public static List<CoordinateIF> bezierEdge(List<CoordinateIF> points, int edge, double[] control1,
			double[] control2, int segments) throws FinShapeException {
		double[][] ends = edge(points, edge);
		double[] a = ends[0];
		double[] b = ends[1];
		List<double[]> curve = new ArrayList<>();
		for (int i = 0; i <= segments; i++) {
			double t = (double) i / segments;
			double u = 1 - t;
			if (control2 == null) {
				curve.add(add(add(scale(a, u * u), scale(control1, 2 * u * t)), scale(b, t * t)));
			} else {
				curve.add(add(add(scale(a, u * u * u), scale(control1, 3 * u * u * t)),
						add(scale(control2, 3 * u * t * t), scale(b, t * t * t))));
			}
		}
		return replaceEdge(points, edge, curve);
	}

	/**
	 * @return default control points for a Bézier curve on the edge: at a third and two thirds of the edge, moved
	 * 		outwards by a quarter of its length; a quadratic curve uses the first one
	 */
	public static double[][] defaultControlPoints(List<CoordinateIF> points, int edge) throws FinShapeException {
		double[][] ends = edge(points, edge);
		double[] a = ends[0];
		double[] b = ends[1];
		double[] offset = scale(outwardNormal(points, a, b), length(subtract(b, a)) / 4);
		double[] control1 = add(add(scale(a, 2.0 / 3), scale(b, 1.0 / 3)), offset);
		double[] control2 = add(add(scale(a, 1.0 / 3), scale(b, 2.0 / 3)), offset);
		return new double[][]{control1, control2};
	}

	/**
	 * Replaces the straight edge from point edge - 1 to point edge with a quarter of an ellipse whose axes are along
	 * and across the root, like the edges of an elliptical fin.
	 *
	 * @param convex true to bulge outwards, false to curve inwards
	 */
	public static List<CoordinateIF> quarterEllipseEdge(List<CoordinateIF> points, int edge, boolean convex,
			int segments) throws FinShapeException {
		double[][] ends = edge(points, edge);
		double[] a = ends[0];
		double[] b = ends[1];
		if (Math.abs(a[0] - b[0]) < EPSILON || Math.abs(a[1] - b[1]) < EPSILON) {
			throw new FinShapeException(Problem.NEEDS_SLANTED_EDGE);
		}
		// The center is one of the two corners of the box around the edge; the inner one makes a convex curve
		double[] cornerA = {a[0], b[1]};
		double[] cornerB = {b[0], a[1]};
		double[] middle = scale(add(a, b), 0.5);
		double[] outwards = outwardNormal(points, a, b);
		boolean aIsInner = dot(subtract(cornerA, middle), outwards) < 0;
		double[] center = (aIsInner == convex) ? cornerA : cornerB;

		// Walk from a to b: a and b are each on one axis of the ellipse through the center
		double[] toA = subtract(a, center);
		double[] toB = subtract(b, center);
		List<double[]> curve = new ArrayList<>();
		for (int i = 0; i <= segments; i++) {
			double angle = Math.PI / 2 * i / segments;
			curve.add(add(center, add(scale(toA, Math.cos(angle)), scale(toB, Math.sin(angle)))));
		}
		return replaceEdge(points, edge, curve);
	}

	/**
	 * Replaces the outline with a smooth curve through all the points (a centripetal Catmull-Rom spline). The first
	 * and the last point stay where they are.
	 *
	 * @param segments number of straight edges between two neighbouring points
	 */
	public static List<CoordinateIF> smoothOutline(List<CoordinateIF> points, int segments) throws FinShapeException {
		List<double[]> curve = new ArrayList<>();
		int n = points.size();
		curve.add(xy(points.get(0)));
		for (int i = 0; i < n - 1; i++) {
			double[] p1 = xy(points.get(i));
			double[] p2 = xy(points.get(i + 1));
			// At the ends, the missing neighbour is mirrored so the curve leaves the end point along its edge
			double[] p0 = i > 0 ? xy(points.get(i - 1)) : subtract(scale(p1, 2), p2);
			double[] p3 = i + 2 < n ? xy(points.get(i + 2)) : subtract(scale(p2, 2), p1);
			for (int j = 1; j <= segments; j++) {
				curve.add(catmullRom(p0, p1, p2, p3, (double) j / segments));
			}
		}
		return toPoints(curve);
	}

	private static double[] catmullRom(double[] p0, double[] p1, double[] p2, double[] p3, double t) {
		double t0 = 0;
		double t1 = t0 + Math.sqrt(Math.max(length(subtract(p1, p0)), EPSILON));
		double t2 = t1 + Math.sqrt(Math.max(length(subtract(p2, p1)), EPSILON));
		double t3 = t2 + Math.sqrt(Math.max(length(subtract(p3, p2)), EPSILON));
		double tt = t1 + (t2 - t1) * t;
		double[] a1 = lerp(p0, p1, t0, t1, tt);
		double[] a2 = lerp(p1, p2, t1, t2, tt);
		double[] a3 = lerp(p2, p3, t2, t3, tt);
		double[] b1 = lerp(a1, a2, t0, t2, tt);
		double[] b2 = lerp(a2, a3, t1, t3, tt);
		return lerp(b1, b2, t1, t2, tt);
	}

	private static double[] lerp(double[] p, double[] q, double tp, double tq, double t) {
		return add(scale(p, (tq - t) / (tq - tp)), scale(q, (t - tp) / (tq - tp)));
	}

	/** A corner point with unit vectors to its neighbours and the angle between them */
	private record Corner(double[] point, double[] toBefore, double[] toAfter, double lengthBefore,
			double lengthAfter, double angle) {
	}

	private static Corner corner(List<CoordinateIF> points, int index) throws FinShapeException {
		if (index <= 0 || index >= points.size() - 1) {
			throw new FinShapeException(Problem.NOT_A_CORNER);
		}
		double[] point = xy(points.get(index));
		double[] before = subtract(xy(points.get(index - 1)), point);
		double[] after = subtract(xy(points.get(index + 1)), point);
		double lengthBefore = length(before);
		double lengthAfter = length(after);
		if (lengthBefore < EPSILON || lengthAfter < EPSILON) {
			throw new FinShapeException(Problem.STRAIGHT_CORNER);
		}
		double[] toBefore = scale(before, 1 / lengthBefore);
		double[] toAfter = scale(after, 1 / lengthAfter);
		double angle = Math.acos(Math.max(-1, Math.min(1, dot(toBefore, toAfter))));
		if (angle > Math.PI - 1e-6) {
			throw new FinShapeException(Problem.STRAIGHT_CORNER);
		}
		return new Corner(point, toBefore, toAfter, lengthBefore, lengthAfter, angle);
	}

	private static double[][] edge(List<CoordinateIF> points, int edge) throws FinShapeException {
		if (edge <= 0 || edge >= points.size()) {
			throw new FinShapeException(Problem.NOT_AN_EDGE);
		}
		return new double[][]{xy(points.get(edge - 1)), xy(points.get(edge))};
	}

	/**
	 * @return the unit normal of the edge from a to b that points away from the inside of the fin outline, which is
	 * 		closed by the root from the last point back to the first
	 */
	private static double[] outwardNormal(List<CoordinateIF> points, double[] a, double[] b) {
		double area = 0;
		for (int i = 0; i < points.size(); i++) {
			CoordinateIF p = points.get(i);
			CoordinateIF q = points.get((i + 1) % points.size());
			area += p.getX() * q.getY() - q.getX() * p.getY();
		}
		double[] direction = normalize(subtract(b, a));
		// Counter-clockwise outlines have their inside on the left of each edge
		return area > 0 ? new double[]{direction[1], -direction[0]} : new double[]{-direction[1], direction[0]};
	}

	private static List<CoordinateIF> replacePoint(List<CoordinateIF> points, int index, List<double[]> with)
			throws FinShapeException {
		List<double[]> result = new ArrayList<>();
		for (int i = 0; i < points.size(); i++) {
			if (i == index) {
				result.addAll(with);
			} else {
				result.add(xy(points.get(i)));
			}
		}
		return toPoints(result);
	}

	private static List<CoordinateIF> replaceEdge(List<CoordinateIF> points, int edge, List<double[]> curve)
			throws FinShapeException {
		List<double[]> result = new ArrayList<>();
		for (int i = 0; i < edge; i++) {
			result.add(xy(points.get(i)));
		}
		// The curve starts and ends at the end points of the edge, which are already there
		result.addAll(curve.subList(1, curve.size() - 1));
		for (int i = edge; i < points.size(); i++) {
			result.add(xy(points.get(i)));
		}
		return toPoints(result);
	}

	/**
	 * @return the points, without points at the same place as the one before, checked to stay out of the body
	 */
	private static List<CoordinateIF> toPoints(List<double[]> xy) throws FinShapeException {
		List<CoordinateIF> result = new ArrayList<>();
		double[] previous = null;
		for (double[] p : xy) {
			if (p[1] < -EPSILON) {
				throw new FinShapeException(Problem.ENTERS_BODY);
			}
			if (previous == null || length(subtract(p, previous)) > EPSILON) {
				result.add(new Coordinate(p[0], Math.max(0, p[1])));
				previous = p;
			}
		}
		return result;
	}

	private static void requirePositive(double value) throws FinShapeException {
		if (!(value > 0)) {
			throw new FinShapeException(Problem.NOT_POSITIVE);
		}
	}

	private static double[] xy(CoordinateIF c) {
		return new double[]{c.getX(), c.getY()};
	}

	private static double[] add(double[] a, double[] b) {
		return new double[]{a[0] + b[0], a[1] + b[1]};
	}

	private static double[] subtract(double[] a, double[] b) {
		return new double[]{a[0] - b[0], a[1] - b[1]};
	}

	private static double[] scale(double[] a, double factor) {
		return new double[]{a[0] * factor, a[1] * factor};
	}

	private static double dot(double[] a, double[] b) {
		return a[0] * b[0] + a[1] * b[1];
	}

	private static double length(double[] a) {
		return Math.hypot(a[0], a[1]);
	}

	private static double[] normalize(double[] a) {
		return scale(a, 1 / length(a));
	}
}
