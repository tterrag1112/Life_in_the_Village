package tterrag1112.life_in_the_village.Entities.Goals.Profession.Workshop;

import tterrag1112.life_in_the_village.Entities.Goals.Profession.Blacksmith.BlacksmithSpecialization;
import tterrag1112.life_in_the_village.Npc.Specialization.NpcSpecializationTypes;
import tterrag1112.life_in_the_village.Npc.Specialization.SpecializationDef;

import javax.annotation.Nullable;

/**
 * Phase 6.3.2.c — translates between the legacy
 * {@link ProfessionSpecialization} enum surface and the unified
 * {@link SpecializationDef} registry. Mirrors
 * {@code CombatRoleBridge} for the adventurer side.
 *
 * <p>Currently only Blacksmith is migrated. Adding a future profession
 * specialization (Weaver/Carpenter/etc.) is a matter of registering
 * its variants in {@link NpcSpecializationTypes} and adding a case
 * here if the legacy enum API is still consumed.
 */
public final class SpecializationDefBridge {

    private SpecializationDefBridge() {}

    @Nullable
    public static SpecializationDef toDef(@Nullable ProfessionSpecialization spec) {
        if (spec == null) return null;
        if (spec instanceof BlacksmithSpecialization bs) {
            return switch (bs) {
                case GENERALIST  -> NpcSpecializationTypes.BLACKSMITH_GENERALIST;
                case TOOLSMITH   -> NpcSpecializationTypes.BLACKSMITH_TOOLSMITH;
                case ARMORER     -> NpcSpecializationTypes.BLACKSMITH_ARMORER;
                case WEAPONSMITH -> NpcSpecializationTypes.BLACKSMITH_WEAPONSMITH;
            };
        }
        return null;
    }

    @Nullable
    public static ProfessionSpecialization toEnum(@Nullable SpecializationDef def) {
        if (def == null) return null;
        if (def == NpcSpecializationTypes.BLACKSMITH_GENERALIST)  return BlacksmithSpecialization.GENERALIST;
        if (def == NpcSpecializationTypes.BLACKSMITH_TOOLSMITH)   return BlacksmithSpecialization.TOOLSMITH;
        if (def == NpcSpecializationTypes.BLACKSMITH_ARMORER)     return BlacksmithSpecialization.ARMORER;
        if (def == NpcSpecializationTypes.BLACKSMITH_WEAPONSMITH) return BlacksmithSpecialization.WEAPONSMITH;
        return null;
    }
}
