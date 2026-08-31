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
	NO_SURPLUS("no_surplus"),
	/**
	 * It would compress, but the only thing turning this network is an Air Engine — so the rotation
	 * on offer is stored air being spent, and charging off it would be a network paying itself.
	 */
	NETWORK_ON_AIR("network_on_air"),
	/**
	 * It would compress, but the capacity on offer is coming from some other mod's store — a Gravity
	 * Battery letting its weight down, say. Same loop {@link #NETWORK_ON_AIR} refuses, across a mod
	 * boundary instead of within one, and reported separately because the two send a player to
	 * different blocks. Classified by the {@code c:kinetic_energy_storage} tag, so it covers addons
	 * this one has never heard of.
	 */
	NETWORK_ON_STORED_POWER("network_on_stored_power");

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
