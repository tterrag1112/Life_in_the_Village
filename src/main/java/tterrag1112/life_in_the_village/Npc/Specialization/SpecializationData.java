package tterrag1112.life_in_the_village.Npc.Specialization;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/**
 * Phase 6.3.2.c — typed metadata payload for a {@link SpecializationDef}.
 *
 * <p>Sealed so consumers can pattern-match exhaustively:
 * <pre>
 *   switch (spec.extra()) {
 *     case CombatRoleData(weapon, range, sMod, dMod) -> ...
 *     case BlacksmithData() -> ...
 *     case None() -> ...
 *   }
 * </pre>
 */
public sealed interface SpecializationData
        permits SpecializationData.None,
                SpecializationData.BlacksmithData,
                SpecializationData.CombatRoleData {

    record None() implements SpecializationData {
        public static final None INSTANCE = new None();
    }

    /**
     * Blacksmith specialization metadata. Categorization helpers live as
     * static utilities on {@code BlacksmithSpecialization}; this payload
     * is currently a marker that tells consumers "this is a blacksmith
     * spec" so the registry stays uniformly-typed. Phase 6.3.2.c content
     * pass can add fields here (e.g. bonus weights, signature items).
     */
    record BlacksmithData() implements SpecializationData {
        public static final BlacksmithData INSTANCE = new BlacksmithData();
    }

    /**
     * Combat-role metadata — direct port of {@code CombatRole}'s per-constant
     * fields. The deferred adventurer goal cluster reads these via the
     * {@code TownspersonMob.getCombatRole()} bridge.
     */
    record CombatRoleData(Item primaryWeapon,
                          int engagementRange,
                          float speedModifier,
                          float damageModifier,
                          String description,
                          String symbol) implements SpecializationData {

        public static CombatRoleData defaultRookie() {
            return new CombatRoleData(Items.IRON_SWORD, 2, 0.8f, 0.7f,
                    "Untested adventurer. Still learning.", "○");
        }
    }
}
