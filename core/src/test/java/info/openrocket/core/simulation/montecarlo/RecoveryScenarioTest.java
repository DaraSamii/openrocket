package info.openrocket.core.simulation.montecarlo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import info.openrocket.core.document.Simulation;
import info.openrocket.core.rocketcomponent.AxialStage;
import info.openrocket.core.rocketcomponent.DeploymentConfiguration;
import info.openrocket.core.rocketcomponent.DeploymentConfiguration.DeployEvent;
import info.openrocket.core.rocketcomponent.Parachute;
import info.openrocket.core.rocketcomponent.Rocket;
import info.openrocket.core.rocketcomponent.RocketComponent;
import info.openrocket.core.simulation.montecarlo.RecoveryScenario.Kind;
import info.openrocket.core.util.BaseTestCase;
import info.openrocket.core.util.TestRockets;

public class RecoveryScenarioTest extends BaseTestCase {
	private static final double MAIN_ALTITUDE = 60;

	/**
	 * Estes Alpha III with its parachute made a small drogue at apogee, and a large main at 60 m. Nothing deploys
	 * during the ascent, so every scenario reaches the same apogee.
	 */
	private static Simulation dualDeployment() {
		Rocket rocket = TestRockets.makeEstesAlphaIII();
		Parachute drogue = parachute(rocket);
		drogue.setDrogue(true);
		drogue.setDiameter(0.15);
		DeploymentConfiguration atApogee = new DeploymentConfiguration();
		atApogee.setDeployEvent(DeployEvent.APOGEE);
		drogue.getDeploymentConfigurations().setDefault(atApogee);
		drogue.getDeploymentConfigurations().reset();

		Parachute main = new Parachute();
		main.setName("Main");
		main.setDiameter(0.6);
		DeploymentConfiguration deployment = new DeploymentConfiguration();
		deployment.setDeployEvent(DeployEvent.ALTITUDE);
		deployment.setDeployAltitude(MAIN_ALTITUDE);
		main.getDeploymentConfigurations().setDefault(deployment);
		drogue.getParent().addChild(main);

		Simulation simulation = new Simulation(rocket);
		simulation.setFlightConfigurationId(TestRockets.TEST_FCID_0);
		simulation.getOptions().setLaunchRodLength(3.0);
		simulation.getOptions().getAverageWindModel().setAverage(3.0);
		simulation.getOptions().getAverageWindModel().setStandardDeviation(0);
		return simulation;
	}

	private static Parachute parachute(Rocket rocket) {
		for (RocketComponent component : rocket) {
			if (component instanceof Parachute parachute) {
				return parachute;
			}
		}
		throw new AssertionError("No parachute");
	}

	private static AxialStage stage(Simulation simulation) {
		return (AxialStage) simulation.getRocket().getChild(0);
	}

	@Test
	public void testSingleRecoveryDeviceOnlyAllowsBallistic() {
		Rocket rocket = TestRockets.makeEstesAlphaIII();

		List<RecoveryScenario> scenarios = RecoveryScenario.available(rocket, TestRockets.TEST_FCID_0);

		AxialStage stage = (AxialStage) rocket.getChild(0);
		assertEquals(List.of(new RecoveryScenario(Kind.BALLISTIC, stage.getID().toString(), stage.getName())),
				scenarios);
	}

	@Test
	public void testDualDeploymentAllowsEveryScenario() {
		Simulation simulation = dualDeployment();

		List<RecoveryScenario> scenarios = RecoveryScenario.available(simulation.getRocket(),
				TestRockets.TEST_FCID_0);

		assertEquals(List.of(Kind.BALLISTIC, Kind.DROGUE_ONLY, Kind.MAIN_ONLY, Kind.MAIN_AT_APOGEE),
				scenarios.stream().map(RecoveryScenario::kind).toList());
	}

	@Test
	public void testStageWithoutDeployingDevicesHasNoScenario() {
		Rocket rocket = TestRockets.makeEstesAlphaIII();
		DeploymentConfiguration never = new DeploymentConfiguration();
		never.setDeployEvent(DeployEvent.NEVER);
		parachute(rocket).getDeploymentConfigurations().set(TestRockets.TEST_FCID_0, never);

		assertTrue(RecoveryScenario.available(rocket, TestRockets.TEST_FCID_0).isEmpty());
	}

	@Test
	public void testMainAtApogeeDisablesTheDrogueAndDeploysTheMainsAtApogee() {
		Simulation simulation = dualDeployment();
		Rocket rocket = simulation.getRocket();
		AxialStage stage = stage(simulation);

		new RecoveryScenario(Kind.MAIN_AT_APOGEE, stage.getID().toString(), stage.getName())
				.applyTo(rocket, TestRockets.TEST_FCID_0);

		for (RocketComponent component : rocket) {
			if (component instanceof Parachute parachute) {
				DeploymentConfiguration deployment = parachute.getDeploymentConfigurations()
						.get(TestRockets.TEST_FCID_0);
				assertEquals(parachute.isDrogue() ? DeployEvent.NEVER : DeployEvent.APOGEE,
						deployment.getDeployEvent(), parachute.getName());
				assertEquals(0, deployment.getDeployDelay(), parachute.getName());
			}
		}
	}

	/**
	 * Without dispersion, the descent times must follow the drag area under which the rocket falls: the designed
	 * drogue-then-main descent is slower than the drogue alone or the main only near the ground, faster than the
	 * main from apogee, and every recovery is slower than none.
	 */
	@Test
	public void testScenariosChangeTheDescentAsTheirRecoveryImplies() {
		Simulation source = dualDeployment();
		AxialStage stage = stage(source);
		String stageId = stage.getID().toString();
		MonteCarloSettings settings = MonteCarloSettings.builder().runCount(2).seed(97531).threadCount(1).build();
		MonteCarloSimulationRunner runner = new MonteCarloSimulationRunner();

		MonteCarloResult designed = runner.run(source, settings);
		Map<Kind, MonteCarloResult> results = new EnumMap<>(Kind.class);
		for (RecoveryScenario scenario : RecoveryScenario.available(source.getRocket(), TestRockets.TEST_FCID_0)) {
			results.put(scenario.kind(), runner.run(source, settings, scenario, (completed, total) -> {
			}));
		}

		double designedTime = designed.getNominalResult().flightTime();
		double ballisticTime = results.get(Kind.BALLISTIC).getNominalResult().flightTime();
		assertTrue(designedTime < results.get(Kind.MAIN_AT_APOGEE).getNominalResult().flightTime());
		assertTrue(designedTime > results.get(Kind.DROGUE_ONLY).getNominalResult().flightTime());
		assertTrue(designedTime > results.get(Kind.MAIN_ONLY).getNominalResult().flightTime());
		for (Kind kind : List.of(Kind.DROGUE_ONLY, Kind.MAIN_ONLY, Kind.MAIN_AT_APOGEE)) {
			assertTrue(ballisticTime < results.get(kind).getNominalResult().flightTime(), kind.name());
		}

		// The ballistic rocket lands without a recovery device and still has a landing point
		assertNotNull(results.get(Kind.BALLISTIC).getNominalResult().getLandingPoint(stageId));
		assertEquals(2, results.get(Kind.BALLISTIC).getLandingPoints(stageId).size());
		// The source rocket keeps its designed recovery
		assertEquals(List.of(Kind.BALLISTIC, Kind.DROGUE_ONLY, Kind.MAIN_ONLY, Kind.MAIN_AT_APOGEE),
				RecoveryScenario.available(source.getRocket(), TestRockets.TEST_FCID_0).stream()
						.map(RecoveryScenario::kind).toList());
	}
}
