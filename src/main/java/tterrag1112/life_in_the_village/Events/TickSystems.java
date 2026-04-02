package tterrag1112.life_in_the_village.Events;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.phys.AABB;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Kingdom.Kingdom;
import tterrag1112.life_in_the_village.Lore.HistoryTextGenerator;
import tterrag1112.life_in_the_village.Lore.KingdomHistoryData;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Profession.Profession;
import tterrag1112.life_in_the_village.Profession.ProfessionPerkManager;
import tterrag1112.life_in_the_village.Profession.WorkplaceAssignmentManager;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Buildings.HousePurchaseManager;
import tterrag1112.life_in_the_village.Village.Buildings.VillageExpansionManager;
import tterrag1112.life_in_the_village.Village.Decoration.Roads.VillagePath;
import tterrag1112.life_in_the_village.Village.Decoration.VillageAgingManager;
import tterrag1112.life_in_the_village.Village.Decoration.VillageBiomeStyle;

import tterrag1112.life_in_the_village.Village.Decoration.VillageDecorator;
import tterrag1112.life_in_the_village.Village.Economy.CraftingOrderManager;
import tterrag1112.life_in_the_village.Village.Economy.Currency.CurrencyValue;
import tterrag1112.life_in_the_village.Village.Economy.Trade.TradeRouteManager;
import tterrag1112.life_in_the_village.Village.Economy.VillageEconomy;
import tterrag1112.life_in_the_village.Village.Event.VillageEventScheduler;
import tterrag1112.life_in_the_village.Village.Needs.VillageNeedsCalculator;
import tterrag1112.life_in_the_village.Village.Simulation.KingdomEconomyEngine;
import tterrag1112.life_in_the_village.Village.Simulation.VillageSimEngine;
import tterrag1112.life_in_the_village.Village.Village;
import tterrag1112.life_in_the_village.Village.VillageWarningSystem;
import tterrag1112.life_in_the_village.Village.Needs.NeedCategory;

import java.util.Map;
import java.util.UUID;

// =============================================================================
// EVERY-TICK SYSTEMS (interval = 1)
// =============================================================================

/**
 * Village events must be checked every tick to correctly transition
 * ANNOUNCED → ACTIVE → ENDED based on precise timing.
 */
class EventTickSystem implements TickSubsystem {
    @Override public String name()     { return "events"; }
    @Override public int    interval() { return 1; }
    @Override public int    priority() { return 10; }

    @Override
    public void tick(TickContext ctx) {
        VillageEventScheduler.tickEvents(ctx.level(), ctx.villageData(), ctx.tick());
    }
}

// =============================================================================
// EVERY-SECOND SYSTEMS (interval = 20)
// =============================================================================

class AdventurerTickSystem implements TickSubsystem {
    @Override public String name()     { return "adventurer"; }
    @Override public int    interval() { return 20; }
    @Override public int    priority() { return 100; }

    @Override
    public void tick(TickContext ctx) {
        ctx.adventurerData().tick(ctx.level(), ctx.villageData());
    }
}

class CaravanTickSystem implements TickSubsystem {
    @Override public String name()     { return "caravan"; }
    @Override public int    interval() { return 20; }
    @Override public int    priority() { return 100; }

    @Override
    public void tick(TickContext ctx) {
        ctx.caravanData().tick(ctx.level(), ctx.villageData());
    }
}

class CompanyTickSystem implements TickSubsystem {
    @Override public String name()     { return "company"; }
    @Override public int    interval() { return 20; }
    @Override public int    priority() { return 100; }

    @Override
    public void tick(TickContext ctx) {
        ctx.companyData().tick(ctx.level(), ctx.villageData(), ctx.tick());
    }
}

class TradeRouteTickSystem implements TickSubsystem {
    @Override public String name()     { return "trade_routes"; }
    @Override public int    interval() { return 20; }
    @Override public int    priority() { return 110; }

    @Override
    public void tick(TickContext ctx) {
        TradeRouteManager.tick(ctx.level(), ctx.villageData(), ctx.tick());
    }
}

class WarningTickSystem implements TickSubsystem {
    @Override public String name()     { return "warnings"; }
    @Override public int    interval() { return 20; }
    @Override public int    priority() { return 120; }

    @Override
    public void tick(TickContext ctx) {
        VillageWarningSystem.tickWarningSpread(
                ctx.level(), ctx.villageData(), ctx.tick());
    }
}

class ExpansionTickSystem implements TickSubsystem {
    @Override public String name()     { return "expansion"; }
    @Override public int    interval() { return 20; }
    @Override public int    priority() { return 130; }

    @Override
    public void tick(TickContext ctx) {
        VillageExpansionManager.tick(
                ctx.level(), ctx.villageData(), ctx.tick());
    }
}

class AgingTickSystem implements TickSubsystem {
    @Override public String name()     { return "aging"; }
    @Override public int    interval() { return 20; }
    @Override public int    priority() { return 140; }

    @Override
    public void tick(TickContext ctx) {
        VillageAgingManager.tick(ctx.level(), ctx.villageData(), ctx.tick());
    }
}

class PropertyTaxTickSystem implements TickSubsystem {
    @Override public String name()     { return "property_tax"; }
    @Override public int    interval() { return 20; }
    @Override public int    priority() { return 150; }

    @Override
    public void tick(TickContext ctx) {
        HousePurchaseManager.tickPropertyTax(
                ctx.level(), ctx.villageData(), ctx.tick());
    }
}

class WorkplacePayTickSystem implements TickSubsystem {
    @Override public String name()     { return "workplace_pay"; }
    @Override public int    interval() { return 20; }
    @Override public int    priority() { return 160; }

    @Override
    public void tick(TickContext ctx) {
        WorkplaceAssignmentManager.tickWeeklyPay(ctx.level(), ctx.tick());
    }
}

class CraftingOrderTickSystem implements TickSubsystem {
    @Override public String name()     { return "crafting_orders"; }
    @Override public int    interval() { return 20; }
    @Override public int    priority() { return 170; }

    @Override
    public void tick(TickContext ctx) {
        CraftingOrderManager.tick(ctx.level(), ctx.villageData(), ctx.tick());
    }
}

class PlayerPerkTickSystem implements TickSubsystem {
    @Override public String name()     { return "player_perks"; }
    @Override public int    interval() { return 20; }
    @Override public int    priority() { return 200; }

    @Override
    public void tick(TickContext ctx) {
        for (ServerPlayer player :
                ctx.level().getServer().getPlayerList().getPlayers()) {
            ProfessionPerkManager.tickPassiveEffects(player);
        }
    }
}

// =============================================================================
// DAILY VILLAGE SYSTEM (runs once per in-game day, staggered per village)
// =============================================================================

/**
 * Replaces the inline per-village daily loop from the old ServerTickDispatcher.
 *
 * Each village is staggered across the 24000-tick day using a hash of its
 * name, so not all villages fire on the same tick. This was already done
 * in the old code — preserved here with the same offset logic.
 *
 * Kingdom economy evaluation is staggered separately with a +1000 tick
 * offset from the village tick to avoid clustering.
 */
class VillageDailyTickSystem implements TickSubsystem {
    @Override public String name()     { return "village_daily"; }
    @Override public int    interval() { return 1; }  // we do our own gating
    @Override public int    priority() { return 300; } // after all fast systems

    @Override
    public void tick(TickContext ctx) {
        long tick = ctx.tick();
        VillageSavedData vdata = ctx.villageData();
        ServerLevel level = ctx.level();

        for (Village village : vdata.getAllVillages()) {
            long offset = Math.abs(village.getName().hashCode() % 24000L);
            if ((tick + offset) % 24000L != 0) continue;

            // ── Needs calculation (other daily systems depend on this) ────────
            var needs = VillageNeedsCalculator.compute(level, village, vdata);
            village.setNeeds(needs);
            village.setLastNeedsUpdate(tick);

            // ── Food effects ─────────────────────────────────────────────────
            applyFoodEffects(level, village, vdata);

            // ── Event scheduling ─────────────────────────────────────────────
            VillageEventScheduler.tick(level, village, vdata, tick);

            // ── Economy maintenance ──────────────────────────────────────────
            VillageEconomy.purgeStaleListings(village.getId(), tick);

            // ── Path upgrades ────────────────────────────────────────────────
            upgradePathsIfAffordable(level, village, vdata);

            // ── Kingdom formation check ──────────────────────────────────────
            checkKingdomFormation(level, village, vdata, tick);

            // ── Simulation engine update ─────────────────────────────────────
            VillageSimEngine.tick(level, village, vdata, tick);

            // ── Kingdom economy (staggered separately) ───────────────────────
            for (Kingdom kingdom : vdata.getAllKingdoms()) {
                long kOffset = Math.abs(
                        kingdom.getName().hashCode() % 24000L);
                if ((tick + kOffset + 1000L) % 24000L == 0) {
                    KingdomEconomyEngine.evaluate(level, kingdom, vdata);
                }
            }

            vdata.setDirty();
        }
    }

    // =========================================================================
    // Helpers (moved from old ServerTickDispatcher)
    // =========================================================================

    private static void applyFoodEffects(ServerLevel level,
                                         Village village,
                                         VillageSavedData data) {
        var foodNeed = village.getNeeds().get(NeedCategory.FOOD);
        if (foodNeed == null) return;

        AABB bounds = village.getBounds(data)
                .map(b -> b.inflate(16))
                .orElse(null);
        if (bounds == null) return;

        var npcs = level.getEntitiesOfClass(
                TownspersonMob.class, bounds,
                mob -> mob.getAssignedVillageName()
                        .map(n -> n.equals(village.getName()))
                        .orElse(false));

        switch (foodNeed.getLevel()) {
            case SURPLUS -> npcs.forEach(npc ->
                    npc.addEffect(new MobEffectInstance(
                            MobEffects.SATURATION, 24000, 0, false, false)));
            case CRITICAL -> npcs.forEach(npc ->
                    npc.addEffect(new MobEffectInstance(
                            MobEffects.HUNGER, 24000, 0, false, true)));
            default -> {} // SATISFIED and LOW — no effect
        }
    }

    static void upgradePathsIfAffordable(ServerLevel level,
                                         Village village,
                                         VillageSavedData data) {
        Map<VillagePath.PathTier, CurrencyValue> thresholds = Map.of(
                VillagePath.PathTier.DIRT, CurrencyValue.ofGold(5),
                VillagePath.PathTier.GRAVEL, CurrencyValue.ofGold(15),
                VillagePath.PathTier.COBBLESTONE, CurrencyValue.ofGold(40)
        );

        VillagePath.PathTier current = village.getPathTier();
        VillagePath.PathTier next = current.next();
        if (next == null || current.isMaxTier()) return;

        CurrencyValue cost = thresholds.get(next);
        if (cost == null) return;

        long treasury = village.getTreasuryBronze();
        if (treasury < cost.toBronze()) return;

        village.depositToTreasury(cost.toBronze());
        village.setPathTier(next);

        VillageBiomeStyle style = village.getBounds(data)
                .map(b -> VillageBiomeStyle.detect(
                        level, BlockPos.containing(b.getCenter())))
                .orElse(VillageBiomeStyle.PLAINS);

        // Use new organic road upgrade system
        VillageDecorator.upgradeStreets(level, village, data, style, next);
        data.setDirty();
    }

    private static void checkKingdomFormation(ServerLevel level,
                                              Village village,
                                              VillageSavedData data,
                                              long tick) {
        if (data.getKingdomForVillage(village.getId()).isPresent()) return;

        AABB bounds = village.getBounds(data)
                .map(b -> b.inflate(32))
                .orElse(new AABB(0, 0, 0, 0, 0, 0));

        int population = (int) level.getEntitiesOfClass(
                TownspersonMob.class, bounds,
                mob -> mob.getAssignedVillageName()
                        .map(n -> n.equals(village.getName()))
                        .orElse(false)
        ).size();

        if (population < 10) return;

        boolean hasMatureTownHall = village.getBuildingIds().stream()
                .map(data::getBuildingById)
                .filter(java.util.Optional::isPresent)
                .map(java.util.Optional::get)
                .anyMatch(b -> b.getType() == BuildingType.TOWN_HALL
                        && b.getLevel() >= 2);

        if (!hasMatureTownHall) return;

        String kingdomName = village.getName() + " Kingdom";
        Kingdom kingdom = new Kingdom(kingdomName, "default");
        kingdom.addVillage(village.getId());
        data.addKingdom(kingdom);

        kingdom.getHistory().recordEvent(
                HistoryTextGenerator.kingdomFounded(
                        kingdom.getName(), village.getName(), tick),
                kingdom.getName());

        kingdom.getHistory().setOrigin(
                new KingdomHistoryData.KingdomOriginData(
                        KingdomHistoryData.KingdomOrigins.ANCIENT,
                        "the village elders",
                        new UUID(0, 0),
                        village.getName(),
                        tick,
                        kingdom.getVillageIds().size(),
                        "From many, one."));

        data.setDirty();
    }
}