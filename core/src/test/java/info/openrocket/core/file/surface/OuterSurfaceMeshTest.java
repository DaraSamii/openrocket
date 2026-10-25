package info.openrocket.core.file.surface;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import info.openrocket.core.logging.WarningSet;
import info.openrocket.core.rocketcomponent.AxialStage;
import info.openrocket.core.rocketcomponent.BodyTube;
import info.openrocket.core.rocketcomponent.FreeformFinSet;
import info.openrocket.core.rocketcomponent.NoseCone;
import info.openrocket.core.rocketcomponent.Rocket;
import info.openrocket.core.rocketcomponent.Transition;
import info.openrocket.core.rocketcomponent.TrapezoidFinSet;
import info.openrocket.core.rocketcomponent.position.AxialMethod;
import info.openrocket.core.util.BaseTestCase;
import info.openrocket.core.util.Coordinate;
import info.openrocket.core.util.TestRockets;

public class OuterSurfaceMeshTest extends BaseTestCase {

	/** Area factor of the polygon that stands for a circle */
	private static final double POLYGON = OuterSurfaceMesh.ANGULAR_DIVISIONS
			* Math.sin(2 * Math.PI / OuterSurfaceMesh.ANGULAR_DIVISIONS) / (2 * Math.PI);

	@Test
	public void cylinderIsClosedWithItsVolume() {
		Rocket rocket = bodyOnly();
		OuterSurfaceMesh mesh = OuterSurfaceMesh.build(rocket.getSelectedConfiguration(), new WarningSet());

		assertClosed(mesh);
		assertEquals(Math.PI * 0.02 * 0.02 * 0.3 * POLYGON, volume(mesh), 1e-12);
	}

	@Test
	public void noseConeAndTubeAreClosedWithTheirVolume() {
		Rocket rocket = bodyOnly();
		AxialStage stage = rocket.getStage(0);
		NoseCone nose = new NoseCone(Transition.Shape.CONICAL, 0.1, 0.02);
		stage.addChild(nose, 0);

		OuterSurfaceMesh mesh = OuterSurfaceMesh.build(rocket.getSelectedConfiguration(), new WarningSet());

		assertClosed(mesh);
		double cone = Math.PI * 0.02 * 0.02 * 0.1 / 3;
		double tube = Math.PI * 0.02 * 0.02 * 0.3;
		assertEquals((cone + tube) * POLYGON, volume(mesh), 1e-10);
	}

	@Test
	public void finsAreJoinedToTheBody() {
		Rocket rocket = bodyOnly();
		BodyTube tube = (BodyTube) rocket.getStage(0).getChild(0);
		double bodyVolume = volume(OuterSurfaceMesh.build(rocket.getSelectedConfiguration(), new WarningSet()));
		TrapezoidFinSet fins = new TrapezoidFinSet(3, 0.08, 0.04, 0.03, 0.05);
		fins.setThickness(0.003);
		tube.addChild(fins);

		WarningSet warnings = new WarningSet();
		OuterSurfaceMesh mesh = OuterSurfaceMesh.build(rocket.getSelectedConfiguration(), warnings);

		assertClosed(mesh);
		assertTrue(warnings.isEmpty(), warnings.toString());
		// Each fin adds its outline times its thickness, and the strip between the body facet under it and the tube
		double finArea = (0.08 + 0.04) / 2 * 0.05;
		double strip = 0.08 * 0.02 * (1 - Math.cos(Math.asin(0.0015 / 0.02)));
		double expected = bodyVolume + 3 * (finArea + strip) * 0.003;
		assertEquals(expected, volume(mesh), 1e-7);
	}

	@Test
	public void finsOnTheSeamAndOfTwoSetsFit() {
		Rocket rocket = bodyOnly();
		BodyTube tube = (BodyTube) rocket.getStage(0).getChild(0);
		TrapezoidFinSet front = new TrapezoidFinSet(4, 0.05, 0.03, 0.01, 0.03);
		front.setName("fins front");
		front.setBaseRotation(0);
		front.setAxialMethod(AxialMethod.TOP);
		front.setAxialOffset(0.02);
		tube.addChild(front);
		TrapezoidFinSet back = new TrapezoidFinSet(3, 0.06, 0.03, 0.02, 0.04);
		back.setName("fins back");
		back.setBaseRotation(Math.PI / 7);
		tube.addChild(back);

		WarningSet warnings = new WarningSet();
		OuterSurfaceMesh mesh = OuterSurfaceMesh.build(rocket.getSelectedConfiguration(), warnings);

		assertClosed(mesh);
		assertTrue(warnings.isEmpty(), warnings.toString());
		assertEquals(4 + 3, countRegions(mesh, "fins"), "every fin has faces");
	}

	@Test
	public void concaveFreeformFinIsClosed() throws Exception {
		Rocket rocket = bodyOnly();
		BodyTube tube = (BodyTube) rocket.getStage(0).getChild(0);
		FreeformFinSet fins = new FreeformFinSet();
		fins.setPoints(new Coordinate[]{
				new Coordinate(0, 0), new Coordinate(0.02, 0.05), new Coordinate(0.03, 0.02),
				new Coordinate(0.05, 0.06), new Coordinate(0.07, 0)});
		fins.setFinCount(4);
		tube.addChild(fins);

		WarningSet warnings = new WarningSet();
		OuterSurfaceMesh mesh = OuterSurfaceMesh.build(rocket.getSelectedConfiguration(), warnings);

		assertClosed(mesh);
		assertTrue(warnings.isEmpty(), warnings.toString());
	}

	@Test
	public void exampleRocketsAreClosed() {
		for (Rocket rocket : List.of(TestRockets.makeEstesAlphaIII(), TestRockets.makeBeta(),
				TestRockets.makeSimple2Stage(), TestRockets.makeBigBlue(), TestRockets.makeEstesAlphaIIIWithPods())) {
			OuterSurfaceMesh mesh = OuterSurfaceMesh.build(rocket.getSelectedConfiguration(), new WarningSet());
			assertClosed(mesh);
			assertTrue(volume(mesh) > 0, rocket.getName());
		}
	}

	@Test
	public void partsThatAreLeftOutAreReported() {
		WarningSet warnings = new WarningSet();
		OuterSurfaceMesh.build(TestRockets.makeEstesAlphaIIIWithPods().getSelectedConfiguration(), warnings);
		assertFalse(warnings.isEmpty());
	}

	@Test
	public void finBeyondTheBodyIsReported() {
		Rocket rocket = bodyOnly();
		BodyTube tube = (BodyTube) rocket.getStage(0).getChild(0);
		TrapezoidFinSet fins = new TrapezoidFinSet(3, 0.08, 0.04, 0.03, 0.05);
		fins.setAxialMethod(AxialMethod.BOTTOM);
		fins.setAxialOffset(0.04);
		tube.addChild(fins);

		WarningSet warnings = new WarningSet();
		OuterSurfaceMesh mesh = OuterSurfaceMesh.build(rocket.getSelectedConfiguration(), warnings);

		assertClosed(mesh);
		assertEquals(1, warnings.size());
	}

	@Test
	public void finsOfTwoStagesThatMeetAreJoined() {
		Rocket rocket = bodyOnly();
		AxialStage booster = new AxialStage();
		rocket.addChild(booster);
		BodyTube boosterTube = new BodyTube(0.2, 0.02);
		booster.addChild(boosterTube);
		// Upper fins end at the stage joint with a vertical trailing edge, the lower ones start there
		TrapezoidFinSet upper = new TrapezoidFinSet(3, 0.08, 0.05, 0.03, 0.04);
		upper.setAxialMethod(AxialMethod.BOTTOM);
		upper.setAxialOffset(0);
		((BodyTube) rocket.getStage(0).getChild(0)).addChild(upper);
		TrapezoidFinSet lower = new TrapezoidFinSet(3, 0.1, 0.06, 0, 0.06);
		lower.setAxialMethod(AxialMethod.TOP);
		lower.setAxialOffset(0);
		boosterTube.addChild(lower);

		WarningSet warnings = new WarningSet();
		OuterSurfaceMesh mesh = OuterSurfaceMesh.build(rocket.getSelectedConfiguration(), warnings);

		assertClosed(mesh);
		assertTrue(warnings.isEmpty(), warnings.toString());
		assertEquals(3, countRegions(mesh, upper.getName() + " " + lower.getName()));
	}

	@Test
	public void finsWithTheSameSweptEdgeAreJoined() {
		Rocket rocket = bodyOnly();
		BodyTube tube = (BodyTube) rocket.getStage(0).getChild(0);
		// The trailing edge of the first fin, swept back past the root, is the leading edge of the second
		TrapezoidFinSet first = new TrapezoidFinSet(3, 0.05, 0.05, 0.025, 0.03);
		first.setName("first");
		first.setAxialMethod(AxialMethod.TOP);
		first.setAxialOffset(0.1);
		tube.addChild(first);
		TrapezoidFinSet second = new TrapezoidFinSet(3, 0.08, 0.03, 0.025, 0.03);
		second.setName("second");
		second.setAxialMethod(AxialMethod.TOP);
		second.setAxialOffset(0.15);
		tube.addChild(second);

		WarningSet warnings = new WarningSet();
		OuterSurfaceMesh mesh = OuterSurfaceMesh.build(rocket.getSelectedConfiguration(), warnings);

		assertClosed(mesh);
		assertTrue(warnings.isEmpty(), warnings.toString());
		assertEquals(3, countRegions(mesh, "first second"));
	}

	@Test
	public void cantedFinsAreExportedWithoutCant() {
		Rocket rocket = bodyOnly();
		BodyTube tube = (BodyTube) rocket.getStage(0).getChild(0);
		TrapezoidFinSet fins = new TrapezoidFinSet(3, 0.08, 0.04, 0.03, 0.05);
		fins.setBaseRotation(0.3);
		tube.addChild(fins);
		double straight = volume(OuterSurfaceMesh.build(rocket.getSelectedConfiguration(), new WarningSet()));
		fins.setCantAngle(Math.toRadians(4));

		WarningSet warnings = new WarningSet();
		OuterSurfaceMesh mesh = OuterSurfaceMesh.build(rocket.getSelectedConfiguration(), warnings);

		assertClosed(mesh);
		assertEquals(1, warnings.size());
		assertEquals(straight, volume(mesh), 1e-12);
	}

	@Test
	public void intersectingFinsAreReported() {
		Rocket rocket = bodyOnly();
		BodyTube tube = (BodyTube) rocket.getStage(0).getChild(0);
		TrapezoidFinSet first = new TrapezoidFinSet(3, 0.08, 0.04, 0.03, 0.05);
		tube.addChild(first);
		TrapezoidFinSet second = new TrapezoidFinSet(3, 0.08, 0.04, 0.03, 0.05);
		second.setBaseRotation(0.05);
		tube.addChild(second);

		WarningSet warnings = new WarningSet();
		OuterSurfaceMesh mesh = OuterSurfaceMesh.build(rocket.getSelectedConfiguration(), warnings);

		assertClosed(mesh);
		assertEquals(1, warnings.size());
	}

	@Test
	public void concavePolygonIsSplitIntoTriangles() {
		// An L shape with collinear points along the bottom
		double[] u = {0, 1, 2, 3, 3, 1, 1, 0};
		double[] v = {0, 0, 0, 0, 1, 1, 3, 3};
		int[] t = OuterSurfaceMesh.triangulate(u, v);

		assertEquals(3 * (u.length - 2), t.length);
		double area = 0;
		for (int i = 0; i < t.length; i += 3) {
			double a = ((u[t[i + 1]] - u[t[i]]) * (v[t[i + 2]] - v[t[i]])
					- (v[t[i + 1]] - v[t[i]]) * (u[t[i + 2]] - u[t[i]])) / 2;
			assertTrue(a > 0, "counter-clockwise and not degenerate");
			area += a;
		}
		assertEquals(3 + 2, area, 1e-12);
	}

	/** A 40 mm wide, 300 mm long body tube */
	private static Rocket bodyOnly() {
		Rocket rocket = new Rocket();
		AxialStage stage = new AxialStage();
		rocket.addChild(stage);
		stage.addChild(new BodyTube(0.3, 0.02));
		rocket.enableEvents();
		return rocket;
	}

	/**
	 * Checks that every edge is used once in each direction, i.e. the surface is closed and its faces are oriented
	 * the same way, that no triangle is degenerate, and that no two vertices are at the same place, which tools
	 * that join vertices by position would see as faces touching.
	 */
	private static void assertClosed(OuterSurfaceMesh mesh) {
		Set<List<Long>> positions = new HashSet<>();
		for (double[] p : mesh.getVertices()) {
			assertTrue(positions.add(List.of(Math.round(p[0] * 1e9), Math.round(p[1] * 1e9), Math.round(p[2] * 1e9))),
					"two vertices at " + Arrays.toString(p));
		}
		Map<Long, Integer> edges = new HashMap<>();
		for (OuterSurfaceMesh.Face face : mesh.getFaces()) {
			int[] t = face.triangles();
			for (int i = 0; i < t.length; i += 3) {
				int[] v = {face.loop()[t[i]], face.loop()[t[i + 1]], face.loop()[t[i + 2]]};
				assertTrue(area(mesh, v) > 1e-14, "degenerate triangle in " + face.region());
				for (int k = 0; k < 3; k++) {
					long edge = ((long) v[k] << 32) | v[(k + 1) % 3];
					assertEquals(null, edges.put(edge, 1), "edge used twice in one direction in " + face.region());
				}
			}
		}
		for (long edge : edges.keySet()) {
			long reverse = (edge << 32) | (edge >>> 32);
			assertTrue(edges.containsKey(reverse), "open edge");
		}
	}

	private static double area(OuterSurfaceMesh mesh, int[] v) {
		double[] a = mesh.getVertices().get(v[0]);
		double[] b = mesh.getVertices().get(v[1]);
		double[] c = mesh.getVertices().get(v[2]);
		double[] ab = {b[0] - a[0], b[1] - a[1], b[2] - a[2]};
		double[] ac = {c[0] - a[0], c[1] - a[1], c[2] - a[2]};
		double x = ab[1] * ac[2] - ab[2] * ac[1];
		double y = ab[2] * ac[0] - ab[0] * ac[2];
		double z = ab[0] * ac[1] - ab[1] * ac[0];
		return Math.sqrt(x * x + y * y + z * z) / 2;
	}

	/** Enclosed volume by the divergence theorem; positive when the faces point outwards */
	private static double volume(OuterSurfaceMesh mesh) {
		double volume = 0;
		for (OuterSurfaceMesh.Face face : mesh.getFaces()) {
			int[] t = face.triangles();
			for (int i = 0; i < t.length; i += 3) {
				double[] a = mesh.getVertices().get(face.loop()[t[i]]);
				double[] b = mesh.getVertices().get(face.loop()[t[i + 1]]);
				double[] c = mesh.getVertices().get(face.loop()[t[i + 2]]);
				volume += (a[0] * (b[1] * c[2] - b[2] * c[1]) - a[1] * (b[0] * c[2] - b[2] * c[0])
						+ a[2] * (b[0] * c[1] - b[1] * c[0])) / 6;
			}
		}
		return volume;
	}

	/** Number of separate fins: groups of faces of the region connected by shared vertices */
	private static int countRegions(OuterSurfaceMesh mesh, String region) {
		Map<Integer, Integer> parent = new HashMap<>();
		for (OuterSurfaceMesh.Face face : mesh.getFaces()) {
			if (!face.region().startsWith(region)) {
				continue;
			}
			for (int v : face.loop()) {
				union(parent, face.loop()[0], v);
			}
		}
		Set<Integer> roots = new HashSet<>();
		for (int v : parent.keySet()) {
			roots.add(find(parent, v));
		}
		return roots.size();
	}

	private static int find(Map<Integer, Integer> parent, int v) {
		parent.putIfAbsent(v, v);
		while (parent.get(v) != v) {
			v = parent.get(v);
		}
		return v;
	}

	private static void union(Map<Integer, Integer> parent, int a, int b) {
		parent.put(find(parent, a), find(parent, b));
	}
}
