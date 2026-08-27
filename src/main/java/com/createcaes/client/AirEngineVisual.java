package com.createcaes.client;

import java.util.function.Consumer;

import com.createcaes.engine.AirEngineBlock;
import com.createcaes.engine.AirEngineBlockEntity;
import com.createcaes.engine.EngineMode;
import com.simibubi.create.content.kinetics.base.KineticBlockEntityRenderer;
import com.simibubi.create.content.kinetics.base.KineticBlockEntityVisual;

import dev.engine_room.flywheel.api.instance.Instance;
import dev.engine_room.flywheel.api.visual.DynamicVisual;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.instance.InstanceTypes;
import dev.engine_room.flywheel.lib.instance.TransformedInstance;
import dev.engine_room.flywheel.lib.model.Models;
import dev.engine_room.flywheel.lib.model.baked.PartialModel;
import dev.engine_room.flywheel.lib.visual.SimpleDynamicVisual;
import net.createmod.catnip.math.AngleHelper;
import net.minecraft.core.Direction;
import net.minecraft.core.Direction.AxisDirection;
import net.minecraft.util.Mth;

/**
 * The instanced half of the Air Engine's animation, and the one that normally runs.
 *
 * <p>Create pairs a Flywheel visual with a block entity renderer for every rotating block it ships —
 * {@code STEAM_ENGINE} registers {@code SteamEngineVisual} alongside {@code SteamEngineRenderer},
 * and so do {@code POWERED_SHAFT}, {@code FLYWHEEL} and the rest. The visual uploads each partial to
 * the GPU once and thereafter only writes a transform when one changes; the renderer transforms every
 * vertex of both partials on the CPU, every frame, for every engine in view. With the instancing
 * backend on — which is the default — this class is what draws the engine and
 * {@link AirEngineRenderer} is the fallback, exactly as Create arranges its own.
 *
 * <p>The geometry is deliberately a line-for-line mirror of the renderer's, because they have to
 * agree: whichever path the player's backend takes, the engine has to look the same. Change one and
 * change the other.
 */
public class AirEngineVisual extends KineticBlockEntityVisual<AirEngineBlockEntity>
	implements SimpleDynamicVisual {

	/** How far the rod travels, in model units (1 = a whole block). Matches the renderer. */
	private static final float PISTON_THROW = 2 / 16f;

	private final TransformedInstance flywheel;
	private final TransformedInstance piston;

	private final Direction facing;
	/**
	 * -1 for the three negative facings. The partials are authored pointing up and then swung onto
	 * the facing, so their local up axis points along the facing rather than along the positive axis
	 * Create measures rotation about, which reverses the spin. Same reasoning as the renderer.
	 */
	private final float spin;

	private float lastAngle = Float.NaN;
	private boolean lastIdle;

	public AirEngineVisual(VisualizationContext context, AirEngineBlockEntity blockEntity,
		float partialTick) {
		super(context, blockEntity, partialTick);
		facing = AirEngineBlock.getFacing(blockState);
		spin = facing.getAxisDirection() == AxisDirection.NEGATIVE ? -1 : 1;
		flywheel = instanceOf(CAESPartials.AIR_ENGINE_FLYWHEEL);
		piston = instanceOf(CAESPartials.AIR_ENGINE_PISTON);
		animate();
	}

	private TransformedInstance instanceOf(PartialModel model) {
		return instancerProvider().instancer(InstanceTypes.TRANSFORMED, Models.partial(model))
			.createInstance();
	}

	@Override
	public void beginFrame(DynamicVisual.Context context) {
		animate();
	}

	/**
	 * Writes both transforms, and only when something moved — a stopped engine costs nothing per
	 * frame, which is half the point of being here rather than in the renderer.
	 */
	private void animate() {
		float angle = spin * KineticBlockEntityRenderer.getAngleForBe(blockEntity, pos,
			facing.getAxis());
		boolean idle = blockEntity.getMode() == EngineMode.IDLE;
		if (angle == lastAngle && idle == lastIdle)
			return;
		lastAngle = angle;
		lastIdle = idle;

		oriented(flywheel).rotateYDegrees(AngleHelper.deg(angle))
			.uncenter()
			.setChanged();

		// A quarter turn behind the crank pin, so the rod is at mid-stroke when the pin is at top.
		float stroke = idle ? 0 : Mth.sin(angle) * PISTON_THROW;
		oriented(piston).uncenter()
			.translate(0F, stroke, 0F)
			.setChanged();
	}

	/** Centred and swung to face {@link #facing}, still centred so the caller can spin it. */
	private TransformedInstance oriented(TransformedInstance instance) {
		return instance.setIdentityTransform()
			.translate(getVisualPosition())
			.center()
			.rotateYDegrees(AngleHelper.horizontalAngle(facing))
			.rotateXDegrees(AngleHelper.verticalAngle(facing) + 90);
	}

	@Override
	public void updateLight(float partialTick) {
		relight(flywheel, piston);
	}

	@Override
	protected void _delete() {
		flywheel.delete();
		piston.delete();
	}

	@Override
	public void collectCrumblingInstances(Consumer<Instance> consumer) {
		consumer.accept(flywheel);
		consumer.accept(piston);
	}
}
