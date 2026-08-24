package com.createcaes.engine;

/** What an Air Engine is doing this tick. Persisted by ordinal and synced to the client. */
public enum EngineMode {

	/** Neither drawing nor supplying: the network is balanced, or there is no air and no surplus. */
	IDLE("idle"),
	/** Drawing surplus stress off the network and pumping air into the vessel. */
	COMPRESSING("compressing"),
	/** Spending stored air to supply stress the rest of the network cannot cover. */
	GENERATING("generating");

	private final String name;

	EngineMode(String name) {
		this.name = name;
	}

	public String translationKey() {
		return "tooltip.air_engine.mode." + name;
	}

	public static EngineMode byOrdinal(int ordinal) {
		EngineMode[] values = values();
		return ordinal >= 0 && ordinal < values.length ? values[ordinal] : IDLE;
	}
}
