package com.createcaes.registry;

import java.util.function.Supplier;

import com.createcaes.CreateCAES;
import com.createcaes.engine.AirEngineBlockEntity;
import com.createcaes.vessel.PressureVesselBlockEntity;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.registries.DeferredRegister;

public class CAESBlockEntities {

	public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
		DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, CreateCAES.ID);

	public static final Supplier<BlockEntityType<PressureVesselBlockEntity>> PRESSURE_VESSEL =
		BLOCK_ENTITIES.register("pressure_vessel",
			() -> BlockEntityType.Builder
				.of((pos, state) -> new PressureVesselBlockEntity(CAESBlockEntities.PRESSURE_VESSEL.get(), pos, state),
					CAESBlocks.PRESSURE_VESSEL.get())
				.build(null));

	public static final Supplier<BlockEntityType<AirEngineBlockEntity>> AIR_ENGINE =
		BLOCK_ENTITIES.register("air_engine",
			() -> BlockEntityType.Builder
				.of((pos, state) -> new AirEngineBlockEntity(CAESBlockEntities.AIR_ENGINE.get(), pos, state),
					CAESBlocks.AIR_ENGINE.get())
				.build(null));

	public static void registerCapabilities(RegisterCapabilitiesEvent event) {
		// Every part of a vessel answers, forwarding to the controller's tank -- that is what lets a
		// pipe or a comparator touch any block of the stack and see the whole thing.
		event.registerBlockEntity(Capabilities.FluidHandler.BLOCK, PRESSURE_VESSEL.get(),
			(be, context) -> be.getFluidCapability());
	}
}
