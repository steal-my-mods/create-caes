package com.createcaes.vessel;

import com.createcaes.registry.CAESBlockEntities;
import com.simibubi.create.api.connectivity.ConnectivityHandler;
import com.simibubi.create.content.equipment.wrench.IWrenchable;
import com.simibubi.create.foundation.block.IBE;
import com.simibubi.create.foundation.blockEntity.ComparatorUtil;

import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition.Builder;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.Property;

/**
 * A single course of a Pressure Vessel. TOP and BOTTOM are set by the multiblock as it forms, so a
 * stack renders as one tank with a lid and a floor rather than as a column of identical cubes.
 */
public class PressureVesselBlock extends Block implements IWrenchable, IBE<PressureVesselBlockEntity> {

	public static final BooleanProperty TOP = BooleanProperty.create("top");
	public static final BooleanProperty BOTTOM = BooleanProperty.create("bottom");

	public PressureVesselBlock(Properties properties) {
		super(properties);
		registerDefaultState(defaultBlockState().setValue(TOP, true)
			.setValue(BOTTOM, true));
	}

	@Override
	protected void createBlockStateDefinition(Builder<Block, BlockState> builder) {
		builder.add(new Property[] { TOP, BOTTOM });
	}

	@Override
	public void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean moved) {
		if (oldState.getBlock() == state.getBlock() || moved)
			return;
		withBlockEntityDo(level, pos, PressureVesselBlockEntity::updateConnectivity);
		BlockState newState = level.getBlockState(pos);
		if (state != newState && newState.getBlock() == this)
			level.markAndNotifyBlock(pos, level.getChunkAt(pos), oldState, newState, 11, 512);
	}

	@Override
	public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean isMoving) {
		if (!state.hasBlockEntity() || (state.getBlock() == newState.getBlock() && newState.hasBlockEntity()))
			return;
		if (!(level.getBlockEntity(pos) instanceof PressureVesselBlockEntity vessel))
			return;
		level.removeBlockEntity(pos);
		ConnectivityHandler.splitMulti(vessel);
	}

	/**
	 * A vessel has nothing to reorient, so a plain wrench click passes through to whatever the item
	 * would otherwise do. Sneak-wrenching still dismantles it, which is the half that matters.
	 */
	@Override
	public InteractionResult onWrenched(BlockState state, UseOnContext context) {
		return InteractionResult.PASS;
	}

	@Override
	public boolean hasAnalogOutputSignal(BlockState state) {
		return true;
	}

	@Override
	public int getAnalogOutputSignal(BlockState blockState, Level level, BlockPos pos) {
		return getBlockEntityOptional(level, pos).map(PressureVesselBlockEntity::getControllerBE)
			.map(be -> ComparatorUtil.fractionToRedstoneLevel(be.getFillState()))
			.orElse(0);
	}

	@Override
	public Class<PressureVesselBlockEntity> getBlockEntityClass() {
		return PressureVesselBlockEntity.class;
	}

	@Override
	public BlockEntityType<? extends PressureVesselBlockEntity> getBlockEntityType() {
		return CAESBlockEntities.PRESSURE_VESSEL.get();
	}
}
