package com.createcaes.vessel;

import com.createcaes.CAESConfig;
import com.createcaes.registry.CAESBlockEntities;
import com.simibubi.create.api.connectivity.ConnectivityHandler;
import com.simibubi.create.content.equipment.symmetryWand.SymmetryWandItem;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.nbt.CompoundTag;
import net.neoforged.neoforge.fluids.FluidStack;

/**
 * Places a whole course at a time once a vessel is more than one block wide.
 *
 * <p>Without this, growing a 3x3 means placing nine blocks by hand for every layer, and mis-clicking
 * one of them leaves a vessel that silently refuses to merge. Create's Fluid Tank and Item Vault
 * both solve it the same way and players already expect the behaviour; sneaking still places single
 * blocks.
 */
public class PressureVesselItem extends BlockItem {

	public PressureVesselItem(Block block, Properties properties) {
		super(block, properties);
	}

	@Override
	public InteractionResult place(BlockPlaceContext ctx) {
		InteractionResult initial = super.place(ctx);
		if (initial.consumesAction())
			tryPlaceWholeLayer(ctx);
		return initial;
	}

	private void tryPlaceWholeLayer(BlockPlaceContext ctx) {
		Player player = ctx.getPlayer();
		if (player == null || player.isShiftKeyDown())
			return;

		Direction face = ctx.getClickedFace();
		if (!face.getAxis()
			.isVertical())
			return;

		Level level = ctx.getLevel();
		BlockPos pos = ctx.getClickedPos();
		BlockPos placedOn = pos.relative(face.getOpposite());
		if (!(level.getBlockState(placedOn)
			.getBlock() instanceof PressureVesselBlock))
			return;
		if (SymmetryWandItem.presentInHotbar(player))
			return;

		PressureVesselBlockEntity part =
			ConnectivityHandler.partAt(CAESBlockEntities.PRESSURE_VESSEL.get(), level, placedOn);
		if (part == null)
			return;
		PressureVesselBlockEntity controller = part.getControllerBE();
		if (controller == null || controller.getWidth() == 1)
			return;

		// Only when the block just placed is itself the corner of the next course.
		BlockPos start = face == Direction.DOWN ? controller.getBlockPos()
			.below()
			: controller.getBlockPos()
				.above(controller.getHeight());
		if (start.getY() != pos.getY())
			return;

		int width = controller.getWidth();
		int needed = 0;
		for (int x = 0; x < width; x++)
			for (int z = 0; z < width; z++) {
				BlockState at = level.getBlockState(start.offset(x, 0, z));
				if (at.getBlock() instanceof PressureVesselBlock)
					continue;
				// Something is in the way: place nothing rather than a half course.
				if (!at.canBeReplaced())
					return;
				needed++;
			}

		ItemStack stack = ctx.getItemInHand();
		if (!player.isCreative() && stack.getCount() < needed)
			return;

		for (int x = 0; x < width; x++)
			for (int z = 0; z < width; z++) {
				BlockPos target = start.offset(x, 0, z);
				if (level.getBlockState(target)
					.getBlock() instanceof PressureVesselBlock)
					continue;
				super.place(BlockPlaceContext.at(ctx, target, face));
			}
	}

	/**
	 * A vessel picked up with data must forget where it used to sit, or it rejoins the world thinking
	 * it is still part of a multiblock somewhere else.
	 */
	@Override
	protected boolean updateCustomBlockEntityTag(BlockPos pos, Level level, Player player,
		ItemStack stack, BlockState state) {
		CustomData data = stack.get(DataComponents.BLOCK_ENTITY_DATA);
		if (data != null) {
			CompoundTag tag = data.copyTag();
			tag.remove("Size");
			tag.remove("Height");
			tag.remove("Controller");
			tag.remove("LastKnownPos");
			tag.remove("Engines");
			// Size and Height have just gone, so the stored amount has to be brought down to what one
			// block can hold or it arrives over capacity. Create's FluidTankItem clamps for the same
			// reason; leaving it to the read-side overflow drain would make correctness depend on the
			// order of two lines in read().
			if (tag.contains("TankContent")) {
				FluidStack stored =
					FluidStack.parseOptional(level.registryAccess(), tag.getCompound("TankContent"));
				if (!stored.isEmpty()) {
					stored.setAmount(Math.min(CAESConfig.vesselCapacity(), stored.getAmount()));
					tag.put("TankContent", stored.saveOptional(level.registryAccess()));
				}
			}
			BlockEntity.addEntityType(tag, CAESBlockEntities.PRESSURE_VESSEL.get());
			stack.set(DataComponents.BLOCK_ENTITY_DATA, CustomData.of(tag));
		}
		return super.updateCustomBlockEntityTag(pos, level, player, stack, state);
	}
}
