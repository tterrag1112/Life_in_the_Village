package tterrag1112.life_in_the_village.Guilds.Companies;

import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;
import tterrag1112.life_in_the_village.Guilds.Common.AbstractGuild;
import tterrag1112.life_in_the_village.Guilds.Common.GuildSavedData;
import tterrag1112.life_in_the_village.Guilds.Common.GuildType;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Profession.Profession;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;

import java.util.UUID;

/**
 * T5b-1 — periodic Business ↔ Guild registration pass.
 *
 * <p>Mirrors {@link tterrag1112.life_in_the_village.Guilds.Common.GuildBootstrap}
 * (which auto-joins NPCs to their village guild): businesses and guilds
 * both form lazily, so registration is a throttled periodic sweep run
 * from {@link BusinessSavedData#tick}, not a one-shot at creation.</p>
 *
 * <p>For each active business: derive its craft {@link Profession} from
 * its building type(s), map to a {@link GuildType} via
 * {@link GuildType#primaryFor}, and look up the matching village guild
 * via {@link GuildSavedData#forVillageAndType}. If one exists and the
 * business isn't already registered to it, bind it. If the business's
 * current guild no longer matches (craft changed, guild dissolved), it
 * re-points to the current match or clears.</p>
 *
 * <p>Pure foundation: this writes only {@code Business.guildId}. No
 * request/cascade/task behavior is introduced — those are T5b-2/3.</p>
 */
public final class BusinessGuildRegistrar {

    private static final Logger LOGGER = LogUtils.getLogger();

    private BusinessGuildRegistrar() {}

    /**
     * Idempotent registration sweep over every active business. Safe to
     * call repeatedly — when affiliations are already correct it makes
     * no changes and marks nothing dirty.
     *
     * @return number of businesses whose guildId changed this pass.
     */
    public static int registerAll(ServerLevel level,
                                  BusinessSavedData bdata,
                                  VillageSavedData vdata) {
        GuildSavedData gdata = GuildSavedData.get(level);
        int changed = 0;

        for (Business business : bdata.getAllBusinesses()) {
            if (!business.isActive()) continue;

            UUID villageId = business.getHomeVillageId();
            if (villageId == null) continue;

            // 1. Derive the craft Profession from the business's buildings.
            Profession craft = craftProfession(business, vdata);

            // 2. Resolve the target guild (if any) for that craft in the
            //    business's home village.
            UUID target = null;
            if (craft != null) {
                GuildType type = GuildType.primaryFor(craft);
                if (type != null) {
                    AbstractGuild guild =
                            gdata.forVillageAndType(villageId, type).orElse(null);
                    if (guild != null) target = guild.guildId();
                }
            }

            // 3. Reconcile. Re-point to the current match, or clear when
            //    no guild matches (craft changed / guild dissolved).
            UUID current = business.getGuildId().orElse(null);
            if (java.util.Objects.equals(current, target)) continue; // idempotent

            if (target != null) {
                business.registerWithGuild(target);
                LOGGER.debug("[BusinessGuildRegistrar] {} registered with guild {} ({})",
                        business.getName(), target, craft);
            } else {
                business.clearGuildId();
                LOGGER.debug("[BusinessGuildRegistrar] {} cleared guild link (no match)",
                        business.getName());
            }
            changed++;
        }

        if (changed > 0) bdata.markDirty();
        return changed;
    }

    /**
     * Best-effort craft Profession for a business, derived from its
     * building types. Prefers the building-type signal (full coverage)
     * over {@link Business.ProducerType} (a limited 6-value enum). The
     * first building that maps to a craft Profession with a
     * {@link GuildType} wins; building order is the business's own
     * insertion order.
     *
     * @return the craft Profession, or {@code null} if no building maps
     *         to a guild-eligible craft.
     */
    public static Profession craftProfession(Business business, VillageSavedData vdata) {
        for (UUID buildingId : business.getBuildingIds()) {
            Building building = vdata.getBuildingById(buildingId).orElse(null);
            if (building == null) continue;
            BuildingType type = building.getType();
            if (type == null) continue;
            Profession p = Profession.professionFor(type);
            if (p == null || p == Profession.NONE) continue;
            // Only accept professions that resolve to a guild type.
            if (GuildType.primaryFor(p) != null) return p;
        }
        return null;
    }
}
