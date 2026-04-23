package tterrag1112.life_in_the_village.Npc.Traits;

import com.mojang.serialization.Codec;

/**
 * The eight bipolar personality axes every NPC carries.
 *
 * <p>Values on each axis are continuous floats in {@code [-1, +1]}. The
 * negative and positive poles are displayed as named traits when the axis
 * value exceeds the display threshold (see {@link TraitVector}).</p>
 */
public enum TraitAxis {
    INDUSTRY    ("Lazy",       "Industrious"),
    COURAGE     ("Timid",      "Bold"),
    SOCIABILITY ("Solitary",   "Gregarious"),
    GENEROSITY  ("Miserly",    "Generous"),
    HONESTY     ("Deceitful",  "Forthright"),
    AMBITION    ("Content",    "Ambitious"),
    COMPASSION  ("Callous",    "Compassionate"),
    TEMPERANCE  ("Impulsive",  "Temperate");

    private final String negativePole;
    private final String positivePole;

    TraitAxis(String negativePole, String positivePole) {
        this.negativePole = negativePole;
        this.positivePole = positivePole;
    }

    public String negativePole() { return negativePole; }
    public String positivePole() { return positivePole; }

    public String poleLabel(boolean positive) {
        return positive ? positivePole : negativePole;
    }

    /** Lowercase name used as the NBT key suffix ({@code npcTraits.<key>}). */
    public String key() { return name().toLowerCase(java.util.Locale.ROOT); }

    public static final Codec<TraitAxis> CODEC =
            Codec.STRING.xmap(TraitAxis::valueOf, TraitAxis::name);
}
