package tterrag1112.life_in_the_village.Npc.Religion;

import net.minecraft.server.level.ServerLevel;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Npc.Office.OfficeRegistry;
import tterrag1112.life_in_the_village.Npc.Skills.Skill;
import tterrag1112.life_in_the_village.Npc.Skills.SkillThresholds;
import tterrag1112.life_in_the_village.Village.Village;

/**
 * Religion Rework — Phase R1a. The single canonical gate deciding
 * whether an officiant may perform a given {@link Rite}. Both rite
 * performance paths call {@link #canOfficiate(TownspersonMob, Rite)}:
 * {@code PriestBehavior.findClaimableRite} (realized) and
 * {@code RiteExecutor.runOne} (abstract). Keep the check here, never
 * open-coded twice.
 *
 * <h3>The hybrid model</h3>
 * <p><em>Skills are what an NPC can do; profession/office are what they
 * actually do.</em> An officiant's cap is the higher of:</p>
 * <ul>
 *   <li><b>Skill</b> — SOCIAL (the PRIEST competence axis) gates the
 *       tier, with LITERACY ≥ {@link SkillThresholds#READ_LITERACY_THRESHOLD}
 *       as the secondary "can read the liturgy" requirement for STANDARD
 *       and GRAND. MINOR (rote devotions) needs neither. Thresholds are
 *       anchored to the office competence floors
 *       ({@link SkillThresholds#RITE_STANDARD_SOCIAL} /
 *       {@link SkillThresholds#RITE_GRAND_SOCIAL}).</li>
 *   <li><b>Office</b> — holding {@code VILLAGE_PRIEST} in the officiant's
 *       village raises the cap to {@link RiteTier#GRAND} regardless of
 *       skill, so a freshly-seated priest is naturally qualified for the
 *       village-wide ceremonies the seat exists to run.</li>
 * </ul>
 *
 * <p><b>High priest / KINGDOM deferred.</b> The prompt's
 * {@code TEMPLE_HIGH_PRIEST → KINGDOM} raise is intentionally not wired
 * this phase: there is no KINGDOM-tier rite yet (see {@link RiteTier}),
 * and temple offices are stubbed in v1 ({@code OfficeRegistry
 * .findOfficesHeldBy} produces no temple matches), so a seated high
 * priest is not yet resolvable. The kingdom-rites phase adds the KINGDOM
 * tier and the high-priest branch together. A high priest who is also
 * the village's seated priest still reaches GRAND through the
 * VILLAGE_PRIEST branch.</p>
 */
public final class RiteCapability {

    private RiteCapability() {}

    /** True when {@code officiant} may perform {@code rite}. */
    public static boolean canOfficiate(TownspersonMob officiant, Rite rite) {
        if (officiant == null || rite == null) return false;
        return RiteTier.tierOf(rite).ordinal() <= capOf(officiant).ordinal();
    }

    /** The highest {@link RiteTier} {@code officiant} can perform. */
    public static RiteTier capOf(TownspersonMob officiant) {
        // Office cap — a seated village priest tops out at GRAND.
        if (holdsVillagePriest(officiant)) return RiteTier.GRAND;

        // Skill cap — SOCIAL primary, LITERACY secondary.
        int social   = officiant.getSkills().getLevel(Skill.SOCIAL);
        int literacy = officiant.getSkills().getLevel(Skill.LITERACY);
        boolean canRead = literacy >= SkillThresholds.READ_LITERACY_THRESHOLD;

        if (canRead && social >= SkillThresholds.RITE_GRAND_SOCIAL)    return RiteTier.GRAND;
        if (canRead && social >= SkillThresholds.RITE_STANDARD_SOCIAL) return RiteTier.STANDARD;
        return RiteTier.MINOR;
    }

    /** Whether the officiant currently holds the {@code VILLAGE_PRIEST}
     *  office in its own village. Cheap per-claim: a single map get on the
     *  village's {@link tterrag1112.life_in_the_village.Npc.Office.OfficeState}. */
    private static boolean holdsVillagePriest(TownspersonMob officiant) {
        if (!(officiant.level() instanceof ServerLevel sl)) return false;
        Village village = officiant.getAssignedVillageName()
                .flatMap(VillageSavedData.get(sl)::getVillageByName)
                .orElse(null);
        if (village == null) return false;
        return village.getOffices().get(OfficeRegistry.VILLAGE_PRIEST)
                .filter(h -> h.isHeldBy(officiant.getUUID()))
                .isPresent();
    }
}
