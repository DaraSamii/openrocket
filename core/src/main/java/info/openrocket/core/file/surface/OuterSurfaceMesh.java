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
import info.openrocket.core.rocketcomponent.InnerTube;
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
 * The nose cone, body tubes and transitions on the rocket axis, and inner tubes on the axis where they stick out,
 * form one surface of revolution that follows the largest radius at each station; steps between parts become flat
 * rings and the aft end is closed with a flat base. Fins are joined to the body exactly: the side faces of a flat fin
 * meet a cylinder along lines of constant angle, so the body mesh gets a matching hole under every fin and the fin
 * faces reuse the vertices around it. Rail buttons sit in a patch of the body that is triangulated to meet their base.
 * Every face has its vertices in counter-clockwise order seen from outside, and all but the patches around rail buttons
 * are planar. Coordinates are in meters with x along the rocket axis, as in OpenRocket.
 * <p>
 * Internal parts are left out. Pods, boosters, launch lugs and tube fins are left out with a warning; fin cant, fillets
 * and rounded or airfoil cross-sections are not modeled.
 */
public final class OuterSurfaceMesh {
	private static final Translator trans = Application.getTranslator();

	/** Lengths closer than this, in meters, are the same */
	private static final double EPS = 1e-9;
	/** Width of the body patch around a rail button, relative to the button's base diameter */
	private static final double BUTTON_PATCH = 1.4;

	/**
	 * How finely the surface is divided.
	 */
	public enum Detail {
		COARSE(48, 4, 50, 16),
		NORMAL(96, 3, 100, 24),
		FINE(192, 2, 200, 32),
		VERY_FINE(384, 1.5, 400, 48);

		private final int angularDivisions;
		private final double axialAspect;
		private final int profileDivisions;
		private final int buttonDivisions;

		Detail(int angularDivisions, double axialAspect, int profileDivisions, int buttonDivisions) {
			this.angularDivisions = angularDivisions;
			this.axialAspect = axialAspect;
			this.profileDivisions = profileDivisions;
			this.buttonDivisions = buttonDivisions;
		}

		/** @return the divisions around the rocket */
		public int getAngularDivisions() {
			return angularDivisions;
		}

		/** @return how much longer than wide the body faces may be */
		public double getAxialAspect() {
			return axialAspect;
		}

		/** @return the stations along each nose cone and transition */
		public int getProfileDivisions() {
			return profileDivisions;
		}

		/** @return the divisions around a rail button */
		public int getButtonDivisions() {
			return buttonDivisions;
		}
	}

	/**
	 * What to build.
	 *
	 * @param detail how finely to divide the surface
	 * @param railButtons whether to include the rail buttons
	 */
	public record Options(Detail detail, boolean railButtons) {
		public static final Options DEFAULT = new Options(Detail.NORMAL, true);
	}

	/**
	 * A face of the mesh.
	 *
	 * @param loop the boundary as vertex indices, counter-clockwise seen from outside; planar except for the patches
	 *             around rail buttons, which are single triangles
	 * @param triangles the face split into triangles, as vertex indices; they may use vertices inside the face
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
	 * Builds the outer surface of the active stages of a configuration with the default options.
	 *
	 * @see #build(FlightConfiguration, Options, WarningSet)
	 */
	public static OuterSurfaceMesh build(FlightConfiguration configuration, WarningSet warnings) {
		return build(configuration, Options.DEFAULT, warnings);
	}

	/**
	 * Builds the outer surface of the active stages of a configuration.
	 *
	 * @param configuration the flight configuration whose active parts are used
	 * @param options what to build
	 * @param warnings receives the parts that are left out or simplified
	 * @return the mesh
	 * @throws IllegalStateException if the configuration has no body on the rocket axis
	 */
	public static OuterSurfaceMesh build(FlightConfiguration configuration, Options options, WarningSet warnings) {
		List<Body> bodies = new ArrayList<>();
		List<FinSet> finSets = new ArrayList<>();
		List<RailButton> railButtons = new ArrayList<>();
		List<Body> offAxisTubes = new ArrayList<>();
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
						bodies.add(new Body(symmetric, location.getX(), symmetric.getLength(), symmetric, 0));
					}
				}
			} else if (component instanceof InnerTube tube && tube.getLength() > EPS) {
				// A motor tube only shows where it sticks out of the body, which the largest radius takes care of
				for (InstanceContext context : entry.getValue()) {
					CoordinateIF location = context.getLocation();
					Body body = new Body(tube, location.getX(), tube.getLength(), null, tube.getOuterRadius());
					if (Math.hypot(location.getY(), location.getZ()) > EPS) {
						offAxisTubes.add(body);
					} else {
						bodies.add(body);
					}
				}
			} else if (component instanceof FinSet finSet) {
				finSets.add(finSet);
			} else if (component instanceof RailButton button) {
				if (options.railButtons()) {
					railButtons.add(button);
				}
			} else if (component instanceof LaunchLug || component instanceof TubeFinSet) {
				warn(warnings, "OuterSurface.warning.notIncluded", component);
			}
		}
		if (bodies.isEmpty()) {
			throw new IllegalStateException("No body on the rocket axis");
		}
		// Keep the order of the rocket for stable output
		bodies.sort((a, b) -> Double.compare(a.x0, b.x0));
		finSets.sort((a, b) -> Double.compare(a.getAxialFront(), b.getAxialFront()));
		railButtons.sort((a, b) -> Double.compare(a.getAxialOffset(), b.getAxialOffset()));
		warnProtrudingTubes(bodies, offAxisTubes, warnings);

		List<Footprint> footprints = new ArrayList<>();
		List<Fin> fins = new ArrayList<>();
		for (FinSet finSet : finSets) {
			addFins(configuration, finSet, fins, warnings);
		}
		mergeCoplanarFins(fins, warnings);
		footprints.addAll(fins);
		for (RailButton button : railButtons) {
			addButtons(configuration, button, footprints, warnings);
		}

		OuterSurfaceMesh mesh = new OuterSurfaceMesh();
		new Builder(mesh, options.detail(), bodies, footprints, warnings).build();
		return mesh;
	}

	private static void warn(WarningSet warnings, String key, RocketComponent component) {
		warnings.add(Warning.fromString(String.format(trans.get(key), component.getName())));
	}

	/** Warns about clustered motor tubes that stick out of the body, which are not included */
	private static void warnProtrudingTubes(List<Body> bodies, List<Body> offAxisTubes, WarningSet warnings) {
		double front = Double.POSITIVE_INFINITY;
		double back = Double.NEGATIVE_INFINITY;
		for (Body body : bodies) {
			front = Math.min(front, body.x0);
			back = Math.max(back, body.x1());
		}
		for (Body tube : offAxisTubes) {
			if (tube.x0 < front - EPS || tube.x1() > back + EPS) {
				warn(warnings, "OuterSurface.warning.offAxis", tube.component);
			}
		}
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

	/** Collects the rail buttons of a component that sit on the rocket's main body */
	private static void addButtons(FlightConfiguration configuration, RailButton button, List<Footprint> footprints,
								   WarningSet warnings) {
		final double baseRadius = (button.getBaseHeight() > EPS ? button.getOuterDiameter()
				: Math.min(button.getInnerDiameter(), button.getOuterDiameter())) / 2;
		for (InstanceContext context : configuration.getActiveInstances().get(button)) {
			CoordinateIF location = context.getLocation();
			double radius = Math.hypot(location.getY(), location.getZ());
			double halfWidth = BUTTON_PATCH * baseRadius;
			if (baseRadius < EPS || button.getTotalHeight() < EPS || halfWidth > 0.8 * radius
					|| Math.abs(context.getParentTransform().transform(Coordinate.ZERO).getY()) > EPS
					|| Math.abs(context.getParentTransform().transform(Coordinate.ZERO).getZ()) > EPS) {
				warn(warnings, "OuterSurface.warning.buttonNotPlaced", button);
				return;
			}
			footprints.add(new Button(button, location.getX() - halfWidth, location.getX() + halfWidth,
					location.getX(), normalize(Math.atan2(location.getZ(), location.getY())),
					Math.asin(halfWidth / radius), radius, baseRadius));
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

	/**
	 * The outline of a fin in the plane of the fin, with x along the rocket and y above the body surface. Points are
	 * snapped to a nanometer, so that edges two fins share are exactly the same and join without a slit.
	 */
	private static Area outlineArea(Fin fin) {
		Path2D.Double path = new Path2D.Double();
		path.moveTo(snap(fin.xRef + fin.outline[0][0]), snap(fin.outline[0][1]));
		for (int i = 1; i < fin.outline.length; i++) {
			path.lineTo(snap(fin.xRef + fin.outline[i][0]), snap(fin.outline[i][1]));
		}
		path.closePath();
		return new Area(path);
	}

	private static double snap(double value) {
		return Math.rint(value / EPS) * EPS;
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
		// Remove spikes that go out and back along the same line, and points in the middle of a straight edge
		for (int i = 0; loop.size() > 3 && i < loop.size(); ) {
			double[] prev = loop.get((i + loop.size() - 1) % loop.size());
			double[] cur = loop.get(i);
			double[] next = loop.get((i + 1) % loop.size());
			double cross = (cur[0] - prev[0]) * (next[1] - prev[1]) - (cur[1] - prev[1]) * (next[0] - prev[0]);
			double scale = Math.hypot(cur[0] - prev[0], cur[1] - prev[1]) * Math.hypot(next[0] - cur[0], next[1] - cur[1]);
			if (Math.abs(cross) <= 1e-9 * scale && cur[1] != 0) {
				loop.remove(i);
				i = Math.max(0, i - 1);
			} else {
				i++;
			}
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
	 * A body of revolution on the rocket axis from x0 over the given length: the shape of a symmetric component, or a
	 * tube of the given radius.
	 */
	private record Body(RocketComponent component, double x0, double length, SymmetricComponent shape,
						double tubeRadius) {
		double x1() {
			return x0 + length;
		}

		double radius(double x) {
			return shape == null ? tubeRadius : shape.getRadius(Math.max(0, Math.min(length, x - x0)));
		}

		double maxRadius() {
			return shape == null ? tubeRadius : Math.max(shape.getForeRadius(), shape.getAftRadius());
		}
	}

	/**
	 * Something that sits on a cylinder of the body over a rectangle in (x, angle): the body gets a hole there.
	 */
	private interface Footprint {
		RocketComponent component();

		double xStart();

		double xEnd();

		double angle();

		double halfAngle();

		double radius();

		/**
		 * @return whether the hole has no vertices between its sides on its fore and aft ends, as under a fin, whose
		 * end faces meet the body along a straight chord
		 */
		default boolean closedEnds() {
			return true;
		}
	}

	/**
	 * One fin, or fins joined into one. Its root runs from xStart to xEnd on a cylinder of the given radius, centered
	 * on the angle; the outline points are relative to xRef and to the body surface.
	 */
	private record Fin(FinSet finSet, String region, double xStart, double xEnd, double angle, double radius,
					   double halfAngle, double xRef, double[][] outline) implements Footprint {
		@Override
		public RocketComponent component() {
			return finSet;
		}
	}

	/**
	 * One rail button centered at xCenter and the angle, with a patch of the body around it.
	 */
	private record Button(RailButton component, double xStart, double xEnd, double xCenter, double angle,
						  double halfAngle, double radius, double baseRadius) implements Footprint {
		@Override
		public boolean closedEnds() {
			return false;
		}
	}

	/** A point of the body profile; region is the component of the profile segment that starts here */
	private record ProfilePoint(double x, double r, String region) {
	}

	private static final class Builder {
		private final OuterSurfaceMesh mesh;
		private final Detail detail;
		private final List<Body> bodies;
		private final List<Footprint> footprints;
		private final WarningSet warnings;
		/** Longest edge along the body */
		private final double axialSpacing;
		/** Size of the triangles on fins */
		private final double finSpacing;

		private final List<ProfilePoint> profile = new ArrayList<>();
		private double[] angles;
		/** Vertex of each profile point and angle, -1 inside a footprint; a ring on the axis has one vertex */
		private int[][] ringVertices;
		/** Per footprint, the profile points at its ends and the angle indices of its two sides */
		private final List<int[]> holes = new ArrayList<>();
		/** Cells (profile point, angle index) of the body that are covered by a footprint */
		private final Set<Long> holeCells = new HashSet<>();
		/** Ring vertices inside a footprint, which are left out */
		private final Set<Long> hidden = new HashSet<>();

		Builder(OuterSurfaceMesh mesh, Detail detail, List<Body> bodies, List<Footprint> footprints,
				WarningSet warnings) {
			this.mesh = mesh;
			this.detail = detail;
			this.bodies = bodies;
			this.footprints = new ArrayList<>(footprints);
			this.warnings = warnings;
			double maxRadius = 0;
			for (Body body : bodies) {
				maxRadius = Math.max(maxRadius, body.maxRadius());
			}
			finSpacing = 2 * Math.PI * maxRadius / detail.getAngularDivisions();
			axialSpacing = detail.getAxialAspect() * finSpacing;
		}

		void build() {
			buildProfile();
			placeFootprints();
			buildRings();
			addBodyFaces();
			for (int i = 0; i < footprints.size(); i++) {
				if (footprints.get(i) instanceof Fin fin) {
					addFinFaces(fin, holes.get(i));
				} else {
					addButtonFaces((Button) footprints.get(i), holes.get(i));
				}
			}
		}

		private void buildProfile() {
			List<Double> stations = new ArrayList<>();
			for (Body body : bodies) {
				stations.add(body.x0);
				stations.add(body.x1());
				if (body.shape instanceof Transition) {
					// Cosine spacing puts more stations near the ends, where noses curve most
					int divisions = detail.getProfileDivisions();
					for (int i = 1; i < divisions; i++) {
						stations.add(body.x0 + body.length * (1 - Math.cos(Math.PI * i / divisions)) / 2);
					}
				}
			}
			for (Footprint footprint : footprints) {
				stations.add(footprint.xStart());
				stations.add(footprint.xEnd());
				if (footprint instanceof Button button) {
					// Sides of the patch as finely divided as the base of the button, so its triangles stay small
					double spacing = 2 * Math.PI * button.baseRadius / detail.getButtonDivisions();
					int pieces = (int) Math.ceil((button.xEnd - button.xStart) / spacing);
					for (int k = 1; k < pieces; k++) {
						stations.add(button.xStart + (button.xEnd - button.xStart) * k / pieces);
					}
				}
			}
			stations.sort(null);
			stations = refine(stations);

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

		/** Adds stations so that no two neighbors on the body are further apart than the axial spacing */
		private List<Double> refine(List<Double> stations) {
			List<Double> refined = new ArrayList<>();
			for (int i = 0; i < stations.size(); i++) {
				double x = stations.get(i);
				if (i > 0) {
					double previous = stations.get(i - 1);
					int pieces = (int) Math.ceil((x - previous) / axialSpacing - 1e-9);
					if (pieces > 1 && largest((x + previous) / 2, false) != null) {
						for (int k = 1; k < pieces; k++) {
							refined.add(previous + (x - previous) * k / pieces);
						}
					}
				}
				refined.add(x);
			}
			return refined;
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

		/** Chooses the angles around the rocket and the hole of each footprint, leaving out those that do not fit. */
		private void placeFootprints() {
			List<Footprint> placed = new ArrayList<>();
			List<int[]> rootPoints = new ArrayList<>();
			for (Footprint footprint : footprints) {
				int[] root = findRoot(footprint);
				if (root == null) {
					warn(warnings, footprint instanceof Fin ? "OuterSurface.warning.finNotJoined"
							: "OuterSurface.warning.buttonNotPlaced", footprint.component());
					continue;
				}
				placed.add(footprint);
				rootPoints.add(root);
				double[] candidate = computeAngles(placed);
				if (!holesFit(placed, rootPoints, candidate)) {
					placed.remove(placed.size() - 1);
					rootPoints.remove(rootPoints.size() - 1);
					warn(warnings, footprint instanceof Fin ? "OuterSurface.warning.finOverlap"
							: "OuterSurface.warning.buttonNotPlaced", footprint.component());
				}
			}
			footprints.clear();
			footprints.addAll(placed);
			angles = computeAngles(placed);
			holesFit(placed, rootPoints, angles);
		}

		/** The profile points at both ends of a footprint, if it lies wholly on a cylinder of its radius */
		private int[] findRoot(Footprint footprint) {
			int start = -1;
			int end = -1;
			for (int i = 0; i < profile.size(); i++) {
				ProfilePoint p = profile.get(i);
				if (p.x < footprint.xStart() - EPS || p.x > footprint.xEnd() + EPS) {
					continue;
				}
				boolean onCylinder = Math.abs(p.r - footprint.radius()) < 1e-7;
				if (start < 0) {
					if (Math.abs(p.x - footprint.xStart()) < EPS && onCylinder) {
						start = i;
					}
				} else if (!onCylinder) {
					return null;
				} else if (Math.abs(p.x - footprint.xEnd()) < EPS) {
					end = i;
					break;
				}
			}
			return start < 0 || end < 0 ? null : new int[]{start, end};
		}

		private double[] computeAngles(List<Footprint> placed) {
			final int divisions = detail.getAngularDivisions();
			final double step = 2 * Math.PI / divisions;
			List<Double> list = new ArrayList<>();
			for (int i = 0; i < divisions; i++) {
				double a = i * step;
				boolean free = true;
				for (Footprint footprint : placed) {
					// Keep regular lines away from the sides of a hole to avoid slivers, and out of closed ones
					double distance = angleDistance(a, footprint.angle());
					if (Math.abs(distance - footprint.halfAngle()) < step / 4
							|| (footprint.closedEnds() && distance < footprint.halfAngle())) {
						free = false;
						break;
					}
				}
				if (free) {
					list.add(a);
				}
			}
			for (Footprint footprint : placed) {
				list.add(normalize(footprint.angle() - footprint.halfAngle()));
				list.add(normalize(footprint.angle() + footprint.halfAngle()));
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
		 * Fills in the holes of the footprints and the ring vertices they hide, and checks that no footprint covers a
		 * cell or a side vertex of another.
		 */
		private boolean holesFit(List<Footprint> placed, List<int[]> rootPoints, double[] candidate) {
			holes.clear();
			holeCells.clear();
			hidden.clear();
			Set<Long> sides = new HashSet<>();
			for (int i = 0; i < placed.size(); i++) {
				Footprint footprint = placed.get(i);
				int minus = indexOf(candidate, normalize(footprint.angle() - footprint.halfAngle()));
				int plus = indexOf(candidate, normalize(footprint.angle() + footprint.halfAngle()));
				int[] root = rootPoints.get(i);
				for (int p = root[0]; p <= root[1]; p++) {
					sides.add(cell(p, minus));
					sides.add(cell(p, plus));
					// Lines that pass under this footprint
					boolean end = p == root[0] || p == root[1];
					for (int j = (minus + 1) % candidate.length; j != plus && (!end || footprint.closedEnds());
						 j = (j + 1) % candidate.length) {
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
					addPolygon(new int[]{a, c, d}, region);
				} else if (axisAfter) {
					addPolygon(new int[]{a, b, c}, region);
				} else {
					addPolygon(new int[]{a, b, c, d}, region);
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
					addPolygon(new int[]{ringVertex(p, top.get(t)), ringVertex(p, top.get(t + 1)),
							ringVertex(p + 1, bottom.get(b))}, region);
					t++;
				} else {
					addPolygon(new int[]{ringVertex(p, top.get(t)), ringVertex(p + 1, bottom.get(b + 1)),
							ringVertex(p + 1, bottom.get(b))}, region);
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

			// The outline above the root, split into pieces no longer than the fin spacing
			List<double[]> outline = new ArrayList<>();
			for (int i = 0; i < fin.outline.length - 1; i++) {
				double[] a = fin.outline[i];
				double[] b = fin.outline[i + 1];
				int pieces = Math.max(1, (int) Math.ceil(Math.hypot(b[0] - a[0], b[1] - a[1]) / finSpacing - 1e-9));
				for (int k = i == 0 ? 1 : 0; k < pieces; k++) {
					outline.add(new double[]{a[0] + (b[0] - a[0]) * k / pieces, a[1] + (b[1] - a[1]) * k / pieces});
				}
			}
			// The outline now runs from the first point above the leading edge root to the last before the trailing one
			final int tipCount = outline.size();

			// Plus side: along the root, then back along the outline; counter-clockwise in (x, radial)
			final int rootCount = rootEnd - rootStart + 1;
			final int size = rootCount + tipCount;
			double[] u = new double[size];
			double[] v = new double[size];
			final double rootHeight = fin.radius * Math.cos(fin.halfAngle);
			for (int i = 0; i < rootCount; i++) {
				u[i] = profile.get(rootStart + i).x;
				v[i] = rootHeight;
			}
			for (int i = 0; i < tipCount; i++) {
				double[] point = outline.get(tipCount - 1 - i);
				u[rootCount + i] = fin.xRef + point[0];
				v[rootCount + i] = fin.radius + point[1];
			}
			SurfaceTriangulator.Result side;
			try {
				side = SurfaceTriangulator.triangulate(u, v, finSpacing);
			} catch (IllegalStateException e) {
				throw new IllegalStateException("The outline of " + region + " crosses itself", e);
			}

			// The vertices of each side: the root ones are on the body, the others are new
			final int pointCount = side.u().length;
			int[] plus = new int[pointCount];
			int[] minus = new int[pointCount];
			for (int i = 0; i < pointCount; i++) {
				if (i < rootCount) {
					plus[i] = ringVertex(rootStart + i, plusIndex);
					minus[i] = ringVertex(rootStart + i, minusIndex);
				} else {
					plus[i] = mesh.vertices.size();
					mesh.vertices.add(finVertex(fin, side.u()[i], side.v()[i], halfThickness));
					minus[i] = mesh.vertices.size();
					mesh.vertices.add(finVertex(fin, side.u()[i], side.v()[i], -halfThickness));
				}
			}
			int[] t = side.triangles();
			int[] plusTriangles = new int[t.length];
			int[] minusTriangles = new int[t.length];
			for (int i = 0; i < t.length; i += 3) {
				plusTriangles[i] = plus[t[i]];
				plusTriangles[i + 1] = plus[t[i + 1]];
				plusTriangles[i + 2] = plus[t[i + 2]];
				// The minus side is seen from the other side
				minusTriangles[i] = minus[t[i]];
				minusTriangles[i + 1] = minus[t[i + 2]];
				minusTriangles[i + 2] = minus[t[i + 1]];
			}
			int[] plusLoop = Arrays.copyOf(plus, size);
			int[] minusLoop = new int[size];
			for (int i = 0; i < size; i++) {
				minusLoop[i] = minus[size - 1 - i];
			}
			mesh.faces.add(new Face(plusLoop, plusTriangles, region));
			mesh.faces.add(new Face(minusLoop, minusTriangles, region));

			// Edges: from the root leading edge over the outline to the root trailing edge
			int[] plusEdge = new int[tipCount + 2];
			int[] minusEdge = new int[tipCount + 2];
			plusEdge[0] = plus[0];
			minusEdge[0] = minus[0];
			for (int i = 0; i < tipCount; i++) {
				plusEdge[i + 1] = plus[size - 1 - i];
				minusEdge[i + 1] = minus[size - 1 - i];
			}
			plusEdge[tipCount + 1] = plus[rootCount - 1];
			minusEdge[tipCount + 1] = minus[rootCount - 1];
			for (int i = 0; i < tipCount + 1; i++) {
				addPolygon(new int[]{plusEdge[i], plusEdge[i + 1], minusEdge[i + 1], minusEdge[i]}, region);
			}
		}

		/** A point of the fin's plane (x, radial) on the side at the given offset from the fin's middle plane */
		private static double[] finVertex(Fin fin, double x, double radial, double offset) {
			double cos = Math.cos(fin.angle);
			double sin = Math.sin(fin.angle);
			return new double[]{x, radial * cos - offset * sin, radial * sin + offset * cos};
		}

		/**
		 * Adds a rail button: a solid of revolution about the radial line through its center, standing on the body,
		 * and the patch of body between the rectangular hole and the button's base.
		 */
		private void addButtonFaces(Button button, int[] hole) {
			final int start = hole[0];
			final int end = hole[1];
			final int minusIndex = hole[2];
			final int plusIndex = hole[3];
			final String region = button.component.getName();
			final double radius = button.radius;
			final double cos = Math.cos(button.angle);
			final double sin = Math.sin(button.angle);
			final int divisions = detail.getButtonDivisions();

			// The boundary of the hole: along its fore end, its plus side, its aft end and its minus side
			List<Integer> outer = new ArrayList<>();
			for (int j = minusIndex; j != plusIndex; j = (j + 1) % angles.length) {
				outer.add(ringVertex(start, j));
			}
			for (int p = start; p < end; p++) {
				outer.add(ringVertex(p, plusIndex));
			}
			for (int j = plusIndex; j != minusIndex; j = (j - 1 + angles.length) % angles.length) {
				outer.add(ringVertex(end, j));
			}
			for (int p = end; p > start; p--) {
				outer.add(ringVertex(p, minusIndex));
			}

			// The rings of the button from its base on the body to its top; each is (radius, height above the axis)
			RailButton rb = button.component;
			final double outerRadius = rb.getOuterDiameter() / 2;
			final double innerRadius = Math.min(rb.getInnerDiameter(), rb.getOuterDiameter()) / 2;
			List<double[]> rings = new ArrayList<>();
			double height = radius;
			if (rb.getBaseHeight() > EPS) {
				height += rb.getBaseHeight();
				rings.add(new double[]{outerRadius, height});
				if (innerRadius < outerRadius - EPS) {
					rings.add(new double[]{innerRadius, height});
				}
			}
			if (rb.getInnerHeight() > EPS) {
				height += rb.getInnerHeight();
				rings.add(new double[]{innerRadius, height});
			}
			if (rb.getFlangeHeight() > EPS) {
				if (Math.abs(rings.isEmpty() ? button.baseRadius - outerRadius
						: rings.get(rings.size() - 1)[0] - outerRadius) > EPS) {
					rings.add(new double[]{outerRadius, height});
				}
				height += rb.getFlangeHeight();
				rings.add(new double[]{outerRadius, height});
			}
			if (rings.isEmpty()) {
				height = radius + rb.getTotalHeight();
				rings.add(new double[]{button.baseRadius, height});
			}

			// Around the button axis, so that the rings go counter-clockwise seen from outside
			int[][] ringIndices = new int[rings.size() + 1][divisions];
			double[] footU = new double[divisions];
			double[] footV = new double[divisions];
			for (int k = 0; k < divisions; k++) {
				double alpha = 2 * Math.PI * k / divisions;
				double tangential = button.baseRadius * Math.cos(alpha);
				double axial = button.baseRadius * Math.sin(alpha);
				double up = Math.sqrt(radius * radius - tangential * tangential);
				ringIndices[0][k] = mesh.vertices.size();
				mesh.vertices.add(buttonVertex(button.xCenter + axial, up, tangential, cos, sin));
				footU[k] = axial;
				footV[k] = radius * Math.asin(tangential / radius);
				for (int r = 0; r < rings.size(); r++) {
					double[] ring = rings.get(r);
					ringIndices[r + 1][k] = mesh.vertices.size();
					mesh.vertices.add(buttonVertex(button.xCenter + ring[0] * Math.sin(alpha), ring[1],
							ring[0] * Math.cos(alpha), cos, sin));
				}
			}
			for (int r = 0; r < rings.size(); r++) {
				for (int k = 0; k < divisions; k++) {
					int next = (k + 1) % divisions;
					addPolygon(new int[]{ringIndices[r][k], ringIndices[r][next], ringIndices[r + 1][next],
							ringIndices[r + 1][k]}, region);
				}
			}
			int[] top = ringIndices[rings.size()];
			int[] fan = new int[3 * (divisions - 2)];
			for (int k = 1; k < divisions - 1; k++) {
				fan[3 * (k - 1)] = top[0];
				fan[3 * (k - 1) + 1] = top[k];
				fan[3 * (k - 1) + 2] = top[k + 1];
			}
			mesh.faces.add(new Face(top.clone(), fan, region));

			// The patch: zip the hole boundary and the base together by their angle around the button center
			double[] outerAngle = new double[outer.size()];
			for (int i = 0; i < outer.size(); i++) {
				double[] p = mesh.vertices.get(outer.get(i));
				double u = p[0] - button.xCenter;
				double angle = Math.atan2(p[2], p[1]) - button.angle;
				angle = Math.atan2(Math.sin(angle), Math.cos(angle));
				outerAngle[i] = Math.atan2(radius * angle, u);
			}
			double[] footAngle = new double[divisions];
			for (int k = 0; k < divisions; k++) {
				footAngle[k] = Math.atan2(footV[k], footU[k]);
			}
			zip(outer, outerAngle, ringIndices[0], footAngle, region, button);
		}

		/** A point at the given axial position, height along the button axis and offset across it */
		private static double[] buttonVertex(double x, double up, double across, double cos, double sin) {
			return new double[]{x, up * cos - across * sin, up * sin + across * cos};
		}

		/**
		 * Joins two closed loops around a common center with triangles, sweeping around the center from the first
		 * outer vertex and advancing along whichever loop comes next. Each triangle is oriented to face away from the
		 * rocket axis.
		 */
		private void zip(List<Integer> outer, double[] outerAngle, int[] inner, double[] innerAngle, String region,
						 Button button) {
			// Angles from the first outer vertex, so both loops are swept from the same place
			final double start = outerAngle[byAngle(outerAngle)[0]];
			double[] outerSweep = new double[outerAngle.length];
			for (int i = 0; i < outerAngle.length; i++) {
				outerSweep[i] = sweep(outerAngle[i], start);
			}
			double[] innerSweep = new double[innerAngle.length];
			for (int k = 0; k < innerAngle.length; k++) {
				innerSweep[k] = sweep(innerAngle[k], start);
			}
			int[] outerOrder = byAngle(outerSweep);
			int[] innerOrder = byAngle(innerSweep);
			int n = outerOrder.length;
			int m = innerOrder.length;
			int i = 0;
			int k = 0;
			while (i < n || k < m) {
				int o0 = outer.get(outerOrder[i % n]);
				int i0 = inner[innerOrder[k % m]];
				double nextOuter = i < n ? outerSweep[outerOrder[(i + 1) % n]] + (i + 1 >= n ? 2 * Math.PI : 0)
						: Double.MAX_VALUE;
				double nextInner = k < m ? innerSweep[innerOrder[(k + 1) % m]] + (k + 1 >= m ? 2 * Math.PI : 0)
						: Double.MAX_VALUE;
				if (nextOuter <= nextInner) {
					addOutward(o0, outer.get(outerOrder[(i + 1) % n]), i0, region, button);
					i++;
				} else {
					addOutward(o0, inner[innerOrder[(k + 1) % m]], i0, region, button);
					k++;
				}
			}
		}

		/** The angle measured counter-clockwise from the start, from 0 up to a full turn */
		private static double sweep(double angle, double start) {
			double a = (angle - start) % (2 * Math.PI);
			return a < 0 ? a + 2 * Math.PI : a;
		}

		private static int[] byAngle(double[] angles) {
			Integer[] order = new Integer[angles.length];
			for (int i = 0; i < order.length; i++) {
				order[i] = i;
			}
			Arrays.sort(order, (a, b) -> Double.compare(angles[a], angles[b]));
			int[] result = new int[order.length];
			for (int i = 0; i < order.length; i++) {
				result[i] = order[i];
			}
			return result;
		}

		/** Adds a triangle of a button patch, facing away from the rocket axis */
		private void addOutward(int a, int b, int c, String region, Button button) {
			double[] pa = mesh.vertices.get(a);
			double[] pb = mesh.vertices.get(b);
			double[] pc = mesh.vertices.get(c);
			double nx = (pb[1] - pa[1]) * (pc[2] - pa[2]) - (pb[2] - pa[2]) * (pc[1] - pa[1]);
			double ny = (pb[2] - pa[2]) * (pc[0] - pa[0]) - (pb[0] - pa[0]) * (pc[2] - pa[2]);
			double nz = (pb[0] - pa[0]) * (pc[1] - pa[1]) - (pb[1] - pa[1]) * (pc[0] - pa[0]);
			double outward = ny * Math.cos(button.angle) + nz * Math.sin(button.angle);
			addPolygon(outward >= 0 ? new int[]{a, b, c} : new int[]{a, c, b}, region);
		}

		/** Adds a convex planar polygon, split into triangles from its first vertex */
		private void addPolygon(int[] loop, String region) {
			int[] triangles = new int[3 * (loop.length - 2)];
			for (int i = 1; i < loop.length - 1; i++) {
				triangles[3 * (i - 1)] = loop[0];
				triangles[3 * (i - 1) + 1] = loop[i];
				triangles[3 * (i - 1) + 2] = loop[i + 1];
			}
			mesh.faces.add(new Face(loop, triangles, region));
		}
	}
}
