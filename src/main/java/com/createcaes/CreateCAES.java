package com.createcaes;

import com.createcaes.registry.CAESBlockEntities;
import com.createcaes.registry.CAESBlocks;
import com.createcaes.registry.CAESFluids;
import com.createcaes.registry.CAESItems;

import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.loading.FMLEnvironment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Create: CAES — Compressed Air Energy Storage.
 *
 * <p>An Air Engine bolted to a Pressure Vessel watches the kinetic network it is attached to. While
 * the network has stress capacity to spare the engine runs as a compressor, drawing that surplus and
 * pumping Compressed Air into the vessel. When the rest of the network can no longer carry its own
 * load, the same block acts as a motor instead, spending stored air to hand the capacity back.
 */
@Mod(CreateCAES.ID)
public class CreateCAES {

	public static final String ID = "createcaes";
	public static final Logger LOGGER = LoggerFactory.getLogger("Create: CAES");

	public CreateCAES(IEventBus modBus, ModContainer container) {
		CAESFluids.FLUID_TYPES.register(modBus);
		CAESFluids.FLUIDS.register(modBus);
		CAESBlocks.BLOCKS.register(modBus);
		CAESBlockEntities.BLOCK_ENTITIES.register(modBus);
		CAESItems.ITEMS.register(modBus);
		CAESItems.TABS.register(modBus);

		modBus.addListener(CAESBlockEntities::registerCapabilities);
		modBus.addListener(CAESBlocks::registerStressValues);

		if (FMLEnvironment.dist == Dist.CLIENT)
			com.createcaes.client.CAESClient.init(modBus);

		container.registerConfig(ModConfig.Type.SERVER, CAESConfig.SPEC);
	}

	public static ResourceLocation asResource(String path) {
		return ResourceLocation.fromNamespaceAndPath(ID, path);
	}
}
