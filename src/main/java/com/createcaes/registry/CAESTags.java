package com.createcaes.registry;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;

/**
 * Tags this mod <em>reads</em>, as opposed to the ones it only declares itself into.
 *
 * <p>{@link #KINETIC_ENERGY_STORAGE} is a cross-mod convention rather than this mod's property, and
 * it is in the {@code c} namespace for that reason: nobody owns it, so a second addon adds its own
 * block without depending on this one, and a pack author can add a third mod's block with a datapack
 * and fix an interaction neither author has heard of. That last property is the whole point — the
 * alternative designs (a shared API artifact, a NeoForge capability) both need a common class on the
 * classpath, and neither can be patched in from outside.
 *
 * <p><b>What membership means:</b> the capacity this block supplies to a kinetic network is drawn
 * from a store it filled earlier, not generated. Tag the block that is the <em>kinetic source</em>,
 * which for a multiblock is not necessarily the one a player thinks of as the storage — here that is
 * the Air Engine and not the Pressure Vessel, which holds the air but turns nothing.
 *
 * <p><b>What it does not need to say:</b> how much, or whether it is doing it right now. Create
 * already answers both — {@code KineticNetwork#getActualCapacityOf} multiplies the recorded
 * contribution by {@code getGeneratedSpeed()}, which is zero for a store that is not currently
 * spending. That is why one boolean per block is a sufficient contract, and why this is a tag rather
 * than an interface.
 *
 * <p>This mod already does the same thing for its own engines, and better: the coalition in
 * {@code AirEngineBlockEntity#networkEngines} can read a peer's fields, so it can share one surplus
 * out between several compressors rather than merely refusing. The tag is what that generalises to
 * for a block whose fields cannot be read.
 */
public final class CAESTags {

	/**
	 * Kinetic sources whose capacity comes out of a store. See
	 * {@link com.createcaes.engine.AirEngineBlockEntity#foreignStoredCapacityOnNetwork()}.
	 */
	public static final TagKey<Block> KINETIC_ENERGY_STORAGE = TagKey.create(Registries.BLOCK,
		ResourceLocation.fromNamespaceAndPath("c", "kinetic_energy_storage"));

	private CAESTags() {
		throw new AssertionError("No instances");
	}
}
