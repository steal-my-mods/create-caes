package com.createcaes.registry;

import com.createcaes.CreateCAES;
import com.createcaes.vessel.PressureVesselItem;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public class CAESItems {

	public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(CreateCAES.ID);

	public static final DeferredRegister<CreativeModeTab> TABS =
		DeferredRegister.create(Registries.CREATIVE_MODE_TAB, CreateCAES.ID);

	public static final DeferredItem<BlockItem> PRESSURE_VESSEL = ITEMS.registerItem("pressure_vessel",
		props -> new PressureVesselItem(CAESBlocks.PRESSURE_VESSEL.get(), props));

	public static final DeferredItem<BlockItem> AIR_ENGINE =
		ITEMS.registerSimpleBlockItem(CAESBlocks.AIR_ENGINE);

	public static final DeferredHolder<CreativeModeTab, CreativeModeTab> TAB = TABS.register("main",
		() -> CreativeModeTab.builder()
			.title(Component.translatable("itemGroup.createcaes"))
			.icon(() -> AIR_ENGINE.get()
				.getDefaultInstance())
			.displayItems((params, output) -> {
				output.accept(AIR_ENGINE.get());
				output.accept(PRESSURE_VESSEL.get());
			})
			.build());
}
