package com.createcaes;

import net.neoforged.neoforge.common.ModConfigSpec;
import org.apache.commons.lang3.tuple.Pair;

/**
 * Server config. Everything here changes how much work a network has to do to fill a vessel and how
 * much it gets back, so it has to agree between client and server — hence SERVER rather than COMMON.
 *
 * <p>The one number that ties rotation to air is {@link #airPerStressUnit}: millibuckets moved per
 * Stress Unit per tick. Charging multiplies it by {@link #roundTripEfficiency}; discharging does not.
 * That is the whole energy model — the losses live in one place.
 *
 * <p>Power comes from {@link #maxStress} scaled by an efficiency the engine works out from the size
 * of its vessel, exactly the way a Steam Engine works it out from the size of its boiler. Duration
 * comes from the vessel too, since a bigger vessel holds more air.
 */
public class CAESConfig {

	public static final ModConfigSpec SPEC;
	public static final CAESConfig INSTANCE;

	/** Stress an engine supplies at full efficiency, across its whole speed tier. */
	public final ModConfigSpec.DoubleValue maxStress;
	/** Blocks of vessel needed to run one engine at full efficiency. */
	public final ModConfigSpec.IntValue blocksPerEngine;
	/** Most engines one vessel can run at full efficiency, however large it is. */
	public final ModConfigSpec.IntValue maxEnginesPerVessel;
	/** Efficiency for an engine fed through pipes rather than bolted to a vessel. */
	public final ModConfigSpec.DoubleValue pipeFedEfficiency;
	/** Millibuckets of Compressed Air moved per Stress Unit per tick. */
	public final ModConfigSpec.DoubleValue airPerStressUnit;
	/** Fraction of the work put into compression that comes back out again. */
	public final ModConfigSpec.DoubleValue roundTripEfficiency;
	/** Spare capacity a network must have beyond the compressor's own draw before it starts charging. */
	public final ModConfigSpec.DoubleValue chargeMarginStress;
	/** Fraction of its rating an unloaded motor still spends on air. */
	public final ModConfigSpec.DoubleValue idleAirDraw;

	/** Millibuckets of Compressed Air each Pressure Vessel block holds. */
	public final ModConfigSpec.IntValue vesselCapacity;
	/** Tallest a vessel may be built, whatever its footprint. */
	public final ModConfigSpec.IntValue vesselMaxHeight;

	private CAESConfig(ModConfigSpec.Builder builder) {
		builder.comment("Air Engine").push("engine");
		maxStress = builder
			.comment("Stress Units an engine supplies at full efficiency. This is a total across the",
				"whole speed tier, not a per-RPM rating: a fully supplied engine is worth this much",
				"whether it is turning at 16 RPM or 64. Create's Steam Engine is 16384 for comparison.")
			.defineInRange("maxStress", 8192.0, 0.0, 1000000.0);
		blocksPerEngine = builder
			.comment("Blocks of Pressure Vessel needed to run one Air Engine at full efficiency.",
				"Two engines on a vessel this size each run at half efficiency, the way two Steam",
				"Engines share an undersized boiler. Create uses 4 blocks of boiler per engine.")
			.defineInRange("blocksPerEngine", 9, 1, 1024);
		maxEnginesPerVessel = builder
			.comment("Most engines a single vessel can run at full efficiency, however large it is.",
				"Create's boiler has the same ceiling at 18, and for the same reason: without it a",
				"tall enough tank runs an unbounded number of engines.")
			.defineInRange("maxEnginesPerVessel", 18, 1, 256);
		pipeFedEfficiency = builder
			.comment("Efficiency for an engine drawing through a pipe instead of being bolted to a",
				"vessel, where there is no vessel size to read. Create gives an unheated boiler the",
				"same 0.125.")
			.defineInRange("pipeFedEfficiency", 0.125, 0.0, 1.0);
		airPerStressUnit = builder
			.comment("Millibuckets of Compressed Air per Stress Unit per tick.")
			.defineInRange("airPerStressUnit", 0.04, 0.0001, 100.0);
		roundTripEfficiency = builder
			.comment("Fraction of compression work recovered on the way back out.")
			.defineInRange("roundTripEfficiency", 0.7, 0.01, 1.0);
		chargeMarginStress = builder
			.comment("Stress capacity a network must have spare, on top of the compressor's own draw,",
				"before the compressor will start. This is the deadband that stops an engine",
				"flapping between compressing and generating, and it is also what refuses two",
				"engines on one shaft charging each other.")
			.defineInRange("chargeMarginStress", 64.0, 0.0, 100000.0);
		idleAirDraw = builder
			.comment("Fraction of its rated output an unloaded motor still spends on air, so that a",
				"shaft spinning on stored air is never free.")
			.defineInRange("idleAirDraw", 0.1, 0.0, 1.0);
		builder.pop();

		builder.comment("Pressure Vessel").push("vessel");
		vesselCapacity = builder
			.comment("Millibuckets of Compressed Air held per block of vessel.")
			.defineInRange("vesselCapacity", 8000, 1, 1000000);
		vesselMaxHeight = builder
			.comment("Tallest a vessel may be built. One figure for every footprint, matching",
				"Create's Fluid Tank, which uses a single cap regardless of width.")
			.defineInRange("vesselMaxHeight", 32, 1, 256);
		builder.pop();
	}

	static {
		Pair<CAESConfig, ModConfigSpec> pair = new ModConfigSpec.Builder().configure(CAESConfig::new);
		INSTANCE = pair.getLeft();
		SPEC = pair.getRight();
	}

	/**
	 * Reading a config value before its file is loaded throws. Most callers here run deep inside a
	 * block entity tick, where it always is loaded — but stress ratings are also read by tooltip and
	 * recipe-viewer code that can run earlier, and a crash there would be a poor trade for a number
	 * that has a perfectly good default sitting right next to it.
	 */
	private static <T> T read(ModConfigSpec.ConfigValue<T> value) {
		return SPEC.isLoaded() ? value.get() : value.getDefault();
	}

	public static float maxStress() {
		return read(INSTANCE.maxStress).floatValue();
	}

	public static int blocksPerEngine() {
		return read(INSTANCE.blocksPerEngine);
	}

	public static int maxEnginesPerVessel() {
		return read(INSTANCE.maxEnginesPerVessel);
	}

	public static float pipeFedEfficiency() {
		return read(INSTANCE.pipeFedEfficiency).floatValue();
	}

	public static float airPerStressUnit() {
		return read(INSTANCE.airPerStressUnit).floatValue();
	}

	public static float roundTripEfficiency() {
		return read(INSTANCE.roundTripEfficiency).floatValue();
	}

	public static float chargeMarginStress() {
		return read(INSTANCE.chargeMarginStress).floatValue();
	}

	public static float idleAirDraw() {
		return read(INSTANCE.idleAirDraw).floatValue();
	}

	public static int vesselCapacity() {
		return read(INSTANCE.vesselCapacity);
	}

	public static int vesselMaxHeight() {
		return read(INSTANCE.vesselMaxHeight);
	}

}
