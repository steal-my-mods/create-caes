package com.createcaes.client.ponder;

import net.createmod.ponder.api.PonderPalette;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import net.createmod.ponder.api.scene.Selection;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/**
 * The one scene. It has a single job: show that the block does two things and that it decides which
 * on its own, because that is the only idea in this mod a Create player does not already have.
 *
 * <p>The layout matches {@code tools/generate_ponder.py}, which writes the structure it plays in.
 */
public class AirEngineScenes {

	private static final BlockPos MOTOR = new BlockPos(0, 1, 1);
	private static final BlockPos ENGINE = new BlockPos(2, 1, 1);

	public static void storingSurplus(SceneBuilder scene, SceneBuildingUtil util) {
		scene.title("air_engine", "Storing surplus power as compressed air");
		scene.configureBasePlate(0, 0, 5);
		scene.showBasePlate();
		scene.idle(5);

		Selection vessel = util.select()
			.fromTo(3, 1, 1, 4, 2, 2);
		Selection drive = util.select()
			.fromTo(0, 1, 1, 2, 1, 1);

		scene.world()
			.showSection(vessel, Direction.DOWN);
		scene.idle(10);
		scene.overlay()
			.showText(70)
			.text("Pressure Vessels hold Compressed Air. Stack them up to 3x3 wide, as you would a Fluid Tank")
			.attachKeyFrame()
			.placeNearTarget()
			.pointAt(util.vector()
				.blockSurface(util.grid()
					.at(3, 2, 1), Direction.WEST));
		scene.idle(80);

		scene.world()
			.showSection(drive, Direction.DOWN);
		scene.idle(15);
		scene.overlay()
			.showText(70)
			.text("An Air Engine bolts onto the vessel and drives a shaft from the opposite side")
			.attachKeyFrame()
			.placeNearTarget()
			.pointAt(util.vector()
				.blockSurface(util.grid()
					.at(2, 1, 1), Direction.NORTH));
		scene.idle(80);

		scene.overlay()
			.showOutline(PonderPalette.GREEN, ENGINE, util.select()
				.position(ENGINE), 60);
		scene.overlay()
			.showText(80)
			.colored(PonderPalette.GREEN)
			.text("While the network has capacity to spare, the engine draws it and fills the vessel")
			.attachKeyFrame()
			.placeNearTarget()
			.pointAt(util.vector()
				.topOf(ENGINE));
		scene.idle(90);

		// Pull the source out. The shaft is left behind on purpose: the engine needs something to
		// drive, and it is what the failover is for.
		scene.world()
			.destroyBlock(MOTOR);
		scene.idle(20);
		scene.overlay()
			.showText(90)
			.colored(PonderPalette.RED)
			.text("When the network can no longer carry its own load, the same engine becomes a motor")
			.attachKeyFrame()
			.placeNearTarget()
			.pointAt(util.vector()
				.blockSurface(util.grid()
					.at(1, 1, 1), Direction.UP));
		scene.idle(100);

		scene.overlay()
			.showText(90)
			.text("It spends stored air to supply the missing capacity, turning the shaft the same way and at the same speed it already was")
			.placeNearTarget()
			.pointAt(util.vector()
				.topOf(ENGINE));
		scene.idle(100);

		scene.overlay()
			.showText(90)
			.text("A bigger vessel means more stress supplied and longer to supply it, the way a bigger boiler does for a Steam Engine")
			.attachKeyFrame()
			.placeNearTarget()
			.pointAt(util.vector()
				.blockSurface(util.grid()
					.at(4, 2, 1), Direction.EAST));
		scene.idle(100);
		scene.markAsFinished();
	}
}
