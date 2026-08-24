package com.createcaes.client.ponder;

import com.createcaes.CreateCAES;
import com.simibubi.create.foundation.ponder.PonderWorldBlockEntityFix;

import net.createmod.ponder.api.level.PonderLevel;
import net.createmod.ponder.api.registration.PonderPlugin;
import net.createmod.ponder.api.registration.PonderSceneRegistrationHelper;
import net.minecraft.resources.ResourceLocation;

public class CAESPonderPlugin implements PonderPlugin {

	@Override
	public String getModId() {
		return CreateCAES.ID;
	}

	@Override
	public void registerScenes(PonderSceneRegistrationHelper<ResourceLocation> helper) {
		helper.forComponents(CreateCAES.asResource("air_engine"))
			.addStoryBoard("air_engine", AirEngineScenes::storingSurplus);
	}

	/**
	 * A Pressure Vessel restored into a ponder level comes back at a different position than it was
	 * captured at, so every part still points its controller at the old coordinates and the
	 * multiblock reads as broken. Create's fix walks any {@code IMultiBlockEntityContainer} and
	 * re-anchors it, and ours is one — so this is a call, not a port.
	 */
	@Override
	public void onPonderLevelRestore(PonderLevel ponderLevel) {
		PonderWorldBlockEntityFix.fixControllerBlockEntities(ponderLevel);
	}
}
