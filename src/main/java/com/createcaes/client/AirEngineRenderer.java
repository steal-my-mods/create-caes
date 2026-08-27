package com.createcaes.client;

import com.createcaes.engine.AirEngineBlock;
import com.createcaes.engine.AirEngineBlockEntity;
import com.createcaes.engine.EngineMode;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.simibubi.create.content.kinetics.base.KineticBlockEntityRenderer;
import com.simibubi.create.foundation.blockEntity.renderer.SafeBlockEntityRenderer;

import dev.engine_room.flywheel.lib.model.baked.PartialModel;
import net.createmod.catnip.math.AngleHelper;
import net.createmod.catnip.render.CachedBuffers;
import net.createmod.catnip.render.SuperByteBuffer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider.Context;
import net.minecraft.core.Direction;
import net.minecraft.core.Direction.AxisDirection;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Draws the flywheel and the piston rod on the CPU. The block model itself is static; only these two
 * move.
 *
 * <p>This is the <em>fallback</em> path. {@link AirEngineVisual} draws the same two partials through
 * Flywheel's instancing whenever the backend is on, which is the default, and the two are
 * line-for-line mirrors of each other on purpose — a player must not be able to tell which one drew
 * their engine. <strong>Change the geometry here and change it there.</strong>
 *
 * <p>The orientation transform is Create's — centre the model, swing it to the block's facing, then
 * do the animation in the model's own frame. Both partials are authored pointing up, so a single
 * rotate-to-facing pair covers all six.
 */
public class AirEngineRenderer extends SafeBlockEntityRenderer<AirEngineBlockEntity> {

	/** How far the rod travels, in model units (1 = a whole block). */
	private static final float PISTON_THROW = 2 / 16f;

	public AirEngineRenderer(Context context) {
	}

	@Override
	protected void renderSafe(AirEngineBlockEntity be, float partialTicks, PoseStack ms,
		MultiBufferSource buffer, int light, int overlay) {

		BlockState state = be.getBlockState();
		Direction facing = AirEngineBlock.getFacing(state);
		float angle = KineticBlockEntityRenderer.getAngleForBe(be, be.getBlockPos(), facing.getAxis());
		// The partials are authored pointing up and then swung onto the facing, so their local
		// up axis points along the facing rather than along the positive axis Create measures
		// rotation about. For the three negative facings that reverses the spin.
		if (facing.getAxisDirection() == AxisDirection.NEGATIVE)
			angle = -angle;
		VertexConsumer vb = buffer.getBuffer(RenderType.solid());

		oriented(CAESPartials.AIR_ENGINE_FLYWHEEL, state, facing).rotateYDegrees(AngleHelper.deg(angle))
			.uncenter()
			.light(light)
			.renderInto(ms, vb);

		// A quarter turn behind the crank pin, so the rod is at mid-stroke when the pin is at top.
		float stroke = Mth.sin(angle) * PISTON_THROW;
		if (be.getMode() == EngineMode.IDLE)
			stroke = 0;

		oriented(CAESPartials.AIR_ENGINE_PISTON, state, facing).uncenter()
			.translate(0F, stroke, 0F)
			.light(light)
			.renderInto(ms, vb);
	}

	/** Centred and swung to face {@code facing}, still centred so the caller can spin it. */
	private static SuperByteBuffer oriented(PartialModel model, BlockState state, Direction facing) {
		return CachedBuffers.partial(model, state)
			.center()
			.rotateYDegrees(AngleHelper.horizontalAngle(facing))
			.rotateXDegrees(AngleHelper.verticalAngle(facing) + 90);
	}

	@Override
	public int getViewDistance() {
		return 96;
	}
}
