package info.openrocket.swing.gui.figureelements;

import info.openrocket.core.rocketcomponent.AxialStage;
import info.openrocket.core.rocketcomponent.BodyTube;
import info.openrocket.core.rocketcomponent.FlightConfiguration;
import info.openrocket.core.rocketcomponent.NoseCone;
import info.openrocket.core.rocketcomponent.Rocket;
import info.openrocket.core.rocketcomponent.TrapezoidFinSet;
import info.openrocket.swing.util.BaseTestCase;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MachAngleLinesTest extends BaseTestCase {
	private static final double EPSILON = 1e-9;

	/**
	 * Nose cone 100 mm, body tube 300 mm with radius 25 mm, and four fins at the end of the body tube: root 100 mm,
	 * sweep 30 mm, height 60 mm. The fin tips are 30 mm behind the fin front, 85 mm from the axis.
	 */
	private static FlightConfiguration createRocket() {
		Rocket rocket = new Rocket();
		AxialStage stage = new AxialStage();
		rocket.addChild(stage);
		NoseCone nose = new NoseCone();
		nose.setLength(0.1);
		nose.setAftRadius(0.025);
		stage.addChild(nose);
		BodyTube tube = new BodyTube();
		tube.setLength(0.3);
		tube.setOuterRadius(0.025);
		stage.addChild(tube);
		TrapezoidFinSet fins = new TrapezoidFinSet();
		fins.setFinCount(4);
		fins.setRootChord(0.1);
		fins.setTipChord(0.05);
		fins.setSweep(0.03);
		fins.setHeight(0.06);
		tube.addChild(fins);
		// The stages of a rocket that is built in code are only active after this, as in a loaded design
		rocket.enableEvents();
		FlightConfiguration configuration = rocket.getSelectedConfiguration();
		configuration.update();
		configuration.setAllStages();
		return configuration;
	}

	@Test
	void machAngleIsTheInverseSineOfOneOverMach() {
		assertEquals(Math.toRadians(30), MachAngleLines.machAngle(2), EPSILON);
		assertEquals(Math.toRadians(90), MachAngleLines.machAngle(1 + 1e-12), 1e-5);
		assertTrue(Double.isNaN(MachAngleLines.machAngle(1)));
		assertTrue(Double.isNaN(MachAngleLines.machAngle(0.8)));
	}

	@Test
	void noLinesBelowMachOne() {
		MachAngleLines lines = new MachAngleLines();
		lines.update(createRocket(), 0.9, 0);
		assertTrue(lines.getLines().isEmpty());
		lines.update(createRocket(), Double.NaN, 0);
		assertTrue(lines.getLines().isEmpty());
	}

	/**
	 * At Mach 2 the lines are at 30 degrees: a cone from the nose tip, and a line outwards from the tip of the upper
	 * and the lower fin. The fins at 90 and 270 degrees point at the viewer and have no line.
	 */
	@Test
	void linesStartAtTheNoseTipAndTheFinTipsAtTheMachAngle() {
		MachAngleLines lines = new MachAngleLines();
		lines.update(createRocket(), 2, 0);

		List<double[]> all = lines.getLines();
		assertEquals(4, all.size());
		double slope = Math.tan(Math.toRadians(30));

		// Nose cone, both sides
		assertLine(all.get(0), 0, 0, slope);
		assertLine(all.get(1), 0, 0, -slope);

		// Fin tips: fin front at 0.1 + 0.3 - 0.1 = 0.3 m, tip 0.03 m behind it, 0.025 + 0.06 m from the axis
		assertLine(all.get(2), 0.33, 0.085, slope);
		assertLine(all.get(3), 0.33, -0.085, -slope);
	}

	@Test
	void rotatingTheViewMovesTheFinLines() {
		MachAngleLines lines = new MachAngleLines();
		// Rotated by 45 degrees, all four fin tips are seen at 0.085 * cos(45 degrees) from the axis
		lines.update(createRocket(), 2, Math.toRadians(45));

		List<double[]> all = lines.getLines();
		assertEquals(6, all.size());
		for (double[] line : all.subList(2, 6)) {
			assertEquals(0.085 * Math.cos(Math.toRadians(45)), Math.abs(line[1]), EPSILON);
		}
	}

	private static void assertLine(double[] line, double x, double y, double slope) {
		assertEquals(x, line[0], EPSILON);
		assertEquals(y, line[1], EPSILON);
		assertEquals(slope, (line[3] - line[1]) / (line[2] - line[0]), EPSILON);
	}
}
