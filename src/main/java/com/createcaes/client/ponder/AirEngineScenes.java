package com.createcaes.client.ponder;

import com.createcaes.engine.AirEngineBlockEntity;
import com.createcaes.engine.EngineMode;
import com.simibubi.create.foundation.ponder.CreateSceneBuilder;

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
 *
 * <h2>Nothing in a ponder scene rotates by itself</h2>
 * A ponder level is client-side, and every path that assigns a kinetic speed is server-gated —
 * {@code KineticBlockEntity#tick} only calls {@code attachKinetics()} under {@code !isClientSide},
 * and {@code RotationPropagator} bails the same way. So a shaft in here never acquires a speed on
 * its own, and {@code KineticBlockEntityRenderer#getAngleForBe} reads the speed straight off the
 * block entity. Create's answer is to fake it: {@code setKineticSpeed} writes the {@code Speed} tag
 * the renderer reads, and it lives on {@link CreateSceneBuilder} rather than on Ponder's own
 * builder, which is why every Create scene opens by wrapping the builder it is handed. Without that
 * wrap and those calls the whole scene is a still life with captions.
 *
 * <p>Nothing needs zeroing first, unlike Create's scenes: their structures are captured out of
 * running worlds and arrive carrying real speeds, whereas {@code generate_ponder.py} writes no
 * {@code Speed} tag at all, so everything starts at rest and returns there when the scene replays.
 */
public class AirEngineScenes {

	private static final BlockPos MOTOR = new BlockPos(0, 1, 1);
	private static final BlockPos ENGINE = new BlockPos(2, 1, 1);

	/**
	 * The speed the whole rig runs at.
	 *
	 * <p>Chosen so the rig tells the truth about itself twice over: 16 is what a Creative Motor
	 * facing east generates with its scroll value untouched, which is the state the structure
	 * places it in, and the 2x2x2 vessel puts the engine on tier 3 — a ceiling of 48 — so the
	 * engine really can hold this speed alone once the motor is gone, which is the claim the
	 * failover text makes.
	 */
	private static final float NETWORK_RPM = 16;

	public static void storingSurplus(SceneBuilder builder, SceneBuildingUtil util) {
		CreateSceneBuilder scene = new CreateSceneBuilder(builder);
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
		// The engine arrives spinning but idle -- flywheel turning, piston still -- because the
		// renderer freezes the rod in IDLE. That makes the mode change below a visible event
		// rather than a caption.
		scene.world()
			.setKineticSpeed(drive, NETWORK_RPM);
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

		setMode(scene, util, EngineMode.COMPRESSING);
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
		// The shaft keeps its speed across the handover, deliberately and not by omission. That is
		// both what the text below claims and what the engine really does: rememberedSpeed was 16
		// and the tier ceiling is 48, so getGeneratedSpeed() would return the 16 it was already at.
		setMode(scene, util, EngineMode.GENERATING);
		scene.overlay()
			.showOutline(PonderPalette.RED, ENGINE, util.select()
				.position(ENGINE), 60);
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

	/**
	 * Sets the engine's mode the only way a client-side level can: by hand.
	 *
	 * <p>{@link AirEngineBlockEntity#tick()} returns before deciding anything when
	 * {@code level.isClientSide}, so in here the mode is whatever the last write left — which is
	 * why it also stays put once set, rather than being argued with every tick.
	 */
	private static void setMode(CreateSceneBuilder scene, SceneBuildingUtil util, EngineMode mode) {
		scene.world()
			.modifyBlockEntityNBT(util.select()
				.position(ENGINE), AirEngineBlockEntity.class,
				nbt -> nbt.putInt("Mode", mode.ordinal()));
	}
}
