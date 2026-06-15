package tterrag1112.life_in_the_village.Npc.Tasks.Producer;

import net.minecraft.world.item.Item;
import tterrag1112.life_in_the_village.Npc.Tasks.Objective;

import java.util.Optional;

/**
 * Shared accessor for the item / quantity an acquisition objective targets,
 * generalized from the blacksmith pilot's {@code IronObjectives}. Used by both
 * the generic source (intermediate reserves) and per-profession
 * intermediate-acquisition fulfillments (the blacksmith's smelt / buy).
 */
public final class AcquireObjectives {

    private AcquireObjectives() {}

    /** The item of an {@code Acquire} or {@code MaintainStock} objective; empty
     *  for any other variant. */
    public static Optional<Item> itemOf(Objective objective) {
        if (objective instanceof Objective.Acquire a) return Optional.of(a.item());
        if (objective instanceof Objective.MaintainStock m) return Optional.of(m.item());
        return Optional.empty();
    }

    /** The quantity an acquisition objective wants (Acquire qty, or the
     *  MaintainStock target). 0 for any other variant. */
    public static int qtyOf(Objective objective) {
        if (objective instanceof Objective.Acquire a) return a.qty();
        if (objective instanceof Objective.MaintainStock m) return m.target();
        return 0;
    }
}
