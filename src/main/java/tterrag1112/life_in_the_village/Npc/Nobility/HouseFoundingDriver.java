package tterrag1112.life_in_the_village.Npc.Nobility;

import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;
import org.slf4j.Logger;
import tterrag1112.life_in_the_village.Cultures.Culture;
import tterrag1112.life_in_the_village.Cultures.CultureResolver;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Kingdom.HeraldryGenerator;
import tterrag1112.life_in_the_village.Kingdom.Houses.House;
import tterrag1112.life_in_the_village.Kingdom.Kingdom;
import tterrag1112.life_in_the_village.Kingdom.KingdomModifier;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.Optional;
import java.util.UUID;

/**
 * Track D3.2b — daily-tick driver that founds noble houses for
 * eligible NPCs. Consumes the {@link NobilityComponent#FOUNDING_THRESHOLD}
 * + {@link NobilityComponent#isFoundingEligible()} predicate that
 * D3.2a shipped, plus the per-culture {@code minNobilityTier} gate
 * from {@code CultureKingdomDefaults}.
 *
 * <h3>Eligibility</h3>
 * <ul>
 *   <li>NPC has no {@code dynastyHouseId} set.</li>
 *   <li>NPC's prestige ≥ {@link NobilityComponent#FOUNDING_THRESHOLD}.</li>
 *   <li>NPC's rank index ≥ {@code culture.minNobilityTier}.</li>
 *   <li>NPC is assigned to a village whose kingdom is known.</li>
 *   <li>NPC is an adult (children + retired NPCs don't found).</li>
 * </ul>
 *
 * <h3>Founding outcome</h3>
 * <ul>
 *   <li>New {@link House} is created with a fresh UUID, name
 *       derived from the NPC's last name (or first name fallback),
 *       founder = current NPC, foundingTick = current tick,
 *       headUuid = current NPC, heraldry from
 *       {@link HeraldryGenerator#forHouse}, prestige = NPC's
 *       prestige at founding, motto empty.</li>
 *   <li>NPC's {@code dynastyHouseId} updates to the new house id.</li>
 *   <li>Kingdom emits {@link KingdomModifier} {@code "house.founded"}
 *       (+1 stability, +1 legitimacy, expires after 7 days).</li>
 * </ul>
 */
public final class HouseFoundingDriver {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** "house.founded" modifier duration (7 in-game days). */
    private static final long HOUSE_FOUNDED_DURATION = 24000L * 7L;

    /**
     * Search radius around each village centre when collecting
     * loaded NPCs. Mirrors the food/effects helper's inflation.
     */
    private static final double VILLAGE_SCAN_INFLATE = 64.0;

    private HouseFoundingDriver() {}

    /**
     * Daily entry point. Iterates every kingdom's villages,
     * collects loaded adult NPCs, and founds a house for each
     * eligible one.
     *
     * @return number of houses founded this tick.
     */
    public static int dailyTick(ServerLevel level, VillageSavedData data, long tick) {
        int founded = 0;
        for (Kingdom kingdom : data.getAllKingdoms()) {
            for (UUID vid : kingdom.getVillageIds()) {
                Village village = data.getVillageById(vid).orElse(null);
                if (village == null) continue;
                AABB bounds = village.getBounds(data)
                        .map(b -> b.inflate(VILLAGE_SCAN_INFLATE))
                        .orElse(null);
                if (bounds == null) continue;
                var npcs = level.getEntitiesOfClass(
                        TownspersonMob.class, bounds,
                        npc -> npc.getAssignedVillageName()
                                .map(n -> n.equals(village.getName()))
                                .orElse(false));
                for (TownspersonMob npc : npcs) {
                    if (tryFound(level, data, kingdom, npc, tick)) founded++;
                }
            }
        }
        if (founded > 0) {
            data.setDirty();
            LOGGER.info("[HouseFoundingDriver] founded {} noble house(s) on tick {}", founded, tick);
        }
        return founded;
    }

    /**
     * Single-NPC founding attempt; public for unit tests + the
     * debug command. Returns {@code true} when a house was
     * actually created.
     */
    public static boolean tryFound(ServerLevel level, VillageSavedData data,
                                   Kingdom kingdom, TownspersonMob npc, long tick) {
        if (npc == null || !npc.isAdult()) return false;
        NobilityComponent nob = npc.getNobility();
        if (!nob.isFoundingEligible()) return false;

        Culture culture = CultureResolver.of(npc);
        int minTier = culture.kingdomDefaults().minNobilityTier();
        if (nob.getRankIndex() < minTier) return false;

        UUID houseId = UUID.randomUUID();
        UUID founder = npc.getUUID();
        String houseName = deriveHouseName(npc);

        House house = new House(
                houseId,
                houseName,
                kingdom.getId(),
                founder,
                tick,
                Optional.of(founder),
                HeraldryGenerator.forHouse(houseId, founder),
                nob.getPrestige(),
                "");

        kingdom.addHouse(house);
        nob.setDynastyHouseId(houseId);

        kingdom.addModifier(KingdomModifier.expiring(
                "house.founded",
                "Founding of " + houseName + " by " + npc.getNpcName(),
                +1, +1,
                tick,
                HOUSE_FOUNDED_DURATION));

        // Track D3.3 — event-driven province recompute invalidation.
        // A new noble house is the canonical "manor changes hands"
        // signal that promotes a recompute beyond the weekly cadence.
        kingdom.setLastProvinceRecomputeTick(-1L);

        return true;
    }

    /**
     * Best-effort house name from an NPC name. Splits on the last
     * space ("John Stoneblossom" → "House Stoneblossom"); falls
     * back to the full name when no surname is present.
     */
    private static String deriveHouseName(TownspersonMob npc) {
        String full = npc.getNpcName();
        if (full == null || full.isBlank()) return "House Unnamed";
        int sp = full.lastIndexOf(' ');
        String tail = (sp >= 0 && sp + 1 < full.length()) ? full.substring(sp + 1) : full;
        return "House " + tail;
    }
}
