package com.createcaes.test;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.List;

import com.createcaes.CAESConfig;
import com.createcaes.CreateCAES;
import com.createcaes.engine.AirEngineBlock;
import com.createcaes.engine.AirEngineBlockEntity;
import com.createcaes.engine.EngineMode;
import com.createcaes.engine.IdleReason;
import com.createcaes.registry.CAESBlocks;
import com.createcaes.registry.CAESTags;
import com.createcaes.registry.CAESItems;
import com.createcaes.registry.CAESFluids;
import com.createcaes.vessel.PressureVesselBlock;
import com.createcaes.vessel.PressureVesselBlockEntity;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity;
import com.simibubi.create.foundation.fluid.SmartFluidTank;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.GameType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ComparatorBlockEntity;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * The rig is always the same line of blocks: a driver, then the engine, then the vessel it is
 * bolted to. What changes between tests is which driver is on the shaft side and how much air the
 * vessel starts with, because those two are exactly what the engine reads when it picks a mode.
 */
@GameTestHolder(CreateCAES.ID)
@PrefixGameTestTemplate(false)
public class CAESGameTests {

	private static final int SITE_SIZE = 11;

	/** Shaft side, engine, vessel — west to east. */
	private static final BlockPos DRIVER = new BlockPos(2, 1, 5);
	private static final BlockPos ENGINE = new BlockPos(3, 1, 5);
	private static final BlockPos VESSEL = new BlockPos(4, 1, 5);

	/** Past the engine's warm-up, with room for the rotation propagator to settle. */
	private static final int SETTLE_TICKS = 12;

	/** How long {@code aTrickleFedEngineDoesNotFlapItsMode} watches, and how many changes it allows. */
	private static final int FLAP_SAMPLE_TICKS = 100;
	/**
	 * Budget for that window. The guarded engine settles into roughly a 28-tick cycle — a short
	 * discharge, then {@code NO_AIR_COOLDOWN_TICKS} of waiting — so about seven changes. The
	 * unguarded one managed 30, so this catches the regression with room to spare either way.
	 */
	private static final int FLAP_BUDGET = 14;

	/** A comparator's own two-tick delay, plus a tick for the neighbour update to reach it. */
	private static final int COMPARATOR_DELAY = 4;

	/** How long aRunningVesselBarelyEverSweepsItsParts watches, and how many sweeps it allows. */
	private static final int SWEEP_SAMPLE_TICKS = 100;
	private static final int SWEEP_BUDGET = 2;

	/** Long enough for a tier-1 engine to work through 100mB at a few mB a tick. */
	private static final int DRAIN_SAMPLE_TICKS = 80;
	/**
	 * Smallest whole stroke a tier-1 engine on a one-block vessel ever takes. Its draw is about
	 * 3.64mB a tick and the buffer carries the fraction, so the integer part is always 3 or 4 —
	 * anything smaller than this came out of a partial drain.
	 */
	private static final int MIN_STROKE = 3;

	/** How long {@code oneNetworkNeverChargesAndDischargesAtOnce} watches every engine on the shaft. */
	private static final int MODE_SAMPLE_TICKS = 100;

	// --- compressing ---------------------------------------------------------------------------

	@GameTest(template = "test_rig", timeoutTicks = 200)
	public static void surplusPowerFillsTheVessel(GameTestHelper helper) {
		rig(helper);
		helper.setBlock(DRIVER, AllBlocks.CREATIVE_MOTOR.getDefaultState()
			.setValue(BlockStateProperties.FACING, Direction.EAST));

		helper.runAfterDelay(SETTLE_TICKS + 20, () -> {
			AirEngineBlockEntity engine = engine(helper);
			helper.assertTrue(engine.getMode() == EngineMode.COMPRESSING,
				"engine should be compressing off a creative motor's surplus, was " + engine.getMode());
			helper.assertTrue(air(helper) > 0,
				"the vessel should have gained air, holds " + air(helper) + "mB");
			helper.succeed();
		});
	}

	/** A compressor with nowhere to put air must stop drawing, not keep spinning against a full tank. */
	@GameTest(template = "test_rig", timeoutTicks = 200)
	public static void aFullVesselStopsTheCompressor(GameTestHelper helper) {
		rig(helper);
		helper.setBlock(DRIVER, AllBlocks.CREATIVE_MOTOR.getDefaultState()
			.setValue(BlockStateProperties.FACING, Direction.EAST));
		fill(helper, Integer.MAX_VALUE);

		helper.runAfterDelay(SETTLE_TICKS + 10, () -> {
			AirEngineBlockEntity engine = engine(helper);
			helper.assertTrue(engine.getMode() == EngineMode.IDLE,
				"a full vessel should leave the engine idle, was " + engine.getMode());
			helper.assertTrue(engine.calculateStressApplied() == 0,
				"an idle engine must place no load on the network");
			helper.succeed();
		});
	}

	/**
	 * The property the whole design turns on. An engine that tested the network's balance
	 * <em>including</em> its own contribution would compress, see the deficit it just created,
	 * generate, see the surplus it just created, and compress again — once a tick, for ever. This
	 * asserts the mode settles and then stays put.
	 */
	@GameTest(template = "test_rig", timeoutTicks = 300)
	public static void theEngineSettlesOnOneModeRatherThanFlipping(GameTestHelper helper) {
		rig(helper);
		helper.setBlock(DRIVER, AllBlocks.CREATIVE_MOTOR.getDefaultState()
			.setValue(BlockStateProperties.FACING, Direction.EAST));

		List<EngineMode> seen = new ArrayList<>();
		helper.startSequence()
			.thenIdle(SETTLE_TICKS)
			.thenExecuteFor(80, () -> seen.add(engine(helper).getMode()))
			.thenExecute(() -> {
				long changes = 0;
				for (int i = 1; i < seen.size(); i++)
					if (seen.get(i) != seen.get(i - 1))
						changes++;
				helper.assertTrue(changes == 0,
					"the mode changed " + changes + " times in 80 settled ticks: " + seen);
				helper.assertTrue(seen.get(0) == EngineMode.COMPRESSING,
					"expected it to have settled on compressing, got " + seen.get(0));
			})
			.thenSucceed();
	}

	/**
	 * The mechanism behind the test above, asserted directly. A creative motor has so much capacity
	 * to spare that a mode flip would not actually show up in {@code settlesOnOneMode}; what would
	 * show up on a marginal network is measuring the wrong quantity, so measure that instead. While
	 * the compressor is drawing 512su the engine must read the rest of the network's load as exactly
	 * zero, because it is the only load there is.
	 */
	@GameTest(template = "test_rig", timeoutTicks = 200)
	public static void theCompressorDoesNotSeeItsOwnDraw(GameTestHelper helper) {
		rig(helper);
		helper.setBlock(DRIVER, AllBlocks.CREATIVE_MOTOR.getDefaultState()
			.setValue(BlockStateProperties.FACING, Direction.EAST));

		helper.runAfterDelay(SETTLE_TICKS + 10, () -> {
			AirEngineBlockEntity engine = engine(helper);
			helper.assertTrue(engine.getMode() == EngineMode.COMPRESSING,
				"expected it to be compressing, was " + engine.getMode());
			helper.assertTrue(engine.calculateStressApplied() > 0,
				"a compressing engine must actually be loading the network");
			helper.assertTrue(engine.networkStressWithoutSelf() == 0,
				"the engine is the only load on this network, so everything else's stress must read "
					+ "as zero; it read " + engine.networkStressWithoutSelf());
			helper.assertTrue(engine.networkCapacityWithoutSelf() > 0,
				"the motor's capacity should still be visible");
			helper.succeed();
		});
	}

	// --- generating ----------------------------------------------------------------------------

	/** With no other source on the network, stored air is what turns the shaft. */
	@GameTest(template = "test_rig", timeoutTicks = 200)
	public static void storedAirDrivesTheShaft(GameTestHelper helper) {
		rig(helper);
		helper.setBlock(DRIVER, AllBlocks.ENCASED_FAN.getDefaultState()
			.setValue(BlockStateProperties.FACING, Direction.WEST));
		fill(helper, 8000);
		int before = air(helper);

		helper.runAfterDelay(SETTLE_TICKS + 20, () -> {
			AirEngineBlockEntity engine = engine(helper);
			helper.assertTrue(engine.getMode() == EngineMode.GENERATING,
				"the engine should be generating off stored air, was " + engine.getMode());
			helper.assertTrue(engine.getSpeed() != 0,
				"a generating engine should be turning");

			KineticBlockEntity fan = helper.getBlockEntity(DRIVER);
			helper.assertTrue(fan.getSpeed() != 0,
				"the load on the shaft should be turning too");
			helper.assertTrue(!fan.isOverStressed(),
				"the engine's capacity should be covering the load rather than the network failing");
			helper.assertTrue(air(helper) < before,
				"generating should have spent air; still holds " + air(helper) + "mB of " + before);
			helper.succeed();
		});
	}

	/** No air, no torque — and specifically no free rotation. */
	@GameTest(template = "test_rig", timeoutTicks = 200)
	public static void anEmptyVesselDrivesNothing(GameTestHelper helper) {
		rig(helper);
		helper.setBlock(DRIVER, AllBlocks.ENCASED_FAN.getDefaultState()
			.setValue(BlockStateProperties.FACING, Direction.WEST));

		helper.runAfterDelay(SETTLE_TICKS + 10, () -> {
			AirEngineBlockEntity engine = engine(helper);
			helper.assertTrue(engine.getMode() == EngineMode.IDLE,
				"an empty vessel should leave the engine idle, was " + engine.getMode());
			helper.assertTrue(engine.getSpeed() == 0,
				"an idle engine must not be turning, was " + engine.getSpeed());
			helper.succeed();
		});
	}

	/**
	 * A charged engine on a shaft with nothing drawing on it keeps its air.
	 *
	 * <p><b>This test used to assert the opposite</b>, as {@code anUnloadedMotorStillSpendsAir}: "a bare
	 * shaft is still something to drive". Reported from play, for this mod and for Create: Gravity
	 * Batteries alike — attaching a bare shaft to a charged engine started it generating and it emptied
	 * its vessel driving nothing. A clutch did it too, and that is the clearest case, because a
	 * disengaged clutch splits the network: the engine's whole world was itself and a clutch passing
	 * nothing through.
	 *
	 * <p>The old test's stated reason was that without the floor "a charged vessel is a perpetual motion
	 * machine: rotation for nothing, for ever", and that reasoning does not hold. Declining to spend air
	 * is not perpetual motion, it is not spending; nothing is created either way. What the old behaviour
	 * actually bought was a vessel that drained itself whenever a player left a shaft attached.
	 *
	 * <p>{@code anEngineDrivingNothingStaysIdle} covers the neighbouring case — nothing on the shaft
	 * face at all — and passed throughout, which is exactly why this one was needed: an empty face was
	 * already handled and a face with a shaft on it was not.
	 */
	@GameTest(template = "test_rig", timeoutTicks = 200)
	public static void aChargedEngineOnABareShaftHoldsItsAir(GameTestHelper helper) {
		rig(helper);
		// A shaft, a cogwheel and a clutch. Not one of the three draws a Stress Unit.
		helper.setBlock(DRIVER, AllBlocks.SHAFT.getDefaultState()
			.setValue(BlockStateProperties.AXIS, Direction.Axis.X));
		helper.setBlock(DRIVER.west(), AllBlocks.COGWHEEL.getDefaultState()
			.setValue(BlockStateProperties.AXIS, Direction.Axis.X));
		helper.setBlock(DRIVER.west().west(), AllBlocks.CLUTCH.getDefaultState()
			.setValue(BlockStateProperties.AXIS, Direction.Axis.X));
		fill(helper, 8000);
		int before = air(helper);

		helper.runAfterDelay(SETTLE_TICKS + 40, () -> {
			AirEngineBlockEntity engine = engine(helper);
			// The air is the substance: an engine that spent it has spent it whatever it reports.
			helper.assertTrue(air(helper) == before,
				"the engine spent air into a network that draws nothing; " + air(helper) + "mB of "
					+ before);
			helper.assertTrue(engine.getMode() == EngineMode.IDLE,
				"expected it to hold its air, it is " + engine.getMode());
			helper.assertTrue(engine.getIdleReason() == IdleReason.NOTHING_TO_DRIVE,
				"an engine holding because nothing wants power should say so rather than report the "
					+ "shaft as unpowered; it says " + engine.getIdleReason());
			helper.succeed();
		});
	}

	/** An engine with nothing on its shaft has no reason to spend anything. */
	@GameTest(template = "test_rig", timeoutTicks = 200)
	public static void anEngineDrivingNothingStaysIdle(GameTestHelper helper) {
		rig(helper);
		fill(helper, 8000);
		int before = air(helper);

		helper.runAfterDelay(SETTLE_TICKS + 20, () -> {
			helper.assertTrue(engine(helper).getMode() == EngineMode.IDLE,
				"nothing is attached, so there is nothing to generate for");
			helper.assertTrue(air(helper) == before,
				"an idle engine must not leak air");
			helper.succeed();
		});
	}

	/** The same exclusion, the other way round: a motor must not count its own capacity as cover. */
	@GameTest(template = "test_rig", timeoutTicks = 200)
	public static void theMotorDoesNotSeeItsOwnCapacity(GameTestHelper helper) {
		rig(helper);
		helper.setBlock(DRIVER, AllBlocks.ENCASED_FAN.getDefaultState()
			.setValue(BlockStateProperties.FACING, Direction.WEST));
		fill(helper, 8000);

		helper.runAfterDelay(SETTLE_TICKS + 10, () -> {
			AirEngineBlockEntity engine = engine(helper);
			helper.assertTrue(engine.getMode() == EngineMode.GENERATING,
				"expected it to be generating, was " + engine.getMode());
			helper.assertTrue(engine.calculateAddedStressCapacity() > 0,
				"a generating engine must actually be supplying capacity");
			helper.assertTrue(engine.networkCapacityWithoutSelf() == 0,
				"nothing else on this network generates, so capacity-without-self must be zero; it read "
					+ engine.networkCapacityWithoutSelf());
			helper.assertTrue(engine.networkStressWithoutSelf() > 0,
				"the fan's load should still be visible; it read " + engine.networkStressWithoutSelf());
			helper.succeed();
		});
	}

	/**
	 * The economic invariant, and the reason {@link CAESConfig#chargeMarginStress()} exists at all.
	 * Two engines back to back on one shaft, one generating and one compressing, would be a machine
	 * that makes air out of the air it is spending. The margin is what refuses it: a compressor
	 * needs strictly <em>more</em> spare capacity than it will draw, and a motor of the same rating
	 * supplies exactly as much as the compressor wants.
	 */
	@GameTest(template = "test_rig", timeoutTicks = 300)
	public static void twoEnginesOnOneShaftCannotChargeEachOther(GameTestHelper helper) {
		floor(helper);

		// The charged half: vessel, engine facing it, shaft to the west.
		helper.setBlock(VESSEL, CAESBlocks.PRESSURE_VESSEL.get()
			.defaultBlockState());
		helper.setBlock(ENGINE, CAESBlocks.AIR_ENGINE.get()
			.defaultBlockState()
			.setValue(AirEngineBlock.FACING, Direction.EAST));
		fill(helper, 8000);

		// The empty half: the same shaft, an engine facing the other way, its own vessel.
		BlockPos shaft = DRIVER;
		BlockPos otherEngine = new BlockPos(1, 1, 5);
		BlockPos otherVessel = new BlockPos(0, 1, 5);
		// A cogwheel with a fan branched off it rather than a plain shaft, because a bare shaft is no
		// longer something an engine will spend air on, and the second engine does not count either --
		// it is a store, and a store's draw is not demand worth generating for.
		load(helper);
		helper.setBlock(otherEngine, CAESBlocks.AIR_ENGINE.get()
			.defaultBlockState()
			.setValue(AirEngineBlock.FACING, Direction.WEST));
		helper.setBlock(otherVessel, CAESBlocks.PRESSURE_VESSEL.get()
			.defaultBlockState());

		int startingAir = 8000;
		helper.runAfterDelay(SETTLE_TICKS + 60, () -> {
			AirEngineBlockEntity driving = engine(helper);
			AirEngineBlockEntity leeching = helper.getBlockEntity(otherEngine);
			PressureVesselBlockEntity target = ((PressureVesselBlockEntity) helper.getBlockEntity(otherVessel))
				.getControllerBE();

			helper.assertTrue(driving.getMode() == EngineMode.GENERATING,
				"the charged engine should be driving the shaft, was " + driving.getMode());
			helper.assertTrue(leeching.getMode() != EngineMode.COMPRESSING,
				"the second engine must not compress off the first one's output, was "
					+ leeching.getMode());
			helper.assertTrue(target.getTankInventory()
				.getFluidAmount() == 0,
				"no air should have appeared in the second vessel, it holds " + target.getTankInventory()
					.getFluidAmount() + "mB");
			helper.assertTrue(air(helper) < startingAir,
				"and the first vessel should be paying for the rotation");
			helper.succeed();
		});
	}

	// --- tiers ------------------------------------------------------------------------------

	/** Vessel size picks the speed tier, the way boiler size picks the Steam Engine's. */
	@GameTest(template = "test_rig", timeoutTicks = 200)
	public static void vesselSizeSetsTheSpeedTier(GameTestHelper helper) {
		floor(helper);
		helper.setBlock(VESSEL, vessel());
		helper.setBlock(ENGINE, engineFacing(Direction.EAST));

		helper.runAfterDelay(SETTLE_TICKS + 12, () -> {
			AirEngineBlockEntity small = engine(helper);
			helper.assertTrue(small.getSpeedTier() == 1,
				"one block of vessel is the bottom tier, got " + small.getSpeedTier());

			// Grow it to a full 3x3x1 slab: one engine's worth at the default nine blocks each.
			for (int x = 0; x < 3; x++)
				for (int z = -1; z < 2; z++)
					helper.setBlock(VESSEL.offset(x, 0, z), vessel());

			helper.runAfterDelay(24, () -> {
				AirEngineBlockEntity full = engine(helper);
				helper.assertTrue(full.getSpeedTier() == 4,
					"nine blocks should reach the top tier, got " + full.getSpeedTier()
						+ " at efficiency " + full.getEfficiency());
				helper.succeed();
			});
		});
	}

	/** Two engines on one vessel split it, exactly as two Steam Engines split an undersized boiler. */
	@GameTest(template = "test_rig", timeoutTicks = 250)
	public static void enginesShareAVesselTheWayTheyShareABoiler(GameTestHelper helper) {
		floor(helper);
		for (int x = 0; x < 3; x++)
			for (int z = -1; z < 2; z++)
				helper.setBlock(VESSEL.offset(x, 0, z), vessel());

		helper.setBlock(ENGINE, engineFacing(Direction.EAST));

		helper.runAfterDelay(SETTLE_TICKS + 12, () -> {
			float alone = engine(helper).getRatedStress();
			helper.assertTrue(engine(helper).getSpeedTier() == 4,
				"one engine on nine blocks should be at the top tier");

			// A second engine on the far face of the same vessel.
			BlockPos other = VESSEL.offset(3, 0, 0);
			helper.setBlock(other, engineFacing(Direction.WEST));

			helper.runAfterDelay(24, () -> {
				AirEngineBlockEntity first = engine(helper);
				AirEngineBlockEntity second = helper.getBlockEntity(other);
				helper.assertTrue(first.getSpeedTier() == 3 && second.getSpeedTier() == 3,
					"sharing should drop both to tier 3, got " + first.getSpeedTier() + " and "
						+ second.getSpeedTier());
				float shared = first.getRatedStress() + second.getRatedStress();
				helper.assertTrue(Math.abs(shared - alone) < 1.0F,
					"two engines sharing a vessel should be worth what one engine was: " + shared
						+ " against " + alone);
				helper.succeed();
			});
		});
	}

	/**
	 * A generating engine declares one of the four tier speeds, and which one is decided by the
	 * vessel. Neither a fixed configured RPM nor the speed the shaft happens to be turning at would
	 * satisfy this at both sizes; the previous design used the latter and fell back to the former.
	 */
	@GameTest(template = "test_rig", timeoutTicks = 250)
	public static void aGeneratingEngineDeclaresItsTierSpeed(GameTestHelper helper) {
		floor(helper);
		helper.setBlock(VESSEL, vessel());
		helper.setBlock(ENGINE, engineFacing(Direction.EAST));
		load(helper);
		fill(helper, 8000);

		helper.runAfterDelay(SETTLE_TICKS + 12, () -> {
			AirEngineBlockEntity small = engine(helper);
			helper.assertTrue(small.getMode() == EngineMode.GENERATING,
				"the small rig should be generating, it is " + small.getMode());
			helper.assertTrue(Math.abs(small.getGeneratedSpeed()) == 16 * small.getSpeedTier(),
				"tier " + small.getSpeedTier() + " should declare " + (16 * small.getSpeedTier())
					+ " RPM, it declares " + Math.abs(small.getGeneratedSpeed()));
			helper.assertTrue(Math.abs(small.getGeneratedSpeed()) == 16,
				"a one-block vessel is tier 1, so 16 RPM, not " + Math.abs(small.getGeneratedSpeed()));

			// Grow the vessel under it and the declared speed must climb with the tier.
			for (int x = 0; x < 3; x++)
				for (int z = -1; z < 2; z++)
					helper.setBlock(VESSEL.offset(x, 0, z), vessel());
			fill(helper, 40000);

			helper.runAfterDelay(24, () -> {
				AirEngineBlockEntity big = engine(helper);
				helper.assertTrue(Math.abs(big.getGeneratedSpeed()) == 16 * big.getSpeedTier(),
					"tier " + big.getSpeedTier() + " should declare " + (16 * big.getSpeedTier())
						+ " RPM, it declares " + Math.abs(big.getGeneratedSpeed()));
				helper.assertTrue(Math.abs(big.getGeneratedSpeed()) == 64,
					"nine blocks is tier 4, so 64 RPM, not " + Math.abs(big.getGeneratedSpeed()));
				helper.succeed();
			});
		});
	}

	/**
	 * Failover must not change how fast the factory runs. A tier-4 engine can drive a dead network at
	 * 64 RPM, but taking over an 8 RPM one at 64 would multiply every belt speed and every consumer's
	 * draw by eight the instant the source died. The tier is a ceiling, not a target.
	 */
	@GameTest(template = "test_rig", timeoutTicks = 300)
	public static void failoverHoldsTheSpeedTheNetworkWasRunningAt(GameTestHelper helper) {
		floor(helper);
		// Nine blocks, so the engine is tier 4 and could drive at 64 if it wanted to.
		for (int x = 0; x < 3; x++)
			for (int z = -1; z < 2; z++)
				helper.setBlock(VESSEL.offset(x, 0, z), vessel());
		helper.setBlock(ENGINE, engineFacing(Direction.EAST));
		// A shaft the engine still has something to drive after the motor goes.
		load(helper);
		BlockPos motor = DRIVER.west();
		helper.setBlock(motor, AllBlocks.CREATIVE_MOTOR.getDefaultState()
			.setValue(BlockStateProperties.FACING, Direction.EAST));
		helper.runAfterDelay(2, () -> ((CreativeMotorBlockEntity) helper.getBlockEntity(motor))
			.generatedSpeed.setValue(8));
		fill(helper, 40000);

		helper.runAfterDelay(SETTLE_TICKS + 20, () -> {
			AirEngineBlockEntity engine = engine(helper);
			helper.assertTrue(engine.getSpeedTier() == 4,
				"the rig needs a tier 4 engine, it is tier " + engine.getSpeedTier());
			helper.assertTrue(Math.abs(engine.getTheoreticalSpeed()) == 8,
				"the rig needs the network at 8 RPM, it is at " + engine.getTheoreticalSpeed());

			helper.destroyBlock(motor);

			helper.runAfterDelay(24, () -> {
				AirEngineBlockEntity after = engine(helper);
				helper.assertTrue(after.getMode() == EngineMode.GENERATING,
					"the engine should have taken over, it is " + after.getMode());
				helper.assertTrue(Math.abs(after.getGeneratedSpeed()) == 8,
					"failover changed the network speed: it was 8 RPM and the engine now declares "
						+ Math.abs(after.getGeneratedSpeed()));
				helper.succeed();
			});
		});
	}

	/**
	 * The other half of the cap. Remembering the network's speed must not let a geared-up network
	 * raise what the engine is worth: a tier-1 engine that inherited 64 RPM would supply four times
	 * its rating, which is the gearing exploit coming back in through the failover path.
	 */
	@GameTest(template = "test_rig", timeoutTicks = 300)
	public static void aFastNetworkDoesNotRaiseTheCeiling(GameTestHelper helper) {
		floor(helper);
		// One block, so tier 1: the engine may never declare more than 16 RPM.
		helper.setBlock(VESSEL, vessel());
		helper.setBlock(ENGINE, engineFacing(Direction.EAST));
		load(helper);
		BlockPos motor = DRIVER.west();
		helper.setBlock(motor, AllBlocks.CREATIVE_MOTOR.getDefaultState()
			.setValue(BlockStateProperties.FACING, Direction.EAST));
		helper.runAfterDelay(2, () -> ((CreativeMotorBlockEntity) helper.getBlockEntity(motor))
			.generatedSpeed.setValue(64));
		fill(helper, 4000);

		helper.runAfterDelay(SETTLE_TICKS + 20, () -> {
			AirEngineBlockEntity engine = engine(helper);
			helper.assertTrue(engine.getSpeedTier() == 1,
				"the rig needs a tier 1 engine, it is tier " + engine.getSpeedTier());
			helper.assertTrue(Math.abs(engine.getTheoreticalSpeed()) == 64,
				"the rig needs the network at 64 RPM, it is at " + engine.getTheoreticalSpeed());

			helper.destroyBlock(motor);

			helper.runAfterDelay(24, () -> {
				AirEngineBlockEntity after = engine(helper);
				helper.assertTrue(after.getMode() == EngineMode.GENERATING,
					"the engine should have taken over, it is " + after.getMode());
				helper.assertTrue(Math.abs(after.getGeneratedSpeed()) == 16,
					"a tier 1 engine declared " + Math.abs(after.getGeneratedSpeed())
						+ " RPM after inheriting a 64 RPM network");
				float supplied = after.calculateAddedStressCapacity()
					* Math.abs(after.getGeneratedSpeed());
				helper.assertTrue(supplied <= after.getRatedStress() + 1,
					"the engine is supplying " + supplied + "su against a rating of "
						+ after.getRatedStress());
				helper.succeed();
			});
		});
	}

	/**
	 * The regression this model exists for. A generator contributes at the speed it <em>declares</em>,
	 * not the speed the shaft is spun at, so gearing a network up must not multiply what the engine
	 * is worth. The previous design declared the network's speed and did exactly that.
	 */
	@GameTest(template = "test_rig", timeoutTicks = 250)
	public static void ratedOutputDoesNotChangeWithNetworkSpeed(GameTestHelper helper) {
		floor(helper);
		helper.setBlock(VESSEL, vessel());
		helper.setBlock(ENGINE, engineFacing(Direction.EAST));
		helper.setBlock(DRIVER, AllBlocks.CREATIVE_MOTOR.getDefaultState()
			.setValue(BlockStateProperties.FACING, Direction.EAST));

		helper.runAfterDelay(SETTLE_TICKS + 12, () -> {
			AirEngineBlockEntity engine = engine(helper);
			float slowRating = engine.getRatedStress();
			float slowSpeed = Math.abs(engine.getTheoreticalSpeed());

			((CreativeMotorBlockEntity) helper.getBlockEntity(DRIVER)).generatedSpeed.setValue(64);

			helper.runAfterDelay(20, () -> {
				AirEngineBlockEntity after = engine(helper);
				float fastSpeed = Math.abs(after.getTheoreticalSpeed());
				helper.assertTrue(fastSpeed > slowSpeed,
					"the test needs the network to actually speed up: " + slowSpeed + " then " + fastSpeed);
				helper.assertTrue(Math.abs(after.getRatedStress() - slowRating) < 1.0F,
					"a four-fold gearing changed what the engine is worth: " + slowRating + " then "
						+ after.getRatedStress());
				helper.succeed();
			});
		});
	}

	/**
	 * An engine must join a turning shaft, not fight it — the Steam Engine's own flip. Reaching the
	 * generating path needs the shaft to stop first, because an engine with surplus available has no
	 * reason to generate: so the rig drives it backwards as a compressor, then pulls the motor. What
	 * the flip buys is that failover keeps the network turning the way it already was, instead of
	 * reversing every machine on it.
	 */
	@GameTest(template = "test_rig", timeoutTicks = 250)
	public static void theEngineFlipsToMatchAShaftAlreadyTurning(GameTestHelper helper) {
		floor(helper);
		helper.setBlock(VESSEL, vessel());
		helper.setBlock(ENGINE, engineFacing(Direction.EAST));
		// A shaft between motor and engine, so pulling the motor still leaves something to drive.
		load(helper);
		BlockPos motor = DRIVER.west();
		helper.setBlock(motor, AllBlocks.CREATIVE_MOTOR.getDefaultState()
			.setValue(BlockStateProperties.FACING, Direction.EAST));
		fill(helper, 4000);

		// Drive the shaft against the engine's natural sense.
		helper.runAfterDelay(2, () -> ((CreativeMotorBlockEntity) helper.getBlockEntity(motor))
			.generatedSpeed.setValue(-32));

		helper.runAfterDelay(SETTLE_TICKS + 12, () -> {
			AirEngineBlockEntity engine = engine(helper);
			helper.assertTrue(engine.getTheoreticalSpeed() < 0,
				"the rig needs the shaft turning negative, it is at " + engine.getTheoreticalSpeed());
			helper.assertBlockPresent(CAESBlocks.AIR_ENGINE.get(), ENGINE);

			helper.destroyBlock(motor);

			helper.runAfterDelay(24, () -> {
				AirEngineBlockEntity after = engine(helper);
				helper.assertTrue(after.getMode() == EngineMode.GENERATING,
					"with the motor gone the engine should take over, it is " + after.getMode());
				helper.assertTrue(after.getGeneratedSpeed() < 0,
					"the engine reversed the network on failover: it declares "
						+ after.getGeneratedSpeed() + " where the shaft had been turning negative");
				helper.succeed();
			});
		});
	}

	// --- the vessel ----------------------------------------------------------------------------

	/** Stacking vessels has to pool what is already in them, not strand it or duplicate it. */
	@GameTest(template = "test_rig", timeoutTicks = 200)
	public static void stackedVesselsPoolTheirAir(GameTestHelper helper) {
		floor(helper);
		helper.setBlock(VESSEL, CAESBlocks.PRESSURE_VESSEL.get().defaultBlockState());
		fill(helper, 5000);
		int single = vessel(helper).getTankInventory()
			.getCapacity();

		helper.setBlock(VESSEL.above(), CAESBlocks.PRESSURE_VESSEL.get().defaultBlockState());

		helper.runAfterDelay(SETTLE_TICKS, () -> {
			PressureVesselBlockEntity lower = helper.getBlockEntity(VESSEL);
			PressureVesselBlockEntity upper = helper.getBlockEntity(VESSEL.above());
			helper.assertTrue(lower.getController()
				.equals(upper.getController()), "both blocks should answer to one controller");

			PressureVesselBlockEntity controller = lower.getControllerBE();
			helper.assertTrue(controller.getTankInventory()
				.getCapacity() == single * 2,
				"two blocks should hold twice as much, got " + controller.getTankInventory()
					.getCapacity());
			helper.assertTrue(controller.getTankInventory()
				.getFluidAmount() == 5000,
				"the air already stored should survive the merge, got " + controller.getTankInventory()
					.getFluidAmount());

			// The lid and the floor are what stop a stack rendering as identical cubes.
			helper.assertBlockProperty(VESSEL, PressureVesselBlock.BOTTOM, true);
			helper.assertBlockProperty(VESSEL, PressureVesselBlock.TOP, false);
			helper.assertBlockProperty(VESSEL.above(), PressureVesselBlock.TOP, true);
			helper.succeed();
		});
	}

	/**
	 * The other half of the multiblock, and the half where air could go missing. Create's splitter
	 * hands the controller one block's worth and spreads the rest over the remaining parts, so a
	 * three-high vessel broken down to two must still hold everything it held.
	 */
	@GameTest(template = "test_rig", timeoutTicks = 200)
	public static void breakingAVesselSplitsTheAirRatherThanLosingIt(GameTestHelper helper) {
		floor(helper);
		for (int y = 0; y < 3; y++)
			helper.setBlock(VESSEL.above(y), CAESBlocks.PRESSURE_VESSEL.get()
				.defaultBlockState());

		helper.runAfterDelay(2, () -> {
			fill(helper, 12000);
			helper.assertTrue(air(helper) == 12000, "the stack should have taken all 12000mB");

			helper.destroyBlock(VESSEL.above(2));

			helper.runAfterDelay(SETTLE_TICKS, () -> {
				PressureVesselBlockEntity controller = vessel(helper);
				helper.assertTrue(controller.getTankInventory()
					.getCapacity() == CAESConfig.vesselCapacity() * 2,
					"two blocks are left, so capacity should have halved to two blocks' worth; it is "
						+ controller.getTankInventory()
							.getCapacity());
				helper.assertTrue(air(helper) == 12000,
					"all the air should have survived the split; " + air(helper) + "mB of 12000");
				helper.succeed();
			});
		});
	}

	/**
	 * Every footprint shares one height cap, which is the whole point of collapsing the three
	 * per-footprint caps an earlier version had.
	 *
	 * <p>The cap itself is deliberately not asserted behaviourally: it defaults to 32 and the GameTest
	 * template is six blocks tall, so a test that stacked past it would have to write outside the
	 * structure box. An earlier version of this test did exactly that, and compared a six-block stack
	 * against a limit of six — which is to say it asserted nothing at all.
	 */
	@GameTest(template = "test_rig", timeoutTicks = 200)
	public static void everyFootprintSharesOneHeightCap(GameTestHelper helper) {
		floor(helper);
		helper.setBlock(VESSEL, vessel());

		helper.runAfterDelay(SETTLE_TICKS, () -> {
			PressureVesselBlockEntity be = vessel(helper);
			int expected = CAESConfig.vesselMaxHeight();
			for (int width = 1; width <= 3; width++)
				helper.assertTrue(be.getMaxLength(Direction.Axis.Y, width) == expected,
					"a " + width + "x" + width + " vessel is capped at "
						+ be.getMaxLength(Direction.Axis.Y, width) + ", not the configured " + expected);
			helper.succeed();
		});
	}

	/** A stack within the cap forms to its full height, and the capacity follows the block count. */
	@GameTest(template = "test_rig", timeoutTicks = 200)
	public static void aStackFormsToItsFullHeight(GameTestHelper helper) {
		floor(helper);
		// Four blocks: the template's ceiling is y=5 and the vessel starts at y=1, so this is the
		// tallest stack that stays inside the structure box.
		int height = 4;
		for (int y = 0; y < height; y++)
			helper.setBlock(VESSEL.above(y), vessel());

		helper.runAfterDelay(SETTLE_TICKS, () -> {
			PressureVesselBlockEntity bottom = vessel(helper);
			helper.assertTrue(bottom.getHeight() == height,
				"expected a height of " + height + ", got " + bottom.getHeight());
			helper.assertTrue(bottom.getTankInventory()
				.getCapacity() == height * CAESConfig.vesselCapacity(),
				"capacity should follow the block count, it is " + bottom.getTankInventory()
					.getCapacity());
			helper.succeed();
		});
	}

	/**
	 * Once a vessel is wider than one block, placing against its top or bottom face lays the whole
	 * course. Nine hand-placed blocks per layer is not a mechanic, and a mis-click leaves a vessel
	 * that quietly refuses to merge.
	 */
	@GameTest(template = "test_rig", timeoutTicks = 200)
	public static void placingAgainstAWideVesselLaysAWholeCourse(GameTestHelper helper) {
		floor(helper);
		for (int x = 0; x < 2; x++)
			for (int z = 0; z < 2; z++)
				helper.setBlock(VESSEL.offset(x, 0, z), vessel());

		helper.runAfterDelay(SETTLE_TICKS, () -> {
			helper.assertTrue(vessel(helper).getWidth() == 2, "the rig needs a 2x2 to start from");

			// A plain mock player, not makeMockServerPlayerInLevel: that one is a ServerPlayer with
			// no real connection, and Create tries to sync an edge group to it on placement.
			Player player = helper.makeMockPlayer(GameType.SURVIVAL);
			ItemStack stack = new ItemStack(CAESItems.PRESSURE_VESSEL.get(), 16);
			player.setItemInHand(InteractionHand.MAIN_HAND, stack);

			// Click the top face of one corner: one placement, a whole layer expected.
			BlockPos clicked = helper.absolutePos(VESSEL);
			BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(clicked)
				.add(0, 0.5, 0), Direction.UP, clicked, false);
			CAESItems.PRESSURE_VESSEL.get()
				.place(new BlockPlaceContext(new UseOnContext(helper.getLevel(), player,
					InteractionHand.MAIN_HAND, stack, hit)));

			for (int x = 0; x < 2; x++)
				for (int z = 0; z < 2; z++)
					helper.assertBlockPresent(CAESBlocks.PRESSURE_VESSEL.get(),
						VESSEL.offset(x, 1, z));

			helper.runAfterDelay(SETTLE_TICKS, () -> {
				helper.assertTrue(vessel(helper).getHeight() == 2,
					"the new course should have joined the vessel, height is "
						+ vessel(helper).getHeight());
				helper.succeed();
			});
		});
	}

	/** A pressure vessel is not a bucket. */
	@GameTest(template = "test_rig", timeoutTicks = 100)
	public static void theVesselTakesOnlyCompressedAir(GameTestHelper helper) {
		floor(helper);
		helper.setBlock(VESSEL, CAESBlocks.PRESSURE_VESSEL.get().defaultBlockState());

		PressureVesselBlockEntity be = vessel(helper);
		helper.assertTrue(be.getTankInventory()
			.fill(new FluidStack(net.minecraft.world.level.material.Fluids.WATER, 1000), FluidAction.EXECUTE) == 0,
			"water should be refused");
		helper.assertTrue(be.getTankInventory()
			.fill(new FluidStack(CAESFluids.COMPRESSED_AIR.get(), 1000), FluidAction.EXECUTE) == 1000,
			"compressed air should be accepted");
		helper.succeed();
	}

	/** Air stored per block has to follow the configured figure, or the balance numbers mean nothing. */
	@GameTest(template = "test_rig", timeoutTicks = 100)
	public static void vesselCapacityFollowsTheConfig(GameTestHelper helper) {
		floor(helper);
		helper.setBlock(VESSEL, CAESBlocks.PRESSURE_VESSEL.get().defaultBlockState());
		helper.assertTrue(vessel(helper).getTankInventory()
			.getCapacity() == CAESConfig.vesselCapacity(),
			"one block should hold exactly the configured amount");
		helper.succeed();
	}

	// --- ponder ------------------------------------------------------------------------------

	/**
	 * The Ponder structure is generated by {@code tools/generate_ponder.py}, and a scene only loads
	 * its structure when a player opens it — a typo in a block id or a property name sits there
	 * silently until then, and the client log says nothing at startup (checked). This parses the
	 * file the same way the game will and holds every palette entry up against the real registry.
	 *
	 * <p>It cannot tell you the scene looks right. It can tell you it will not throw.
	 */
	@GameTest(template = "test_rig", timeoutTicks = 100)
	public static void thePonderStructureIsValid(GameTestHelper helper) {
		CompoundTag root;
		try (InputStream in = CAESGameTests.class
			.getResourceAsStream("/assets/createcaes/ponder/air_engine.nbt")) {
			helper.assertTrue(in != null, "the ponder structure is missing from the build");
			root = NbtIo.readCompressed(in, NbtAccounter.unlimitedHeap());
		} catch (IOException e) {
			throw new GameTestAssertException("the ponder structure would not parse: " + e);
		}

		ListTag palette = root.getList("palette", Tag.TAG_COMPOUND);
		helper.assertTrue(!palette.isEmpty(), "the structure has an empty palette");

		Set<String> seen = new HashSet<>();
		for (int i = 0; i < palette.size(); i++) {
			CompoundTag entry = palette.getCompound(i);
			String name = entry.getString("Name");
			seen.add(name);
			ResourceLocation id = ResourceLocation.parse(name);
			helper.assertTrue(BuiltInRegistries.BLOCK.containsKey(id), "no such block: " + name);

			Block block = BuiltInRegistries.BLOCK.get(id);
			CompoundTag properties = entry.getCompound("Properties");
			for (String key : properties.getAllKeys()) {
				Property<?> property = block.getStateDefinition()
					.getProperty(key);
				helper.assertTrue(property != null, name + " has no property '" + key + "'");
				helper.assertTrue(property.getValue(properties.getString(key))
					.isPresent(),
					name + "." + key + " rejects '" + properties.getString(key) + "'");
			}
		}

		// The scene destroys the motor to show the failover, and narrates over the other three.
		for (String required : new String[] { "createcaes:air_engine", "createcaes:pressure_vessel",
			"create:shaft", "create:creative_motor" })
			helper.assertTrue(seen.contains(required),
				"the scene needs a " + required + " and the structure has none");

		helper.assertTrue(root.getList("size", Tag.TAG_INT)
			.size() == 3, "the structure has no size");

		// The vessel has to arrive already formed. A ponder level never runs the connectivity
		// handler, so a vessel saved without a Controller pointer is a pile of one-block tanks whose
		// textures will not join -- which is exactly what shipped the first time.
		ListTag blocks = root.getList("blocks", Tag.TAG_COMPOUND);
		int controllers = 0;
		int parts = 0;
		for (int i = 0; i < blocks.size(); i++) {
			CompoundTag block = blocks.getCompound(i);
			String name = palette.getCompound(block.getInt("state"))
				.getString("Name");
			if (!name.equals("createcaes:pressure_vessel"))
				continue;
			CompoundTag be = block.getCompound("nbt");
			helper.assertTrue(!be.isEmpty(), "a vessel in the ponder structure has no block entity");
			helper.assertTrue(be.contains("LastKnownPos"),
				"a vessel has no LastKnownPos, so Ponder cannot re-anchor its controller");
			if (be.contains("Controller"))
				parts++;
			else {
				controllers++;
				helper.assertTrue(be.getInt("Size") > 1 && be.getInt("Height") > 1,
					"the controller should describe a multi-block vessel");
			}
		}
		helper.assertTrue(controllers == 1,
			"the scene should contain exactly one vessel controller, found " + controllers);
		helper.assertTrue(parts == 7, "expected 7 vessel parts around it, found " + parts);
		helper.succeed();
	}

	// --- staying cheap -------------------------------------------------------------------------

	/**
	 * A vessel taking in air more slowly than the engine spends it must settle, not stutter.
	 *
	 * <p>This is a performance lock, and the thing it is guarding is expensive out of all proportion
	 * to this mod. Every drop out of GENERATING takes the engine's generated speed to zero, and with
	 * nothing else on the shaft that sends Create's {@code propagateMissingSource} over the entire
	 * kinetic network — every member, with a {@code sendData} each — and the next attempt builds it
	 * all back. Before {@code NO_AIR_COOLDOWN_TICKS} and the whole-stroke affordability test, this
	 * rig changed mode 30 times in 100 ticks: one full network teardown every third tick, on a
	 * network as large as whatever the player built.
	 *
	 * <p>What this budget locks is the cooldown and the simulate-before-drain in {@code generate};
	 * removing either puts the count straight back over. It does not pin the entry threshold, because
	 * with the cooldown in place that threshold does not change how often the mode moves on this rig —
	 * anEngineWillNotStartAStrokeItCannotPayFor covers that instead, on a rig where it does.
	 *
	 * <p>Both halves are asserted: the sampled count, and the engine's own, which also sees a mode
	 * taken and given back within one tick.
	 */
	@GameTest(template = "test_rig", timeoutTicks = 300)
	public static void aTrickleFedEngineDoesNotFlapItsMode(GameTestHelper helper) {
		rig(helper);
		helper.setBlock(DRIVER, AllBlocks.ENCASED_FAN.getDefaultState()
			.setValue(BlockStateProperties.FACING, Direction.WEST));

		int[] changes = { 0 };
		EngineMode[] previous = { null };
		long[] countedAtStart = { 0 };

		// Every callback is scheduled from the test body, flat, and that matters: GameTestInfo runs
		// the body before it takes its iterator over the schedule, but a callback that schedules
		// more runs *inside* that iteration, and enough new entries rehash the map underneath it.
		// Nesting these hundred crashed the test server in three runs out of ten.
		helper.runAfterDelay(SETTLE_TICKS, () -> countedAtStart[0] = engine(helper).getModeChanges());

		for (int i = 1; i <= FLAP_SAMPLE_TICKS; i++)
			helper.runAfterDelay(SETTLE_TICKS + i, () -> {
				// A millibucket a tick, against an engine that wants a few: the shape that used to
				// flip the mode every third tick.
				fill(helper, 1);
				EngineMode now = engine(helper).getMode();
				if (previous[0] != null && previous[0] != now)
					changes[0]++;
				previous[0] = now;
			});

		helper.runAfterDelay(SETTLE_TICKS + FLAP_SAMPLE_TICKS + 1, () -> {
			helper.assertTrue(changes[0] <= FLAP_BUDGET,
				"a trickle-fed engine should settle rather than flap: " + changes[0]
					+ " mode changes in " + FLAP_SAMPLE_TICKS + " ticks, budget " + FLAP_BUDGET);
			// And the engine's own count, which unlike the sample above also sees a mode taken and
			// given back inside one tick.
			long counted = engine(helper).getModeChanges() - countedAtStart[0];
			helper.assertTrue(counted <= FLAP_BUDGET,
				"the engine counted " + counted + " mode changes in " + FLAP_SAMPLE_TICKS
					+ " ticks, budget " + FLAP_BUDGET);
			helper.succeed();
		});
	}

	/**
	 * A running vessel sweeps its parts almost never, and this is the budget that says so.
	 *
	 * <p>The only performance assertion worth having in CI is a count of work done, not a stopwatch:
	 * a count is a property of the code and comes out the same on every machine, where a microsecond
	 * budget is a property of whatever ran the build. So this counts sweeps.
	 *
	 * <p>An engine moves air on every tick it runs, so {@code onFluidStackChanged} fires 100 times in
	 * this window and the unguarded code swept on every one of them — 100 sweeps, 4,500 block entity
	 * lookups and 4,500 neighbour updates, all to publish a redstone level that had not changed.
	 * A hundred ticks of compression moves about 3% of a 45-block vessel, so the reading can move at
	 * most once. The budget is two, which leaves a 50x margin over the regression and no room for the
	 * guard to be quietly removed.
	 */
	@GameTest(template = "test_rig", timeoutTicks = 400)
	public static void aRunningVesselBarelyEverSweepsItsParts(GameTestHelper helper) {
		floor(helper);
		for (int y = 1; y <= 5; y++)
			for (int x = 4; x <= 6; x++)
				for (int z = 4; z <= 6; z++)
					helper.setBlock(new BlockPos(x, y, z), vessel());
		helper.setBlock(ENGINE, engineFacing(Direction.EAST));
		helper.setBlock(DRIVER, AllBlocks.CREATIVE_MOTOR.getDefaultState()
			.setValue(BlockStateProperties.FACING, Direction.EAST));

		long[] sweptAtStart = { 0 };
		int[] ticksCompressing = { 0 };

		helper.runAfterDelay(SETTLE_TICKS + 20,
			() -> sweptAtStart[0] = vessel(helper).getComparatorSweeps());

		// Counting the compressing ticks as well, so a version that swept rarely because it was not
		// running cannot pass by accident.
		for (int i = 1; i <= SWEEP_SAMPLE_TICKS; i++)
			helper.runAfterDelay(SETTLE_TICKS + 20 + i, () -> {
				if (engine(helper).getMode() == EngineMode.COMPRESSING)
					ticksCompressing[0]++;
			});

		helper.runAfterDelay(SETTLE_TICKS + 21 + SWEEP_SAMPLE_TICKS, () -> {
			helper.assertTrue(ticksCompressing[0] >= SWEEP_SAMPLE_TICKS - 2,
				"the engine should have been compressing throughout, was for only "
					+ ticksCompressing[0] + " of " + SWEEP_SAMPLE_TICKS + " ticks");
			long swept = vessel(helper).getComparatorSweeps() - sweptAtStart[0];
			helper.assertTrue(swept <= SWEEP_BUDGET,
				"a compressing vessel swept its parts " + swept + " times in " + SWEEP_SAMPLE_TICKS
					+ " ticks, budget " + SWEEP_BUDGET + " (unguarded this is one per tick)");
			helper.succeed();
		});
	}

	/**
	 * The comparator still hears about it — and specifically, so do the parts that are not the
	 * controller.
	 *
	 * <p>The sweep runs only when the reading moves, because unguarded it runs twenty times a second
	 * to publish a number that changed zero times in 100 ticks. This is the other half of that
	 * guard: suppressing the sweep for a level that has not moved must not suppress it for one that
	 * has.
	 *
	 * <p>Two things about this rig are load-bearing, and an earlier version of it had both wrong and
	 * passed with the sweep deleted outright.
	 *
	 * <p>The comparator sits beside the <em>upper</em> block, which is not the controller.
	 * {@code BlockEntity.setChanged} already calls {@code updateNeighbourForOutputSignal} for its own
	 * position, so the controller's neighbours are told every tick whatever this guard does — a
	 * comparator next to the controller therefore tests nothing. Telling the rest of the multiblock
	 * is the sweep's entire job.
	 *
	 * <p>And the assertion is on the level stored in the comparator's block entity, not on POWERED.
	 * POWERED is not a probe: {@code DiodeBlock.tick} powers an unpowered diode on any scheduled tick
	 * and only then schedules another to turn it back off, so it flickers true for reasons that have
	 * nothing to do with the vessel. {@code ComparatorBlockEntity.output} only moves when the
	 * comparator is genuinely re-evaluated.
	 *
	 * <p>Two smaller traps: the comparator has to sit at the same height as the block that notifies
	 * it, because {@code ComparatorBlock.onNeighborChange} ignores a neighbour at a different Y; and
	 * a diode's FACING points at what it reads, not at what it powers.
	 */
	@GameTest(template = "test_rig", timeoutTicks = 200)
	public static void aVesselKeepsTellingItsComparators(GameTestHelper helper) {
		rig(helper);
		BlockPos upper = VESSEL.above();
		helper.setBlock(upper, vessel());
		// Beside the upper course, facing north at it. A diode needs something solid underneath, and
		// andesite is a redstone conductor, so a notification from the lower course stops there
		// rather than reaching the comparator: only the sweep can tell this one.
		BlockPos comparator = upper.offset(0, 0, 1);
		helper.setBlock(comparator.below(), Blocks.POLISHED_ANDESITE);
		helper.setBlock(comparator, Blocks.COMPARATOR.defaultBlockState()
			.setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH));

		// Scheduled flat rather than nested, for the reason given in
		// aTrickleFedEngineDoesNotFlapItsMode. Two courses hold 16,000mB, so a quarter full is
		// 4,000 and reads 4 on Create's floor(frac * 14 + 1) curve.
		helper.runAfterDelay(SETTLE_TICKS, () -> {
			helper.assertTrue(vessel(helper).getBlockPos()
				.equals(helper.absolutePos(VESSEL)),
				"this test needs the lower course to be the controller, so that the comparator is "
					+ "beside a part that only the sweep will tell");
			assertComparator(helper, comparator, 0, "an empty vessel");
			fill(helper, 4000);
		});

		helper.runAfterDelay(SETTLE_TICKS + COMPARATOR_DELAY, () -> {
			assertComparator(helper, comparator, 4, "a quarter-full vessel");
			fill(helper, 12000);
		});

		helper.runAfterDelay(SETTLE_TICKS + 2 * COMPARATOR_DELAY, () -> {
			assertComparator(helper, comparator, 15, "a full vessel");
			vessel(helper).getTankInventory()
				.drain(Integer.MAX_VALUE, FluidAction.EXECUTE);
		});

		helper.runAfterDelay(SETTLE_TICKS + 3 * COMPARATOR_DELAY, () -> {
			assertComparator(helper, comparator, 0, "an emptied vessel");
			helper.succeed();
		});
	}

	/**
	 * Every drain is a whole stroke or nothing.
	 *
	 * <p>{@code generate} asks the supply what it can give before taking any of it. Draining short
	 * instead would mean the engine turned for air it never received, and {@code setMode} clears the
	 * air buffer on the way out, so the shortfall was forgiven rather than carried — measured, on a
	 * trickle, as an engine running mostly on air nothing paid for. The flap budget does not catch
	 * that on its own: restoring the partial drain while still arming the cooldown keeps the mode
	 * changes inside budget and only the air goes missing. So this watches the tank instead, and a
	 * tier-1 engine's stroke is only ever 3 or 4mB, which makes a partial one obvious.
	 */
	@GameTest(template = "test_rig", timeoutTicks = 300)
	public static void anEngineNeverDrainsAPartialStroke(GameTestHelper helper) {
		rig(helper);
		load(helper);
		// Not a whole number of strokes, so the run has to end on one it cannot afford.
		fill(helper, 100);

		int[] previous = { -1 };
		int[] smallestDrop = { Integer.MAX_VALUE };
		for (int i = 1; i <= DRAIN_SAMPLE_TICKS; i++)
			helper.runAfterDelay(SETTLE_TICKS + i, () -> {
				int now = air(helper);
				if (previous[0] > now)
					smallestDrop[0] = Math.min(smallestDrop[0], previous[0] - now);
				previous[0] = now;
			});

		helper.runAfterDelay(SETTLE_TICKS + DRAIN_SAMPLE_TICKS + 1, () -> {
			helper.assertTrue(smallestDrop[0] != Integer.MAX_VALUE,
				"the engine should have spent some air over " + DRAIN_SAMPLE_TICKS + " ticks");
			helper.assertTrue(smallestDrop[0] >= MIN_STROKE,
				"every drain should be a whole stroke; the smallest was " + smallestDrop[0] + "mB");
			helper.assertTrue(air(helper) > 0,
				"the last stroke it could not afford should have been left in the vessel");
			helper.succeed();
		});
	}

	private static void assertComparator(GameTestHelper helper, BlockPos pos, int expected,
		String what) {
		ComparatorBlockEntity be = helper.getBlockEntity(pos);
		helper.assertTrue(be.getOutputSignal() == expected,
			what + " should read " + expected + " on the comparator, read " + be.getOutputSignal());
	}

	/**
	 * An engine with no usable air never enters GENERATING, not even for a tick.
	 *
	 * <p>What this locks is that the engine stays put and says why — the existing
	 * anEmptyVesselDrivesNothing checks one instant, this watches every tick and also checks the
	 * reason the goggles would show.
	 *
	 * <p>Removing the entry gate in {@code decideMode} makes the engine take GENERATING and drop it
	 * again inside the same tick, which neither the per-tick sample below nor IdleReason can see —
	 * the cooldown the bail-out arms reports NO_AIR on the next tick just as the gate would have.
	 * That is what {@code getModeChanges()} is for, and why this asserts on it as well.
	 */
	@GameTest(template = "test_rig", timeoutTicks = 300)
	public static void anEmptyVesselNeverStartsGenerating(GameTestHelper helper) {
		rig(helper);
		load(helper);

		int[] blips = { 0 };
		for (int i = 1; i <= FLAP_SAMPLE_TICKS; i++)
			helper.runAfterDelay(SETTLE_TICKS + i, () -> {
				if (engine(helper).getMode() != EngineMode.IDLE)
					blips[0]++;
			});

		helper.runAfterDelay(SETTLE_TICKS + FLAP_SAMPLE_TICKS + 1, () -> {
			helper.assertTrue(blips[0] == 0,
				"an empty vessel should never start a stroke; the engine left IDLE on " + blips[0]
					+ " of " + FLAP_SAMPLE_TICKS + " ticks");
			// Not one mode change, ever -- not even one it took and gave back inside a tick, which
			// is what the entry gate in decideMode is for and what the sample above cannot see.
			helper.assertTrue(engine(helper).getModeChanges() == 0,
				"an empty vessel should cost no mode changes at all, counted "
					+ engine(helper).getModeChanges());
			// And it should say why, since that is what a player sees on the goggles.
			helper.assertTrue(engine(helper).getIdleReason() == IdleReason.NO_AIR,
				"it should be idle for want of air, reported " + engine(helper).getIdleReason());
			helper.succeed();
		});
	}

	/**
	 * A vessel holding less than one stroke keeps it.
	 *
	 * <p>{@code generate} asks the supply what it can give before taking any of it. Draining short
	 * instead would mean the engine turned for air it never received, and {@code setMode} clears the
	 * air buffer on the way out, so the shortfall was forgiven rather than carried — measured, on a
	 * trickle, as an engine running mostly on air nothing paid for. The dregs staying put is the
	 * visible half of that.
	 */
	@GameTest(template = "test_rig", timeoutTicks = 200)
	public static void anEngineWillNotStartAStrokeItCannotPayFor(GameTestHelper helper) {
		rig(helper);
		load(helper);
		// Less than one stroke: a tier-1 engine on a one-block vessel wants about 4mB a tick.
		fill(helper, 2);

		helper.runAfterDelay(SETTLE_TICKS + 40, () -> {
			helper.assertTrue(air(helper) == 2,
				"a vessel holding less than one stroke should keep it, holds " + air(helper) + "mB");
			helper.assertTrue(engine(helper).getMode() == EngineMode.IDLE,
				"and the engine should be idle, was " + engine(helper).getMode());
			// Nor should it have taken the mode and given it straight back: this is what pins the
			// entry gate to a whole stroke rather than to any air at all.
			helper.assertTrue(engine(helper).getModeChanges() == 0,
				"it should never have entered a mode, counted " + engine(helper).getModeChanges());
			// And it should say why, since that is what a player sees on the goggles.
			helper.assertTrue(engine(helper).getIdleReason() == IdleReason.NO_AIR,
				"it should be idle for want of air, reported " + engine(helper).getIdleReason());
			helper.succeed();
		});
	}

	/**
	 * Engines are counted on every outward face, caps included.
	 *
	 * <p>The scan walks the six outward face slabs rather than all six faces of every shell block,
	 * which is six loops with their own bounds instead of one with a {@code contains} filter. An
	 * off-by-one in any of them loses engines silently — the vessel would simply report a better
	 * efficiency than it is entitled to — and the west-wall engine the other tests use would not
	 * notice. So this one hangs an engine off a cap and off two different walls at once.
	 */
	@GameTest(template = "test_rig", timeoutTicks = 250)
	public static void enginesAreCountedOnEveryFace(GameTestHelper helper) {
		floor(helper);
		// A 3x3x2 vessel: 18 blocks, two engines' worth at the default nine blocks each.
		for (int x = 4; x <= 6; x++)
			for (int y = 1; y <= 2; y++)
				for (int z = 4; z <= 6; z++)
					helper.setBlock(new BlockPos(x, y, z), vessel());

		helper.setBlock(new BlockPos(5, 3, 5), engineFacing(Direction.DOWN));
		helper.setBlock(new BlockPos(7, 1, 5), engineFacing(Direction.WEST));
		helper.setBlock(new BlockPos(5, 1, 3), engineFacing(Direction.SOUTH));
		// A decoy: touching the vessel, but pointing away from it. Being next to a vessel is not
		// what attaches an engine to it, and counting this one would derate the other three.
		helper.setBlock(new BlockPos(5, 1, 7), engineFacing(Direction.SOUTH));

		helper.runAfterDelay(SETTLE_TICKS + 12, () -> {
			PressureVesselBlockEntity tank = vessel(helper);
			helper.assertTrue(tank.getTotalVesselSize() == 18,
				"expected an 18-block vessel, got " + tank.getTotalVesselSize());
			// Two engines' worth shared three ways. Miss one of the three and this reads 1.0; count
			// the decoy and it reads 0.5.
			float efficiency = tank.getEngineEfficiency();
			helper.assertTrue(Math.abs(efficiency - 2F / 3F) < 0.01F,
				"three engines on eighteen blocks should each get two thirds, got " + efficiency);
			helper.succeed();
		});
	}

	// --- one network, one direction ------------------------------------------------------------

	/**
	 * A kinetic network is either charging or discharging, never both at once.
	 *
	 * <p>The rig is the reported failure, reduced to the smallest thing that shows it: three engines
	 * on one shaft, two bolted to nine-block vessels and one to a single block, with air in the two
	 * big ones and none in the small one. {@code chargeMarginStress} does not refuse this. It was
	 * only ever sized to stop <em>one</em> motor covering <em>one</em> compressor of its own tier —
	 * and here the compressor is a tier below what is driving it, so its draw is a fraction of the
	 * capacity on offer: 3,641su against 8,192su, which clears the margin with room to spare. Before
	 * the coalition rule the small engine charged happily off the big one's output, turning stored
	 * air back into stored air at the round-trip loss, for ever.
	 *
	 * <p>Every tick is sampled rather than only the end state, because the interesting failure is not
	 * a steady leech but a network that spends alternate ticks charging and discharging — which a
	 * single reading at the end would miss entirely.
	 */
	@GameTest(template = "test_rig", timeoutTicks = 300)
	public static void oneNetworkNeverChargesAndDischargesAtOnce(GameTestHelper helper) {
		floor(helper);

		// Two nine-block vessels facing each other down one shaft, each with its own engine. Nine
		// blocks to one engine is full efficiency, so both of these are tier 4.
		BlockPos westVessel = new BlockPos(0, 1, 4);
		BlockPos eastVessel = new BlockPos(8, 1, 4);
		for (int x = 0; x < 3; x++)
			for (int z = 0; z < 3; z++) {
				helper.setBlock(westVessel.offset(x, 0, z), vessel());
				helper.setBlock(eastVessel.offset(x, 0, z), vessel());
			}

		BlockPos westEngine = new BlockPos(3, 1, 5);
		BlockPos eastEngine = new BlockPos(7, 1, 5);
		helper.setBlock(westEngine, engineFacing(Direction.WEST));
		helper.setBlock(eastEngine, engineFacing(Direction.EAST));
		helper.setBlock(new BlockPos(4, 1, 5), cogwheel());
		helper.setBlock(new BlockPos(5, 1, 5), shaft());
		helper.setBlock(new BlockPos(6, 1, 5), shaft());

		// The third engine hangs off a cogwheel above the first, because a shaft only ever reaches
		// the two blocks at its ends -- three engines cannot sit in one straight line. One block of
		// vessel is a ninth of an engine's worth: tier 1, and a draw well under what either of the
		// other two supplies.
		BlockPos smallVessel = new BlockPos(7, 2, 5);
		BlockPos smallEngine = new BlockPos(6, 2, 5);
		helper.setBlock(new BlockPos(4, 2, 5), cogwheel());
		helper.setBlock(new BlockPos(5, 2, 5), shaft());
		helper.setBlock(smallEngine, engineFacing(Direction.EAST));
		helper.setBlock(smallVessel, vessel());

		// And something on the network that actually wants turning, meshed onto the cogwheel stack a
		// level higher. Without it none of the three engines generates at all: engines are stores, and
		// a store's draw is not demand worth generating for, so a network of nothing but engines,
		// shafts and cogwheels asks for nothing. See aChargedEngineOnABareShaftHoldsItsAir.
		helper.setBlock(new BlockPos(4, 3, 5), cogwheel());
		helper.setBlock(new BlockPos(5, 3, 5), AllBlocks.ENCASED_FAN.getDefaultState()
			.setValue(BlockStateProperties.FACING, Direction.EAST));

		int charge = 40000;
		tankAt(helper, westVessel).fill(new FluidStack(CAESFluids.COMPRESSED_AIR.get(), charge),
			FluidAction.EXECUTE);
		tankAt(helper, eastVessel).fill(new FluidStack(CAESFluids.COMPRESSED_AIR.get(), charge),
			FluidAction.EXECUTE);

		int[] splitTicks = { 0 };
		int[] chargingTicks = { 0 };

		// Flat, never nested: a callback that schedules more runs inside GameTestInfo's own
		// iteration over the schedule, and enough new entries rehash the map underneath it.
		for (int i = 0; i <= MODE_SAMPLE_TICKS; i++)
			helper.runAfterDelay(SETTLE_TICKS + i, () -> {
				boolean generating = false;
				boolean compressing = false;
				for (BlockPos pos : List.of(westEngine, eastEngine, smallEngine)) {
					EngineMode mode = ((AirEngineBlockEntity) helper.getBlockEntity(pos)).getMode();
					generating |= mode == EngineMode.GENERATING;
					compressing |= mode == EngineMode.COMPRESSING;
				}
				if (generating && compressing)
					splitTicks[0]++;
				if (compressing)
					chargingTicks[0]++;
			});

		helper.runAfterDelay(SETTLE_TICKS + MODE_SAMPLE_TICKS + 1, () -> {
			AirEngineBlockEntity small = helper.getBlockEntity(smallEngine);

			helper.assertTrue(splitTicks[0] == 0,
				"no tick may find one engine generating while another compresses; " + splitTicks[0]
					+ " of " + MODE_SAMPLE_TICKS + " ticks did");
			helper.assertTrue(chargingTicks[0] == 0,
				"nothing on a network running on stored air may charge; something did on "
					+ chargingTicks[0] + " ticks");
			helper.assertTrue(tankAt(helper, smallVessel).getFluidAmount() == 0,
				"the small vessel should have gained nothing, holds "
					+ tankAt(helper, smallVessel).getFluidAmount() + "mB");
			// And it should say why, since that is what a player sees on the goggles.
			helper.assertTrue(small.getIdleReason() == IdleReason.NETWORK_ON_AIR,
				"the small engine should blame the network, reported " + small.getIdleReason());
			// The other half of the rule: refusing to charge must not have refused to discharge too.
			helper.assertTrue(tankAt(helper, westVessel).getFluidAmount() < charge,
				"and a charged vessel should be paying for the rotation");
			helper.succeed();
		});
	}

	/**
	 * One vessel, two engines, two separate networks — and it charges from one while discharging
	 * into the other.
	 *
	 * <p>This is the case the coalition rule must <em>not</em> catch, and the reason it is keyed on
	 * the kinetic network rather than on the vessel. A vessel standing between a network with power
	 * to spare and a network that is short of it is the mod working as intended; the engines on
	 * either side of it are strangers to each other and have no business agreeing on a direction.
	 */
	@GameTest(template = "test_rig", timeoutTicks = 250)
	public static void aVesselBuffersBetweenTwoNetworks(GameTestHelper helper) {
		floor(helper);

		BlockPos tank = new BlockPos(1, 1, 3);
		for (int x = 0; x < 3; x++)
			for (int z = 0; z < 3; z++)
				helper.setBlock(tank.offset(x, 0, z), vessel());

		// The charging side: a creative motor straight onto an engine facing down into the vessel.
		BlockPos charger = new BlockPos(2, 2, 4);
		helper.setBlock(charger, engineFacing(Direction.DOWN));
		helper.setBlock(new BlockPos(2, 3, 4), AllBlocks.CREATIVE_MOTOR.getDefaultState()
			.setValue(BlockStateProperties.FACING, Direction.DOWN));

		// The discharging side: an engine on the vessel's north wall with a shaft, and a fan on the end
		// of it so there is something that actually wants turning. Its own network, with no source and
		// no capacity of its own. The fan is not decoration: a bare shaft is no longer something an
		// engine will spend air on -- see aChargedEngineOnABareShaftHoldsItsAir.
		BlockPos discharger = new BlockPos(2, 1, 2);
		helper.setBlock(discharger, engineFacing(Direction.SOUTH));
		helper.setBlock(new BlockPos(2, 1, 1), AllBlocks.SHAFT.getDefaultState()
			.setValue(BlockStateProperties.AXIS, Direction.Axis.Z));
		helper.setBlock(new BlockPos(2, 1, 0), AllBlocks.ENCASED_FAN.getDefaultState()
			.setValue(BlockStateProperties.FACING, Direction.NORTH));

		// Enough to start a stroke on, so the discharging side does not spend the test waiting for
		// the charging side to hand it a first whole one.
		tankAt(helper, tank).fill(new FluidStack(CAESFluids.COMPRESSED_AIR.get(), 4000),
			FluidAction.EXECUTE);

		helper.runAfterDelay(SETTLE_TICKS + 30, () -> {
			AirEngineBlockEntity charging = helper.getBlockEntity(charger);
			AirEngineBlockEntity discharging = helper.getBlockEntity(discharger);

			helper.assertTrue(charging.getMode() == EngineMode.COMPRESSING,
				"the motor-driven engine should be charging the vessel, was " + charging.getMode());
			helper.assertTrue(discharging.getMode() == EngineMode.GENERATING,
				"the engine on the other network should be discharging it, was "
					+ discharging.getMode());
			helper.succeed();
		});
	}

	/**
	 * Two engines on one motor both charge, and the network-wide rule must not have stopped them.
	 *
	 * <p>The companion to {@code oneNetworkNeverChargesAndDischargesAtOnce}: a rule that refuses a
	 * network running on its own stored air has to be able to tell that apart from a network with a
	 * creative motor on it, and the allocation that shares one surplus between several compressors
	 * has to actually hand it out. Both fail closed — as an engine that quietly never charges — so
	 * neither would be noticed without this.
	 */
	@GameTest(template = "test_rig", timeoutTicks = 250)
	public static void twoCompressorsShareOneMotorsSurplus(GameTestHelper helper) {
		floor(helper);

		BlockPos westVessel = new BlockPos(2, 1, 5);
		BlockPos upperVessel = new BlockPos(7, 2, 5);
		helper.setBlock(westVessel, vessel());
		helper.setBlock(upperVessel, vessel());

		BlockPos westEngine = new BlockPos(3, 1, 5);
		BlockPos upperEngine = new BlockPos(6, 2, 5);
		helper.setBlock(westEngine, engineFacing(Direction.WEST));
		helper.setBlock(upperEngine, engineFacing(Direction.EAST));

		// One motor, two engines, joined by a pair of meshed cogwheels -- the same branch the
		// three-engine rig uses, for the same reason.
		helper.setBlock(new BlockPos(4, 1, 5), cogwheel());
		helper.setBlock(new BlockPos(5, 1, 5), shaft());
		helper.setBlock(new BlockPos(6, 1, 5), AllBlocks.CREATIVE_MOTOR.getDefaultState()
			.setValue(BlockStateProperties.FACING, Direction.WEST));
		helper.setBlock(new BlockPos(4, 2, 5), cogwheel());
		helper.setBlock(new BlockPos(5, 2, 5), shaft());

		helper.runAfterDelay(SETTLE_TICKS + 30, () -> {
			AirEngineBlockEntity west = helper.getBlockEntity(westEngine);
			AirEngineBlockEntity upper = helper.getBlockEntity(upperEngine);

			helper.assertTrue(west.getMode() == EngineMode.COMPRESSING,
				"the first engine should be charging off the motor, was " + west.getMode());
			helper.assertTrue(upper.getMode() == EngineMode.COMPRESSING,
				"the second engine should be too, was " + upper.getMode());
			helper.assertTrue(tankAt(helper, westVessel).getFluidAmount() > 0
				&& tankAt(helper, upperVessel).getFluidAmount() > 0,
				"and both vessels should be filling");
			helper.succeed();
		});
	}

	// --- rig -----------------------------------------------------------------------------------

	private static net.minecraft.world.level.block.state.BlockState vessel() {
		return CAESBlocks.PRESSURE_VESSEL.get()
			.defaultBlockState();
	}

	/** A shaft along X, which is the axis every rig in here runs its shaft line on. */
	private static net.minecraft.world.level.block.state.BlockState shaft() {
		return AllBlocks.SHAFT.getDefaultState()
			.setValue(BlockStateProperties.AXIS, Direction.Axis.X);
	}

	/**
	 * A cogwheel on the same axis. Two of these one above the other mesh, which is how a rig gets a
	 * third engine onto a shaft that only has two ends.
	 */
	private static net.minecraft.world.level.block.state.BlockState cogwheel() {
		return AllBlocks.COGWHEEL.getDefaultState()
			.setValue(BlockStateProperties.AXIS, Direction.Axis.X);
	}

	private static net.minecraft.world.level.block.state.BlockState engineFacing(Direction facing) {
		return CAESBlocks.AIR_ENGINE.get()
			.defaultBlockState()
			.setValue(AirEngineBlock.FACING, facing);
	}

	// --- the cross-mod convention ----------------------------------------------------------------

	/**
	 * The Air Engine is in {@code c:kinetic_energy_storage}, under that exact name.
	 *
	 * <p>This is the half of the convention that this mod owes everybody else. An engine's own refusal
	 * to compress on borrowed capacity is worth nothing on its own: a Gravity Battery only leaves this
	 * engine's charge alone because it can see this tag, and if the file goes missing, or the name
	 * drifts, every other addon honouring the convention goes back to winding up on this vessel's air.
	 * Nothing else in either mod would notice — which is exactly why the name is spelled out here
	 * rather than read from {@link CAESTags}, so a consistent rename across the constant and the json
	 * still fails.
	 *
	 * <p>The Pressure Vessel is deliberately <em>not</em> in the tag: it holds the air but turns
	 * nothing, and the tag classifies kinetic sources.
	 */
	@GameTest(template = "test_rig", timeoutTicks = 40)
	public static void theAirEngineDeclaresItselfAsKineticStorage(GameTestHelper helper) {
		TagKey<Block> convention = TagKey.create(Registries.BLOCK,
			ResourceLocation.fromNamespaceAndPath("c", "kinetic_energy_storage"));
		helper.assertTrue(CAESBlocks.AIR_ENGINE.get().defaultBlockState().is(convention),
			"the Air Engine must be in c:kinetic_energy_storage, or every other addon honouring the "
				+ "convention will go on compressing against its stored air");
		helper.assertTrue(!CAESBlocks.PRESSURE_VESSEL.get().defaultBlockState().is(convention),
			"the tag classifies kinetic sources, and a Pressure Vessel turns nothing");
		helper.assertTrue(convention.location().equals(CAESTags.KINETIC_ENERGY_STORAGE.location()),
			"CAESTags.KINETIC_ENERGY_STORAGE has drifted from the shared name: "
				+ CAESTags.KINETIC_ENERGY_STORAGE.location());
		helper.succeed();
	}

	/**
	 * An engine's own generation is not counted as foreign borrowing.
	 *
	 * <p>{@link AirEngineBlockEntity#foreignStoredCapacityOnNetwork()} skips Air Engines because the
	 * coalition has already taken them out of {@code externalCapacity}. Drop that exclusion and a
	 * generating engine's capacity comes off the charging balance twice, which reads as a deficit that
	 * is not there — so a network with one engine generating and real spare capacity from a motor would
	 * refuse to compress on the motor's surplus.
	 *
	 * <p>This is what the suite can reach without a second mod on the classpath. The <em>foreign</em>
	 * half — a tagged block that is not an Air Engine — is exercised by the identical scan in Create:
	 * Gravity Batteries, whose own test uses a tagged source going down the same line. Proving the pair
	 * together would need both mods in one dev runtime, which is a build-topology change rather than a
	 * test.
	 */
	@GameTest(template = "test_rig", timeoutTicks = 200)
	public static void anEnginesOwnAirIsNotCountedAsBorrowedCapacity(GameTestHelper helper) {
		rig(helper);
		// A bare shaft, so the engine is the network's only possible source and starts generating.
		load(helper);
		fill(helper, 8000);

		helper.runAfterDelay(SETTLE_TICKS + 20, () -> {
			AirEngineBlockEntity be = engine(helper);
			helper.assertTrue(be.getMode() == EngineMode.GENERATING,
				"the rig should have the engine generating, so there is capacity to miscount; it was "
					+ be.getMode() + "/" + be.getIdleReason());
			helper.assertTrue(be.foreignStoredCapacityOnNetwork() == 0,
				"an Air Engine's own capacity is the coalition's to subtract, not the tag scan's, but "
					+ "the scan claimed " + be.foreignStoredCapacityOnNetwork() + " was borrowed");
			helper.succeed();
		});
	}

	/**
	 * A cogwheel where the plain shaft used to go, with an Encased Fan meshed onto it from above —
	 * something on the network that actually wants turning.
	 *
	 * <p>Needed by every test that expects the engine to generate, because since
	 * {@code aChargedEngineOnABareShaftHoldsItsAir} an engine declines to spend air into a network where
	 * nothing is drawing. The rig used to get its generation for free from a bare shaft, and several
	 * tests said so in as many words — "a shaft the engine still has something to drive after the motor
	 * goes" was the assumption this replaces.
	 *
	 * <p>A cogwheel with a branch rather than a fan on the end of the chain, and the geometry is forced.
	 * The engine has one kinetic face, so the whole network hangs off {@link #DRIVER}; the tests that
	 * pull a motor put it at {@code DRIVER.west()}, so a load further west dies with the motor and the
	 * failover it is supposed to survive. Meshing a second cogwheel above {@code DRIVER} puts the load
	 * on a branch that outlives anything happening on the main line.
	 */
	private static void load(GameTestHelper helper) {
		helper.setBlock(DRIVER, AllBlocks.COGWHEEL.getDefaultState()
			.setValue(BlockStateProperties.AXIS, Direction.Axis.X));
		helper.setBlock(DRIVER.above(), AllBlocks.COGWHEEL.getDefaultState()
			.setValue(BlockStateProperties.AXIS, Direction.Axis.X));
		helper.setBlock(DRIVER.above().east(), AllBlocks.ENCASED_FAN.getDefaultState()
			.setValue(BlockStateProperties.FACING, Direction.EAST));
	}

	private static void floor(GameTestHelper helper) {
		for (int x = 0; x < SITE_SIZE; x++)
			for (int z = 0; z < SITE_SIZE; z++)
				helper.setBlock(new BlockPos(x, 0, z), Blocks.POLISHED_ANDESITE);
	}

	/** Engine facing east into a vessel, its shaft side left open for the test to fill in. */
	private static void rig(GameTestHelper helper) {
		floor(helper);
		helper.setBlock(VESSEL, CAESBlocks.PRESSURE_VESSEL.get().defaultBlockState());
		helper.setBlock(ENGINE, CAESBlocks.AIR_ENGINE.get().defaultBlockState()
			.setValue(AirEngineBlock.FACING, Direction.EAST));
	}

	private static AirEngineBlockEntity engine(GameTestHelper helper) {
		return helper.getBlockEntity(ENGINE);
	}

	private static PressureVesselBlockEntity vessel(GameTestHelper helper) {
		PressureVesselBlockEntity be = helper.getBlockEntity(VESSEL);
		return be.getControllerBE();
	}

	private static void fill(GameTestHelper helper, int amount) {
		vessel(helper).getTankInventory()
			.fill(new FluidStack(CAESFluids.COMPRESSED_AIR.get(), amount), FluidAction.EXECUTE);
	}

	private static int air(GameTestHelper helper) {
		return vessel(helper).getTankInventory()
			.getFluidAmount();
	}

	/** The tank of whatever vessel {@code pos} belongs to, which is its controller's and not its own. */
	private static SmartFluidTank tankAt(GameTestHelper helper, BlockPos pos) {
		return ((PressureVesselBlockEntity) helper.getBlockEntity(pos)).getControllerBE()
			.getTankInventory();
	}
}
