package com.createcaes.registry;

import com.createcaes.CreateCAES;

import net.minecraft.core.registries.Registries;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.level.material.Fluid;
import net.neoforged.neoforge.common.SoundActions;
import net.neoforged.neoforge.fluids.BaseFlowingFluid;
import net.neoforged.neoforge.fluids.FluidType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

/**
 * Compressed Air, the thing a Pressure Vessel actually holds.
 *
 * <p>It is a real fluid rather than a private counter on the vessel so that all of Create's fluid
 * plumbing — pipes, pumps, gauges, comparators, the multiblock's own merge-and-split rules — applies
 * to it without a line of code here. It has no block and no bucket: a gas at pressure only exists
 * inside something built to hold it, and there is deliberately no way to pour it on the floor.
 */
public class CAESFluids {

	public static final DeferredRegister<FluidType> FLUID_TYPES =
		DeferredRegister.create(NeoForgeRegistries.Keys.FLUID_TYPES, CreateCAES.ID);

	public static final DeferredRegister<Fluid> FLUIDS =
		DeferredRegister.create(Registries.FLUID, CreateCAES.ID);

	public static final DeferredHolder<FluidType, FluidType> COMPRESSED_AIR_TYPE =
		FLUID_TYPES.register("compressed_air", () -> new FluidType(FluidType.Properties.create()
			.descriptionId("fluid.createcaes.compressed_air")
			.density(400)
			.viscosity(200)
			.temperature(300)
			.canDrown(false)
			.canSwim(false)
			.canExtinguish(false)
			.supportsBoating(false)
			.canHydrate(false)
			.canPushEntity(false)
			.sound(SoundActions.BUCKET_FILL, SoundEvents.BUCKET_FILL)
			.sound(SoundActions.BUCKET_EMPTY, SoundEvents.BUCKET_EMPTY)));

	public static final DeferredHolder<Fluid, BaseFlowingFluid.Source> COMPRESSED_AIR =
		FLUIDS.register("compressed_air", () -> new BaseFlowingFluid.Source(properties()));

	public static final DeferredHolder<Fluid, BaseFlowingFluid.Flowing> FLOWING_COMPRESSED_AIR =
		FLUIDS.register("flowing_compressed_air", () -> new BaseFlowingFluid.Flowing(properties()));

	/**
	 * A fresh Properties per fluid, on purpose. The two fluids reference each other, so the
	 * properties cannot be a constant initialised before either holder exists.
	 */
	private static BaseFlowingFluid.Properties properties() {
		return new BaseFlowingFluid.Properties(COMPRESSED_AIR_TYPE, COMPRESSED_AIR, FLOWING_COMPRESSED_AIR)
			.tickRate(20);
	}
}
