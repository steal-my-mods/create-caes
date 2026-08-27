package com.createcaes.engine;

import java.util.List;

import com.createcaes.CAESConfig;
import com.createcaes.CAESLang;
import com.createcaes.registry.CAESFluids;
import com.createcaes.vessel.PressureVesselBlockEntity;
import com.simibubi.create.content.kinetics.base.GeneratingKineticBlockEntity;
import com.simibubi.create.content.kinetics.base.IRotate;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.utility.CreateLang;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup.Provider;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.capabilities.BlockCapabilityCache;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;
import org.jetbrains.annotations.Nullable;

/**
 * The whole mod, really: one kinetic block that runs in both directions.
 *
 * <h2>How it decides</h2>
 * Every tick it works out the network's balance <em>excluding itself</em> — subtracting the very
 * numbers it last handed the network, so the sum is exact rather than approximate. Then:
 *
 * <ul>
 * <li>if the rest of the network cannot carry its own load, it generates;
 * <li>if the rest of the network has spare capacity for its draw <em>plus a margin</em>, it compresses;
 * <li>otherwise it idles.
 * </ul>
 *
 * <p>Excluding itself is what makes this stable. A naive engine reads total capacity minus total
 * stress, starts compressing, sees the deficit its own draw created, flips to generating, sees the
 * surplus its own capacity created, and flips back — once per tick, forever. Excluding itself means
 * the quantity it tests does not move when it acts on it, and the margin leaves a band in the middle
 * where neither test fires.
 *
 * <h2>Speed and tiers</h2>
 * Generation copies Create's Steam Engine exactly. Efficiency comes from the size of the vessel the
 * way a Steam Engine's comes from the size of its boiler — {@link PressureVesselBlockEntity#getEngineEfficiency()}
 * — and that efficiency picks one of four speed tiers, 16/32/48/64&nbsp;RPM. Capacity is divided by
 * the same tier, so an engine is worth {@code efficiency × maxStress} in total whichever tier it
 * lands on, and that figure does <em>not</em> move when the network is geared up.
 *
 * <p>That last part is the reason for the model. A generator contributes
 * {@code capacity × |getGeneratedSpeed()|}, using the speed it <em>declares</em>, not the speed the
 * shaft is spun at — measured: a 16&nbsp;RPM motor dragged to 64&nbsp;RPM by a second source still
 * contributes its own 16&nbsp;RPM worth. An engine that declared the network's speed instead would
 * be the only generator in the game whose output you could multiply with a gearbox.
 *
 * <p>Fighting the network is avoided the way the Steam Engine avoids it, by
 * {@link #alignDirectionWith(float) flipping to match} a shaft that is already turning rather than
 * by declining to have a speed of its own. {@code applyNewSpeed} destroys a generator whose sign
 * opposes a stronger network; it is perfectly happy with one that is merely slower.
 */
public class AirEngineBlockEntity extends GeneratingKineticBlockEntity {

	/**
	 * Ticks to wait before deciding anything. A block that has only just been placed has not been
	 * found by the rotation propagator yet, so it reads as having no source and no network capacity —
	 * which looks exactly like being the only thing that could turn the shaft. Without this an engine
	 * placed next to a running motor and a charged vessel spends its first tick generating.
	 */
	private static final int WARMUP_TICKS = 5;

	/** Base RPM of the lowest tier. Tiers are multiples of it, as with the Steam Engine. */
	private static final int TIER_RPM = 16;

	/**
	 * Ticks an engine waits, after a discharge stops for want of air, before trying again.
	 *
	 * <p>Not a cosmetic delay. Dropping out of GENERATING takes {@link #getGeneratedSpeed()} from
	 * non-zero to zero, and with nothing else driving the shaft that is the expensive branch of
	 * Create's {@code applyNewSpeed}: {@code detachKinetics} sends {@code RotationPropagator}'s
	 * {@code propagateMissingSource} over the whole network, calling {@code sendData} on every
	 * member it walks, and the next attempt runs {@code attachKinetics} to build all of it back
	 * again. {@code refreshKineticContribution} then recomputes the network's stress, and
	 * {@code KineticNetwork.calculateStress} walks every member with a {@code getBlockEntity}
	 * apiece. The cost of one flip is therefore O(the player's factory), not O(this engine).
	 *
	 * <p>Measured, on a vessel taking in air more slowly than the engine spends it: 30 mode changes
	 * in 100 ticks, one every third tick, each one of those teardowns. {@link CAESConfig#chargeMarginStress()}
	 * is the deadband between compressing and generating; this is the deadband between generating
	 * and giving up. The mod needs both, and for the same reason.
	 *
	 * <p>This is the guard that bounds the churn, and {@code aTrickleFedEngineDoesNotFlapItsMode}
	 * fails without it. The whole-stroke affordability test in {@link #decideMode} is its cheaper
	 * companion: it keeps the engine from taking a mode it would leave again on the same tick, which
	 * next to a merely empty vessel is two {@code updateGeneratedRotation} calls — an attach and a
	 * detach of the rotation network — once per cooldown, for ever.
	 *
	 * <p>That companion's whole effect is a mode change that begins and ends inside one tick, so
	 * neither a per-tick sample of the mode nor {@link IdleReason} can see it — the cooldown that
	 * {@link #generate}'s bail-out arms reports NO_AIR on the following tick exactly as the gate
	 * would have. {@link #getModeChanges()} is what makes it visible, and
	 * {@code anEmptyVesselNeverStartsGenerating} and {@code anEngineWillNotStartAStrokeItCannotPayFor}
	 * both require it to be zero. Lowering the threshold to a single millibucket fails the second;
	 * deleting the gate fails both.
	 */
	private static final int NO_AIR_COOLDOWN_TICKS = 20;

	private EngineMode mode = EngineMode.IDLE;
	private IdleReason idleReason = IdleReason.NONE;
	private int warmup = WARMUP_TICKS;
	/** Set when the engine has flipped to agree with a shaft that was already turning. */
	private boolean reversed;
	/**
	 * The last speed this engine saw the network running at under someone else's power. Generating
	 * is capped to it so a failover does not change how fast the factory runs — see
	 * {@link #getGeneratedSpeed()}.
	 */
	private float rememberedSpeed;
	/**
	 * Worked out on the server once per tick and synced. It has to be a field rather than a live
	 * lookup because half of what feeds it — the fluid capability in front — does not exist on the
	 * client, and the goggle overlay reads the tier from the client's copy.
	 */
	private float efficiency;

	/** Millibuckets owed but not yet whole. Rates are fractional; tank transfers are integers. */
	private float airBuffer;
	/** Air moved on the last tick, in mB. Display only, synced for the goggle overlay. */
	private float airRate;
	/** Ticks left before a discharge that ran dry may start again. See {@link #NO_AIR_COOLDOWN_TICKS}. */
	private int airCooldown;

	/**
	 * How many times this engine has changed mode.
	 *
	 * <p>Diagnostic, and what the GameTests budget in place of wall-clock. A mode change is the
	 * expensive event here — see {@link #NO_AIR_COOLDOWN_TICKS} for what one costs — and counting them
	 * is machine-independent in a way that timing them is not. It also sees what sampling
	 * {@link #getMode()} once a tick cannot: a mode taken and given back inside the same tick, which
	 * is exactly the shape the entry gate in {@link #decideMode} exists to prevent.
	 *
	 * <p>Not persisted and not synced: it is a rate, not state.
	 */
	private long modeChanges;

	public long getModeChanges() {
		return modeChanges;
	}

	@Nullable
	private BlockCapabilityCache<IFluidHandler, Direction> airCache;
	@Nullable
	private BlockPos cachedAirPos;

	public AirEngineBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
		super(type, pos, state);
	}

	@Override
	public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
	}

	public EngineMode getMode() {
		return mode;
	}

	/**
	 * Why this engine is idle, if it is. Diagnostic — nothing branches on it — but it is what the
	 * goggles show, and it is also the only outward sign of which gate in {@link #decideMode}
	 * stopped a discharge: an engine that never entered GENERATING reports NO_AIR, whereas one that
	 * entered and bailed out of {@link #generate} still reports NONE.
	 */
	public IdleReason getIdleReason() {
		return idleReason;
	}

	// --- kinetics ---------------------------------------------------------------------------

	/** How well the attached air supply can serve this engine, 0..1. */
	public float getEfficiency() {
		return efficiency;
	}

	/**
	 * A vessel works efficiency out from its own size and how many engines are sharing it; a pipe has
	 * no size to read, so a pipe-fed engine gets the flat {@link CAESConfig#pipeFedEfficiency()} —
	 * the same allowance Create gives a boiler that is not being heated.
	 */
	private float computeEfficiency() {
		PressureVesselBlockEntity vessel = getAttachedVessel();
		if (vessel != null)
			return Mth.clamp(vessel.getEngineEfficiency(), 0, 1);
		return getAirHandler() != null ? CAESConfig.pipeFedEfficiency() : 0;
	}

	/** 1..4, the same curve the Steam Engine uses to turn boiler efficiency into a speed tier. */
	public int getSpeedTier() {
		return tierFor(getEfficiency());
	}

	/** Create's own boiler-efficiency-to-speed-tier curve. Shared so the vessel can quote it too. */
	public static int tierFor(float supply) {
		if (supply <= 0)
			return 0;
		return (int) (1 + (supply >= 1 ? 3 : Math.min(2, Math.floor(supply * 4))));
	}

	/** Total stress this engine is worth when generating, flat across its tier. */
	public float getRatedStress() {
		return getEfficiency() * CAESConfig.maxStress();
	}

	/**
	 * The tier is a ceiling, not a target.
	 *
	 * <p>An engine that always declared its tier speed would yank the whole network up to it the
	 * moment it took over — measured, an 8 RPM network jumping to 64 the instant the source died,
	 * which is every belt and every machine on it changing pace at once. Capping to the speed the
	 * network was already running at costs nothing: whether the engine can carry the load is decided
	 * by {@link #ratingPerRpm()}, and the load scales with speed in exactly the same way it does, so
	 * the coverage at 8 RPM and at 64 RPM is identical. The tier only decides how fast this engine
	 * can drive a network that has no speed of its own yet.
	 *
	 * <p>Capping cannot be geared around, because it is a {@code min} against the tier: a faster
	 * network raises the remembered speed but not the ceiling.
	 */
	@Override
	public float getGeneratedSpeed() {
		if (mode != EngineMode.GENERATING)
			return 0;
		int tier = getSpeedTier();
		if (tier == 0)
			return 0;
		float ceiling = TIER_RPM * tier;
		float target = rememberedSpeed > 0 ? Math.min(ceiling, rememberedSpeed) : ceiling;
		return direction() * target;
	}

	/**
	 * Compressing costs what generating pays, at the same tier and the same speed. Keeping the two
	 * ratings identical is what makes {@link CAESConfig#chargeMarginStress()} sufficient to refuse a
	 * self-charging loop: a motor cannot cover a compressor of its own tier plus the margin.
	 */
	private float ratingPerRpm() {
		int tier = getSpeedTier();
		return tier == 0 ? 0 : getRatedStress() / (TIER_RPM * tier);
	}

	@Override
	public float calculateStressApplied() {
		float impact = mode == EngineMode.COMPRESSING ? ratingPerRpm() : 0;
		this.lastStressApplied = impact;
		return impact;
	}

	@Override
	public float calculateAddedStressCapacity() {
		float added = mode == EngineMode.GENERATING ? ratingPerRpm() : 0;
		this.lastCapacityProvided = added;
		return added;
	}

	private int direction() {
		int base = convertToDirection(1, AirEngineBlock.getFacing(getBlockState())) > 0 ? 1 : -1;
		return reversed ? -base : base;
	}

	/**
	 * Records how fast the network runs when something else is driving it.
	 *
	 * <p>Only when something else is driving: while this engine is the source, the speed it reads is
	 * its own output, and remembering that would pin the ceiling to whatever it happened to settle on
	 * rather than to what the network is really for.
	 */
	private void rememberNetworkSpeed() {
		if (!hasSource())
			return;
		float observed = Math.abs(getTheoreticalSpeed());
		if (observed > 0 && observed != rememberedSpeed) {
			rememberedSpeed = observed;
			setChanged();
		}
	}

	/**
	 * Never fight a shaft that is already turning. This is the Steam Engine's own answer to the
	 * problem — it flips its rotation setting rather than asserting a direction that would have
	 * {@code applyNewSpeed} destroy the block.
	 */
	private void alignDirectionWith(float shaftSpeed) {
		if (shaftSpeed == 0)
			return;
		if (shaftSpeed > 0 != direction() > 0) {
			reversed = !reversed;
			setChanged();
		}
	}

	/**
	 * What this engine is currently contributing to the network's stress total — the value the
	 * network actually has recorded for it, not a fresh calculation, so subtracting it leaves
	 * exactly what everything else is doing.
	 */
	private float ownStress() {
		return lastStressApplied * Math.abs(getTheoreticalSpeed());
	}

	private float ownCapacity() {
		return lastCapacityProvided * Math.abs(getGeneratedSpeed());
	}

	public float networkCapacityWithoutSelf() {
		return capacity - ownCapacity();
	}

	public float networkStressWithoutSelf() {
		return stress - ownStress();
	}

	// --- the loop ---------------------------------------------------------------------------

	@Override
	public void tick() {
		super.tick();
		if (level == null || level.isClientSide)
			return;

		// Worked out before the warm-up gate: the goggle overlay should read a sensible tier from
		// the moment the block is placed, even while it is still deciding what to do.
		float freshEfficiency = computeEfficiency();
		if (Math.abs(freshEfficiency - efficiency) > 1.0E-4F) {
			efficiency = freshEfficiency;
			// The tier may have moved with it, so the network needs re-telling -- but the mode has
			// not changed, so this must not go through setMode and reset the air buffer.
			if (mode != EngineMode.IDLE)
				refreshKineticContribution();
			sendData();
		}

		if (warmup > 0) {
			warmup--;
			return;
		}

		if (airCooldown > 0)
			airCooldown--;

		alignDirectionWith(getTheoreticalSpeed());
		rememberNetworkSpeed();

		IFluidHandler air = getAirHandler();
		EngineMode desired = decideMode(air);
		if (desired != mode)
			setMode(desired);

		switch (mode) {
			case COMPRESSING -> compress(air);
			case GENERATING -> generate(air);
			case IDLE -> setAirRate(0);
		}
	}

	private EngineMode decideMode(@Nullable IFluidHandler air) {
		if (!IRotate.StressImpact.isEnabled())
			return idle(IdleReason.NONE);
		if (air == null)
			return idle(IdleReason.NO_SUPPLY);
		// No rating means no mode it could act on: it would report "Generating" while supplying
		// nothing, or blame the network for a shortfall that is really an empty tier. Unreachable at
		// the default config -- a vessel always gives at least 1/blocksPerEngine and a pipe gives
		// pipeFedEfficiency -- so it is defence for someone setting pipeFedEfficiency to 0, and is
		// **not covered by a test** for that reason.
		if (getSpeedTier() == 0)
			return idle(IdleReason.NO_SUPPLY);

		float headroom = networkCapacityWithoutSelf() - networkStressWithoutSelf();

		// Nothing else on this network can turn it, and there is something attached worth turning.
		// The second half matters: without it a charged engine sitting in a chest room would spin
		// itself against nothing and quietly empty its vessel.
		boolean soleSource =
			!hasSource() && networkCapacityWithoutSelf() <= 0 && hasSomethingToDrive();

		boolean wantsToGenerate = headroom < 0 || soleSource;
		if (wantsToGenerate) {
			// Two gates, and both are hysteresis rather than gameplay. The cooldown keeps a supply
			// that cannot keep up from flipping the mode every few ticks, and the affordability test
			// asks for a whole stroke rather than the single millibucket this used to accept -- an
			// engine that cannot pay for one tick has no business starting one. Continuing a
			// discharge asks for neither: it runs until generate() finds it cannot pay, which is
			// what makes this a band and not just a higher threshold.
			if (mode != EngineMode.GENERATING
				&& (airCooldown > 0 || !canDraw(air, strokeCost())))
				return idle(IdleReason.NO_AIR);
			idleReason = IdleReason.NONE;
			return EngineMode.GENERATING;
		}

		float speed = Math.abs(getTheoreticalSpeed());
		if (speed == 0)
			return idle(IdleReason.NOT_TURNING);
		if (!hasRoom(air))
			return idle(IdleReason.VESSEL_FULL);

		float draw = getCompressorDraw();
		if (draw > 0 && headroom >= draw + CAESConfig.chargeMarginStress()) {
			idleReason = IdleReason.NONE;
			return EngineMode.COMPRESSING;
		}
		return idle(IdleReason.NO_SURPLUS);
	}

	private EngineMode idle(IdleReason reason) {
		if (idleReason != reason) {
			idleReason = reason;
			sendData();
		}
		return EngineMode.IDLE;
	}

	/** Stress this engine would draw if it were compressing at the shaft's current speed. */
	public float getCompressorDraw() {
		return ratingPerRpm() * Math.abs(getTheoreticalSpeed());
	}

	/** Whether the shaft side actually has a kinetic block on it that would take the rotation. */
	private boolean hasSomethingToDrive() {
		Direction shaftSide = AirEngineBlock.getFacing(getBlockState())
			.getOpposite();
		BlockPos neighbour = worldPosition.relative(shaftSide);
		BlockState state = level.getBlockState(neighbour);
		return state.getBlock() instanceof IRotate rotate
			&& rotate.hasShaftTowards(level, neighbour, state, shaftSide.getOpposite());
	}

	private void setMode(EngineMode next) {
		modeChanges++;
		mode = next;
		// Only on a real change: a compressor's leftovers must not be spent as a motor's first drink.
		airBuffer = 0;
		refreshKineticContribution();
		setChanged();
		sendData();
	}

	/**
	 * Re-tells the network what this engine is worth. Also what starts or stops the shaft when this
	 * engine is the network's root, since {@code updateGeneratedRotation} is where that happens.
	 */
	private void refreshKineticContribution() {
		updateGeneratedRotation();
		// updateGeneratedRotation only refreshes stress while its speed is non-zero, so releasing a
		// compressor's load on a network this engine does not drive has to be done here.
		if (hasNetwork()) {
			getOrCreateNetwork().updateStressFor(this, calculateStressApplied());
			getOrCreateNetwork().updateStress();
		}
	}

	private void compress(@Nullable IFluidHandler air) {
		if (air == null)
			return;
		// getSpeed, not getTheoreticalSpeed: an overstressed network is not turning, so it is not
		// doing any compressing either. In steady state the two agree by construction -- the charge
		// test excludes this engine's own draw, so a compressor cannot be the thing that overstressed
		// its network -- and this only bites on the tick another machine starts up and the balance
		// has not caught up yet. It is not separately observable in a test for that reason.
		float drawn = ratingPerRpm() * Math.abs(getSpeed());
		float mb = drawn * CAESConfig.airPerStressUnit() * CAESConfig.roundTripEfficiency();
		setAirRate(mb);

		airBuffer += mb;
		int whole = (int) airBuffer;
		if (whole <= 0)
			return;

		int filled = air.fill(new FluidStack(CAESFluids.COMPRESSED_AIR.get(), whole), FluidAction.EXECUTE);
		airBuffer -= filled;
	}

	private void generate(@Nullable IFluidHandler air) {
		if (air == null)
			return;
		float mb = generationDraw();
		setAirRate(-mb);

		airBuffer += mb;
		int whole = (int) airBuffer;
		if (whole <= 0)
			return;

		// Ask before taking. Draining short would mean the engine turned for air it never got, and
		// setMode clears the buffer on the way out, so the shortfall was forgiven rather than
		// carried -- measured, an engine on a 1mB/tick trickle running mostly on air nothing paid
		// for. A stroke it cannot afford is not started, and the dregs stay in the vessel until
		// there is enough for a whole one. One stack, both calls: drain does not modify its
		// argument.
		FluidStack stroke = new FluidStack(CAESFluids.COMPRESSED_AIR.get(), whole);
		if (air.drain(stroke, FluidAction.SIMULATE)
			.getAmount() < whole) {
			airCooldown = NO_AIR_COOLDOWN_TICKS;
			setMode(EngineMode.IDLE);
			return;
		}
		airBuffer -= air.drain(stroke, FluidAction.EXECUTE)
			.getAmount();
	}

	/**
	 * Air this engine spends on one tick of generating, in mB.
	 *
	 * <p>Only what the network is actually asking of it, but never nothing: a shaft spinning on
	 * stored air has to cost something or the vessel would be a perpetual motion machine.
	 */
	private float generationDraw() {
		float rated = getRatedStress();
		float deficit = networkStressWithoutSelf() - networkCapacityWithoutSelf();
		float supplied = Mth.clamp(deficit, rated * CAESConfig.idleAirDraw(), rated);
		return supplied * CAESConfig.airPerStressUnit();
	}

	/** One whole stroke's worth of air, rounded up — what starting a discharge has to afford. */
	private int strokeCost() {
		return Math.max(1, (int) Math.ceil(generationDraw()));
	}

	private void setAirRate(float rate) {
		// Only worth a packet when it changes visibly; this runs every tick.
		if (Math.abs(rate - airRate) > 0.05f) {
			airRate = rate;
			sendData();
		}
	}

	// --- the vessel in front ------------------------------------------------------------------

	/**
	 * Any fluid handler on the face this engine points at, not only a Pressure Vessel. Compressed Air
	 * is a real fluid, so a Create pump and a length of pipe are a perfectly good way to reach a
	 * vessel that is not bolted directly to the engine.
	 */
	/** The Pressure Vessel this engine is bolted to, if it is bolted to one at all. */
	@Nullable
	public PressureVesselBlockEntity getAttachedVessel() {
		if (level == null)
			return null;
		BlockPos target = worldPosition.relative(AirEngineBlock.getFacing(getBlockState()));
		return level.getBlockEntity(target) instanceof PressureVesselBlockEntity vessel
			? vessel.getControllerBE()
			: null;
	}

	@Nullable
	public IFluidHandler getAirHandler() {
		if (!(level instanceof ServerLevel serverLevel))
			return null;
		BlockPos target = worldPosition.relative(AirEngineBlock.getFacing(getBlockState()));
		if (airCache == null || !target.equals(cachedAirPos)) {
			cachedAirPos = target;
			// The validity supplier has to go false when the engine is turned, or every facing it has
			// ever pointed in leaves a live invalidation listener on the level until the block goes.
			airCache = BlockCapabilityCache.create(Capabilities.FluidHandler.BLOCK, serverLevel, target,
				AirEngineBlock.getFacing(getBlockState())
					.getOpposite(),
				() -> !isRemoved() && target.equals(cachedAirPos), () -> {
				});
		}
		return airCache.getCapability();
	}

	/** Whether the supply could hand over {@code amount} mB right now, in full. */
	private static boolean canDraw(IFluidHandler handler, int amount) {
		return handler
			.drain(new FluidStack(CAESFluids.COMPRESSED_AIR.get(), amount), FluidAction.SIMULATE)
			.getAmount() >= amount;
	}

	private static boolean hasRoom(IFluidHandler handler) {
		return handler.fill(new FluidStack(CAESFluids.COMPRESSED_AIR.get(), 1), FluidAction.SIMULATE) > 0;
	}

	// --- persistence ---------------------------------------------------------------------------

	@Override
	protected void write(CompoundTag compound, Provider registries, boolean clientPacket) {
		compound.putInt("Mode", mode.ordinal());
		compound.putInt("IdleReason", idleReason.ordinal());
		compound.putBoolean("Reversed", reversed);
		compound.putFloat("RememberedSpeed", rememberedSpeed);
		compound.putFloat("Efficiency", efficiency);
		compound.putFloat("AirRate", airRate);
		if (!clientPacket) {
			compound.putFloat("AirBuffer", airBuffer);
			compound.putInt("AirCooldown", airCooldown);
		}
		super.write(compound, registries, clientPacket);
	}

	@Override
	protected void read(CompoundTag compound, Provider registries, boolean clientPacket) {
		super.read(compound, registries, clientPacket);
		mode = EngineMode.byOrdinal(compound.getInt("Mode"));
		idleReason = IdleReason.byOrdinal(compound.getInt("IdleReason"));
		reversed = compound.getBoolean("Reversed");
		rememberedSpeed = compound.getFloat("RememberedSpeed");
		efficiency = compound.getFloat("Efficiency");
		airRate = compound.getFloat("AirRate");
		if (!clientPacket) {
			airBuffer = compound.getFloat("AirBuffer");
			airCooldown = compound.getInt("AirCooldown");
		}
	}

	@Override
	protected AABB createRenderBoundingBox() {
		// The piston rod and the crank pin both sit against the edge of the block.
		return super.createRenderBoundingBox().inflate(0.5);
	}

	// --- goggles ---------------------------------------------------------------------------------

	@Override
	public boolean addToGoggleTooltip(List<Component> tooltip, boolean isPlayerSneaking) {
		CAESLang.translate("tooltip.air_engine.title")
			.forGoggles(tooltip);
		CAESLang.translate("tooltip.air_engine.mode")
			.style(ChatFormatting.GRAY)
			.forGoggles(tooltip, 1);
		CAESLang.translate(mode.translationKey())
			.style(modeColour())
			.forGoggles(tooltip, 2);

		CAESLang.translate("tooltip.air_engine.tier")
			.style(ChatFormatting.GRAY)
			.forGoggles(tooltip, 1);
		CreateLang.number(getSpeedTier())
			.text(" / 4")
			.style(ChatFormatting.GOLD)
			.forGoggles(tooltip, 2);

		if (mode == EngineMode.IDLE && idleReason != IdleReason.NONE) {
			CAESLang.translate(idleReason.translationKey())
				.style(ChatFormatting.GRAY)
				.forGoggles(tooltip, 2);
			if (idleReason == IdleReason.NO_SURPLUS) {
				float available = networkCapacityWithoutSelf() - networkStressWithoutSelf();
				CAESLang
					.translate("tooltip.air_engine.needs",
						CreateLang.number(getCompressorDraw())
							.translate("generic.unit.stress"),
						CreateLang.number(Math.max(0, available))
							.translate("generic.unit.stress"))
					.style(ChatFormatting.DARK_GRAY)
					.forGoggles(tooltip, 2);
			}
		}

		if (mode != EngineMode.IDLE) {
			CAESLang.translate("tooltip.air_engine.air_rate")
				.style(ChatFormatting.GRAY)
				.forGoggles(tooltip, 1);
			CreateLang.number(Math.abs(airRate) * 20)
				.translate("generic.unit.millibuckets")
				.text("/s")
				.style(ChatFormatting.GOLD)
				.forGoggles(tooltip, 2);
		}

		// Create's generator lines go underneath; the return says this block filled the overlay,
		// which it has whether or not there was any stress worth quoting.
		super.addToGoggleTooltip(tooltip, isPlayerSneaking);
		return true;
	}

	private ChatFormatting modeColour() {
		return switch (mode) {
			case COMPRESSING -> ChatFormatting.AQUA;
			case GENERATING -> ChatFormatting.GREEN;
			case IDLE -> ChatFormatting.DARK_GRAY;
		};
	}
}
