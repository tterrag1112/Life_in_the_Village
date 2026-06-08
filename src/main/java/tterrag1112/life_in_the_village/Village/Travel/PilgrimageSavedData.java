package tterrag1112.life_in_the_village.Village.Travel;

import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import tterrag1112.life_in_the_village.Entities.ModEntities;
import tterrag1112.life_in_the_village.Entities.NpcNameRegistry;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Npc.Mood.MoodTrigger;
import tterrag1112.life_in_the_village.Npc.Roles.NpcRoleTypes;
import tterrag1112.life_in_the_village.Npc.Roles.RoleAssignment;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Religion Rework R3e-3b — owns + ticks every {@link Pilgrimage}. The
 * single-traveller counterpart of {@code CaravanSavedData}: it drives each
 * pilgrimage through {@link TravellingGroupEngine}, realizes/dehydrates the
 * principal entity, and on return reintegrates the adherent as a normal resident
 * with a modest boon.
 *
 * <p>Principal handling copies the caravan machinery (find-by-UUID, discard on
 * dehydrate, fresh fallback) per the approved decision — sharing its Phase-7c
 * identity limitation, to be hardened for both systems separately. One small
 * robustness addition over caravans: reintegration fresh-spawns the principal at
 * home if it was lost while unobserved, so no adherent is left a permanent
 * traveller ("no NPCs lost").</p>
 */
public class PilgrimageSavedData extends SavedData {

    private static final org.slf4j.Logger LOGGER =
            org.slf4j.LoggerFactory.getLogger(PilgrimageSavedData.class);

    private static final int TICK_INTERVAL = 20;

    /** Modest completion boon (anti-farm): a piety deepening + a mood lift. */
    private static final float BOON_PIETY = 0.05f;
    private static final int   BOON_MOOD  = 15;

    public static final SavedDataType<PilgrimageSavedData> TYPE = new SavedDataType<>(
            "pilgrimages",
            PilgrimageSavedData::new,
            RecordCodecBuilder.create(instance -> instance.group(
                    Pilgrimage.CODEC.listOf().fieldOf("pilgrimages")
                            .forGetter(d -> new ArrayList<>(d.pilgrimages.values()))
            ).apply(instance, PilgrimageSavedData::fromCodec)));

    private static PilgrimageSavedData fromCodec(List<Pilgrimage> list) {
        PilgrimageSavedData data = new PilgrimageSavedData();
        list.forEach(p -> data.pilgrimages.put(p.getPilgrimageId(), p));
        return data;
    }

    private final Map<UUID, Pilgrimage> pilgrimages = new HashMap<>();
    private long lastTickTime = 0L;

    public static PilgrimageSavedData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(TYPE);
    }

    // ── Tick ─────────────────────────────────────────────────────────────────

    public void tick(ServerLevel level, VillageSavedData villageData) {
        long currentTick = level.getGameTime();
        if (currentTick - lastTickTime < TICK_INTERVAL) return;
        lastTickTime = currentTick;

        boolean dirty = false;
        List<UUID> toRemove = new ArrayList<>();

        for (Pilgrimage p : pilgrimages.values()) {
            if (TravellingGroupEngine.tick(p, level, villageData, currentTick)) {
                dirty = true;
            }
            // Returned home — reintegrate the adherent and retire the pilgrimage.
            if (p.getState() == Pilgrimage.PilgrimState.RETURNING && p.getProgress() >= 1.0) {
                reintegrate(p, level, villageData, currentTick);
                if (p.isSpawned()) dehydratePilgrim(p, level);
                toRemove.add(p.getPilgrimageId());
                dirty = true;
            }
        }

        toRemove.forEach(pilgrimages::remove);
        if (dirty) setDirty();
    }

    // ── Dispatch (conversion: realized resident → pilgrim) ────────────────────

    /**
     * Converts a realized resident into a single-member {@link Pilgrimage} to a
     * route-connected destination village. Assigns the {@link NpcRoleTypes#PILGRIM}
     * away-state role and registers the group; the engine takes over next tick.
     */
    public Pilgrimage dispatchPilgrimage(TownspersonMob adherent, UUID routeId,
                                         UUID homeVillageId, UUID destVillageId,
                                         long currentTick) {
        Pilgrimage p = Pilgrimage.create(routeId, homeVillageId, destVillageId,
                adherent.getUUID(), currentTick);
        assignPilgrimRole(adherent);
        pilgrimages.put(p.getPilgrimageId(), p);
        setDirty();
        LOGGER.info("[Pilgrimage] {} departs {} → {} (route {})",
                adherent.getNpcName(), homeVillageId, destVillageId, routeId);
        return p;
    }

    // ── Realize / dehydrate (mirror CaravanSavedData) ─────────────────────────

    public void realizePilgrim(Pilgrimage p, BlockPos pos, ServerLevel level,
                               VillageSavedData villageData) {
        if (level == null) return;
        TownspersonMob pilgrim = findOrSpawnPrincipal(p, pos, level, villageData);
        if (pilgrim != null) {
            pilgrim.setPos(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
            assignPilgrimRole(pilgrim);
        }
        p.setSpawned(true);
    }

    public void dehydratePilgrim(Pilgrimage p, ServerLevel level) {
        if (level == null) return;
        UUID pid = p.getPrincipalId();
        if (pid != null) {
            var ent = level.getEntity(pid);
            // Discard the entity but KEEP the PILGRIM away-state in the group
            // (the entity is re-found / re-spawned on the next realization).
            if (ent instanceof TownspersonMob mob && !mob.isRemoved()) mob.discard();
        }
        p.onDespawned();
    }

    /** Find the principal by UUID, or spawn a fresh stand-in (caravan Phase-7c
     *  fallback — identity may reset; flagged for the shared hardening). */
    private TownspersonMob findOrSpawnPrincipal(Pilgrimage p, BlockPos pos,
                                                ServerLevel level, VillageSavedData villageData) {
        UUID pid = p.getPrincipalId();
        if (pid != null) {
            var existing = level.getEntity(pid);
            if (existing instanceof TownspersonMob mob && !mob.isRemoved()) return mob;
        }
        TownspersonMob fresh = ModEntities.TOWNSPERSON.get()
                .create(level, EntitySpawnReason.MOB_SUMMONED);
        if (fresh == null) return null;
        fresh.setPos(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
        fresh.finalizeSpawn(level, level.getCurrentDifficultyAt(pos),
                EntitySpawnReason.MOB_SUMMONED, null);
        fresh.setAssignedVillageName(villageData.getVillageById(p.getOriginVillageId())
                .map(Village::getName).orElse("Unknown"));
        fresh.setNpcName(NpcNameRegistry.INSTANCE.generateFirstName(fresh.isMale(), level.getRandom())
                + " " + NpcNameRegistry.INSTANCE.generateSurname(level.getRandom()));
        level.addFreshEntity(fresh);
        p.setPrincipalId(fresh.getUUID());
        LOGGER.warn("[Pilgrimage] principal {} unavailable — spawned fresh stand-in {}",
                pid, fresh.getUUID());
        return fresh;
    }

    // ── Reintegration + boon ──────────────────────────────────────────────────

    private void reintegrate(Pilgrimage p, ServerLevel level, VillageSavedData villageData,
                             long currentTick) {
        UUID pid = p.getPrincipalId();
        TownspersonMob mob = pid == null ? null
                : (level.getEntity(pid) instanceof TownspersonMob m && !m.isRemoved() ? m : null);
        // Ensure an adherent actually returns home (no NPC lost as a traveller):
        // if the principal was lost while unobserved, fresh-spawn one at home.
        if (mob == null) {
            BlockPos home = villageData.getVillageById(p.getOriginVillageId())
                    .map(Village::getVillageCentre).orElse(null);
            if (home != null) mob = findOrSpawnPrincipal(p, home, level, villageData);
        }
        if (mob == null) return;
        clearPilgrimRole(mob);
        // Completion boon — deepen the adherent's own faith + a mood lift.
        mob.getPiety().primaryReligion().ifPresent(faith ->
                mob.getPiety().adjustBelief(faith, BOON_PIETY));
        mob.getMood().applyWithRawMagnitude(MoodTrigger.FESTIVAL_ATTENDED, BOON_MOOD, currentTick);
        LOGGER.info("[Pilgrimage] {} returned home and reintegrated", mob.getNpcName());
    }

    // ── Role projection ───────────────────────────────────────────────────────

    private static void assignPilgrimRole(TownspersonMob npc) {
        npc.getRoles().assignRole(RoleAssignment.conditional(NpcRoleTypes.PILGRIM, Map.of()));
    }

    private static void clearPilgrimRole(TownspersonMob npc) {
        npc.getRoles().removeRole(NpcRoleTypes.PILGRIM);
    }

    // ── Queries ────────────────────────────────────────────────────────────────

    public Optional<Pilgrimage> getPilgrimage(UUID id) {
        return Optional.ofNullable(pilgrimages.get(id));
    }

    public Optional<Pilgrimage> getByPrincipal(UUID principalId) {
        if (principalId == null) return Optional.empty();
        for (Pilgrimage p : pilgrimages.values()) {
            if (principalId.equals(p.getPrincipalId())) return Optional.of(p);
        }
        return Optional.empty();
    }

    public List<Pilgrimage> getAllPilgrimages() {
        return new ArrayList<>(pilgrimages.values());
    }
}
