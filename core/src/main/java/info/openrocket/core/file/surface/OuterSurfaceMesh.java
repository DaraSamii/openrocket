package info.openrocket.core.file.surface;

import java.awt.geom.Area;
import java.awt.geom.Path2D;
import java.awt.geom.PathIterator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import info.openrocket.core.l10n.Translator;
import info.openrocket.core.logging.Warning;
import info.openrocket.core.logging.WarningSet;
import info.openrocket.core.rocketcomponent.FinSet;
import info.openrocket.core.rocketcomponent.FlightConfiguration;
import info.openrocket.core.rocketcomponent.InstanceContext;
import info.openrocket.core.rocketcomponent.LaunchLug;
import info.openrocket.core.rocketcomponent.RailButton;
import info.openrocket.core.rocketcomponent.RocketComponent;
import info.openrocket.core.rocketcomponent.SymmetricComponent;
import info.openrocket.core.rocketcomponent.Transition;
import info.openrocket.core.rocketcomponent.TubeFinSet;
import info.openrocket.core.startup.Application;
import info.openrocket.core.util.Coordinate;
import info.openrocket.core.util.CoordinateIF;
import info.openrocket.core.util.Transformation;

/**
 * The closed outer surface of a rocket as one watertight mesh, for CFD meshing tools and CAD.
 * <p>
 * The nose cone, body tubes and transitions on the rocket axis form one surface of revolution that follows the
 * largest radius at each station; steps between parts become flat rings and the aft end is closed with a flat base.
 * Fins are joined to the body exactly: the side faces of a flat fin meet a cylinder along lines of constant angle,
 * so the body mesh gets a matching hole under every fin and the fin faces reuse the vertices around it.
 * Every face is a planar polygon with its vertices in counter-clockwise order seen from outside.
 * Coordinates are in meters with x along the rocket axis, as in OpenRocket.
 * <p>
 * Internal parts are left out. Pods, boosters, launch lugs, rail buttons and tube fins are left out with a warning;
 * fin cant, fillets and rounded or airfoil cross-sections are not modeled.
 */
public final class OuterSurfaceMesh {
	private static final Translator trans = Application.getTranslator();

	/** Divisions around the rocket */
	static final int ANGULAR_DIVISIONS = 128;
	/** Stations along each nose cone and transition */
	static final int PROFILE_DIVISIONS = 100;
	/** Lengths closer than this, in meters, are the same */
	private static final double EPS = 1e-9;

	/**
	 * A planar face of the mesh.
	 *
	 * @param loop vertex indices, counter-clockwise seen from outside
	 * @param triangles the face split into triangles, as indices into {@code loop}
	 * @param region name of the rocket component the face belongs to
	 */
	public record Face(int[] loop, int[] triangles, String region) {
	}

	private final List<double[]> vertices = new ArrayList<>();
	private final List<Face> faces = new ArrayList<>();

	private OuterSurfaceMesh() {
	}

	/** @return the vertex coordinates (x, y, z) in meters */
	public List<double[]> getVertices() {
		return vertices;
	}

	public List<Face> getFaces() {
		return faces;
	}

	/**
	 * Builds the outer surface of the active stages of a configuration.
	 *
	 * @param configuration the flight configuration whose active parts are used
	 * @param warnings receives the parts that are left out or simplified
	 * @return the mesh
	 * @throws IllegalStateException if the configuration has no body on the rocket axis
	 */
	public static OuterSurfaceMesh build(FlightConfiguration configuration, WarningSet warnings) {
		List<Body> bodies = new ArrayList<>();
		List<FinSet> finSets = new ArrayList<>();
		for (Map.Entry<RocketComponent, ArrayList<InstanceContext>> entry : configuration.getActiveInstances().entrySet()) {
			RocketComponent component = entry.getKey();
			if (component instanceof SymmetricComponent symmetric) {
				for (InstanceContext context : entry.getValue()) {
					CoordinateIF location = context.getLocation();
					if (Math.hypot(location.getY(), location.getZ()) > EPS) {
						warn(warnings, "OuterSurface.warning.offAxis", component);
						break;
					}
					if (symmetric.getLength() > EPS) {
						bodies.add(new Body(symmetric, location.getX()));
					}
				}
			} else if (component instanceof FinSet finSet) {
				finSets.add(finSet);
			} else if (component instanceof LaunchLug || component instanceof RailButton
					|| component instanceof TubeFinSet) {
				warn(warnings, "OuterSurface.warning.notIncluded", component);
			}
		}
		if (bodies.isEmpty()) {
			throw new IllegalStateException("No body on the rocket axis");
		}
		// Keep the order of the rocket for stable output
		bodies.sort((a, b) -> Double.compare(a.x0, b.x0));
		finSets.sort((a, b) -> Double.compare(a.getAxialFront(), b.getAxialFront()));

		List<Fin> fins = new ArrayList<>();
		for (FinSet finSet : finSets) {
			addFins(configuration, finSet, fins, warnings);
		}
		mergeCoplanarFins(fins, warnings);

		OuterSurfaceMesh mesh = new OuterSurfaceMesh();
		new Builder(mesh, bodies, fins, warnings).build();
		return mesh;
	}

	private static void warn(WarningSet warnings, String key, RocketComponent component) {
		warnings.add(Warning.fromString(String.format(trans.get(key), component.getName())));
	}

	/** Collects the fins of a fin set that can be joined to the body. */
	private static void addFins(FlightConfiguration configuration, FinSet finSet, List<Fin> fins, WarningSet warnings) {
		if (!(finSet.getParent() instanceof SymmetricComponent parent)) {
			return;
		}
		List<InstanceContext> parentContexts = configuration.getActiveInstances().get(parent);
		for (InstanceContext context : parentContexts) {
			CoordinateIF location = context.getLocation();
			if (Math.hypot(location.getY(), location.getZ()) > EPS) {
				return;		// Already reported with its body
			}
		}

		final double radius = finSet.getBodyRadius();
		final double halfThickness = finSet.getThickness() / 2;
		CoordinateIF[] finPoints = finSet.getFinPoints();
		List<double[]> outline = new ArrayList<>();
		for (CoordinateIF p : finPoints) {
			if (outline.isEmpty() || Math.abs(p.getX() - outline.get(outline.size() - 1)[0]) > EPS
					|| Math.abs(p.getY() - outline.get(outline.size() - 1)[1]) > EPS) {
				outline.add(new double[]{p.getX(), p.getY()});
			}
		}
		double[] first = outline.get(0);
		double[] last = outline.get(outline.size() - 1);
		// A canted fin's root ends are lowered into the body; the fin is exported without cant, so put them back
		if (Math.abs(first[1] - last[1]) < EPS && first[1] <= 0) {
			first[1] = 0;
			last[1] = 0;
		}
		if (halfThickness < EPS) {
			warn(warnings, "OuterSurface.warning.zeroThickness", finSet);
			return;
		}
		if (!finSet.isRootStraight() || radius < 2 * halfThickness || outline.size() < 3
				|| Math.abs(first[1]) > EPS || Math.abs(last[1]) > EPS || last[0] - first[0] < EPS) {
			warn(warnings, "OuterSurface.warning.finNotJoined", finSet);
			return;
		}
		if (Math.abs(finSet.getCantAngle()) > EPS) {
			warn(warnings, "OuterSurface.warning.cant", finSet);
		}
		if (finSet.getFilletRadius() > EPS) {
			warn(warnings, "OuterSurface.warning.fillet", finSet);
		}
		if (finSet.getCrossSection() != FinSet.CrossSection.SQUARE) {
			warn(warnings, "OuterSurface.warning.crossSection", finSet);
		}

		final double halfAngle = Math.asin(halfThickness / radius);
		final double[][] points = outline.toArray(new double[0][]);
		// Place the fins from their angles rather than their instance locations, which include the cant
		Transformation parentTransform = parentContexts.get(0).transform;
		for (double instanceAngle : finSet.getInstanceAngles()) {
			CoordinateIF root = parentTransform.transform(
					Transformation.rotate_x(instanceAngle).transform(new Coordinate(finSet.getAxialFront(), radius, 0)));
			double angle = Math.atan2(root.getZ(), root.getY());
			fins.add(new Fin(finSet, finSet.getName(), root.getX() + first[0], root.getX() + last[0],
					normalize(angle), radius, halfAngle, root.getX(), points));
		}
	}

	/**
	 * Joins fins that lie in the same plane and touch or overlap, such as fins of two stages that meet at the stage
	 * joint, into one fin with the union of their outlines. Otherwise their faces would lie on top of or cut through
	 * each other. Fins that overlap but cannot be joined into one outline on a straight root are left out.
	 */
	private static void mergeCoplanarFins(List<Fin> fins, WarningSet warnings) {
		fins.sort((a, b) -> Double.compare(a.xStart, b.xStart));
		for (int i = 0; i < fins.size(); i++) {
			for (int k = 0; k < fins.size(); k++) {
				Fin a = fins.get(i);
				Fin b = fins.get(k);
				if (k == i || angleDistance(a.angle, b.angle) > 1e-12 || Math.abs(a.halfAngle - b.halfAngle) > 1e-12
						|| Math.abs(a.radius - b.radius) > EPS) {
					continue;
				}
				Area union = outlineArea(a);
				Area other = outlineArea(b);
				Area common = new Area(union);
				common.intersect(other);
				union.add(other);
				double[][] outline = union.isSingular() ? rootedOutline(union, a.xRef) : null;
				if (outline == null) {
					if (!common.isEmpty()) {
						warn(warnings, "OuterSurface.warning.finOverlap", b.finSet);
						fins.remove(k);
						i = -1;
						break;
					}
					continue;
				}
				fins.set(i, new Fin(a.finSet, a.region + " " + b.region, a.xRef + outline[0][0],
						a.xRef + outline[outline.length - 1][0], a.angle, a.radius, a.halfAngle, a.xRef, outline));
				fins.remove(k);
				i = -1;		// Start over, the joined fin may touch further fins
				break;
			}
		}
	}

	/** The outline of a fin in the plane of the fin, with x along the rocket and y above the body surface */
	private static Area outlineArea(Fin fin) {
		Path2D.Double path = new Path2D.Double();
		path.moveTo(fin.xRef + fin.outline[0][0], fin.outline[0][1]);
		for (int i = 1; i < fin.outline.length; i++) {
			path.lineTo(fin.xRef + fin.outline[i][0], fin.outline[i][1]);
		}
		path.closePath();
		return new Area(path);
	}

	/**
	 * The outline of a single area as fin points relative to xRef, from the root leading edge over the fin to the root
	 * trailing edge, or null if the bottom of the area is not one straight root on the body surface.
	 */
	private static double[][] rootedOutline(Area area, double xRef) {
		List<double[]> loop = new ArrayList<>();
		double[] coords = new double[6];
		for (PathIterator it = area.getPathIterator(null); !it.isDone(); it.next()) {
			int type = it.currentSegment(coords);
			if (type == PathIterator.SEG_MOVETO || type == PathIterator.SEG_LINETO) {
				double[] p = {coords[0] - xRef, Math.abs(coords[1]) < EPS ? 0 : coords[1]};
				if (loop.isEmpty() || Math.abs(p[0] - loop.get(loop.size() - 1)[0]) > EPS
						|| Math.abs(p[1] - loop.get(loop.size() - 1)[1]) > EPS) {
					loop.add(p);
				}
			}
		}
		if (loop.size() > 1 && Math.abs(loop.get(0)[0] - loop.get(loop.size() - 1)[0]) <= EPS
				&& Math.abs(loop.get(0)[1] - loop.get(loop.size() - 1)[1]) <= EPS) {
			loop.remove(loop.size() - 1);
		}
		final int n = loop.size();
		int leading = -1;
		int trailing = -1;
		for (int i = 0; i < n; i++) {
			double[] p = loop.get(i);
			if (p[1] != 0) {
				continue;
			}
			if (leading < 0 || p[0] < loop.get(leading)[0]) {
				leading = i;
			}
			if (trailing < 0 || p[0] > loop.get(trailing)[0]) {
				trailing = i;
			}
		}
		if (leading < 0 || leading == trailing) {
			return null;
		}
		// One way around the loop is the root; the other is the outline
		for (int step : new int[]{1, -1}) {
			List<double[]> path = new ArrayList<>();
			boolean onRoot = true;
			for (int i = leading; ; i = (i + step + n) % n) {
				path.add(loop.get(i));
				onRoot &= loop.get(i)[1] == 0;
				if (i == trailing) {
					break;
				}
			}
			if (!onRoot) {
				List<double[]> back = new ArrayList<>();
				for (int i = trailing; ; i = (i + step + n) % n) {
					back.add(loop.get(i));
					if (i == leading) {
						break;
					}
				}
				for (double[] p : back) {
					if (p[1] != 0) {
						return null;		// The root side is not straight
					}
				}
				return path.toArray(new double[0][]);
			}
		}
		return null;
	}

	private static double normalize(double angle) {
		double a = angle % (2 * Math.PI);
		return a < 0 ? a + 2 * Math.PI : a;
	}

	/** Cyclic distance between two angles */
	private static double angleDistance(double a, double b) {
		double d = Math.abs(normalize(a) - normalize(b));
		return Math.min(d, 2 * Math.PI - d);
	}

	/**
	 * Splits a simple polygon into triangles by ear clipping.
	 *
	 * @param u first coordinates of the polygon, counter-clockwise
	 * @param v second coordinates
	 * @return triangles as indices into the polygon
	 * @throws IllegalStateException if the polygon cannot be split, e.g. because it crosses itself
	 */
	static int[] triangulate(double[] u, double[] v) {
		final int n = u.length;
		List<Integer> remaining = new ArrayList<>();
		for (int i = 0; i < n; i++) {
			remaining.add(i);
		}
		int[] result = new int[3 * (n - 2)];
		int count = 0;
		while (remaining.size() > 3) {
			boolean clipped = false;
			for (int k = 0; k < remaining.size(); k++) {
				int a = remaining.get((k + remaining.size() - 1) % remaining.size());
				int b = remaining.get(k);
				int c = remaining.get((k + 1) % remaining.size());
				if (cross(u, v, a, b, c) <= 1e-18 || containsOther(u, v, remaining, a, b, c)) {
					continue;
				}
				result[count++] = a;
				result[count++] = b;
				result[count++] = c;
				remaining.remove(k);
				clipped = true;
				break;
			}
			if (!clipped) {
				throw new IllegalStateException("Polygon cannot be split into triangles");
			}
		}
		result[count++] = remaining.get(0);
		result[count++] = remaining.get(1);
		result[count] = remaining.get(2);
		return result;
	}

	private static double cross(double[] u, double[] v, int a, int b, int c) {
		return (u[b] - u[a]) * (v[c] - v[a]) - (v[b] - v[a]) * (u[c] - u[a]);
	}

	/** Whether another polygon vertex lies in or on the triangle a, b, c */
	private static boolean containsOther(double[] u, double[] v, List<Integer> remaining, int a, int b, int c) {
		for (int p : remaining) {
			if (p == a || p == b || p == c
					|| (u[p] == u[a] && v[p] == v[a]) || (u[p] == u[c] && v[p] == v[c])) {
				continue;
			}
			if (cross(u, v, a, b, p) >= -1e-18 && cross(u, v, b, c, p) >= -1e-18 && cross(u, v, c, a, p) >= -1e-18) {
				return true;
			}
		}
		return false;
	}

	/** A body of revolution on the rocket axis, starting at x0 */
	private record Body(SymmetricComponent component, double x0) {
		double x1() {
			return x0 + component.getLength();
		}

		double radius(double x) {
			return component.getRadius(Math.max(0, Math.min(component.getLength(), x - x0)));
		}
	}

	/**
	 * One fin, or fins joined into one. Its root runs from xStart to xEnd on a cylinder of the given radius, centered
	 * on the angle; the outline points are relative to xRef and to the body surface.
	 */
	private record Fin(FinSet finSet, String region, double xStart, double xEnd, double angle, double radius, double halfAngle,
					   double xRef, double[][] outline) {
	}

	/** A point of the body profile; region is the component of the profile segment that starts here */
	private record ProfilePoint(double x, double r, String region) {
	}

	private static final class Builder {
		private final OuterSurfaceMesh mesh;
		private final List<Body> bodies;
		private final List<Fin> fins;
		private final WarningSet warnings;

		private final List<ProfilePoint> profile = new ArrayList<>();
		private double[] angles;
		/** Vertex of each profile point and angle, -1 inside a fin; a ring on the axis has one vertex */
		private int[][] ringVertices;
		/** Per fin, the profile points at the start and end of its root and the angle indices of its two sides */
		private final List<int[]> holes = new ArrayList<>();
		/** Cells (profile point, angle index) of the body that are covered by a fin root */
		private final Set<Long> holeCells = new HashSet<>();
		/** Ring vertices inside a fin, which are left out */
		private final Set<Long> hidden = new HashSet<>();

		Builder(OuterSurfaceMesh mesh, List<Body> bodies, List<Fin> fins, WarningSet warnings) {
			this.mesh = mesh;
			this.bodies = bodies;
			this.fins = new ArrayList<>(fins);
			this.warnings = warnings;
		}

		void build() {
			buildProfile();
			placeFins();
			buildRings();
			addBodyFaces();
			for (int i = 0; i < fins.size(); i++) {
				addFinFaces(fins.get(i), holes.get(i));
			}
		}

		private void buildProfile() {
			List<Double> stations = new ArrayList<>();
			for (Body body : bodies) {
				stations.add(body.x0);
				stations.add(body.x1());
				if (body.component instanceof Transition) {
					// Cosine spacing puts more stations near the ends, where noses curve most
					for (int i = 1; i < PROFILE_DIVISIONS; i++) {
						stations.add(body.x0 + body.component.getLength() * (1 - Math.cos(Math.PI * i / PROFILE_DIVISIONS)) / 2);
					}
				}
			}
			for (Fin fin : fins) {
				stations.add(fin.xStart);
				stations.add(fin.xEnd);
			}
			stations.sort(null);

			double previous = Double.NEGATIVE_INFINITY;
			for (double x : stations) {
				if (x - previous < EPS) {
					continue;
				}
				previous = x;
				Body before = largest(x, true);
				Body after = largest(x, false);
				if (before == null && after == null) {
					continue;		// A fin end beyond the body
				}
				double rBefore = before == null ? 0 : before.radius(x);
				double rAfter = after == null ? 0 : after.radius(x);
				if (Math.abs(rBefore - rAfter) > EPS) {
					addProfilePoint(x, rBefore, rBefore > rAfter ? before : after);
				}
				addProfilePoint(x, rAfter, after != null ? after : before);
			}

			for (int i = 1; i < profile.size() - 1; i++) {
				if (profile.get(i).r < EPS) {
					throw new IllegalStateException("The rocket body has a gap at x=" + profile.get(i).x);
				}
			}
		}

		private void addProfilePoint(double x, double r, Body region) {
			if (!profile.isEmpty()) {
				ProfilePoint last = profile.get(profile.size() - 1);
				if (Math.abs(last.x - x) < EPS && Math.abs(last.r - r) < EPS) {
					return;
				}
			}
			profile.add(new ProfilePoint(x, r < EPS ? 0 : r, region.component.getName()));
		}

		/** The body with the largest radius just before (or just after) x, or null */
		private Body largest(double x, boolean before) {
			Body best = null;
			double bestRadius = -1;
			for (Body body : bodies) {
				boolean covers = before ? body.x0 < x - EPS && x <= body.x1() + EPS
						: body.x0 - EPS <= x && x < body.x1() - EPS;
				if (covers && body.radius(x) > bestRadius) {
					best = body;
					bestRadius = body.radius(x);
				}
			}
			return best;
		}

		/** Chooses the angles around the rocket and the hole of each fin, leaving out fins that do not fit. */
		private void placeFins() {
			List<Fin> placed = new ArrayList<>();
			List<int[]> rootPoints = new ArrayList<>();
			for (Fin fin : fins) {
				int[] root = findRoot(fin);
				if (root == null) {
					warn(warnings, "OuterSurface.warning.finNotJoined", fin.finSet);
					continue;
				}
				placed.add(fin);
				rootPoints.add(root);
				double[] candidate = computeAngles(placed);
				if (!holesFit(placed, rootPoints, candidate)) {
					placed.remove(placed.size() - 1);
					rootPoints.remove(rootPoints.size() - 1);
					warn(warnings, "OuterSurface.warning.finOverlap", fin.finSet);
				}
			}
			fins.clear();
			fins.addAll(placed);
			angles = computeAngles(placed);
			holesFit(placed, rootPoints, angles);
		}

		/** The profile points at both ends of the fin root, if the whole root lies on a cylinder of the fin's radius */
		private int[] findRoot(Fin fin) {
			int start = -1;
			int end = -1;
			for (int i = 0; i < profile.size(); i++) {
				ProfilePoint p = profile.get(i);
				if (p.x < fin.xStart - EPS || p.x > fin.xEnd + EPS) {
					continue;
				}
				boolean onCylinder = Math.abs(p.r - fin.radius) < 1e-7;
				if (start < 0) {
					if (Math.abs(p.x - fin.xStart) < EPS && onCylinder) {
						start = i;
					}
				} else if (!onCylinder) {
					return null;
				} else if (Math.abs(p.x - fin.xEnd) < EPS) {
					end = i;
					break;
				}
			}
			return start < 0 || end < 0 ? null : new int[]{start, end};
		}

		private double[] computeAngles(List<Fin> placed) {
			final double step = 2 * Math.PI / ANGULAR_DIVISIONS;
			List<Double> list = new ArrayList<>();
			for (int i = 0; i < ANGULAR_DIVISIONS; i++) {
				double a = i * step;
				boolean free = true;
				for (Fin fin : placed) {
					// Keep regular lines out of the fin hole, and away from its edges to avoid slivers
					if (angleDistance(a, fin.angle) < fin.halfAngle + step / 4) {
						free = false;
						break;
					}
				}
				if (free) {
					list.add(a);
				}
			}
			for (Fin fin : placed) {
				list.add(normalize(fin.angle - fin.halfAngle));
				list.add(normalize(fin.angle + fin.halfAngle));
			}
			list.sort(null);
			List<Double> unique = new ArrayList<>();
			for (double a : list) {
				if (unique.isEmpty() || a - unique.get(unique.size() - 1) > 1e-12) {
					unique.add(a);
				}
			}
			if (unique.size() > 1 && unique.get(0) + 2 * Math.PI - unique.get(unique.size() - 1) <= 1e-12) {
				unique.remove(unique.size() - 1);
			}
			return unique.stream().mapToDouble(Double::doubleValue).toArray();
		}

		/**
		 * Fills in the holes of the fins and the ring vertices they hide, and checks that no fin covers a cell or a
		 * side vertex of another fin.
		 */
		private boolean holesFit(List<Fin> placed, List<int[]> rootPoints, double[] candidate) {
			holes.clear();
			holeCells.clear();
			hidden.clear();
			Set<Long> sides = new HashSet<>();
			for (int i = 0; i < placed.size(); i++) {
				Fin fin = placed.get(i);
				int minus = indexOf(candidate, normalize(fin.angle - fin.halfAngle));
				int plus = indexOf(candidate, normalize(fin.angle + fin.halfAngle));
				int[] root = rootPoints.get(i);
				for (int p = root[0]; p <= root[1]; p++) {
					sides.add(cell(p, minus));
					sides.add(cell(p, plus));
					// Lines of other fins that pass under this fin
					for (int j = (minus + 1) % candidate.length; j != plus; j = (j + 1) % candidate.length) {
						hidden.add(cell(p, j));
					}
					for (int j = minus; p < root[1] && j != plus; j = (j + 1) % candidate.length) {
						if (!holeCells.add(cell(p, j))) {
							return false;
						}
					}
				}
				holes.add(new int[]{root[0], root[1], minus, plus});
			}
			for (long side : sides) {
				if (hidden.contains(side)) {
					return false;
				}
			}
			return true;
		}

		private static int indexOf(double[] values, double value) {
			for (int i = 0; i < values.length; i++) {
				if (Math.abs(values[i] - value) <= 1e-12) {
					return i;
				}
			}
			return -1;
		}

		private static long cell(int profileIndex, int angleIndex) {
			return ((long) profileIndex << 32) | angleIndex;
		}

		private void buildRings() {
			ringVertices = new int[profile.size()][angles.length];
			for (int i = 0; i < profile.size(); i++) {
				ProfilePoint p = profile.get(i);
				if (p.r == 0) {
					Arrays.fill(ringVertices[i], mesh.vertices.size());
					mesh.vertices.add(new double[]{p.x, 0, 0});
					continue;
				}
				for (int j = 0; j < angles.length; j++) {
					if (hidden.contains(cell(i, j))) {
						ringVertices[i][j] = -1;
					} else {
						ringVertices[i][j] = mesh.vertices.size();
						mesh.vertices.add(new double[]{p.x, p.r * Math.cos(angles[j]), p.r * Math.sin(angles[j])});
					}
				}
			}
		}

		private int ringVertex(int profileIndex, int angleIndex) {
			return ringVertices[profileIndex][angleIndex % angles.length];
		}

		private void addBodyFaces() {
			for (int p = 0; p < profile.size() - 1; p++) {
				boolean axisBefore = profile.get(p).r == 0;
				boolean axisAfter = profile.get(p + 1).r == 0;
				if (axisBefore && axisAfter) {
					continue;
				}
				String region = profile.get(p).region;
				// Split the row where both rings have a vertex; hidden vertices only occur between such angles
				List<Integer> breaks = new ArrayList<>();
				for (int j = 0; j < angles.length; j++) {
					if (ringVertex(p, j) >= 0 && ringVertex(p + 1, j) >= 0) {
						breaks.add(j);
					}
				}
				for (int k = 0; k < breaks.size(); k++) {
					int from = breaks.get(k);
					int to = k + 1 < breaks.size() ? breaks.get(k + 1) : breaks.get(0) + angles.length;
					if (!holeCells.contains(cell(p, from))) {
						addRowPiece(p, from, to, axisBefore, axisAfter, region);
					}
				}
			}
		}

		/** Adds the body between two rings from one angle index to another, with the vertices both rings have there */
		private void addRowPiece(int p, int from, int to, boolean axisBefore, boolean axisAfter, String region) {
			if (to == from + 1) {
				int a = ringVertex(p, from);
				int b = ringVertex(p, to);
				int c = ringVertex(p + 1, to);
				int d = ringVertex(p + 1, from);
				if (axisBefore) {
					addFace(new int[]{a, c, d}, new int[]{0, 1, 2}, region);
				} else if (axisAfter) {
					addFace(new int[]{a, b, c}, new int[]{0, 1, 2}, region);
				} else {
					addFace(new int[]{a, b, c, d}, new int[]{0, 1, 2, 0, 2, 3}, region);
				}
				return;
			}
			// Some vertices are hidden, so the piece is not planar: zip the two rings together with triangles
			List<Integer> top = new ArrayList<>();
			List<Integer> bottom = new ArrayList<>();
			for (int j = from; j <= to; j++) {
				if (ringVertex(p, j) >= 0 && (!axisBefore || top.isEmpty())) {
					top.add(j);
				}
				if (ringVertex(p + 1, j) >= 0 && (!axisAfter || bottom.isEmpty())) {
					bottom.add(j);
				}
			}
			int t = 0;
			int b = 0;
			while (t < top.size() - 1 || b < bottom.size() - 1) {
				boolean advanceTop = b == bottom.size() - 1
						|| (t < top.size() - 1 && top.get(t + 1) <= bottom.get(b + 1));
				if (advanceTop) {
					addFace(new int[]{ringVertex(p, top.get(t)), ringVertex(p, top.get(t + 1)),
							ringVertex(p + 1, bottom.get(b))}, new int[]{0, 1, 2}, region);
					t++;
				} else {
					addFace(new int[]{ringVertex(p, top.get(t)), ringVertex(p + 1, bottom.get(b + 1)),
							ringVertex(p + 1, bottom.get(b))}, new int[]{0, 1, 2}, region);
					b++;
				}
			}
		}

		private void addFinFaces(Fin fin, int[] hole) {
			final int rootStart = hole[0];
			final int rootEnd = hole[1];
			final int minusIndex = hole[2];
			final int plusIndex = hole[3];
			final double halfThickness = fin.radius * Math.sin(fin.halfAngle);
			final String region = fin.region;

			// The outline above the root, from the leading edge to the trailing edge, on both sides
			final int tipCount = fin.outline.length - 2;
			final int plusTip = mesh.vertices.size();
			for (int i = 1; i <= tipCount; i++) {
				mesh.vertices.add(finVertex(fin, fin.outline[i], halfThickness));
			}
			final int minusTip = mesh.vertices.size();
			for (int i = 1; i <= tipCount; i++) {
				mesh.vertices.add(finVertex(fin, fin.outline[i], -halfThickness));
			}

			// Plus side: along the root, then back along the outline; counter-clockwise in (x, radial)
			final int rootCount = rootEnd - rootStart + 1;
			final int size = rootCount + tipCount;
			int[] plusLoop = new int[size];
			int[] minusLoop = new int[size];
			double[] u = new double[size];
			double[] v = new double[size];
			final double rootHeight = fin.radius * Math.cos(fin.halfAngle);
			for (int i = 0; i < rootCount; i++) {
				plusLoop[i] = ringVertex(rootStart + i, plusIndex);
				minusLoop[i] = ringVertex(rootStart + i, minusIndex);
				u[i] = profile.get(rootStart + i).x;
				v[i] = rootHeight;
			}
			for (int i = 0; i < tipCount; i++) {
				int outlineIndex = tipCount - i;
				plusLoop[rootCount + i] = plusTip + outlineIndex - 1;
				minusLoop[rootCount + i] = minusTip + outlineIndex - 1;
				u[rootCount + i] = fin.xRef + fin.outline[outlineIndex][0];
				v[rootCount + i] = fin.radius + fin.outline[outlineIndex][1];
			}
			int[] triangles;
			try {
				triangles = triangulate(u, v);
			} catch (IllegalStateException e) {
				throw new IllegalStateException("The outline of " + region + " crosses itself", e);
			}
			addFace(plusLoop, triangles, region);

			// Minus side: the same polygon seen from the other side
			int[] reversedLoop = new int[size];
			for (int i = 0; i < size; i++) {
				reversedLoop[i] = minusLoop[size - 1 - i];
			}
			int[] reversedTriangles = new int[triangles.length];
			for (int i = 0; i < triangles.length; i += 3) {
				reversedTriangles[i] = size - 1 - triangles[i];
				reversedTriangles[i + 1] = size - 1 - triangles[i + 2];
				reversedTriangles[i + 2] = size - 1 - triangles[i + 1];
			}
			addFace(reversedLoop, reversedTriangles, region);

			// Edges: from the root leading edge over the outline to the root trailing edge
			int[] plusEdge = new int[tipCount + 2];
			int[] minusEdge = new int[tipCount + 2];
			plusEdge[0] = ringVertex(rootStart, plusIndex);
			minusEdge[0] = ringVertex(rootStart, minusIndex);
			for (int i = 0; i < tipCount; i++) {
				plusEdge[i + 1] = plusTip + i;
				minusEdge[i + 1] = minusTip + i;
			}
			plusEdge[tipCount + 1] = ringVertex(rootEnd, plusIndex);
			minusEdge[tipCount + 1] = ringVertex(rootEnd, minusIndex);
			for (int i = 0; i < tipCount + 1; i++) {
				addFace(new int[]{plusEdge[i], plusEdge[i + 1], minusEdge[i + 1], minusEdge[i]},
						new int[]{0, 1, 2, 0, 2, 3}, region);
			}
		}

		/** A fin outline point on the side at the given offset from the fin's middle plane */
		private static double[] finVertex(Fin fin, double[] point, double offset) {
			double radial = fin.radius + point[1];
			double cos = Math.cos(fin.angle);
			double sin = Math.sin(fin.angle);
			return new double[]{fin.xRef + point[0], radial * cos - offset * sin, radial * sin + offset * cos};
		}

		private void addFace(int[] loop, int[] triangles, String region) {
			mesh.faces.add(new Face(loop, triangles, region));
		}
	}
}
