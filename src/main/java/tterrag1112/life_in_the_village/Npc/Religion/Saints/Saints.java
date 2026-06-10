package tterrag1112.life_in_the_village.Npc.Religion.Saints;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Npc.Religion.DivineFavour;
import tterrag1112.life_in_the_village.Npc.Religion.God;
import tterrag1112.life_in_the_village.Npc.Religion.GodRegistry;
import tterrag1112.life_in_the_village.Npc.Religion.PietyTier;
import tterrag1112.life_in_the_village.Npc.Religion.Religion;
import tterrag1112.life_in_the_village.Npc.Religion.Religions;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.UUID;

/**
 * Saints &amp; Relics SR1 — the one home for living-saint <b>transitions</b>
 * (designation + loss), kept out of the effect sites that merely read the status.
 *
 * <p><b>Two criteria paths, by necessity</b> (the divine layer — favour + theophany —
 * is player-only today): a <b>player</b> is anointed when a glory theophany fires for
 * them (already gated to PIOUS + peak favour), and lapses when their favour collapses
 * into displeasure or they fall below PIOUS / renounce the faith. An <b>NPC</b> (no
 * favour, no theophany) is designated by sustained exceptional piety — PIOUS in its
 * faith with consistent monthly attendance — and lapses when its piety falls below
 * PIOUS. Honest asymmetry; a future NPC-divine pass can unify the paths.</p>
 */
public final class Saints {

    private Saints() {}

    /** Favour at or below which a player saint's standing has "collapsed into
     *  displeasure" (revocation trigger) — the V4 curse band. */
    private static final float LAPSE_FAVOUR = DivineFavour.CURSE_AT;

    // ── Player path ──────────────────────────────────────────────────────────

    /** The anointing: a glory theophany makes the (PIOUS, peak-favour) player a living
     *  saint of {@code godId}. Idempotent. Called from {@code DivineTheophany.fireFavour}. */
    public static void anointPlayer(ServerLevel level, UUID playerId, String godId, long now) {
        SaintsSavedData.get(level).add(playerId, godId, now, true);
    }

    /** Loss review for a player saint of {@code godId}: revoke when favour has collapsed
     *  into displeasure OR they are no longer PIOUS (apostasy / lapse). Cheap (a map
     *  lookup; the tier read only when they ARE a saint) — called on the existing
     *  per-player theophany cadence, never per tick. {@code fav} is the already-read
     *  current favour with the god. */
    public static void reviewPlayerLapse(ServerLevel level, UUID playerId, String godId,
                                         float fav, long now) {
        SaintsSavedData saints = SaintsSavedData.get(level);
        if (!saints.isLivingSaint(playerId, godId)) return;
        boolean lapsed = fav <= LAPSE_FAVOUR
                || DivineFavour.tierForGod(level, playerId, godId) != PietyTier.PIOUS;
        if (lapsed) saints.remove(playerId);
    }

    // ── NPC path (sustained piety) ───────────────────────────────────────────

    /**
     * A light daily sweep: designate sustained-PIOUS NPCs as holy people of their
     * faith's primary god, and revoke any whose piety has lapsed below PIOUS. Samples
     * loaded NPCs per village (no per-tick scan). Called from {@code RiteScheduler
     * .dailyTick}.
     */
    public static void dailyNpcSweep(ServerLevel level, VillageSavedData data, long now) {
        SaintsSavedData saints = SaintsSavedData.get(level);
        for (Village village : data.getAllVillages()) {
            AABB bounds = village.getBounds(data).map(b -> b.inflate(32)).orElse(null);
            if (bounds == null) continue;
            for (TownspersonMob npc : level.getEntitiesOfClass(TownspersonMob.class, bounds,
                    m -> m.getAssignedVillageName()
                            .map(n -> n.equals(village.getName())).orElse(false))) {
                reviewNpc(level, saints, npc, now);
            }
        }
    }

    private static void reviewNpc(ServerLevel level, SaintsSavedData saints,
                                  TownspersonMob npc, long now) {
        UUID id = npc.getUUID();
        String faith = npc.getPiety().primaryReligion().orElse(null);
        boolean pious = npc.getPiety().primaryTier() == PietyTier.PIOUS;

        // Revoke a designated NPC whose piety has lapsed below PIOUS.
        if (!pious) {
            if (saints.livingSaintGod(id).isPresent()) saints.remove(id);
            return;
        }
        // Designate a sustained-PIOUS NPC (PIOUS + consistent monthly attendance).
        if (faith == null || !npc.getPiety().meetsMonthlyAttendance()) return;
        Religion r = Religions.get(level, faith);
        God god = r == null ? null : GodRegistry.primaryGod(r).orElse(null);
        if (god != null) saints.add(id, god.id(), now, false);
    }
}
