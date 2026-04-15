// src/main/java/tterrag1112/life_in_the_village/Village/Planning/Zoning/SlotPreference.java
package tterrag1112.life_in_the_village.Village.Planning.Zoning;

import java.util.EnumSet;
import java.util.Set;

/**
 * One tier in a building's ordered preference list.
 *
 * <p>A slot satisfies this preference when it carries <em>every</em>
 * tag in {@link #required}. Tags in {@link #preferred} don't gate
 * acceptance but contribute to scoring — a slot carrying more
 * preferred tags wins ties.
 *
 * @param required  all must be present on the slot; empty set means
 *                  "any slot matches this tier"
 * @param preferred bonus tags; each matching tag adds to score
 * @param weight    base score multiplier for this tier. Earlier tiers
 *                  carry higher weights so the matcher prefers them
 *                  when slots in both are acceptable.
 */
public record SlotPreference(Set<SlotTag> required,
                             Set<SlotTag> preferred,
                             int weight) {
    public SlotPreference {
        required = required == null ? EnumSet.noneOf(SlotTag.class)
                : EnumSet.copyOf(required);
        preferred = preferred == null ? EnumSet.noneOf(SlotTag.class)
                : EnumSet.copyOf(preferred);
    }

    // ── Fluent builders ────────────────────────────────────────────────────

    public static SlotPreference require(int weight, SlotTag... tags) {
        return new SlotPreference(EnumSet.copyOf(java.util.Arrays.asList(tags)),
                EnumSet.noneOf(SlotTag.class), weight);
    }

    public static SlotPreference requireAny(int weight, SlotTag... anyOf) {
        // "any of" is modeled as separate tiers at the profile level;
        // this helper is a placeholder for clarity at call sites.
        throw new UnsupportedOperationException(
                "Use separate tiers for OR semantics");
    }

    public static SlotPreference prefer(int weight, SlotTag... preferredTags) {
        return new SlotPreference(EnumSet.noneOf(SlotTag.class),
                EnumSet.copyOf(java.util.Arrays.asList(preferredTags)), weight);
    }

    public static SlotPreference tier(int weight,
                                      Set<SlotTag> required,
                                      Set<SlotTag> preferred) {
        return new SlotPreference(required, preferred, weight);
    }
}