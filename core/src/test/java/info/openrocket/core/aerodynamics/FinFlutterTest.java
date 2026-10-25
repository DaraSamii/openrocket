package info.openrocket.core.aerodynamics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import info.openrocket.core.database.Databases;
import info.openrocket.core.document.OpenRocketDocumentFactory;
import info.openrocket.core.document.Simulation;
import info.openrocket.core.material.Material;
import info.openrocket.core.material.MaterialGroup;
import info.openrocket.core.rocketcomponent.EllipticalFinSet;
import info.openrocket.core.rocketcomponent.FinSet;
import info.openrocket.core.rocketcomponent.FreeformFinSet;
import info.openrocket.core.rocketcomponent.Rocket;
import info.openrocket.core.rocketcomponent.RocketComponent;
import info.openrocket.core.rocketcomponent.TrapezoidFinSet;
import info.openrocket.core.simulation.FlightData;
import info.openrocket.core.simulation.FlightDataBranch;
import info.openrocket.core.simulation.FlightDataType;
import info.openrocket.core.util.BaseTestCase;
import info.openrocket.core.util.Coordinate;
import info.openrocket.core.util.TestRockets;

public class FinFlutterTest extends BaseTestCase {
	private static final double INCH = 0.0254;

	/**
	 * The worked example of Peak of Flight newsletter 615: G10 fins with a 7.5 in root chord, 2.5 in tip chord,
	 * 4.285 in sweep and 3 in height, at 18,500 ft (7.2 psi, speed of sound 1043.36 ft/s). The shear modulus and
	 * pressure only appear as a ratio, so psi can be used for both, and the velocity comes out in ft/s.
	 */
	@Test
	public void bennettWorkedExample() {
		TrapezoidFinSet fins = new TrapezoidFinSet(3, 7.5 * INCH, 2.5 * INCH, 4.285 * INCH, 3 * INCH);

		fins.setThickness(0.125 * INCH);
		FinFlutter.Geometry geometry = FinFlutter.geometry(fins);
		assertEquals(0.6, geometry.aspectRatio(), 1e-9);
		assertEquals(1.0 / 3, geometry.taperRatio(), 1e-9);
		assertEquals(0.349, geometry.epsilon(), 0.0005);
		assertEquals(1425, FinFlutter.flutterVelocity(600000, geometry, 1043.36, 7.2), 1);

		fins.setThickness(5.0 / 32 * INCH);
		assertEquals(1991.7, FinFlutter.flutterVelocity(600000, FinFlutter.geometry(fins), 1043.36, 7.2), 1);

		fins.setThickness(3.0 / 16 * INCH);
		assertEquals(2618.1, FinFlutter.flutterVelocity(600000, FinFlutter.geometry(fins), 1043.36, 7.2), 1);
	}

	@Test
	public void ellipticalFinsUseTheEquivalentTrapezoid() {
		EllipticalFinSet fins = new EllipticalFinSet();
		fins.setLength(0.1);
		fins.setHeight(0.05);
		FinFlutter.Geometry geometry = FinFlutter.geometry(fins);

		// The outline is a polygon, so allow for its slightly smaller area
		assertEquals(0.25, geometry.epsilon(), 1e-3);
		assertEquals(Math.PI / 2 - 1, geometry.taperRatio(), 0.01);
		assertEquals(0.05 * 0.05 / (Math.PI * 0.1 * 0.05 / 4), geometry.aspectRatio(), 0.01);
	}

	@Test
	public void flutterNeedsAShearModulusAndAFinCentroidBehindTheQuarterChord() throws Exception {
		TrapezoidFinSet fins = new TrapezoidFinSet(3, 0.1, 0.05, 0.03, 0.05);
		fins.setMaterial(Material.newMaterial(Material.Type.BULK, "No modulus", 1000, 0, MaterialGroup.OTHER, true));
		assertTrue(Double.isNaN(FinFlutter.flutterVelocity(fins, 340, 101325)));

		// A fin swept forward so far that its centroid lies ahead of the quarter root chord
		FreeformFinSet forward = new FreeformFinSet();
		forward.setPoints(new Coordinate[]{new Coordinate(0, 0), new Coordinate(-0.2, 0.05),
				new Coordinate(-0.18, 0.05), new Coordinate(0.05, 0)});
		forward.setMaterial(Databases.findMaterial(Material.Type.BULK, "Fiberglass", 1850, 4.14e9,
				MaterialGroup.COMPOSITES));
		assertTrue(FinFlutter.geometry(forward).epsilon() < 0);
		assertTrue(Double.isNaN(FinFlutter.flutterVelocity(forward, 340, 101325)));
	}

	@Test
	public void flutterIsSlowerInDenserAirAndFasterForThickerFins() {
		TrapezoidFinSet fins = new TrapezoidFinSet(3, 0.1, 0.05, 0.03, 0.05);
		fins.setMaterial(Databases.findMaterial(Material.Type.BULK, "Fiberglass", 1850, 4.14e9,
				MaterialGroup.COMPOSITES));
		fins.setThickness(0.002);
		double thin = FinFlutter.flutterVelocity(fins, 340, 101325);
		fins.setThickness(0.004);
		double thick = FinFlutter.flutterVelocity(fins, 340, 101325);

		// The velocity scales with the thickness ratio to the power 3/2 and with one over the root of the pressure
		assertEquals(Math.pow(2, 1.5), thick / thin, 1e-9);
		assertEquals(2, FinFlutter.flutterVelocity(fins, 340, 101325 / 4.0) / thick, 1e-9);
	}

	@Test
	public void simulationStoresFlutterVelocityAndMargin() throws Exception {
		Rocket rocket = TestRockets.makeEstesAlphaIII();
		FinSet fins = null;
		for (RocketComponent component : rocket) {
			if (component instanceof FinSet finSet) {
				fins = finSet;
			}
		}
		assertNotNull(fins);
		fins.setMaterial(Databases.findMaterial(Material.Type.BULK, "Balsa", 170, 0.23e9, MaterialGroup.WOODS));
		Simulation simulation = new Simulation(OpenRocketDocumentFactory.createDocumentFromRocket(rocket), rocket);
		simulation.setFlightConfigurationId(TestRockets.TEST_FCID_0);
		simulation.getOptions().setISAAtmosphere(true);
		simulation.getOptions().setTimeStep(0.05);
		simulation.simulate();

		FlightData data = simulation.getSimulatedData();
		FlightDataBranch branch = data.getBranch(0);
		List<Double> flutter = branch.get(FlightDataType.TYPE_FLUTTER_VELOCITY);
		List<Double> margin = branch.get(FlightDataType.TYPE_FLUTTER_MARGIN);
		List<Double> soundSpeed = branch.get(FlightDataType.TYPE_SPEED_OF_SOUND);
		List<Double> pressure = branch.get(FlightDataType.TYPE_AIR_PRESSURE);
		List<Double> mach = branch.get(FlightDataType.TYPE_MACH_NUMBER);

		double lowest = Double.NaN;
		int checked = 0;
		for (int i = 0; i < flutter.size(); i++) {
			if (Double.isNaN(soundSpeed.get(i)) || Double.isNaN(margin.get(i))) {
				continue;
			}
			assertEquals(FinFlutter.flutterVelocity(fins, soundSpeed.get(i), pressure.get(i)), flutter.get(i), 1e-6);
			double airspeed = mach.get(i) * soundSpeed.get(i);
			assertEquals(flutter.get(i) / airspeed - 1, margin.get(i), 1e-6 * Math.max(1, margin.get(i)));
			if (Double.isNaN(lowest) || margin.get(i) < lowest) {
				lowest = margin.get(i);
			}
			checked++;
		}
		assertTrue(checked > 10);
		assertEquals(lowest, data.getMinFlutterMargin(), 1e-12);
		assertFalse(Double.isNaN(data.getFlutterVelocity()));
		// A balsa Alpha III on its test motor stays below its flutter velocity
		assertTrue(data.getMinFlutterMargin() > 0, "margin " + data.getMinFlutterMargin());
	}
}
