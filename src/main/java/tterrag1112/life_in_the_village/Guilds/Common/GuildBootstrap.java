package tterrag1112.life_in_the_village.Guilds.Common;

import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Profession.Profession;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Phase 4 doc 27 — bootstrap pass for implicit (L0) guilds.
 *
 * <p>For every village, count NPCs by their {@link GuildType}
 * primary mapping. Any cluster of ≥ 2 members spawns an implicit
 * guild of that type. Subsequent profession changes are handled by
 * {@link #onProfessionChanged}.</p>
 *
 * <p>Adventurer guilds intentionally skip the implicit pass: they
 * already exist as legacy {@code GuildData} records on every village
 * with a {@code GUILD_HALL}, and the v1 adventurer surface has no
 * concept of an implicit-only guild without a hall.</p>
 */
public final class GuildBootstrap {

    private static final Logger LOGGER = LogUtils.getLogger();
    public static final int IMPLICIT_THRESHOLD = 2;

    private GuildBootstrap() {}

    /**
     * Scans the village's loaded NPCs and creates / updates implicit
     * guilds for every profession cluster ≥ {@link #IMPLICIT_THRESHOLD}.
     * Existing guilds (any level) keep their state — only new clusters
     * become implicit guilds.
     */
    public static void scanAndCreateImplicit(ServerLevel level, Village village,
                                             VillageSavedData vdata, long tick) {
        GuildSavedData gdata = GuildSavedData.get(level);
        Map<GuildType, List<TownspersonMob>> clusters = clusterByGuildType(level, village, vdata);
        for (Map.Entry<GuildType, List<TownspersonMob>> e : clusters.entrySet()) {
            GuildType type = e.getKey();
            if (type == GuildType.ADVENTURER) continue; // legacy path owns this
            List<TownspersonMob> members = e.getValue();
            if (members.size() < IMPLICIT_THRESHOLD) continue;

            AbstractGuild existing = gdata.forVillageAndType(village.getId(), type).orElse(null);
            if (existing == null) {
                AbstractGuild guild = createImplicit(village, type, tick);
                for (TownspersonMob mob : members) {
                    guild.addMember(mob.getUUID(), GuildRankTier.BRONZE, tick);
                }
                gdata.putGuild(guild);
                LOGGER.info("[GuildBootstrap] Implicit {} guild created in {} ({} members)",
                        type, village.getName(), members.size());
            } else {
                // Reconcile membership against the latest scan.
                for (TownspersonMob mob : members) {
                    if (!existing.hasMember(mob.getUUID())) {
                        existing.addMember(mob.getUUID(), GuildRankTier.APPLICANT, tick);
                    }
                }
                gdata.putGuild(existing);
            }
        }
    }

    /**
     * Hook fired when an NPC's profession changes. Removes them from
     * any guild whose type doesn't match the new profession; auto-
     * joins them to the matching village guild (creating an implicit
     * one if the cluster threshold is met).
     */
    public static void onProfessionChanged(ServerLevel level, TownspersonMob npc,
                                           Profession previous, Profession current,
                                           long tick) {
        GuildSavedData gdata = GuildSavedData.get(level);
        UUID memberId = npc.getUUID();

        // Drop from any guild whose type no longer fits.
        for (AbstractGuild g : gdata.forMember(memberId)) {
            if (!g.type().accepts(current)) {
                g.removeMember(memberId);
                gdata.putGuild(g);
            }
        }

        if (current == null) return;
        GuildType newType = GuildType.primaryFor(current);
        if (newType == null || newType == GuildType.ADVENTURER) return;

        UUID villageId = npc.getAssignedVillageName()
                .flatMap(name -> VillageSavedData.get(level).getVillageByName(name))
                .map(Village::getId).orElse(null);
        if (villageId == null) return;

        AbstractGuild target = gdata.forVillageAndType(villageId, newType).orElse(null);
        if (target == null) {
            // Cluster threshold check: count current matching NPCs in
            // the village (including this one's new profession).
            Village village = VillageSavedData.get(level).getVillageById(villageId).orElse(null);
            if (village == null) return;
            int matching = countMatching(level, village, VillageSavedData.get(level), newType);
            if (matching < IMPLICIT_THRESHOLD) return;
            target = createImplicit(village, newType, tick);
            gdata.putGuild(target);
        }
        target.addMember(memberId, GuildRankTier.APPLICANT, tick);
        gdata.putGuild(target);
    }

    /**
     * Hook fired when a guild hall building is constructed. Promotes
     * the matching implicit guild to ESTABLISHED. If no implicit
     * guild exists yet (small village, no cluster), creates one
     * directly at L1.
     */
    public static AbstractGuild onHallConstructed(ServerLevel level, Building hall,
                                                  Village village, long tick) {
        if (hall == null || village == null) return null;
        GuildType type = GuildHallTypes.guildTypeForHall(hall.getType());
        if (type == null || type == GuildType.ADVENTURER) return null;

        GuildSavedData gdata = GuildSavedData.get(level);
        AbstractGuild guild = gdata.forVillageAndType(village.getId(), type).orElse(null);
        if (guild == null) {
            guild = createImplicit(village, type, tick);
        }
        guild.promoteToEstablished(hall.getId());
        gdata.putGuild(guild);
        LOGGER.info("[GuildBootstrap] {} guild promoted to ESTABLISHED in {}",
                type, village.getName());
        return guild;
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private static AbstractGuild createImplicit(Village village, GuildType type, long tick) {
        return new AbstractGuild(
                UUID.randomUUID(),
                village.getName() + " " + type.name().toLowerCase().replace('_', ' '),
                type,
                village.getId(),
                /* hallBuildingId */ null,
                GuildLevel.IMPLICIT,
                tick);
    }

    private static Map<GuildType, List<TownspersonMob>> clusterByGuildType(
            ServerLevel level, Village village, VillageSavedData vdata) {
        EnumMap<GuildType, List<TownspersonMob>> clusters = new EnumMap<>(GuildType.class);
        var bounds = village.getBounds(vdata).orElse(null);
        if (bounds == null) return clusters;
        List<TownspersonMob> nearby = level.getEntitiesOfClass(
                TownspersonMob.class, bounds.inflate(16),
                m -> m.getAssignedVillageName()
                        .map(n -> n.equals(village.getName())).orElse(false));
        for (TownspersonMob mob : nearby) {
            GuildType t = GuildType.primaryFor(mob.getProfession());
            if (t == null) continue;
            clusters.computeIfAbsent(t, k -> new java.util.ArrayList<>()).add(mob);
        }
        return clusters;
    }

    private static int countMatching(ServerLevel level, Village village,
                                     VillageSavedData vdata, GuildType type) {
        var bounds = village.getBounds(vdata).orElse(null);
        if (bounds == null) return 0;
        return (int) level.getEntitiesOfClass(
                TownspersonMob.class, bounds.inflate(16),
                m -> m.getAssignedVillageName()
                                .map(n -> n.equals(village.getName())).orElse(false)
                        && type.accepts(m.getProfession())).size();
    }

    /** Suppresses unused-import warning on BuildingType (used in
     *  GuildHallTypes lookup). */
    @SuppressWarnings("unused")
    private static final BuildingType DOC_HOOK = null;
}
