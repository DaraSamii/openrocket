package info.openrocket.core.simulation.montecarlo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

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
}
