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
        if (cared.isPresent()) {
            Grave g = cared.get();
            npc.getMood().applyWithRawMagnitude(MoodTrigger.LETTER_FROM_FRIEND, GRIEF_EASE, now);
            npc.getMemory().add(NpcMemory.create(
                    MemoryType.SHARED_HARDSHIP, List.of(g.deceasedId()), now, REMEMBRANCE_VAL,
                    "Visited " + g.name() + "'s grave and remembered them"));
        } else {
            npc.getMood().applyWithRawMagnitude(MoodTrigger.WEATHER_PLEASANT, CONTEMPLATE, now);
        }
    }

    private static Graveyard graveyardOf(ServerLevel level, TownspersonMob npc) {
        Village village = npc.getAssignedVillageName()
                .flatMap(VillageSavedData.get(level)::getVillageByName).orElse(null);
        if (village == null) return null;
        return GraveyardSavedData.get(level).getGraveyard(village.getId()).orElse(null);
    }
}
