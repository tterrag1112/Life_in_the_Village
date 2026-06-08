package tterrag1112.life_in_the_village.Npc.Religion;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Npc.Roles.NpcRoleTypes;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.Economy.Currency.CoinHelper;
import tterrag1112.life_in_the_village.Village.Economy.Currency.CurrencyValue;
import tterrag1112.life_in_the_village.Village.Economy.EconomicBalance;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.Map;
import java.util.UUID;

/**
 * Religion Rework R4d-1 — the steady religious income that keeps a devout
 * village's temple solvent (feeding R4a's {@code BuildingEconomy} → R4c
 * solvency). Two recurring tithers, both reusing the one tithe primitive
 * ({@link #contribute} — wallet debit → building-economy deposit, the R4a
 * mechanism {@code handleTithe} also routes through):
 * <ul>
 *   <li><b>NPC</b> — each devout, locally-served adherent weekly auto-tithes to
 *       their same-faith building (a minority tithes to their shrine; the
 *       unserved don't tithe locally — consistent with R3e).</li>
 *   <li><b>Player</b> — an opt-in (the {@code pay_tithe} verb) weekly auto-deducts
 *       the player's tithe to their chosen temple, growing their piety.</li>
 * </ul>
 *
 * <p>Weekly + per-payer-staggered (by UUID) on the existing daily religion tick
 * so payments don't spike; bounded amount ({@link EconomicBalance#TITHE_AMOUNT});
 * affordability-gated (a poor payer / lapsed adherent skips).</p>
 */
public final class Tithing {

    private Tithing() {}

    private static final long  DAY         = 24000L;
    /** FAITHFUL+ — lapsed/UNAFFILIATED (< 0.2) adherents don't tithe. */
    private static final float MIN_PIETY   = 0.2f;
    /** Small piety deepening per tithe. */
    private static final float TITHE_PIETY = 0.01f;
    private static final long  AMOUNT      = EconomicBalance.TITHE_AMOUNT;

    /** Called daily from {@link RiteScheduler#dailyTick}. */
    public static void tick(ServerLevel level, long currentTick) {
        if (level == null) return;
        VillageSavedData data = VillageSavedData.get(level);
        long day = currentTick / DAY;
        tickNpcTithes(level, data, day, currentTick);
        tickPlayerTithes(level, data, day, currentTick);
    }

    // ── NPC recurring tithe ──────────────────────────────────────────────────

    private static void tickNpcTithes(ServerLevel level, VillageSavedData data,
                                      long day, long now) {
        for (var e : level.getEntities().getAll()) {
            if (!(e instanceof TownspersonMob npc) || !npc.isAlive()) continue;
            if (npc.isVisitor()) continue;
            if (npc.getRoles().hasRole(NpcRoleTypes.PILGRIM)) continue;       // away
            if (!dueThisWeek(day, npc.getUUID())) continue;                   // weekly stagger
            if (npc.getPiety().primaryStrength() < MIN_PIETY) continue;       // lapsed
            String faith = npc.getPiety().primaryReligion().orElse(null);
            if (faith == null) continue;
            Village village = npc.getAssignedVillageName()
                    .flatMap(data::getVillageByName).orElse(null);
            if (village == null) continue;
            Building venue = BuildingFaith.religiousBuildingsByFaith(level, village).get(faith);
            if (venue == null) continue;                                      // unserved → no local tithe

            if (contribute(data, npc, venue.getId(), AMOUNT) > 0) {
                npc.getPiety().adjustBelief(faith, TITHE_PIETY);
                npc.getPiety().recordRiteAttendance(now);
            }
        }
    }

    // ── Player auto-tithe ────────────────────────────────────────────────────

    private static void tickPlayerTithes(ServerLevel level, VillageSavedData data,
                                         long day, long now) {
        RiteSavedData rdata = RiteSavedData.get(level);
        for (Map.Entry<UUID, UUID> entry : rdata.autoTitheTemples().entrySet()) {
            UUID playerId = entry.getKey();
            UUID buildingId = entry.getValue();
            if (!dueThisWeek(day, playerId)) continue;

            Building b = data.getBuildingById(buildingId).orElse(null);
            if (b == null || !b.getCondition().isFunctional()) {
                rdata.clearAutoTithe(playerId);                               // temple gone/ruined → opt out
                continue;
            }
            ServerPlayer player = level.getServer().getPlayerList().getPlayer(playerId);
            if (player == null) continue;                                     // offline → skip this week

            if (contributePlayer(data, player, buildingId, AMOUNT) > 0) {
                PietyComponent pp = rdata.getOrCreatePlayerPiety(playerId);
                String faith = pp.primaryReligion().orElse(ReligionRegistry.SUNSTEAD);
                pp.adjustBelief(faith, TITHE_PIETY);
                pp.recordRiteAttendance(now);
                rdata.markDirty();
            }
        }
    }

    // ── The shared tithe primitive (R4a) ─────────────────────────────────────

    /** Debits up to {@code amount} from the NPC's wallet (capped by what they
     *  hold) into the building's {@code BuildingEconomy}. Returns bronze paid. */
    public static long contribute(VillageSavedData data, TownspersonMob payer,
                                  UUID buildingId, long amount) {
        long give = Math.min(amount, payer.getWallet().toBronze());
        if (give <= 0) return 0L;
        if (!payer.getWallet().spend(CurrencyValue.of(give))) return 0L;
        data.getOrCreateBuildingEconomy(buildingId).depositRevenue(give);
        data.setDirty();
        return give;
    }

    /** Player counterpart — debits the player's inventory coins (affordability-
     *  gated, never negative) into the building economy. Returns bronze paid. */
    private static long contributePlayer(VillageSavedData data, ServerPlayer player,
                                         UUID buildingId, long amount) {
        long give = Math.min(amount, CoinHelper.countCoins(player).toBronze());
        if (give <= 0) return 0L;
        if (!CoinHelper.removeCoins(player, CurrencyValue.of(give))) return 0L;
        data.getOrCreateBuildingEconomy(buildingId).depositRevenue(give);
        data.setDirty();
        return give;
    }

    /** Weekly cadence, staggered per payer (by UUID) so tithes don't all land the
     *  same day. */
    private static boolean dueThisWeek(long day, UUID payer) {
        return (day + Math.floorMod(payer.hashCode(), 7)) % 7 == 0;
    }
}
