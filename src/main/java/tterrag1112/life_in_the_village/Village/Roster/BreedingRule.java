package tterrag1112.life_in_the_village.Village.Roster;

import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * Phase 6.3.3.d — describes how a {@link RosterDefinition} reproduces.
 *
 * @param feedItems       items consumed per breeding event (drawn from
 *                        the host building's storage)
 * @param minAdultPairs   minimum adult count for breeding to fire. Most
 *                        animals require 2 adults; the mod doesn't
 *                        track gender so this is simply "any 2 adults"
 * @param cooldownTicks   minimum interval between breeding events
 */
public record BreedingRule(List<ItemStack> feedItems,
                           int minAdultPairs,
                           long cooldownTicks) {

    public BreedingRule {
        feedItems = List.copyOf(feedItems == null ? List.of() : feedItems);
        if (minAdultPairs < 1) minAdultPairs = 2;
        if (cooldownTicks < 0L) cooldownTicks = 0L;
    }
}
