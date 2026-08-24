package com.createcaes.client;

import com.createcaes.CreateCAES;

import dev.engine_room.flywheel.lib.model.baked.PartialModel;

/**
 * Moving pieces of the Air Engine, kept out of the block model so they can be drawn at an angle.
 *
 * <p>Both are authored pointing <em>up</em>, matching the block model and the blockstate rotations,
 * so one transform serves all six facings.
 */
public class CAESPartials {

	public static final PartialModel AIR_ENGINE_FLYWHEEL = block("air_engine_flywheel");
	public static final PartialModel AIR_ENGINE_PISTON = block("air_engine_piston");

	private static PartialModel block(String path) {
		return PartialModel.of(CreateCAES.asResource("block/" + path));
	}

	/** Touching the class is the registration; this exists to make that deliberate at a call site. */
	public static void init() {
	}
}
