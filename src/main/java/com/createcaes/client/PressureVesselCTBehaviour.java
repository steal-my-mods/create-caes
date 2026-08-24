package com.createcaes.client;

import com.createcaes.CreateCAES;
import com.simibubi.create.api.connectivity.ConnectivityHandler;
import com.simibubi.create.foundation.block.connected.AllCTTypes;
import com.simibubi.create.foundation.block.connected.CTSpriteShiftEntry;
import com.simibubi.create.foundation.block.connected.CTSpriteShifter;
import com.simibubi.create.foundation.block.connected.ConnectedTextureBehaviour;

import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * Makes a stack of vessels render as one tank instead of a pile of crates.
 *
 * <p>The connection rule is the important line: two faces join when they belong to the same
 * multiblock, not merely when they are the same block. Two separate vessels built side by side hold
 * separate air and are drawn as two tanks, which is also how Create's Fluid Tank behaves.
 *
 * <p>The shift is chosen from the sprite rather than the face direction. Walls and end caps are
 * different art with different sheets, and which one a face carries is decided by the block model
 * (from the TOP/BOTTOM properties), not by which way the face points — the top face of a middle
 * block is wall, not cap.
 */
public class PressureVesselCTBehaviour extends ConnectedTextureBehaviour.Base {

	private final CTSpriteShiftEntry wall = shift("pressure_vessel_side");
	private final CTSpriteShiftEntry cap = shift("pressure_vessel_cap");

	private static CTSpriteShiftEntry shift(String texture) {
		return CTSpriteShifter.getCT(AllCTTypes.OMNIDIRECTIONAL,
			CreateCAES.asResource("block/" + texture),
			CreateCAES.asResource("block/" + texture + "_ct"));
	}

	@Override
	public CTSpriteShiftEntry getShift(BlockState state, Direction direction,
		@Nullable TextureAtlasSprite sprite) {
		if (sprite != null && cap.getOriginal() == sprite)
			return cap;
		return wall;
	}

	/**
	 * Faces buried inside the multiblock still need their context built, or the block above an
	 * exposed one reads as absent and every course grows a border along its top edge.
	 */
	@Override
	public boolean buildContextForOccludedDirections() {
		return true;
	}

	@Override
	public boolean connectsTo(BlockState state, BlockState other, BlockAndTintGetter reader,
		BlockPos pos, BlockPos otherPos, Direction face) {
		return state.getBlock() == other.getBlock()
			&& ConnectivityHandler.isConnected(reader, pos, otherPos);
	}
}
