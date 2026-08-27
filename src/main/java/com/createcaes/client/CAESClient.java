package com.createcaes.client;

import com.createcaes.CreateCAES;
import com.createcaes.registry.CAESBlockEntities;
import com.createcaes.registry.CAESFluids;
import com.createcaes.client.ponder.CAESPonderPlugin;
import com.simibubi.create.CreateClient;
import net.createmod.ponder.foundation.PonderIndex;
import net.createmod.ponder.foundation.registration.PonderLocalization;
import net.createmod.ponder.foundation.registration.PonderSceneRegistry;
import net.minecraft.client.resources.language.I18n;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import com.simibubi.create.foundation.block.connected.CTModel;

import dev.engine_room.flywheel.api.visualization.VisualizerRegistry;
import dev.engine_room.flywheel.lib.visualization.SimpleBlockEntityVisualizer;

import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions;
import net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent;

public class CAESClient {

	private static final ResourceLocation AIR_STILL = CreateCAES.asResource("fluid/compressed_air_still");
	private static final ResourceLocation AIR_FLOW = CreateCAES.asResource("fluid/compressed_air_flow");

	public static void init(IEventBus modBus) {
		modBus.addListener(CAESClient::registerRenderers);
		modBus.addListener(CAESClient::registerClientExtensions);
		modBus.addListener(CAESClient::clientSetup);
		CAESPartials.init();
		registerConnectedTextures();
	}

	/**
	 * Compiles this mod's Ponder scenes at startup and reports anything wrong with them.
	 *
	 * <p>Both failures this guards against are silent: a scene whose structure is missing and a scene
	 * whose text has no lang key both load a perfectly clean client and only go wrong when a player
	 * opens them — at which point they see raw translation keys, or nothing. Compiling the scene is
	 * what populates the localization map, so asking Ponder what keys it wants means running the
	 * storyboard.
	 *
	 * <p>The structure is the other half and is covered by {@code thePonderStructureIsValid}; the
	 * headless compile below does not load one.
	 *
	 * <p>Development only. With the generated files present, none of this can fire.
	 */
	private static void checkPonderScenes() {
		if (FMLEnvironment.production)
			return;
		if (!(PonderIndex.getLangAccess() instanceof PonderLocalization localization))
			return;

		// The static compileScene is the headless path -- it takes a null level, which is how
		// Create's own datagen compiles scenes to harvest their lang. Going through
		// SceneRegistryAccess.compile instead builds a PonderLevel, and that needs a world loaded,
		// so it throws at the title screen.
		int compiled = 0;
		try {
			for (var entry : PonderIndex.getSceneAccess()
				.getRegisteredEntries()) {
				if (!entry.getKey()
					.getNamespace()
					.equals(CreateCAES.ID))
					continue;
				PonderSceneRegistry.compileScene(localization, entry.getValue(), null);
				compiled++;
			}
		} catch (Exception e) {
			CreateCAES.LOGGER.warn("A Ponder scene failed to compile", e);
			return;
		}

		// A guard that passes because it inspected nothing is the failure mode it was written for.
		if (compiled == 0) {
			CreateCAES.LOGGER.warn(
				"No Ponder scene compiled for {} -- the plugin may not have registered", CreateCAES.ID);
			return;
		}

		int inspected = 0;
		for (var scene : localization.specific.entrySet()) {
			ResourceLocation sceneId = scene.getKey();
			if (!sceneId.getNamespace()
				.equals(CreateCAES.ID))
				continue;
			for (var text : scene.getValue()
				.entrySet()) {
				String langKey = sceneId.getNamespace() + ".ponder." + sceneId.getPath() + "."
					+ text.getKey();
				inspected++;
				if (!I18n.exists(langKey))
					CreateCAES.LOGGER.warn(
						"Ponder scene text has no translation: {} -- run tools/generate_ponder.py (\"{}\")",
						langKey, text.getValue());
			}
		}

		if (inspected == 0)
			CreateCAES.LOGGER.warn("{} Ponder scene(s) compiled but registered no text to check",
				compiled);
	}

	private static void registerConnectedTextures() {
		PressureVesselCTBehaviour behaviour = new PressureVesselCTBehaviour();
		CreateClient.MODEL_SWAPPER.getCustomBlockModels()
			.register(CreateCAES.asResource("pressure_vessel"), model -> new CTModel(model, behaviour));
	}

	private static boolean ponderChecked;

	private static void onClientTick(ClientTickEvent.Post event) {
		if (ponderChecked)
			return;
		ponderChecked = true;
		checkPonderScenes();
	}

	private static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
		event.registerBlockEntityRenderer(CAESBlockEntities.AIR_ENGINE.get(), AirEngineRenderer::new);
	}

	/**
	 * The instanced path for the Air Engine, and the one that normally runs.
	 *
	 * <p>Create registers a Flywheel visual next to the renderer for every rotating block it ships;
	 * it does it through Registrate's {@code .visual(...)}, which this mod does not use, so the
	 * underlying registry call is made by hand. {@code skipVanillaRender} defaults to true, which is
	 * what stops both paths drawing the engine at once — with the backend on the visual draws it,
	 * with the backend off {@link AirEngineRenderer} does.
	 */
	private static void registerVisuals() {
		SimpleBlockEntityVisualizer.builder(CAESBlockEntities.AIR_ENGINE.get())
			.factory(AirEngineVisual::new)
			.apply();

		// A visual that did not register is the same silent failure as a missing Ponder key: the
		// engine simply renders without its flywheel or its rod on every backend but the fallback,
		// and nothing is logged. Cheap to check, and this is the only check there is -- a dedicated
		// server builds no visuals at all, so no GameTest can see this.
		if (VisualizerRegistry.getVisualizer(CAESBlockEntities.AIR_ENGINE.get()) == null)
			CreateCAES.LOGGER.warn("The Air Engine's Flywheel visual did not register; it will fall "
				+ "back to the block entity renderer on every backend");
	}

	private static void clientSetup(FMLClientSetupEvent event) {
		event.enqueueWork(() -> {
			registerVisuals();
			CAESTooltips.register();
			PonderIndex.addPlugin(new CAESPonderPlugin());
			NeoForge.EVENT_BUS.addListener(CAESClient::onClientTick);
			// Compressed Air is drawn translucent wherever a tank shows its contents.
			ItemBlockRenderTypes.setRenderLayer(CAESFluids.COMPRESSED_AIR.get(), RenderType.translucent());
			ItemBlockRenderTypes.setRenderLayer(CAESFluids.FLOWING_COMPRESSED_AIR.get(), RenderType.translucent());
		});
	}

	private static void registerClientExtensions(RegisterClientExtensionsEvent event) {
		event.registerFluidType(new IClientFluidTypeExtensions() {
			@Override
			public ResourceLocation getStillTexture() {
				return AIR_STILL;
			}

			@Override
			public ResourceLocation getFlowingTexture() {
				return AIR_FLOW;
			}

			@Override
			public int getTintColor() {
				return 0x99D6ECFF;
			}
		}, CAESFluids.COMPRESSED_AIR_TYPE.get());
	}
}
