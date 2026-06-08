package tterrag1112.life_in_the_village.Village.Graveyard;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Npc.Memory.MemoryType;
import tterrag1112.life_in_the_village.Npc.Memory.NpcMemory;
import tterrag1112.life_in_the_village.Npc.Mood.MoodTrigger;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.List;
import java.util.Optional;

/**
 * Religion Rework R5b — the brains of the {@code visit_grave} hobby: which grave
 * an NPC visits and the grief/remembrance effect of visiting. Reuses the R5a
 * {@link Graveyard}/{@link Grave} lookup, {@link NpcRelationshipLedger} (who they
 * cared about), the mood comfort channel, and the memory log — no parallel
 * grief/visit system.
 */
public final class GraveVisit {

    private GraveVisit() {}

    /** Minimum relationship score to count a buried deceased as "cared about"
     *  (kin/close friends score well above this; a random villager does not). */
    private static final int   MIN_CARE        = 20;
    /** Grief-ease per visit for the bereaved. Uses the SAME daily-capped comfort
     *  trigger the FUNERAL rite uses, so the two don't double-dip (the per-day cap
     *  bounds total recovery) and grief eases GRADUALLY over repeated visits. */
    private static final int   GRIEF_EASE      = 5;
    /** A small calming touch for a general (non-bereaved) contemplative visit. */
    private static final int   CONTEMPLATE     = 2;
    private static final int   REMEMBRANCE_VAL = 40;
    /** R5c — reverence/resolve mood for an ancestor veneration (a DISTINCT channel
     *  from the grief-comfort {@code LETTER_FROM_FRIEND}, so worship doesn't share
     *  the mourning budget). FESTIVAL_ATTENDED is the bounded religious-observance
     *  mood (daily-cap 0.2). */
    private static final int   REVERENCE_MOOD  = 4;
    /** A small ancestor-connection that deepens the venerator's faith. */
    private static final float VENERATION_PIETY = 0.01f;

    /**
     * The grave of the buried deceased this NPC most cared about (highest positive
     * relationship ≥ {@link #MIN_CARE}), or empty when the village has no
     * graveyard / no cared-about grave.
     */
    public static Optional<Grave> caredAboutGrave(ServerLevel level, TownspersonMob npc) {
        Graveyard gy = graveyardOf(level, npc);
        if (gy == null) return Optional.empty();
        var rel = npc.getNpcRelationships();
        Grave best = null;
        int bestScore = MIN_CARE;
        for (Grave g : gy.graves()) {
            int score = rel.getScore(g.deceasedId());
            if (score > bestScore) { bestScore = score; best = g; }
        }
        return Optional.ofNullable(best);
    }

    /** The visit target: a cared-about grave's slot if one exists, else the
     *  graveyard's general {@code visitTarget}; empty when there's no graveyard. */
    public static Optional<BlockPos> visitTarget(ServerLevel level, TownspersonMob npc) {
        Graveyard gy = graveyardOf(level, npc);
        if (gy == null) return Optional.empty();
        return Optional.of(caredAboutGrave(level, npc).map(Grave::slot).orElse(gy.visitTarget()));
    }

    /**
     * Applies the visit effect once, when the NPC reaches the grave. A bereaved
     * visitor (a cared-about grave) gets a bounded grief-ease + a remembrance
     * memory tied to the deceased; a general visitor gets a small contemplative
     * mood touch. Bounded by the mood daily-stack cap (anti-farm), and gradual —
     * not a one-visit cure.
     */
    public static void contemplate(ServerLevel level, TownspersonMob npc, long now) {
        Optional<Grave> cared = caredAboutGrave(level, npc);
        boolean venerator = isAncestorVenerator(npc);
        if (cared.isPresent()) {
            // R5b grief-ease + remembrance (universal mourning of a loved one).
            Grave g = cared.get();
            npc.getMood().applyWithRawMagnitude(MoodTrigger.LETTER_FROM_FRIEND, GRIEF_EASE, now);
            npc.getMemory().add(NpcMemory.create(
                    MemoryType.SHARED_HARDSHIP, List.of(g.deceasedId()), now, REMEMBRANCE_VAL,
                    "Visited " + g.name() + "'s grave and remembered them"));
        } else if (!venerator) {
            // General contemplative visit (non-venerators without a loved one here).
            npc.getMood().applyWithRawMagnitude(MoodTrigger.WEATHER_PLEASANT, CONTEMPLATE, now);
        }
        // R5c — ancestor veneration (Forge Creed faith practice): a reverence/
        // resolve mood + a small ancestor-connection, on a DISTINCT channel from
        // the grief-ease above (worship, not mourning). Additive for a Forge
        // adherent even when also grieving a loved one.
        if (venerator) venerate(npc, now);
    }

    /** True when {@code npc}'s primary faith is the ancestor-worship Forge Creed. */
    public static boolean isAncestorVenerator(TownspersonMob npc) {
        return npc.getPiety().primaryReligion()
                .map(tterrag1112.life_in_the_village.Npc.Religion.ReligionRegistry.FORGE_CREED::equals)
                .orElse(false);
    }

    /** R5c — whether {@code npc} is drawn to the graveyard: a bereaved NPC with a
     *  cared-about grave, OR a Forge venerator where a graveyard exists. Used to
     *  bias the {@code visit_grave} hobby. */
    public static boolean drawsToGrave(ServerLevel level, TownspersonMob npc) {
        if (caredAboutGrave(level, npc).isPresent()) return true;
        return isAncestorVenerator(npc) && graveyardOf(level, npc) != null;
    }

    private static void venerate(TownspersonMob npc, long now) {
        npc.getMood().applyWithRawMagnitude(MoodTrigger.FESTIVAL_ATTENDED, REVERENCE_MOOD, now);
        String faith = npc.getPiety().primaryReligion()
                .orElse(tterrag1112.life_in_the_village.Npc.Religion.ReligionRegistry.FORGE_CREED);
        npc.getPiety().adjustBelief(faith, VENERATION_PIETY); // ancestor-connection deepens faith
    }

    private static Graveyard graveyardOf(ServerLevel level, TownspersonMob npc) {
        Village village = npc.getAssignedVillageName()
                .flatMap(VillageSavedData.get(level)::getVillageByName).orElse(null);
        if (village == null) return null;
        return GraveyardSavedData.get(level).getGraveyard(village.getId()).orElse(null);
    }
}
