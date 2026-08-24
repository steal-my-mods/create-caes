package com.createcaes.vessel;

import java.util.List;
import java.util.Objects;

import com.createcaes.CAESConfig;
import com.createcaes.CAESLang;
import com.createcaes.engine.AirEngineBlock;
import com.createcaes.engine.AirEngineBlockEntity;
import com.createcaes.registry.CAESFluids;
import com.simibubi.create.api.connectivity.ConnectivityHandler;
import com.simibubi.create.api.equipment.goggles.IHaveGoggleInformation;
import com.simibubi.create.foundation.blockEntity.IMultiBlockEntityContainer;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.fluid.SmartFluidTank;
import com.simibubi.create.foundation.utility.CreateLang;

import net.createmod.catnip.nbt.NBTHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Direction.Axis;
import net.minecraft.core.HolderLookup.Provider;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.IFluidTank;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;
import net.neoforged.neoforge.fluids.capability.templates.FluidTank;
import org.jetbrains.annotations.Nullable;

/**
 * One block of a Pressure Vessel. The multiblock itself is Create's — {@link ConnectivityHandler}
 * forms and splits any {@link IMultiBlockEntityContainer} whose parts share a block entity type, so
 * this class only has to say how big a vessel may get and where its air lives; the 3x3 footprint
 * rule, the controller election and the fluid merge on join are all upstream.
 *
 * <p>Only the controller holds a tank. Every other part answers capability queries by forwarding to
 * it, which is what makes a stack of these read as a single container to a pipe or a comparator.
 */
public class PressureVesselBlockEntity extends SmartBlockEntity
	implements IHaveGoggleInformation, IMultiBlockEntityContainer.Fluid {

	private static final int MAX_WIDTH = 3;
	private static final int SYNC_RATE = 8;

	protected IFluidHandler fluidCapability;
	protected final SmartFluidTank tankInventory;
	protected BlockPos controller;
	protected BlockPos lastKnownPos;
	protected boolean updateConnectivity;
	protected boolean updateCapability;
	protected int width = 1;
	protected int height = 1;

	private int syncCooldown;
	private boolean queuedSync;

	/** Air Engines drawing on this vessel. Controller only; recounted on the lazy tick. */
	protected int attachedEngines;

	public PressureVesselBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
		super(type, pos, state);
		tankInventory = new SmartFluidTank(CAESConfig.vesselCapacity(), this::onFluidStackChanged);
		tankInventory.setValidator(stack -> stack.getFluid().isSame(CAESFluids.COMPRESSED_AIR.get()));
		refreshCapability();
	}

	@Override
	public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
	}

	@Override
	public void initialize() {
		super.initialize();
		// The goggle overlay reads the tank from the client's copy, so the client needs one.
		sendData();
	}

	@Override
	public void lazyTick() {
		super.lazyTick();
		if (!level.isClientSide && isController())
			updateAttachedEngines();
	}

	/**
	 * Counts the Air Engines pointing into this vessel, the way a boiler counts the Steam Engines
	 * around it. Only the outward faces are probed — an interior block of a 3x3 has no face an
	 * engine could be attached to — so this stays cheap enough to run on the lazy tick.
	 */
	private void updateAttachedEngines() {
		int found = 0;
		for (int yOffset = 0; yOffset < height; yOffset++)
			for (int xOffset = 0; xOffset < width; xOffset++)
				for (int zOffset = 0; zOffset < width; zOffset++) {
					boolean onShell = yOffset == 0 || yOffset == height - 1 || xOffset == 0
						|| xOffset == width - 1 || zOffset == 0 || zOffset == width - 1;
					if (!onShell)
						continue;
					BlockPos part = worldPosition.offset(xOffset, yOffset, zOffset);
					for (Direction face : Direction.values()) {
						BlockPos outside = part.relative(face);
						if (contains(outside))
							continue;
						if (level.getBlockEntity(outside) instanceof AirEngineBlockEntity engine
							&& contains(outside.relative(AirEngineBlock.getFacing(engine.getBlockState()))))
							found++;
					}
				}

		if (found != attachedEngines) {
			attachedEngines = found;
			setChanged();
			sendData();
		}
	}

	private boolean contains(BlockPos pos) {
		BlockPos min = worldPosition;
		return pos.getX() >= min.getX() && pos.getX() < min.getX() + width
			&& pos.getY() >= min.getY() && pos.getY() < min.getY() + height
			&& pos.getZ() >= min.getZ() && pos.getZ() < min.getZ() + width;
	}

	/**
	 * How well this vessel can supply one engine, on Create's boiler rule: size buys you engines at
	 * full output rather than a better single engine. A vessel with
	 * {@link CAESConfig#blocksPerEngine()} blocks per attached engine runs them all at 1.0; a smaller
	 * one derates every engine on it by the same share.
	 */
	public float getEngineEfficiency() {
		PressureVesselBlockEntity controller = getControllerBE();
		if (controller == null)
			return 0;
		int engines = Math.max(1, controller.attachedEngines);
		// min(cap, size / perEngine) is Create's boiler rule -- min(18, boilerSize / 4) -- with our
		// own divisor. The cap only bites past ~162 blocks, but without it a tall enough vessel runs
		// an unbounded number of engines, which Create's does not.
		float supported = Math.min(CAESConfig.maxEnginesPerVessel(),
			(float) controller.getTotalVesselSize() / CAESConfig.blocksPerEngine());
		return Math.min(1, supported / engines);
	}

	@Override
	public void tick() {
		super.tick();

		if (syncCooldown > 0) {
			syncCooldown--;
			if (syncCooldown == 0 && queuedSync)
				sendData();
		}

		if (lastKnownPos == null) {
			lastKnownPos = getBlockPos();
		} else if (!lastKnownPos.equals(worldPosition)) {
			// Moved by a contraption. The old multi is meaningless now.
			removeController(true);
			lastKnownPos = worldPosition;
			return;
		}

		if (updateCapability) {
			updateCapability = false;
			refreshCapability();
		}

		if (updateConnectivity)
			updateConnectivity();
	}

	public void updateConnectivity() {
		updateConnectivity = false;
		if (level.isClientSide)
			return;
		if (isController())
			ConnectivityHandler.formMulti(this);
	}

	// --- multiblock plumbing ----------------------------------------------------------------

	@Override
	public BlockPos getController() {
		return isController() ? worldPosition : controller;
	}

	@Override
	public boolean isController() {
		return controller == null || worldPosition.equals(controller);
	}

	@Override
	@SuppressWarnings("unchecked")
	public PressureVesselBlockEntity getControllerBE() {
		if (isController() || !hasLevel())
			return this;
		BlockEntity be = level.getBlockEntity(controller);
		return be instanceof PressureVesselBlockEntity vessel ? vessel : null;
	}

	@Override
	public void setController(BlockPos controller) {
		if (level.isClientSide && !isVirtual())
			return;
		if (controller.equals(this.controller))
			return;
		this.controller = controller;
		refreshCapability();
		setChanged();
		sendData();
	}

	@Override
	public void removeController(boolean keepFluids) {
		if (level.isClientSide)
			return;
		updateConnectivity = true;
		if (!keepFluids)
			applyVesselSize(1);
		controller = null;
		width = 1;
		height = 1;
		onFluidStackChanged(tankInventory.getFluid());

		BlockState state = getBlockState();
		if (state.getBlock() instanceof PressureVesselBlock) {
			state = state.setValue(PressureVesselBlock.BOTTOM, true)
				.setValue(PressureVesselBlock.TOP, true);
			level.setBlock(worldPosition, state, 22);
		}

		refreshCapability();
		setChanged();
		sendData();
	}

	@Override
	public BlockPos getLastKnownPos() {
		return lastKnownPos;
	}

	@Override
	public void preventConnectivityUpdate() {
		updateConnectivity = false;
	}

	@Override
	public void notifyMultiUpdated() {
		BlockState state = getBlockState();
		if (state.getBlock() instanceof PressureVesselBlock) {
			state = state.setValue(PressureVesselBlock.BOTTOM, getController().getY() == getBlockPos().getY());
			state = state.setValue(PressureVesselBlock.TOP,
				getController().getY() + height - 1 == getBlockPos().getY());
			level.setBlock(getBlockPos(), state, 6);
		}
		onFluidStackChanged(tankInventory.getFluid());
		setChanged();
	}

	@Override
	public Axis getMainConnectionAxis() {
		return Axis.Y;
	}

	@Override
	public int getMaxLength(Axis longAxis, int width) {
		return longAxis == Axis.Y ? CAESConfig.vesselMaxHeight() : MAX_WIDTH;
	}

	@Override
	public int getMaxWidth() {
		return MAX_WIDTH;
	}

	@Override
	public int getHeight() {
		return height;
	}

	@Override
	public void setHeight(int height) {
		this.height = height;
	}

	@Override
	public int getWidth() {
		return width;
	}

	@Override
	public void setWidth(int width) {
		this.width = width;
	}

	@Override
	public boolean hasTank() {
		return true;
	}

	@Override
	public int getTankSize(int tank) {
		return CAESConfig.vesselCapacity();
	}

	@Override
	public void setTankSize(int tank, int blocks) {
		applyVesselSize(blocks);
	}

	@Override
	public IFluidTank getTank(int tank) {
		return tankInventory;
	}

	@Override
	public FluidStack getFluid(int tank) {
		return tankInventory.getFluid().copy();
	}

	// --- storage ----------------------------------------------------------------------------

	/**
	 * Capacity in millibuckets for a vessel of this many blocks.
	 *
	 * <p>Computed as a long and clamped: the configurable maxima multiply out past a signed int
	 * (2304 blocks x 1,000,000mB is about 2.3 billion), and the wrap lands negative, which
	 * {@code setCapacity} accepts and then drains the whole tank to satisfy.
	 */
	private static int capacityFor(int blocks) {
		long capacity = (long) blocks * CAESConfig.vesselCapacity();
		return (int) Math.min(capacity, Integer.MAX_VALUE);
	}

	public void applyVesselSize(int blocks) {
		tankInventory.setCapacity(capacityFor(blocks));
		int overflow = tankInventory.getFluidAmount() - tankInventory.getCapacity();
		if (overflow > 0)
			tankInventory.drain(overflow, FluidAction.EXECUTE);
	}

	public int getTotalVesselSize() {
		return width * width * height;
	}

	public SmartFluidTank getTankInventory() {
		return tankInventory;
	}

	public float getFillState() {
		int capacity = tankInventory.getCapacity();
		return capacity == 0 ? 0 : (float) tankInventory.getFluidAmount() / capacity;
	}

	protected void onFluidStackChanged(FluidStack newFluidStack) {
		if (!hasLevel())
			return;
		for (int yOffset = 0; yOffset < height; yOffset++)
			for (int xOffset = 0; xOffset < width; xOffset++)
				for (int zOffset = 0; zOffset < width; zOffset++) {
					BlockPos pos = worldPosition.offset(xOffset, yOffset, zOffset);
					PressureVesselBlockEntity partAt = ConnectivityHandler.partAt(getType(), level, pos);
					if (partAt != null)
						level.updateNeighbourForOutputSignal(pos, partAt.getBlockState().getBlock());
				}

		if (!level.isClientSide) {
			setChanged();
			sendData();
		}
	}

	void refreshCapability() {
		fluidCapability = handlerForCapability();
		invalidateCapabilities();
	}

	private IFluidHandler handlerForCapability() {
		if (isController())
			return tankInventory;
		PressureVesselBlockEntity controllerBE = getControllerBE();
		return controllerBE != null ? controllerBE.handlerForCapability() : new FluidTank(0);
	}

	@Nullable
	public IFluidHandler getFluidCapability() {
		if (fluidCapability == null)
			refreshCapability();
		return fluidCapability;
	}

	// --- sync -------------------------------------------------------------------------------

	@Override
	public void sendData() {
		if (syncCooldown > 0) {
			queuedSync = true;
			return;
		}
		super.sendData();
		queuedSync = false;
		syncCooldown = SYNC_RATE;
	}

	@Override
	protected void write(CompoundTag compound, Provider registries, boolean clientPacket) {
		if (updateConnectivity)
			compound.putBoolean("Uninitialized", true);
		if (lastKnownPos != null)
			compound.put("LastKnownPos", NbtUtils.writeBlockPos(lastKnownPos));
		if (!isController())
			compound.put("Controller", NbtUtils.writeBlockPos(controller));
		if (isController()) {
			compound.put("TankContent", tankInventory.writeToNBT(registries, new CompoundTag()));
			compound.putInt("Size", width);
			compound.putInt("Height", height);
			compound.putInt("Engines", attachedEngines);
		}
		super.write(compound, registries, clientPacket);
	}

	@Override
	protected void read(CompoundTag compound, Provider registries, boolean clientPacket) {
		super.read(compound, registries, clientPacket);

		BlockPos controllerBefore = controller;
		int prevSize = width;
		int prevHeight = height;

		updateConnectivity = compound.contains("Uninitialized");
		lastKnownPos = compound.contains("LastKnownPos") ? NBTHelper.readBlockPos(compound, "LastKnownPos") : null;
		controller = compound.contains("Controller") ? NBTHelper.readBlockPos(compound, "Controller") : null;

		if (isController()) {
			width = compound.getInt("Size");
			height = compound.getInt("Height");
			if (width == 0)
				width = 1;
			if (height == 0)
				height = 1;
			tankInventory.setCapacity(capacityFor(getTotalVesselSize()));
			attachedEngines = compound.getInt("Engines");
			tankInventory.readFromNBT(registries, compound.getCompound("TankContent"));
			if (tankInventory.getSpace() < 0)
				tankInventory.drain(-tankInventory.getSpace(), FluidAction.EXECUTE);
		}

		updateCapability = true;

		if (clientPacket) {
			boolean changeOfController = !Objects.equals(controllerBefore, controller);
			if ((changeOfController || prevSize != width || prevHeight != height) && hasLevel())
				invalidateRenderBoundingBox();
		}
	}

	@Override
	public void writeSafe(CompoundTag compound, Provider registries) {
		if (isController()) {
			compound.putInt("Size", width);
			compound.putInt("Height", height);
		}
	}

	@Override
	protected AABB createRenderBoundingBox() {
		return isController()
			? super.createRenderBoundingBox().expandTowards(width - 1, height - 1, width - 1)
			: super.createRenderBoundingBox();
	}

	/**
	 * A bespoke readout rather than Create's {@code containedFluidTooltip}. That one is right for a
	 * tank whose job is to hold whatever you put in it, and it says so — "Fluid Container:
	 * Compressed Air". A vessel only ever holds one thing, and what a player wants off it is the
	 * pressure, how big it is and what it can drive.
	 */
	@Override
	public boolean addToGoggleTooltip(List<Component> tooltip, boolean isPlayerSneaking) {
		PressureVesselBlockEntity controller = getControllerBE();
		if (controller == null)
			return false;

		FluidTank tank = controller.tankInventory;
		int stored = tank.getFluidAmount();
		int capacity = tank.getCapacity();

		CAESLang.translate("tooltip.pressure_vessel.title")
			.forGoggles(tooltip);

		CAESLang.translate("tooltip.pressure_vessel.pressure")
			.style(ChatFormatting.GRAY)
			.forGoggles(tooltip, 1);
		CreateLang.number(Math.round(controller.getFillState() * 100))
			.text("%")
			.style(pressureColour(controller.getFillState()))
			.space()
			.add(CAESLang.translate("tooltip.pressure_vessel.of_capacity",
				CreateLang.number(stored)
					.add(CreateLang.translate("generic.unit.millibuckets")),
				CreateLang.number(capacity)
					.add(CreateLang.translate("generic.unit.millibuckets")))
				.style(ChatFormatting.DARK_GRAY))
			.forGoggles(tooltip, 2);

		CAESLang.translate("tooltip.pressure_vessel.size")
			.style(ChatFormatting.GRAY)
			.forGoggles(tooltip, 1);
		CAESLang.translate("tooltip.pressure_vessel.dimensions", controller.width, controller.width,
			controller.height, controller.getTotalVesselSize())
			.style(ChatFormatting.GOLD)
			.forGoggles(tooltip, 2);

		CAESLang.translate("tooltip.pressure_vessel.engines")
			.style(ChatFormatting.GRAY)
			.forGoggles(tooltip, 1);
		if (controller.attachedEngines == 0) {
			CAESLang.translate("tooltip.pressure_vessel.no_engines")
				.style(ChatFormatting.DARK_GRAY)
				.forGoggles(tooltip, 2);
		} else {
			CAESLang.translate("tooltip.pressure_vessel.engine_count", controller.attachedEngines,
				AirEngineBlockEntity.tierFor(controller.getEngineEfficiency()))
				.style(ChatFormatting.GOLD)
				.forGoggles(tooltip, 2);
		}

		return true;
	}

	private static ChatFormatting pressureColour(float fill) {
		if (fill <= 0)
			return ChatFormatting.DARK_GRAY;
		return fill < 0.25F ? ChatFormatting.RED : ChatFormatting.AQUA;
	}
}
