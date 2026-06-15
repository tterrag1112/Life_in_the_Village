package tterrag1112.life_in_the_village.Npc.Tasks.Blacksmith;

import net.minecraft.world.item.Item;
import tterrag1112.life_in_the_village.Npc.Tasks.Objective;

import java.util.Optional;

/** Small shared accessor: the item an iron-acquisition objective targets. */
final class IronObjectives {

    private IronObjectives() {}

    /** The item of an {@code Acquire} or {@code MaintainStock} objective; empty
     *  for any other variant. */
    static Optional<Item> itemOf(Objective objective) {
        if (objective instanceof Objective.Acquire a) return Optional.of(a.item());
        if (objective instanceof Objective.MaintainStock m) return Optional.of(m.item());
        return Optional.empty();
    }

    /** The quantity an iron-acquisition objective wants (Acquire qty, or the
     *  MaintainStock target). 0 for any other variant. */
    static int qtyOf(Objective objective) {
        if (objective instanceof Objective.Acquire a) return a.qty();
        if (objective instanceof Objective.MaintainStock m) return m.target();
        return 0;
    }
}
