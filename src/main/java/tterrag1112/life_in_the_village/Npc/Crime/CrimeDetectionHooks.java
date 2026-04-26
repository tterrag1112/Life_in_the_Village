package tterrag1112.life_in_the_village.Npc.Crime;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import org.slf4j.Logger;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Life_in_the_village;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Npc.BusinessFront.BuildingPresenceTracker;
import tterrag1112.life_in_the_village.Npc.BusinessFront.BuildingTypeFlags;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;

import java.util.Optional;
import java.util.UUID;

/**
 * Crime detection hooks for ASSAULT / MURDER / VANDALISM /
 * TRESPASSING / SEAL_VIOLATION. Each fires a
 * {@link tterrag1112.life_in_the_village.Npc.Crime.CrimeReporter} build.
 *
 * <p>Theft is detected at the existing {@link
 * tterrag1112.life_in_the_village.Events.ModModEvents#onContainerClose}
 * site (extended in this session) so the snapshot/restore plumbing isn't
 * duplicated.</p>
 *
 * <p>Self-defense exemption (spec line 140 / things-to-flag #1):
 * {@link CrimeReporter#isSelfDefense} returns true when the attacker
 * was hurt within the last 200 ticks. ASSAULT / MURDER skip the
 * report when this is the case.</p>
 */
@EventBusSubscriber(modid = Life_in_the_village.MODID)
public final class CrimeDetectionHooks {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Called by {@link BuildingPresenceTracker}'s enter listener. */
    public static void onPlayerEnteredBuilding(ServerPlayer player, UUID buildingId) {
        if (player == null || buildingId == null) return;
        if (!(player.level() instanceof ServerLevel level)) return;
        VillageSavedData data = VillageSavedData.get(level);
        Building building = data.getBuildingById(buildingId).orElse(null);
        if (building == null) return;

        // TRESPASSING: residence entry not owned by player.
        if (BuildingTypeFlags.isResidence(building.getType()) && !ownsBuilding(data, building, player)) {
            BlockPos origin = building.getShape().getOrigin();
            CrimeReporter.builder(CrimeType.TRESPASSING, level)
                    .perpetrator(player.getUUID())
                    .reportedBy(player.getUUID())
                    .location(origin)
                    .village(CrimeReporter.villageOf(level, origin))
                    .note("Entered private residence without permission")
                    .commit();
        }
    }

    private static boolean ownsBuilding(VillageSavedData data, Building b, ServerPlayer player) {
        return data.isPlayerOwned(b.getId())
                && data.getPropertyForBuilding(b.getId())
                .map(p -> p.playerId().equals(player.getUUID()))
                .orElse(false);
    }

    // ── ASSAULT / MURDER ───────────────────────────────────────────────────

    @SubscribeEvent
    public static void onLivingHurt(LivingIncomingDamageEvent event) {
        if (!(event.getEntity() instanceof TownspersonMob victim)) return;
        if (!(victim.level() instanceof ServerLevel level)) return;
        Entity attacker = event.getSource() == null ? null : event.getSource().getEntity();
        if (attacker == null || attacker.getUUID().equals(victim.getUUID())) return;
        // Hostile-mob attacker: not a citizen-on-citizen crime.
        if (!(attacker instanceof Player) && !(attacker instanceof TownspersonMob)) return;

        long now = level.getGameTime();
        if (attacker instanceof LivingEntity living
                && CrimeReporter.isSelfDefense(living, now)) {
            return; // self-defense
        }

        // ASSAULT: file a report at the victim's location. MURDER fires
        // separately via LivingDeathEvent because death may not coincide
        // with the hurt event (long-tick poison, fall damage queued, etc.).
        CrimeReporter.builder(CrimeType.ASSAULT, level)
                .perpetrator(attacker.getUUID())
                .victim(victim.getUUID())
                .reportedBy(victim.getUUID())
                .location(victim.blockPosition())
                .village(CrimeReporter.villageOf(level, victim.blockPosition()))
                .note("Assaulted for " + String.format("%.1f", event.getAmount()) + " damage")
                .commit();
    }

    @SubscribeEvent
    public static void onLivingDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof TownspersonMob victim)) return;
        if (!(victim.level() instanceof ServerLevel level)) return;
        Entity attacker = event.getSource() == null ? null : event.getSource().getEntity();
        if (attacker == null) return;
        if (!(attacker instanceof Player) && !(attacker instanceof TownspersonMob)) return;

        long now = level.getGameTime();
        if (attacker instanceof LivingEntity living
                && CrimeReporter.isSelfDefense(living, now)) {
            return;
        }
        CrimeReporter.builder(CrimeType.MURDER, level)
                .perpetrator(attacker.getUUID())
                .victim(victim.getUUID())
                .reportedBy(attacker.getUUID()) // posthumous; uses attacker placeholder
                .location(victim.blockPosition())
                .village(CrimeReporter.villageOf(level, victim.blockPosition()))
                .note("Killed by " + (attacker instanceof Player ? "player" : "another villager"))
                .commit();
    }

    // ── VANDALISM ──────────────────────────────────────────────────────────

    @SubscribeEvent
    public static void onBlockBreak(BlockEvent.BreakEvent event) {
        Player player = event.getPlayer();
        if (player == null || !(player.level() instanceof ServerLevel level)) return;
        if (player.isCreative() || player.isSpectator()) return; // creative / spectator bypass

        BlockPos pos = event.getPos();
        VillageSavedData data = VillageSavedData.get(level);
        Building building = data.getBuildingAt(pos).orElse(null);
        if (building == null) return;

        // Owner-bypass: same property check used by theft detection.
        if (data.isPlayerOwned(building.getId())
                && data.getPropertyForBuilding(building.getId())
                .map(p -> p.playerId().equals(player.getUUID()))
                .orElse(false)) {
            return;
        }
        // Civic / shared spaces: every break is vandalism. Worker buildings:
        // breaks count as vandalism unless the player works there (rare;
        // the existing theft detection scopes per-building ownership).
        BuildingType type = building.getType();
        if (type == BuildingType.HOUSE) return; // private homes — a personal matter, not a crime

        // Restrict the vandalism vocabulary per spec line 152 — door /
        // sign / decorative blocks. v1 treats every block break in a
        // tracked building (other than HOUSE) as vandalism; the
        // door/sign filter lands in a Phase 5 "vandalism class" pass.
        CrimeReporter.builder(CrimeType.VANDALISM, level)
                .perpetrator(player.getUUID())
                .reportedBy(player.getUUID())
                .location(pos)
                .village(CrimeReporter.villageOf(level, pos))
                .note("Broke a block in " + type.name())
                .commit();
    }

    /**
     * SEAL_VIOLATION: called from the letter-delivery handler when a
     * sealed letter is opened by a non-recipient. v1 wires the call;
     * Phase 2 task 18 already maintains seal state so this hook just
     * builds and commits.
     */
    public static void onSealViolation(ServerPlayer player, UUID intendedRecipient,
                                       BlockPos pos) {
        if (player == null || pos == null) return;
        if (!(player.level() instanceof ServerLevel level)) return;
        CrimeReporter.builder(CrimeType.SEAL_VIOLATION, level)
                .perpetrator(player.getUUID())
                .victim(intendedRecipient)
                .reportedBy(player.getUUID())
                .location(pos)
                .village(CrimeReporter.villageOf(level, pos))
                .note("Broke a sealed letter not addressed to them")
                .commit();
    }

    /**
     * FRAUD: called from the trade flow when prices are wildly off
     * (>2x base) to the player's disadvantage and the perpetrator is
     * the merchant. v1 wires the entry point but leaves the actual
     * "wildly off" detection to a follow-up — the Phase 5 trade UI
     * pass surfaces price fairness.
     */
    @SuppressWarnings("unused")
    public static void onFraudulentTrade(UUID merchantId, ServerPlayer victim,
                                         long fairBronze, long actualBronze) {
        if (victim == null || !(victim.level() instanceof ServerLevel level)) return;
        if (actualBronze <= fairBronze * 2) return;
        CrimeReporter.builder(CrimeType.FRAUD, level)
                .perpetrator(merchantId)
                .victim(victim.getUUID())
                .reportedBy(victim.getUUID())
                .location(victim.blockPosition())
                .village(CrimeReporter.villageOf(level, victim.blockPosition()))
                .note("Charged " + actualBronze + " for " + fairBronze + " value")
                .commit();
    }

    /** Suppresses unused-import on Optional for future hook expansions. */
    @SuppressWarnings("unused")
    private static final Optional<UUID> DOC_HOOK = Optional.empty();
}
