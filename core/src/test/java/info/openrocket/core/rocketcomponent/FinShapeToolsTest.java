package info.openrocket.core.rocketcomponent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import info.openrocket.core.rocketcomponent.FinShapeTools.FinShapeException;
import info.openrocket.core.rocketcomponent.FinShapeTools.Problem;
import info.openrocket.core.util.BaseTestCase;
import info.openrocket.core.util.Coordinate;
import info.openrocket.core.util.CoordinateIF;

public class FinShapeToolsTest extends BaseTestCase {
	private static final double EPSILON = 1e-9;

	/** A square fin, 100 mm long and high: up the leading edge, along the tip, down the trailing edge */
	private static List<CoordinateIF> square() {
		return List.of(new Coordinate(0, 0), new Coordinate(0, 0.1), new Coordinate(0.1, 0.1), new Coordinate(0.1, 0));
	}

	private static void assertPoint(double x, double y, CoordinateIF point) {
		assertEquals(x, point.getX(), EPSILON, "x of " + point);
		assertEquals(y, point.getY(), EPSILON, "y of " + point);
	}

	/**
	 * A 20 mm fillet on the 90 degree corner at the top of the leading edge touches both edges 20 mm from the corner,
	 * and all its points are 20 mm from its center at (20 mm, 80 mm).
	 */
	@Test
	public void testRoundingACornerMakesAnArcTangentToBothEdges() throws FinShapeException {
		List<CoordinateIF> result = FinShapeTools.roundCorner(square(), 1, 0.02, 8);

		assertEquals(4 + 8, result.size());
		assertPoint(0, 0.08, result.get(1));
		assertPoint(0.02, 0.1, result.get(9));
		for (int i = 1; i <= 9; i++) {
			assertEquals(0.02, Math.hypot(result.get(i).getX() - 0.02, result.get(i).getY() - 0.08), EPSILON);
		}
		assertPoint(0.1, 0.1, result.get(10));
	}

	@Test
	public void testTheFilletRadiusIsLimitedByTheEdges() throws FinShapeException {
		// tan(45 degrees) times the shorter edge
		assertEquals(0.1, FinShapeTools.maxCornerRadius(square(), 1), EPSILON);
		FinShapeException tooLarge = assertThrows(FinShapeException.class,
				() -> FinShapeTools.roundCorner(square(), 1, 0.15, 8));
		assertEquals(Problem.TOO_LARGE, tooLarge.getProblem());
		assertEquals(0.1, tooLarge.getLimit(), EPSILON);

		// The full radius uses up both edges; the corner points of the edges are not doubled
		List<CoordinateIF> full = FinShapeTools.roundCorner(square(), 1, 0.1, 4);
		assertEquals(4 + 4 - 2, full.size());
	}

	@Test
	public void testChamferingACornerCutsItAtTheDistanceOnBothEdges() throws FinShapeException {
		List<CoordinateIF> result = FinShapeTools.chamferCorner(square(), 1, 0.03);

		assertEquals(0.1, FinShapeTools.maxChamferDistance(square(), 1), EPSILON);
		assertEquals(5, result.size());
		assertPoint(0, 0.07, result.get(1));
		assertPoint(0.03, 0.1, result.get(2));
	}

	@Test
	public void testCornerToolsNeedACornerAndAPositiveSize() {
		assertEquals(Problem.NOT_A_CORNER, assertThrows(FinShapeException.class,
				() -> FinShapeTools.roundCorner(square(), 0, 0.01, 8)).getProblem());
		assertEquals(Problem.NOT_A_CORNER, assertThrows(FinShapeException.class,
				() -> FinShapeTools.chamferCorner(square(), 3, 0.01)).getProblem());
		assertEquals(Problem.NOT_POSITIVE, assertThrows(FinShapeException.class,
				() -> FinShapeTools.chamferCorner(square(), 1, 0)).getProblem());
		List<CoordinateIF> inLine = List.of(new Coordinate(0, 0), new Coordinate(0, 0.05), new Coordinate(0, 0.1),
				new Coordinate(0.1, 0));
		assertEquals(Problem.STRAIGHT_CORNER, assertThrows(FinShapeException.class,
				() -> FinShapeTools.roundCorner(inLine, 1, 0.01, 8)).getProblem());
	}

	/**
	 * An arc on the 100 mm tip edge with its middle 20 mm out has the radius (0.05^2 + 0.02^2) / (2 * 0.02) = 72.5 mm
	 * and bulges away from the fin (upwards); a negative bulge curves into the fin.
	 */
	@Test
	public void testArcEdgeBulgesOutwardsWithTheRadiusOfItsHeight() throws FinShapeException {
		List<CoordinateIF> outwards = FinShapeTools.arcEdge(square(), 2, 0.02, 16);

		assertEquals(4 + 15, outwards.size());
		assertPoint(0.05, 0.12, outwards.get(1 + 8));
		double radius = (0.05 * 0.05 + 0.02 * 0.02) / (2 * 0.02);
		for (CoordinateIF point : outwards.subList(1, outwards.size() - 1)) {
			assertEquals(radius, Math.hypot(point.getX() - 0.05, point.getY() - (0.12 - radius)), EPSILON);
		}

		List<CoordinateIF> inwards = FinShapeTools.arcEdge(square(), 2, -0.02, 16);
		assertPoint(0.05, 0.08, inwards.get(1 + 8));

		assertEquals(0.05, FinShapeTools.maxArcBulge(square(), 2), EPSILON);
		assertEquals(Problem.TOO_LARGE, assertThrows(FinShapeException.class,
				() -> FinShapeTools.arcEdge(square(), 2, 0.06, 16)).getProblem());
	}

	@Test
	public void testBezierEdgesFollowTheirControlPoints() throws FinShapeException {
		// A quadratic curve is at a quarter of each end and half of the control point in its middle
		List<CoordinateIF> quadratic = FinShapeTools.bezierEdge(square(), 2, new double[]{0.05, 0.2}, null, 2);
		assertEquals(5, quadratic.size());
		assertPoint(0.05, 0.15, quadratic.get(2));

		// A cubic curve with symmetric control points has its middle at 3/4 of their height
		List<CoordinateIF> cubic = FinShapeTools.bezierEdge(square(), 2, new double[]{0.03, 0.14},
				new double[]{0.07, 0.14}, 2);
		assertPoint(0.05, 0.1 + 0.75 * 0.04, cubic.get(2));

		// The default control points are at a third and two thirds of the edge, a quarter of its length outwards
		double[][] controls = FinShapeTools.defaultControlPoints(square(), 2);
		assertEquals(0.1 / 3, controls[0][0], EPSILON);
		assertEquals(0.125, controls[0][1], EPSILON);
		assertEquals(0.2 / 3, controls[1][0], EPSILON);
		assertEquals(0.125, controls[1][1], EPSILON);
	}

	/**
	 * A quarter ellipse on a slanted leading edge from (0, 0) to (50 mm, 100 mm) has its center at (50 mm, 0) when it
	 * bulges outwards, like an elliptical fin, and at (0, 100 mm) when it curves inwards.
	 */
	@Test
	public void testQuarterEllipseEdge() throws FinShapeException {
		List<CoordinateIF> fin = List.of(new Coordinate(0, 0), new Coordinate(0.05, 0.1), new Coordinate(0.1, 0.1),
				new Coordinate(0.1, 0));

		List<CoordinateIF> convex = FinShapeTools.quarterEllipseEdge(fin, 1, true, 8);
		for (CoordinateIF point : convex.subList(0, 9)) {
			double u = (point.getX() - 0.05) / 0.05;
			double v = point.getY() / 0.1;
			assertEquals(1, u * u + v * v, 1e-9, "on the ellipse: " + point);
		}
		// Halfway round, the outward curve is above and in front of the straight edge
		CoordinateIF halfway = convex.get(4);
		assertTrue(halfway.getY() > 2 * halfway.getX(), halfway.toString());

		List<CoordinateIF> concave = FinShapeTools.quarterEllipseEdge(fin, 1, false, 8);
		CoordinateIF inwards = concave.get(4);
		assertTrue(inwards.getY() < 2 * inwards.getX(), inwards.toString());

		assertEquals(Problem.NEEDS_SLANTED_EDGE, assertThrows(FinShapeException.class,
				() -> FinShapeTools.quarterEllipseEdge(square(), 2, true, 8)).getProblem());
	}

	@Test
	public void testSmoothingPassesThroughEveryPoint() throws FinShapeException {
		List<CoordinateIF> smooth = FinShapeTools.smoothOutline(square(), 6);

		assertEquals(1 + 3 * 6, smooth.size());
		for (int i = 0; i < square().size(); i++) {
			assertPoint(square().get(i).getX(), square().get(i).getY(), smooth.get(i * 6));
		}
	}

	@Test
	public void testCurvesMayNotGoIntoTheBody() {
		// A control point below the root pulls the curve of the trailing edge into the body
		assertEquals(Problem.ENTERS_BODY, assertThrows(FinShapeException.class,
				() -> FinShapeTools.bezierEdge(square(), 3, new double[]{0.15, -0.08}, null, 8)).getProblem());
	}

	/** The result is a valid free-form fin outline that the fin set takes as it is */
	@Test
	public void testResultsAreAcceptedByAFreeformFinSet() throws FinShapeException {
		Rocket rocket = new Rocket();
		AxialStage stage = new AxialStage();
		rocket.addChild(stage);
		BodyTube body = new BodyTube(0.3, 0.025);
		stage.addChild(body);
		FreeformFinSet fins = new FreeformFinSet();
		body.addChild(fins);
		fins.setPoints(square().toArray(new CoordinateIF[0]));

		List<CoordinateIF> shape = FinShapeTools.roundCorner(square(), 2, 0.03, 8);
		shape = FinShapeTools.chamferCorner(shape, 1, 0.01);
		shape = FinShapeTools.arcEdge(shape, 1, -0.01, 8);
		fins.setPoints(shape.toArray(new CoordinateIF[0]));

		assertEquals(shape.size(), fins.getPointCount());
	}
}
