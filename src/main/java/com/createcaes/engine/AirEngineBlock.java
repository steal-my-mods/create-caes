package com.createcaes.engine;

import com.createcaes.registry.CAESBlockEntities;
import com.createcaes.registry.CAESFluids;
import com.simibubi.create.content.kinetics.base.DirectionalKineticBlock;
import com.simibubi.create.foundation.block.IBE;

import net.createmod.catnip.data.Iterate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Direction.Axis;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;

/**
 * FACING points at the air — a Pressure Vessel, or anything else that will hold Compressed Air. The
 * shaft is on the opposite face, so the block reads as an engine bolted to the side of a tank with
 * its output pointing away from it.
 */
public class AirEngineBlock extends DirectionalKineticBlock implements IBE<AirEngineBlockEntity> {

	// The housing is inset on the two axes across the shaft, which is what leaves the flywheel rim
	// and the piston rod visible from outside.
	private static final VoxelShape SHAPE_X = Block.box(0, 2, 2, 16, 14, 14);
	private static final VoxelShape SHAPE_Y = Block.box(2, 0, 2, 14, 16, 14);
	private static final VoxelShape SHAPE_Z = Block.box(2, 2, 0, 14, 14, 16);

	public AirEngineBlock(Properties properties) {
		super(properties);
	}

	public static Direction getFacing(BlockState state) {
		return state.getValue(FACING);
	}

	@Override
	public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return switch (getFacing(state).getAxis()) {
			case X -> SHAPE_X;
			case Y -> SHAPE_Y;
			case Z -> SHAPE_Z;
		};
	}

	@Override
	public boolean hasShaftTowards(LevelReader world, BlockPos pos, BlockState state, Direction face) {
		return face == getFacing(state).getOpposite();
	}

	@Override
	public Axis getRotationAxis(BlockState state) {
		return getFacing(state).getAxis();
	}

	@Override
	public BlockState getStateForPlacement(BlockPlaceContext context) {
		Level level = context.getLevel();
		BlockPos pos = context.getClickedPos();

		// Slapping it onto the face of a vessel is the obvious gesture; honour that over any shaft
		// the superclass would rather line up with.
		Direction towardsClicked = context.getClickedFace()
			.getOpposite();
		if (holdsAir(level, pos.relative(towardsClicked)))
			return defaultBlockState().setValue(FACING, towardsClicked);

		for (Direction direction : Iterate.directions)
			if (holdsAir(level, pos.relative(direction)))
				return defaultBlockState().setValue(FACING, direction);

		return super.getStateForPlacement(context);
	}

	private static boolean holdsAir(Level level, BlockPos pos) {
		IFluidHandler handler = level.getCapability(Capabilities.FluidHandler.BLOCK, pos, null);
		if (handler == null)
			return false;
		FluidStack probe = new FluidStack(CAESFluids.COMPRESSED_AIR.get(), 1);
		return handler.fill(probe, FluidAction.SIMULATE) > 0
			|| !handler.drain(probe, FluidAction.SIMULATE)
				.isEmpty();
	}

	@Override
	public Class<AirEngineBlockEntity> getBlockEntityClass() {
		return AirEngineBlockEntity.class;
	}

	@Override
	public BlockEntityType<? extends AirEngineBlockEntity> getBlockEntityType() {
		return CAESBlockEntities.AIR_ENGINE.get();
	}
}
