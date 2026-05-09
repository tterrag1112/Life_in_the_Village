package tterrag1112.life_in_the_village.Kingdom.Worldgen;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import org.slf4j.Logger;
import tterrag1112.life_in_the_village.Cultures.Culture;
import tterrag1112.life_in_the_village.Cultures.CultureBundles;
import tterrag1112.life_in_the_village.Cultures.CultureRegistry;
import tterrag1112.life_in_the_village.Entities.ModEntities;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Kingdom.Events.KingdomEvent;
import tterrag1112.life_in_the_village.Kingdom.Events.KingdomEventBus;
import tterrag1112.life_in_the_village.Kingdom.Kingdom;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Npc.Office.OfficeDefinition;
import tterrag1112.life_in_the_village.Npc.Office.OfficeHolding;
import tterrag1112.life_in_the_village.Npc.Office.OfficeRegistry;
import tterrag1112.life_in_the_village.Npc.Office.OfficeState;
import tterrag1112.life_in_the_village.Npc.Office.SelectionMethod;
import tterrag1112.life_in_the_village.Profession.Profession;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;

/**
 * Track D3.1 — post-realisation office staffing for capital villages.
 * Runs once per kingdom on the first server tick after the capital
 * is realised (i.e. {@code Village.getBuildingIds()} is non-empty).
 *
 * <h3>Hybrid staffing strategy (user-confirmed)</h3>
 * <ul>
 *   <li><b>Founding ruler</b>: fresh-spawn a {@code KINGDOM_RULER}
 *       {@link TownspersonMob} at the capital's anchor position and
 *       seat them as {@code kingdom_king}. Fires
 *       {@link KingdomEvent.RulerSucceeded}.</li>
 *   <li><b>Culture-required offices</b>: for each office id listed
 *       in the culture's {@code requiredOffices} (D1 sub-bundle):
 *       try to draft an existing inhabitant whose profession matches
 *       the office's {@link OfficeDefinition#eligibleProfessions};
 *       if no compatible inhabitant is loaded, fresh-spawn an NPC
 *       with the office's first eligible profession. Fires
 *       {@link KingdomEvent.OfficeFilled} per filled office.</li>
 *   <li><b>Optional offices</b>: not staffed at bootstrap; remain
 *       vacant. D3 phase 3 ticks population and turnover for
 *       optional offices.</li>
 * </ul>
 *
 * <h3>Idempotency</h3>
 * Gated on {@code OfficeState.isVacant("kingdom_king")}. Once the
 * king is seated, this bootstrap skips the kingdom on subsequent
 * ticks. King death + succession is D3 phase 2+ territory; D3.1
 * never depopulates kingdom_king once seated.
 *
 * <h3>Caveats</h3>
 * Fresh-spawned NPCs are placed at the village anchor with no
 * household / building assignment. Their profession is set; their
 * behaviour goal is whatever NpcAi defaults to for that profession.
 * Most kingdom-tier professions (GENERAL / MAGISTRATE / SPYMASTER /
 * DIPLOMAT / SCHOLAR) have no behaviour goal in D3.1 — they idle.
 * D3 phase 3 wires office-tier behaviour.
 */
public final class KingdomOfficeBootstrap {

    private static final Logger LOGGER = LogUtils.getLogger();

    private KingdomOfficeBootstrap() {}

    /**
     * Drives the bootstrap. Safe to call every tick — short-circuits
     * fast on kingdoms whose king is already seated.
     */
    public static void runOnce(ServerLevel level) {
        VillageSavedData vdata = VillageSavedData.get(level);

        for (Kingdom k : vdata.getAllKingdoms()) {
            UUID capitalId = k.getCapitalVillageId().orElse(null);
            if (capitalId == null) continue;
            Village capital = vdata.getVillageById(capitalId).orElse(null);
            if (capital == null) continue;

            // Realised? (V2 villages populate buildingIds at realisation
            // time. Pre-realised virtual villages have an empty list.)
            if (capital.getBuildingIds().isEmpty()) continue;

            // Already bootstrapped? (king is the gate.)
            OfficeState state = k.getOffices();
            if (state == null) continue;
            if (!state.isVacant(OfficeRegistry.KINGDOM_KING)) continue;

            bootstrap(level, k, capital, vdata);
        }
    }

    private static void bootstrap(ServerLevel level, Kingdom kingdom,
                                   Village capital, VillageSavedData vdata) {
        Culture cultureRecord = CultureRegistry.getOrDefault(kingdom.getCulture());
        CultureBundles.CultureKingdomDefaults kd = cultureRecord.kingdomDefaults();
        long tick = level.getGameTime();
        Random rng = new Random(kingdom.getId().getMostSignificantBits()
                ^ kingdom.getId().getLeastSignificantBits()
                ^ kingdom.getFoundingTick());

        // ── Founding ruler: fresh-spawn KINGDOM_RULER ──────────────
        TownspersonMob ruler = freshSpawn(level, capital, Profession.KINGDOM_RULER, rng);
        if (ruler == null) {
            LOGGER.warn("[KingdomOfficeBootstrap] Could not fresh-spawn ruler for '{}'",
                    kingdom.getName());
            return;
        }
        OfficeHolding kingHolding = OfficeHolding.heldByNpc(
                OfficeRegistry.KINGDOM_KING, kingdom.getId(), ruler.getUUID(),
                tick, 0L, SelectionMethod.HEREDITARY);
        kingdom.getOffices().set(OfficeRegistry.KINGDOM_KING, kingHolding);
        kingdom.setRulerEntityId(ruler.getUUID());
        vdata.markDirty();

        KingdomEventBus.fire(new KingdomEvent.RulerSucceeded(
                kingdom.getId(), null, ruler.getUUID(), tick));
        KingdomEventBus.fire(new KingdomEvent.OfficeFilled(
                kingdom.getId(), OfficeRegistry.KINGDOM_KING,
                ruler.getUUID(), tick));

        LOGGER.info("[KingdomOfficeBootstrap] '{}' got ruler {} (kingdom_king)",
                kingdom.getName(), ruler.getUUID().toString().substring(0, 8));

        // ── Culture-required offices: hybrid draft + fresh-spawn ──
        int filled = 1; // king already counted
        for (String officeId : kd.requiredOffices()) {
            if (officeId.equals(OfficeRegistry.KINGDOM_KING)) continue;
            if (!state.isVacant(officeId)) continue;
            OfficeDefinition def = OfficeRegistry.get(officeId);
            if (def == null) continue;

            UUID holderId = draftFromInhabitants(level, capital, def);
            SelectionMethod method = SelectionMethod.APPOINTED;
            if (holderId == null) {
                // Fresh-spawn the first eligible profession.
                if (def.eligibleProfessions().isEmpty()) continue;
                Profession freshProf = def.eligibleProfessions().get(0);
                TownspersonMob mob = freshSpawn(level, capital, freshProf, rng);
                if (mob == null) continue;
                holderId = mob.getUUID();
                method = SelectionMethod.APPOINTED;
            }
            kingdom.getOffices().set(officeId, OfficeHolding.heldByNpc(
                    officeId, kingdom.getId(), holderId, tick, 0L, method));
            KingdomEventBus.fire(new KingdomEvent.OfficeFilled(
                    kingdom.getId(), officeId, holderId, tick));
            filled++;
        }
        vdata.markDirty();
        LOGGER.info("[KingdomOfficeBootstrap] '{}' filled {} office(s)",
                kingdom.getName(), filled);
    }

    /**
     * Looks for an existing capital inhabitant whose profession is in
     * {@code def.eligibleProfessions()}. Returns the NPC's UUID or null.
     */
    private static UUID draftFromInhabitants(ServerLevel level,
                                              Village capital,
                                              OfficeDefinition def) {
        BlockPos anchor = capital.getAnchorPos();
        if (anchor == null) return null;
        // 96-block scan around the anchor; assumes capital NPCs are
        // typically loaded when the player is in the capital.
        var box = new net.minecraft.world.phys.AABB(
                anchor.getX() - 96, anchor.getY() - 32, anchor.getZ() - 96,
                anchor.getX() + 96, anchor.getY() + 32, anchor.getZ() + 96);
        List<TownspersonMob> nearby = level.getEntitiesOfClass(
                TownspersonMob.class, box, m -> {
                    if (def.eligibleProfessions().isEmpty()) return false;
                    return def.eligibleProfessions().contains(m.getProfession());
                });
        return nearby.isEmpty() ? null : nearby.get(0).getUUID();
    }

    /**
     * Fresh-spawns a {@link TownspersonMob} at the capital anchor
     * (jittered by 4 blocks) with the given profession. Returns null
     * if the entity create failed.
     */
    private static TownspersonMob freshSpawn(ServerLevel level, Village capital,
                                              Profession profession, Random rng) {
        BlockPos anchor = capital.getAnchorPos();
        if (anchor == null) return null;
        double jx = (rng.nextDouble() - 0.5) * 8.0;
        double jz = (rng.nextDouble() - 0.5) * 8.0;
        BlockPos spawnPos = anchor.offset((int) jx, 0, (int) jz);

        try {
            TownspersonMob mob = ModEntities.TOWNSPERSON.get()
                    .create(level, EntitySpawnReason.MOB_SUMMONED);
            if (mob == null) return null;
            mob.setPos(spawnPos.getX() + 0.5, spawnPos.getY(), spawnPos.getZ() + 0.5);
            mob.setYRot(rng.nextFloat() * 360);
            mob.finalizeSpawn(level,
                    level.getCurrentDifficultyAt(spawnPos),
                    EntitySpawnReason.MOB_SUMMONED, null);
            mob.setProfession(profession);
            level.addFreshEntity(mob);
            return mob;
        } catch (Throwable t) {
            LOGGER.warn("[KingdomOfficeBootstrap] freshSpawn failed for {}: {}",
                    profession, t.getMessage());
            return null;
        }
    }
}
