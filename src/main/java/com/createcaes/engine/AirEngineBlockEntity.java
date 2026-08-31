package com.createcaes.engine;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import com.createcaes.CAESConfig;
import com.createcaes.CAESLang;
import com.createcaes.registry.CAESFluids;
import com.createcaes.registry.CAESTags;
import com.createcaes.vessel.PressureVesselBlockEntity;
import com.simibubi.create.content.kinetics.KineticNetwork;
import com.simibubi.create.content.kinetics.base.GeneratingKineticBlockEntity;
import com.simibubi.create.content.kinetics.base.IRotate;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
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
 * Every tick it works out what the kinetic network would be doing with <em>every</em> Air Engine on
 * it taken out — subtracting the very numbers each of them last handed the network, so the sum is
 * exact rather than approximate. Call that the network's balance. Then:
 *
 * <ul>
 * <li>if the network cannot carry its own load, it generates;
 * <li>if the network has spare capacity for its draw <em>plus a margin</em>, and something other than
 * an Air Engine is providing that capacity, it compresses;
 * <li>otherwise it idles.
 * </ul>
 *
 * <p>Excluding the engines is what makes this stable, and it has to be all of them rather than only
 * this one. A naive engine reads total capacity minus total stress, starts compressing, sees the
 * deficit its own draw created, flips to generating, sees the surplus its own capacity created, and
 * flips back — once per tick, forever. Excluding itself fixes that for a single engine; it does not
 * fix it for three, where two generators cover a third's draw and the loop simply closes through a
 * longer path. Excluding the whole coalition means the quantity every engine tests does not move
 * when any of them acts on it, and the margin leaves a band in the middle where neither test fires.
 *
 * <p>The rule that falls out is the one a player would state: <strong>a kinetic network is either
 * charging or discharging, never both.</strong> It is a property of the network rather than of the
 * vessel, which is deliberate — one Pressure Vessel may serve engines on several separate networks,
 * and buffering between a network that has power spare and a network that is short of it is exactly
 * what it is for. See {@link #networkEngines()}.
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

	/**
	 * How long a cached coalition is trusted before it is walked out of the network again.
	 *
	 * <p>Only a safety net, not the mechanism. An engine that does not find itself in its cached
	 * list rebuilds on the spot, and a rebuild is handed to every engine it found — so a newly
	 * placed engine joins its network's coalition on its first deciding tick and tells the others
	 * about itself in the same breath. Departures are caught every tick by {@link #sharesNetworkWith},
	 * which is why the interval can be this lazy: what is left for it is the case where a network
	 * changes shape without any engine's network id changing with it.
	 */
	private static final int COALITION_REFRESH_TICKS = 20;

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
	 * The Air Engines sharing this engine's kinetic network, itself included, in a fixed order.
	 *
	 * <p>Server-side and transient. Every engine on a network holds the same list object, because
	 * whichever of them rebuilds it hands the result to all the others — one walk of the network's
	 * members serves the whole coalition rather than one walk per engine. See {@link #networkEngines()}.
	 */
	private List<AirEngineBlockEntity> coalition = List.of();
	/** The network id {@link #coalition} was built for, so a split or a merge rebuilds it at once. */
	@Nullable
	private Long coalitionNetwork;
	/** Ticks of trust left in {@link #coalition}. See {@link #COALITION_REFRESH_TICKS}. */
	private int coalitionAge;

	/**
	 * Whether everything about this engine <em>except</em> the network's balance says it could
	 * compress: it is turning, it has somewhere to put the air, and it is not needed as a generator.
	 *
	 * <p>Peers read it, which is the whole reason it is a field. The charging allocation in
	 * {@link #fitsInChargingAllocation} has to know which engines are contending for the surplus
	 * before it decides who gets it, and asking a peer to re-derive that would be circular — the
	 * answer would depend on the allocation the allocation is trying to compute. Deliberately
	 * independent of the balance for that reason, and it may be one tick stale, which the allocation
	 * tolerates.
	 */
	private boolean wantsToCompress;

	/**
	 * Spare network capacity this engine may compress into, after the engines ahead of it in the
	 * coalition have taken theirs. Display only — it is what the goggles quote against the engine's
	 * draw — and synced because the client cannot walk a kinetic network's members to work it out.
	 */
	private float chargeAllowance;

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

	/**
	 * Capacity on this network that <em>another mod's</em> store is supplying rather than generating.
	 *
	 * <p>The same rule the coalition applies to this mod's own engines, for blocks whose fields cannot
	 * be read. Membership of {@link CAESTags#KINETIC_ENERGY_STORAGE} is the entire contract: Create
	 * already reports the amount through {@code getActualCapacityOf}, and reports it as zero for a
	 * store that is not currently spending, so nothing else has to cross the mod boundary.
	 *
	 * <p><b>Foreign only.</b> Air Engines are excluded because the coalition has already taken them out
	 * of {@code externalCapacity}, and it knows things a tag cannot say — {@code wantsToCompress}, each
	 * peer's exact draw — which is what lets several compressors share one surplus instead of the first
	 * one taking it. Counting them here as well would subtract them twice.
	 *
	 * <p>Cheap enough per tick: {@code sources} holds only the network's generators, not its members,
	 * so this is a handful of entries rather than the whole factory — which is why it needs none of the
	 * caching {@link #networkEngines()} does. Removed entries are skipped because Create only prunes
	 * them on its next capacity recalculation, and a stale one here would be capacity subtracted twice.
	 */
	public float foreignStoredCapacityOnNetwork() {
		if (!hasNetwork())
			return 0;
		KineticNetwork kinetics = getOrCreateNetwork();
		float stored = 0;
		for (KineticBlockEntity source : kinetics.sources.keySet()) {
			if (source instanceof AirEngineBlockEntity || source.isRemoved())
				continue;
			if (source.getBlockState().is(CAESTags.KINETIC_ENERGY_STORAGE))
				stored += kinetics.getActualCapacityOf(source);
		}
		return stored;
	}

	// --- the coalition ----------------------------------------------------------------------

	/**
	 * Every Air Engine on this engine's kinetic network, itself included, in ascending position
	 * order.
	 *
	 * <p>The order is what lets the engines agree on who gets the surplus without talking to each
	 * other: they all walk the same list in the same order and run the same arithmetic, so they
	 * reach the same answer independently. Positions are stable, which a hash order is not.
	 *
	 * <p>Cost is why it is cached. {@code KineticNetwork.members} is every kinetic block in the
	 * factory, and walking it once per engine per tick would put the size of the player's base into
	 * a loop that runs twenty times a second. So the walk happens once per network per
	 * {@link #COALITION_REFRESH_TICKS}, and the engine that does it hands the result to everyone it
	 * found — the other engines then have nothing to do but read it. Between walks the list is kept
	 * honest by {@link #sharesNetworkWith}, which is O(the engines) rather than O(the factory).
	 */
	private List<AirEngineBlockEntity> networkEngines() {
		if (coalitionAge > 0 && Objects.equals(coalitionNetwork, network) && coalition.contains(this)) {
			coalitionAge--;
			return coalition;
		}
		return rebuildCoalition();
	}

	private List<AirEngineBlockEntity> rebuildCoalition() {
		List<AirEngineBlockEntity> found = new ArrayList<>();
		if (hasNetwork()) {
			KineticNetwork kinetics = getOrCreateNetwork();
			for (KineticBlockEntity member : kinetics.members.keySet())
				if (member instanceof AirEngineBlockEntity engine && !engine.isRemoved())
					found.add(engine);
		}
		// A member map that has not caught up with this engine yet must not leave it out of its own
		// coalition, or it would read the network as if it were not on it.
		if (!found.contains(this))
			found.add(this);
		found.sort(Comparator.comparingLong(engine -> engine.worldPosition.asLong()));

		List<AirEngineBlockEntity> shared = List.copyOf(found);
		for (AirEngineBlockEntity engine : shared) {
			engine.coalition = shared;
			engine.coalitionNetwork = network;
			engine.coalitionAge = COALITION_REFRESH_TICKS;
		}
		return shared;
	}

	/**
	 * Whether a cached coalition entry is still one of this engine's peers.
	 *
	 * <p>Checked on every use rather than only on a rebuild. Breaking a shaft splits a network
	 * without necessarily changing either half's id, and an engine that has left still appears in
	 * the stale list — subtracting its contribution from a network total that no longer contains it
	 * would read as capacity that is not there.
	 */
	private boolean sharesNetworkWith(AirEngineBlockEntity engine) {
		return !engine.isRemoved() && Objects.equals(engine.network, network);
	}

	/**
	 * Whether a stress figure is zero as far as the network is concerned.
	 *
	 * <p>Taking every engine's contribution back out of a total those engines are most of leaves two
	 * nearly equal floats subtracted from each other, and that does not reliably land on zero. The
	 * slack is relative because the totals are: a network's capacity is thousands of Stress Units, so
	 * an absolute epsilon would be either useless at the top of the range or wrong at the bottom.
	 */
	private static boolean isNegligible(float value, float scale) {
		return value <= 1.0E-5F * Math.max(1F, Math.abs(scale));
	}

	/**
	 * Draw already spoken for by the engines ahead of this one, and whether this one still fits.
	 *
	 * <p>Every engine runs this over the same list in the same order, so they allocate the shared
	 * surplus consistently without any of them being in charge. An engine that does not fit is
	 * skipped rather than ending the walk, so a large engine at the front cannot starve a small one
	 * behind it.
	 *
	 * <p>Contention is read off {@link #wantsToCompress}, which may be a tick old. The cost of that
	 * is one tick: engines that all start wanting to compress on the same tick see each other's flag
	 * still clear and may collectively overdraw once, at which point the network is overstressed,
	 * {@code getSpeed()} is zero and {@link #compress} moves no air anyway. From the following tick
	 * the flags are set and the allocation is stable. The {@code balance < 0} half of the generating
	 * test in {@link #decideMode} is what keeps that one tick from recruiting generators.
	 *
	 * <p>Half of this is covered: {@code twoCompressorsShareOneMotorsSurplus} fails if it stops
	 * handing the surplus out. The other half — several compressors contending for a surplus too
	 * small for all of them — is <strong>not covered by a test</strong>, because provoking it needs a
	 * source whose capacity a couple of engines can exhaust and a creative motor's is effectively
	 * unbounded. At one engine it reduces exactly to the test it replaced, which the rest of the
	 * suite still covers.
	 */
	private boolean fitsInChargingAllocation(List<AirEngineBlockEntity> coalition, float balance) {
		float margin = CAESConfig.chargeMarginStress();
		float spent = 0;
		for (AirEngineBlockEntity engine : coalition) {
			boolean self = engine == this;
			if (!self && (!sharesNetworkWith(engine)
				|| !(engine.wantsToCompress || engine.mode == EngineMode.COMPRESSING)))
				continue;
			float draw = engine.getCompressorDraw();
			if (self) {
				chargeAllowance = balance - spent;
				return draw > 0 && spent + draw + margin <= balance;
			}
			if (spent + draw + margin <= balance)
				spent += draw;
		}
		// Unreachable: networkEngines() guarantees this engine is in its own coalition.
		chargeAllowance = balance;
		return false;
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
			return idleWithoutCompressing(IdleReason.NONE);
		if (air == null)
			return idleWithoutCompressing(IdleReason.NO_SUPPLY);
		// No rating means no mode it could act on: it would report "Generating" while supplying
		// nothing, or blame the network for a shortfall that is really an empty tier. Unreachable at
		// the default config -- a vessel always gives at least 1/blocksPerEngine and a pipe gives
		// pipeFedEfficiency -- so it is defence for someone setting pipeFedEfficiency to 0, and is
		// **not covered by a test** for that reason.
		if (getSpeedTier() == 0)
			return idleWithoutCompressing(IdleReason.NO_SUPPLY);

		// What the network would be doing with no Air Engine on it at all. Every engine's own
		// contribution comes out, not just this one's, and it comes out as the value the network has
		// recorded for it rather than as a fresh calculation, so the subtraction is exact.
		List<AirEngineBlockEntity> coalition = networkEngines();
		float externalCapacity = capacity;
		float externalStress = stress;
		boolean peerGenerating = false;
		for (AirEngineBlockEntity engine : coalition) {
			if (engine != this && !sharesNetworkWith(engine))
				continue;
			externalCapacity -= engine.ownCapacity();
			externalStress -= engine.ownStress();
			if (engine != this && engine.mode == EngineMode.GENERATING)
				peerGenerating = true;
		}
		float balance = externalCapacity - externalStress;

		// Capacity a foreign store is putting on the network -- a Gravity Battery letting its weight
		// down, say. Compressing on it would be the same round-trip loop `peerGenerating` refuses
		// below, across a mod boundary instead of within one.
		//
		// Deliberately *not* taken out of `balance` itself, only out of what the charging allocation
		// may spend. A discharging store is genuinely holding this network up, so an engine deciding
		// whether to *generate* must go on counting it: subtract it here and a network one battery is
		// comfortably covering reads as a deficit to every engine on it, and they all start generating
		// against a shortfall that does not exist.
		float borrowed = foreignStoredCapacityOnNetwork();

		float headroom = networkCapacityWithoutSelf() - networkStressWithoutSelf();

		// Nothing else on this network can turn it, and there is something attached worth turning.
		// The second half matters: without it a charged engine sitting in a chest room would spin
		// itself against nothing and quietly empty its vessel.
		boolean soleSource =
			!hasSource() && networkCapacityWithoutSelf() <= 0 && hasSomethingToDrive();

		// The deficit has to be the network's own, not one the coalition dug for it. The
		// self-excluded headroom is still the second half of the test, because that is what shares
		// one real deficit between several engines -- each sees it shrink as the others take it up,
		// and stops once it is covered.
		//
		// In steady state the coalition half is redundant: the charging allocation below never hands
		// out more than `balance`, so a compressor cannot be what drove the headroom negative. It is
		// here for the tick where it can -- several engines starting together see each other's
		// wantsToCompress still clear and may overdraw once -- and without it that one tick would
		// call every generator on the network into service, which is the flap the whole design is
		// arranged to avoid. Like the other transient guards it is **not covered by a test**:
		// provoking the overshoot needs a source whose surplus is small enough to exhaust, and a
		// creative motor is the only source a GameTest rig can hold. Verified by mutation that
		// removing it leaves all 37 green.
		boolean wantsToGenerate = (balance < 0 && headroom < 0) || soleSource;
		if (wantsToGenerate) {
			wantsToCompress = false;
			chargeAllowance = 0;
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
			return idleWithoutCompressing(IdleReason.NOT_TURNING);

		// A network with no capacity of its own is turning on stored air, whoever is spending it, so
		// there is nothing here to charge from -- putting it back would be the network paying itself,
		// at a round-trip loss, for ever.
		//
		// The allocation below would refuse this anyway, since `balance` cannot be positive when
		// nothing outside the coalition is providing capacity. This states the rule instead of
		// leaving it to fall out of the arithmetic, which is worth doing for two reasons: it is what
		// puts a reason on the goggles a player can act on, rather than "not enough spare capacity"
		// against a network that looks to them like it has plenty; and the peer test holds on the
		// tick a failover starts, before the network's totals have caught up with it. Removing it
		// fails oneNetworkNeverChargesAndDischargesAtOnce on the reported reason.
		if (peerGenerating || isNegligible(externalCapacity, capacity))
			return idleWithoutCompressing(IdleReason.NETWORK_ON_AIR);

		if (!hasRoom(air))
			return idleWithoutCompressing(IdleReason.VESSEL_FULL);

		wantsToCompress = true;
		if (fitsInChargingAllocation(coalition, balance - borrowed)) {
			idleReason = IdleReason.NONE;
			return EngineMode.COMPRESSING;
		}
		// Which of the two shortfalls this is, exactly rather than approximately: the allocation has
		// just left this engine's share in chargeAllowance, so adding the borrowing back says whether
		// that alone was the difference. Worth the line because the two look identical on a
		// Stressometer -- a network whose surplus is borrowed reads as a network with plenty, and
		// "not enough spare capacity" would send a player looking for a generator they already have.
		boolean borrowingWasTheDifference = borrowed > 0
			&& chargeAllowance + borrowed >= getCompressorDraw() + CAESConfig.chargeMarginStress();
		return idle(borrowingWasTheDifference
			? IdleReason.NETWORK_ON_STORED_POWER : IdleReason.NO_SURPLUS);
	}

	/**
	 * Idles for a reason that also means this engine is not contending for the network's surplus, so
	 * the engines behind it in the coalition may have what it was reserving.
	 */
	private EngineMode idleWithoutCompressing(IdleReason reason) {
		wantsToCompress = false;
		chargeAllowance = 0;
		return idle(reason);
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
		compound.putFloat("ChargeAllowance", chargeAllowance);
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
		chargeAllowance = compound.getFloat("ChargeAllowance");
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
				// The engine's own share of the network's spare capacity, not the whole of it: on a
				// network running several engines the ones ahead of this one have already taken
				// theirs, and quoting a figure this engine cannot have would read as a bug.
				// Computed on the server and synced, because working it out means walking the
				// network's members and the client has no such network to walk.
				CAESLang
					.translate("tooltip.air_engine.needs",
						CreateLang.number(getCompressorDraw())
							.translate("generic.unit.stress"),
						CreateLang.number(Math.max(0, chargeAllowance))
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
