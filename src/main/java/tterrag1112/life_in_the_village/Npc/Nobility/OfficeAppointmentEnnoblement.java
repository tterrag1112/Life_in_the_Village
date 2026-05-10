package tterrag1112.life_in_the_village.Npc.Nobility;

import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;
import tterrag1112.life_in_the_village.Cultures.Culture;
import tterrag1112.life_in_the_village.Cultures.CultureResolver;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Kingdom.Kingdom;
import tterrag1112.life_in_the_village.Kingdom.KingdomModifier;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Npc.Office.OrgType;

import java.util.UUID;

/**
 * Track D3.3b — office-grant ennoblement.
 *
 * <p>When an NPC is seated in a kingdom-tier office (Chancellor /
 * Treasurer / Scholar / General / Magistrate / Spymaster /
 * Diplomat) and their nobility rank is 0 (commoner), they're
 * automatically promoted to rank index 1 (the lowest noble rank in
 * the culture's nobility table). King is excluded — its
 * appointment paths handle ennoblement separately (founder rank
 * at world-gen, or hereditary succession via D3.2b).
 *
 * <p>Per the user-confirmed design ("SQUIRE — lowest noble rank"),
 * this does NOT auto-found a {@link tterrag1112.life_in_the_village
 * .Kingdom.Houses.House} — the D3.2b house founding gate still
 * requires the NPC to clear its own thresholds. Ennoblement is
 * the smallest mutation: rank goes from 0 → 1.
 *
 * <p>For non-monarchy cultures (rank table empty / 1 entry), the
 * helper is a no-op — there's no noble tier to promote to.
 */
public final class OfficeAppointmentEnnoblement {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Rank index assigned on first kingdom-office appointment. */
    public static final int LOWEST_NOBLE_RANK_INDEX = 1;

    private OfficeAppointmentEnnoblement() {}

    /**
     * Called from {@code OfficeElection.seatNpc} after the holding
     * is set. No-op for non-kingdom orgs, the King office, or
     * NPCs already at rank ≥ 1.
     *
     * @param orgType   org receiving the seat (only KINGDOM acts).
     * @param orgId     kingdom id (used to look up the culture).
     * @param officeId  office canonical id (used to skip King).
     * @param npcId     newly-seated NPC's UUID.
     * @param level     server level for entity lookup.
     */
    public static void onSeat(OrgType orgType, UUID orgId, String officeId,
                              UUID npcId, ServerLevel level) {
        if (orgType != OrgType.KINGDOM) return;
        if (npcId == null || level == null) return;
        // King is sovereign — its appointment path is handled by
        // KingdomOfficeBootstrap / hereditary succession.
        if ("kingdom_king".equals(officeId)) return;

        VillageSavedData data = VillageSavedData.get(level);
        Kingdom kingdom = data.getKingdomById(orgId).orElse(null);
        if (kingdom == null) return;

        Culture culture = CultureResolver.ofKingdom(kingdom);
        if (culture == null) return;
        var ranks = culture.kingdomDefaults().nobilityRanks();
        if (ranks == null || ranks.size() < 2) {
            // Non-monarchy culture or single-rank table — no
            // noble tier to promote to.
            return;
        }

        TownspersonMob npc = TownspersonMob.findByUUID(level, npcId).orElse(null);
        if (npc == null) {
            LOGGER.debug("[OfficeAppointmentEnnoblement] NPC {} not loaded; "
                    + "skipping ennoblement for office {}",
                    npcId, officeId);
            return;
        }

        NobilityComponent nob = npc.getNobility();
        if (nob.getRankIndex() >= LOWEST_NOBLE_RANK_INDEX) return;

        nob.setRankIndex(LOWEST_NOBLE_RANK_INDEX);
        String rankName = NobilityRanks.nameFor(culture, LOWEST_NOBLE_RANK_INDEX);
        LOGGER.info("[OfficeAppointmentEnnoblement] {} ennobled to {} on appointment to {}",
                npc.getNpcName(), rankName, officeId);

        // Briefly record the ennoblement on the kingdom for legibility.
        kingdom.addModifier(KingdomModifier.expiring(
                "office.ennoblement." + officeId,
                "Ennoblement of " + npc.getNpcName() + " (" + rankName + ")",
                0, +1,
                level.getGameTime(),
                /*durationTicks=*/ 24000L * 3));
        data.setDirty();
    }
}
