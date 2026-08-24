package com.createcaes.engine;

/**
 * Why an idle engine is idle. Purely diagnostic — nothing branches on it — but "the compressor needs
 * more spare capacity than this network has" is invisible otherwise, and an unexplained idle block is
 * the kind of thing players file bugs about.
 */
public enum IdleReason {

	/** Not idle, or idle for no reason worth reporting. */
	NONE("none"),
	/** Nothing in front of the engine that holds Compressed Air. */
	NO_SUPPLY("no_supply"),
	/** It would generate, but there is no air left. */
	NO_AIR("no_air"),
	/** It would compress, but the vessel is already full. */
	VESSEL_FULL("vessel_full"),
	/** It would compress, but the shaft is not turning. */
	NOT_TURNING("not_turning"),
	/** It would compress, but the network has no capacity to spare for it. */
	NO_SURPLUS("no_surplus");

	private final String name;

	IdleReason(String name) {
		this.name = name;
	}

	public String translationKey() {
		return "tooltip.air_engine.idle." + name;
	}

	public static IdleReason byOrdinal(int ordinal) {
		IdleReason[] values = values();
		return ordinal >= 0 && ordinal < values.length ? values[ordinal] : NONE;
	}
}
