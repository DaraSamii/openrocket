package info.openrocket.core.simulation.montecarlo;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import info.openrocket.core.rocketcomponent.AxialStage;
import info.openrocket.core.rocketcomponent.DeploymentConfiguration;
import info.openrocket.core.rocketcomponent.DeploymentConfiguration.DeployEvent;
import info.openrocket.core.rocketcomponent.FlightConfigurationId;
import info.openrocket.core.rocketcomponent.RecoveryDevice;
import info.openrocket.core.rocketcomponent.Rocket;
import info.openrocket.core.rocketcomponent.RocketComponent;

/**
 * A recovery failure or alternative of one stage, to compare with the designed recovery. It changes only the
 * deployment of the recovery devices of that stage, on the independent copy of the rocket that a trajectory flies.
 * <p>
 * The drogue of a stage is the device marked as drogue in the stage's dual-deployment setting; the other devices of
 * the stage are mains.
 *
 * @param kind what happens to the recovery of the stage
 * @param stageId component id of the stage
 * @param stageName display name of the stage
 */
public record RecoveryScenario(Kind kind, String stageId, String stageName) {
	public enum Kind {
		/** No recovery device of the stage deploys. */
		BALLISTIC,
		/** Only the drogue deploys. */
		DROGUE_ONLY,
		/** Only the mains deploy, at their designed event. */
		MAIN_ONLY,
		/** Only the mains deploy, at apogee. */
		MAIN_AT_APOGEE
	}

	/**
	 * @return the scenarios for the active stages of a flight configuration, stage by stage: ballistic for a stage
	 * 		with recovery, and the drogue and main scenarios for a stage with both
	 */
	public static List<RecoveryScenario> available(Rocket rocket, FlightConfigurationId configurationId) {
		List<RecoveryScenario> scenarios = new ArrayList<>();
		for (AxialStage stage : rocket.getFlightConfiguration(configurationId).getActiveStages()) {
			boolean drogue = false;
			boolean main = false;
			for (RecoveryDevice device : deployingDevices(stage, configurationId)) {
				drogue |= device.isDrogue();
				main |= !device.isDrogue();
			}
			if (!drogue && !main) {
				continue;
			}
			String id = stage.getID().toString();
			scenarios.add(new RecoveryScenario(Kind.BALLISTIC, id, stage.getName()));
			if (drogue && main) {
				scenarios.add(new RecoveryScenario(Kind.DROGUE_ONLY, id, stage.getName()));
				scenarios.add(new RecoveryScenario(Kind.MAIN_ONLY, id, stage.getName()));
				scenarios.add(new RecoveryScenario(Kind.MAIN_AT_APOGEE, id, stage.getName()));
			}
		}
		return scenarios;
	}

	/**
	 * Change the deployment of the recovery devices of the stage. Only call this on a copy of the rocket.
	 */
	public void applyTo(Rocket rocket, FlightConfigurationId configurationId) {
		RocketComponent stage = rocket.findComponent(UUID.fromString(stageId));
		if (!(stage instanceof AxialStage axialStage)) {
			return;
		}
		for (RecoveryDevice device : deployingDevices(axialStage, configurationId)) {
			DeployEvent event = switch (kind) {
				case BALLISTIC -> DeployEvent.NEVER;
				case DROGUE_ONLY -> device.isDrogue() ? null : DeployEvent.NEVER;
				case MAIN_ONLY -> device.isDrogue() ? DeployEvent.NEVER : null;
				case MAIN_AT_APOGEE -> device.isDrogue() ? DeployEvent.NEVER : DeployEvent.APOGEE;
			};
			if (event == null) {
				continue;
			}
			DeploymentConfiguration deployment = device.getDeploymentConfigurations().get(configurationId).clone();
			deployment.setDeployEvent(event);
			deployment.setDeployDelay(0);
			device.getDeploymentConfigurations().set(configurationId, deployment);
		}
	}

	/**
	 * @return the recovery devices of the stage itself (not of stages attached to it) that deploy in the configuration
	 */
	private static List<RecoveryDevice> deployingDevices(AxialStage stage, FlightConfigurationId configurationId) {
		List<RecoveryDevice> devices = new ArrayList<>();
		for (RocketComponent component : stage) {
			if (component instanceof RecoveryDevice device && device.getStage() == stage
					&& device.getDeploymentConfigurations().get(configurationId).getDeployEvent() != DeployEvent.NEVER) {
				devices.add(device);
			}
		}
		return devices;
	}
}
