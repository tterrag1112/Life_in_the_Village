package tterrag1112.life_in_the_village.Guilds.Adventurer;

import net.minecraft.resources.Identifier;
import tterrag1112.life_in_the_village.Npc.Specialization.NpcSpecializationTypes;
import tterrag1112.life_in_the_village.Npc.Specialization.SpecializationDef;

import javax.annotation.Nullable;

/**
 * Phase 6.3.2.c — translation layer between the legacy {@link CombatRole}
 * enum and the unified {@link SpecializationDef} registry. Used by the
 * {@code TownspersonMob.getCombatRole / setCombatRole} bridge to keep
 * the deferred adventurer Goal cluster compiling unchanged.
 *
 * <p>The legacy enum stays — it's the convenient typed handle the 13
 * adventurer goals use. New code should query
 * {@code npc.getSpecializationComponent().get()} and pattern-match
 * the {@code SpecializationData} payload directly.
 */
public final class CombatRoleBridge {

    private CombatRoleBridge() {}

    @Nullable
    public static SpecializationDef toSpec(@Nullable CombatRole role) {
        if (role == null) return null;
        return switch (role) {
            case SWORDSMAN -> NpcSpecializationTypes.ADVENTURER_SWORDSMAN;
            case SPEARMAN  -> NpcSpecializationTypes.ADVENTURER_SPEARMAN;
            case ARCHER    -> NpcSpecializationTypes.ADVENTURER_ARCHER;
            case MAGE      -> NpcSpecializationTypes.ADVENTURER_MAGE;
            case HEALER    -> NpcSpecializationTypes.ADVENTURER_HEALER;
            case SCOUT     -> NpcSpecializationTypes.ADVENTURER_SCOUT;
        };
    }

    @Nullable
    public static CombatRole toEnum(@Nullable Identifier id) {
        if (id == null) return null;
        if (id.equals(NpcSpecializationTypes.ADVENTURER_SWORDSMAN.name())) return CombatRole.SWORDSMAN;
        if (id.equals(NpcSpecializationTypes.ADVENTURER_SPEARMAN.name()))  return CombatRole.SPEARMAN;
        if (id.equals(NpcSpecializationTypes.ADVENTURER_ARCHER.name()))    return CombatRole.ARCHER;
        if (id.equals(NpcSpecializationTypes.ADVENTURER_MAGE.name()))      return CombatRole.MAGE;
        if (id.equals(NpcSpecializationTypes.ADVENTURER_HEALER.name()))    return CombatRole.HEALER;
        if (id.equals(NpcSpecializationTypes.ADVENTURER_SCOUT.name()))     return CombatRole.SCOUT;
        // Rookie + non-adventurer specs return null — the legacy enum
        // has no "rookie" value; goals see "no combat role set" and
        // fall through to their default-rookie branches.
        return null;
    }
}
