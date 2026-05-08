package tterrag1112.life_in_the_village.Village.Decoration.Parks;

import net.minecraft.util.StringRepresentable;

/**
 * B2.4 — catalog of park primitives, doc 09 §"Primitives".
 *
 * <p>Each primitive declares whether its renderer is procedural
 * (block-stamp by code) or NBT-backed (user-authored
 * {@code structures/{culture}/decoration/park/{name}.nbt}). The
 * mix matches the user's B2.4 split: simple geometric pieces
 * (paths, hedges, flower beds, ponds) ship as procedural placers
 * so they're guaranteed to render; complex composed pieces
 * (benches, statues, arches, standing stones) ship as NBT slots
 * the user authors.</p>
 */
public enum ParkPrimitive implements StringRepresentable {

    // ── Procedural (always render) ─────────────────────────────────────

    /** Single-block-wide gravel path — block stamp. */
    GRAVEL_PATH    (Kind.PROCEDURAL, 1),
    /** Sparse flower spots over grass — block stamp. */
    FLOWER_BED     (Kind.PROCEDURAL, 2),
    /** Single row of leaf-block hedges — block stamp. */
    HEDGEROW       (Kind.PROCEDURAL, 1),
    /** Small water rectangle 3×3 to 4×4 — block stamp. */
    POND           (Kind.PROCEDURAL, 4),

    // ── NBT-backed (skipped if NBT missing) ────────────────────────────

    /** Bench seating — NBT. */
    BENCH          (Kind.NBT,        2),
    /** Pedestal + statue — NBT. */
    STATUE_PEDESTAL(Kind.NBT,        2),
    /** Vine trellis — NBT. */
    TRELLIS        (Kind.NBT,        2),
    /** Topiary cone — NBT. */
    TOPIARY        (Kind.NBT,        1),
    /** Stone marker — NBT. */
    STANDING_STONE (Kind.NBT,        1),
    /** Wooden archway — NBT. */
    WOODEN_ARCH    (Kind.NBT,        2),
    /** Light source — NBT (overlaps the B2.2 street-furniture lamppost
     *  but lives in its own decoration/park/lamppost.nbt path so park
     *  authoring stays self-contained). */
    LAMPPOST       (Kind.NBT,        1);

    private final Kind kind;
    private final int footprint;

    ParkPrimitive(Kind kind, int footprint) {
        this.kind = kind;
        this.footprint = footprint;
    }

    public Kind kind()       { return kind; }
    public int  footprint()  { return footprint; }
    public boolean isProcedural() { return kind == Kind.PROCEDURAL; }

    /** Resource path the user authors NBTs at. {@code null} for
     *  procedural primitives. */
    public String nbtPath(String culture) {
        if (kind != Kind.NBT) return null;
        return "structures/" + culture + "/decoration/park/"
                + name().toLowerCase() + ".nbt";
    }

    @Override public String getSerializedName() { return name(); }

    public static final com.mojang.serialization.Codec<ParkPrimitive> CODEC =
            StringRepresentable.fromEnum(ParkPrimitive::values);

    public enum Kind { PROCEDURAL, NBT }
}
