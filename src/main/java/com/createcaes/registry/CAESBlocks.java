package com.createcaes.registry;

import com.createcaes.CAESConfig;
import com.createcaes.CreateCAES;
import com.createcaes.engine.AirEngineBlock;
import com.createcaes.vessel.PressureVesselBlock;
import com.simibubi.create.api.stress.BlockStressValues;

import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;

public class CAESBlocks {

	public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(CreateCAES.ID);

	public static final DeferredBlock<PressureVesselBlock> PRESSURE_VESSEL =
		BLOCKS.register("pressure_vessel", () -> new PressureVesselBlock(BlockBehaviour.Properties.of()
			.mapColor(MapColor.COLOR_LIGHT_GRAY)
			.strength(3.0F, 6.0F)
			.sound(SoundType.COPPER)
			.requiresCorrectToolForDrops()));

	public static final DeferredBlock<AirEngineBlock> AIR_ENGINE =
		BLOCKS.register("air_engine", () -> new AirEngineBlock(BlockBehaviour.Properties.of()
			.mapColor(MapColor.METAL)
			.strength(3.0F, 6.0F)
			.sound(SoundType.NETHERITE_BLOCK)
			.noOcclusion()
			.requiresCorrectToolForDrops()));

	/** RPM of the Air Engine's top tier, matching the Steam Engine's own 16..64 ladder. */
	private static final int TOP_TIER_RPM = 64;

	/**
	 * What Create's item tooltip and stress readouts quote. The engine works out its real figures
	 * per tick from the size of its vessel, so these are the best case: a fully supplied engine at
	 * its top tier. Impact and capacity are registered on the same block on purpose — a dual-mode
	 * engine genuinely has both, and they are deliberately the same number.
	 *
	 * <p>{@code mayGenerateLess} is what tells Create's UI that the quoted speed is a ceiling rather
	 * than a promise, which for a tiered generator it is. The Steam Engine registers itself the same
	 * way.
	 */
	public static void registerStressValues(FMLCommonSetupEvent event) {
		event.enqueueWork(() -> {
			BlockStressValues.IMPACTS.register(AIR_ENGINE.get(), CAESBlocks::topTierRating);
			BlockStressValues.CAPACITIES.register(AIR_ENGINE.get(), CAESBlocks::topTierRating);
			BlockStressValues.setGeneratorSpeed(TOP_TIER_RPM, true)
				.accept(AIR_ENGINE.get());
		});
	}

	private static double topTierRating() {
		return CAESConfig.maxStress() / TOP_TIER_RPM;
	}
}
