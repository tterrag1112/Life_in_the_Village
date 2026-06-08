package tterrag1112.life_in_the_village.Village.Event;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import tterrag1112.life_in_the_village.Entities.ModEntities;
import tterrag1112.life_in_the_village.Entities.NpcNameRegistry;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Profession.Profession;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.List;
import java.util.Optional;

public class EventEffects {

    public static void onEventStart(ServerLevel level,
                                    VillageEvent event,
                                    Village village,
                                    VillageSavedData data) {
        System.out.println("Event starting: " + event.getType()
                + " in " + village.getName());

        switch (event.getType()) {
            case HARVEST_FESTIVAL   -> startHarvestFestival(
                    level, event, village, data);
            case MARKET_DAY         -> startMarketDay(
                    level, event, village, data);
            case FESTIVAL_OF_LIGHTS -> startFestivalOfLights(
                    level, event, village, data);
            case TRAINING_DAY       -> startTrainingDay(
                    level, event, village, data);
            case VILLAGE_FAIR       -> startVillageFair(
                    level, event, village, data);
            // Phase 5 doc 32: every type added in Phase 5 routes to its
            // EventHandler in the registry. Phase-3 types keep their
            // bespoke logic above.
            default                 -> EventHandlerRegistry.dispatchStart(
                    event, level, village, data);
        }
        // 2d — recruit surplus producers into temporary stalls for the
        // market/fair types (centralised so registry-routed types like
        // SUMMER_MARKET are covered too; no-op for other types).
        tterrag1112.life_in_the_village.Village.Markets.Complex.EventStallManager
                .recruit(level, event, village, data);

        // Apply attendance-based eventOverride for Phase-5 types that
        // populate required/invited lists. Phase-3 types apply the
        // override per-NPC inside their dedicated start handlers.
        EventAttendance.applyOverrides(level, event, village, data);

        // R2b — religious holy-day gatherings (SUNSTEAD_EQUINOX / LOOM_THREADING
        // / TIDECALL_FULL_MOON / FORGE_CREED_KINGDOM_DAY) carry no explicit
        // attendee list; like the Phase-3 festivals, the whole village observes.
        // Stamp a village-wide override so AttendGatheringBehavior congregates
        // them at the venue (the linked blessing rite's temple — convergence).
        if (event.getCategory() == EventCategory.RELIGIOUS_RITE
                && event.getActualAttendees().isEmpty()) {
            EventAttendance.applyVillageWideOverride(level, event, village, data);
        }

        // R3e-3a — a grand festival draws PILGRIM visitors of the festival's
        // faith, who converge on the venue and swell the crowd (no-op for every
        // non-grand-festival event).
        tterrag1112.life_in_the_village.Npc.Visitor.PilgrimConvergence
                .onFestivalStart(level, event, village, data);

        // R3e-3b-2 — a grand festival also draws RESIDENT pilgrims: devout,
        // locally-unserved adherents in route-connected villages occasionally
        // depart to attend (and return with a boon). No-op for non-grand events.
        tterrag1112.life_in_the_village.Npc.Religion.PilgrimageDeparture
                .onGrandFestivalStart(level, village, event, data, level.getGameTime());

        // Announce to nearby players
        announceEvent(level, event, village, data, true);
    }

    public static void onEventEnd(ServerLevel level,
                                  VillageEvent event,
                                  Village village,
                                  VillageSavedData data) {
        System.out.println("Event ending: " + event.getType()
                + " in " + village.getName());

        // Remove decorations
        removeDecorations(level, event);

        // Tear down any temporary producer stalls this event placed (2d):
        // return goods, clear structures to the pad, remove records.
        tterrag1112.life_in_the_village.Village.Markets.Complex.EventStallManager
                .teardown(level, data, village, event.getId());

        // Remove NPC effects
        removeNpcEffects(level, village, data);

        // Phase 5 doc 32: per-type completion hooks (memory + mood + gossip
        // seeds). The registry handles every Phase-5 type; the original 5
        // Phase-3 types are no-ops in the registry, so they fall through.
        EventHandlerRegistry.dispatchComplete(event, level, village, data);

        // Announce end
        announceEvent(level, event, village, data, false);

        if (event.getType() == VillageEvent.EventType.MARKET_DAY) {
            String eventIdStr = event.getId().toString();
            village.getBounds(data).ifPresent(bounds ->
                    level.getEntitiesOfClass(
                                    TownspersonMob.class,
                                    bounds.inflate(64),
                                    mob -> mob.getProfession() == Profession.WANDERING_TRADER
                                            && eventIdStr.equals(mob.getPersistentData()
                                            .getString("marketDayEventId")))
                            .forEach(mob -> {
                                mob.setCurrentActivity("Packing up...");
                                // Short delay then discard — let them "walk away"
                                // by setting the goal's spawn tick far in the past
                                mob.getPersistentData().putLong(
                                        "wt_spawnTick",
                                        level.getGameTime() - 200000L);
                            })
            );
        }
    }

    // =========================================================================
    // EVENT STARTS
    // =========================================================================

    private static void startHarvestFestival(ServerLevel level,
                                             VillageEvent event, Village village, VillageSavedData data) {

        // Give farmers speed and strength boost
        getVillageNpcs(level, village, data).forEach(mob -> {
            // Phase 6.3.3.f — FARMHAND consolidated into FARMER; this
            // branch used to include both, now FARMER covers both.
            if (mob.getProfession() == Profession.FARMER) {
                mob.addEffect(new MobEffectInstance(
                        MobEffects.SPEED,
                        (int) event.getType().getDurationTicks(),
                        0, false, true));
                mob.addEffect(new MobEffectInstance(
                        MobEffects.HASTE,
                        (int) event.getType().getDurationTicks(),
                        1, false, true));
            }
            // All NPCs work less — socialize more
            mob.setEventOverride(VillageEvent.EventType.HARVEST_FESTIVAL);
        });

        // Temporarily satisfy food needs
        village.setNeeds(java.util.Map.of(
                tterrag1112.life_in_the_village.Village.Needs.NeedCategory.FOOD,
                new tterrag1112.life_in_the_village.Village.Needs.VillageNeed(
                        tterrag1112.life_in_the_village.Village.Needs.NeedCategory.FOOD,
                        tterrag1112.life_in_the_village.Village.Needs.NeedLevel.SURPLUS,
                        1000, 100, new java.util.HashMap<>())
        ));

        // Place harvest decorations
        placeHarvestDecorations(level, event, village, data);
    }

    private static void startMarketDay(ServerLevel level,
                                       VillageEvent event,
                                       Village village,
                                       VillageSavedData data) {
        // ── Apply event override to all villagers ────────────────────────────
        getVillageNpcs(level, village, data).forEach(mob -> {
            mob.setEventOverride(VillageEvent.EventType.MARKET_DAY);
            if (mob.getProfession() == Profession.MERCHANT) {
                mob.setEventTradeDiscount(0.8f); // 20% off
            }
        });

        // ── Cosmetic carpet+barrels, retained as ambient filler around the
        //    functional 2d producer stalls (recruited centrally in
        //    onEventStart). ──────────────────────────────────────────────────
        placeMarketDecorations(level, event, village, data);

        // ── Spawn one wandering exotic trader near the market building ───────
        spawnMarketDayTrader(level, village, data, event);
    }

    /**
     * Spawns a wandering exotic trader adjacent to the village market for the
     * duration of market day. The trader is tagged with the event ID so it can
     * be discarded when the event ends.
     */
    private static void spawnMarketDayTrader(ServerLevel level,
                                             Village village,
                                             VillageSavedData data,
                                             VillageEvent event) {
        // Find the market building
        Building market = village.getBuildingIds().stream()
                .map(data::getBuildingById)
                .filter(java.util.Optional::isPresent)
                .map(java.util.Optional::get)
                .filter(b -> b.getType() == BuildingType.MARKET)
                .findFirst()
                .orElse(null);

        BlockPos spawnPos;

        if (market != null) {
            // Spawn just outside the market — offset from its origin
            BlockPos origin = market.getShape().getOrigin();
            spawnPos = level.getHeightmapPos(
                    net.minecraft.world.level.levelgen.Heightmap.Types
                            .MOTION_BLOCKING_NO_LEAVES,
                    origin.offset(
                            4 + level.getRandom().nextInt(4),
                            0,
                            4 + level.getRandom().nextInt(4)));
        } else {
            // No market building — spawn at village centre if bounds are available
            spawnPos = village.getBounds(data)
                    .map(b -> BlockPos.containing(b.getCenter()))
                    .map(c -> level.getHeightmapPos(
                            net.minecraft.world.level.levelgen.Heightmap.Types
                                    .MOTION_BLOCKING_NO_LEAVES, c))
                    .orElse(null);
            if (spawnPos == null) return;
        }

        TownspersonMob trader = ModEntities.TOWNSPERSON.get()
                .create(level, net.minecraft.world.entity.EntitySpawnReason.NATURAL);
        if (trader == null) return;

        // Phase 6.3.3.a — gated, OTHER/SYSTEM (event-triggered trader spawn).
        tterrag1112.life_in_the_village.Npc.Career.CareerTransitions.changeProfession(
                trader, Profession.WANDERING_TRADER,
                tterrag1112.life_in_the_village.Npc.Career.ProfessionChangeRequest.Reason.OTHER,
                tterrag1112.life_in_the_village.Npc.Career.ProfessionChangeRequest.Source.SYSTEM);

        // Name the trader
        var rng = level.getRandom();
        String first = NpcNameRegistry.INSTANCE.generateFirstName(rng.nextBoolean(), rng);
        String sur   = NpcNameRegistry.INSTANCE.generateSurname(rng);
        trader.setNpcName(first + " " + sur);

        // Tag with event ID so we can discard on event end
        trader.getPersistentData().putString(
                "marketDayEventId", event.getId().toString());

        // Stamp the spawn tick so WanderingTraderGoal calculates lifespan
        // from now — market day is 1 day, so the trader's 2-day lifespan
        // covers the full event plus a short grace period after it ends
        trader.getPersistentData().putLong("wt_spawnTick", level.getGameTime());

        trader.setPos(spawnPos.getX() + 0.5, spawnPos.getY(), spawnPos.getZ() + 0.5);
        level.addFreshEntity(trader);

        System.out.println("[EventEffects] Market day trader '"
                + trader.getNpcName() + "' spawned at " + spawnPos
                + " for " + village.getName());
    }

    private static void startFestivalOfLights(ServerLevel level,
                                              VillageEvent event, Village village, VillageSavedData data) {

        // NPCs stay up at night
        getVillageNpcs(level, village, data).forEach(mob ->
                mob.setEventOverride(VillageEvent.EventType.FESTIVAL_OF_LIGHTS));

        // Place lanterns and torches throughout village
        placeLightDecorations(level, event, village, data);
    }

    private static void startTrainingDay(ServerLevel level,
                                         VillageEvent event, Village village, VillageSavedData data) {

        // Guards get strength and speed boost
        getVillageNpcs(level, village, data).forEach(mob -> {
            mob.setEventOverride(VillageEvent.EventType.TRAINING_DAY);
            if (mob.getProfession() == Profession.GUARD) {
                mob.addEffect(new MobEffectInstance(
                        MobEffects.STRENGTH,
                        (int) event.getType().getDurationTicks(),
                        1, false, true));
                mob.addEffect(new MobEffectInstance(
                        MobEffects.SPEED,
                        (int) event.getType().getDurationTicks(),
                        0, false, true));
            }
        });

        // Place training targets/dummies
        placeTrainingDecorations(level, event, village, data);
    }

    private static void startVillageFair(ServerLevel level,
                                         VillageEvent event, Village village, VillageSavedData data) {

        // All NPCs socialize more
        getVillageNpcs(level, village, data).forEach(mob ->
                mob.setEventOverride(VillageEvent.EventType.VILLAGE_FAIR));

        // Place fair decorations
        placeFairDecorations(level, event, village, data);
    }

    // =========================================================================
    // DECORATIONS
    // =========================================================================

    private static void placeHarvestDecorations(ServerLevel level,
                                                VillageEvent event, Village village, VillageSavedData data) {

        village.getBounds(data).ifPresent(bounds -> {
            BlockPos center = new BlockPos(
                    (int)((bounds.minX + bounds.maxX) / 2),
                    (int) bounds.minY,
                    (int)((bounds.minZ + bounds.maxZ) / 2)
            );

            // Place hay bales and pumpkins around center
            int[][] offsets = {{3,0,0},{-3,0,0},{0,0,3},{0,0,-3},
                    {3,0,3},{-3,0,-3},{3,0,-3},{-3,0,3}};
            for (int[] off : offsets) {
                BlockPos pos = findSurface(level,
                        center.offset(off[0], 0, off[2]));
                if (canPlace(level, pos)) {
                    BlockState block = (level.getRandom().nextBoolean())
                            ? Blocks.HAY_BLOCK.defaultBlockState()
                            : Blocks.CARVED_PUMPKIN.defaultBlockState();
                    level.setBlock(pos, block, 3);
                    event.addDecoration(pos);
                }
            }

            // Place lanterns on paths
            placeEventLanterns(level, event, center, 8, 3);
        });
    }

    private static void placeMarketDecorations(ServerLevel level,
                                               VillageEvent event, Village village, VillageSavedData data) {

        village.getBounds(data).ifPresent(bounds -> {
            BlockPos center = new BlockPos(
                    (int)((bounds.minX + bounds.maxX) / 2),
                    (int) bounds.minY,
                    (int)((bounds.minZ + bounds.maxZ) / 2)
            );

            // Place carpet stalls
            BlockState[] stalls = {
                    Blocks.RED_CARPET.defaultBlockState(),
                    Blocks.YELLOW_CARPET.defaultBlockState(),
                    Blocks.BLUE_CARPET.defaultBlockState()
            };

            for (int i = 0; i < 4; i++) {
                BlockPos pos = findSurface(level,
                        center.offset(i * 3 - 6, 0, 0));
                if (canPlace(level, pos)) {
                    level.setBlock(pos,
                            stalls[i % stalls.length], 3);
                    event.addDecoration(pos);

                    // Barrel on each stall
                    BlockPos above = pos.above();
                    if (level.getBlockState(above).isAir()) {
                        level.setBlock(above,
                                Blocks.BARREL.defaultBlockState(), 3);
                        event.addDecoration(above);
                    }
                }
            }
        });
    }

    private static void placeLightDecorations(ServerLevel level,
                                              VillageEvent event, Village village, VillageSavedData data) {

        village.getBounds(data).ifPresent(bounds -> {
            BlockPos center = new BlockPos(
                    (int)((bounds.minX + bounds.maxX) / 2),
                    (int) bounds.minY,
                    (int)((bounds.minZ + bounds.maxZ) / 2)
            );

            // Dense lantern placement
            for (int x = -12; x <= 12; x += 4) {
                for (int z = -12; z <= 12; z += 4) {
                    if (level.getRandom().nextFloat() < 0.7f) {
                        BlockPos pos = findSurface(level,
                                center.offset(x, 0, z));
                        if (canPlace(level, pos)) {
                            level.setBlock(pos,
                                    Blocks.LANTERN.defaultBlockState(), 3);
                            event.addDecoration(pos);
                        }
                    }
                }
            }

            // Glowstone clusters
            for (int i = 0; i < 6; i++) {
                int ox = level.getRandom().nextInt(20) - 10;
                int oz = level.getRandom().nextInt(20) - 10;
                BlockPos pos = findSurface(level,
                        center.offset(ox, 1, oz));
                if (canPlace(level, pos)) {
                    level.setBlock(pos,
                            Blocks.GLOWSTONE.defaultBlockState(), 3);
                    event.addDecoration(pos);
                }
            }
        });
    }

    private static void placeTrainingDecorations(ServerLevel level,
                                                 VillageEvent event, Village village, VillageSavedData data) {

        village.getBounds(data).ifPresent(bounds -> {
            BlockPos center = new BlockPos(
                    (int)((bounds.minX + bounds.maxX) / 2),
                    (int) bounds.minY,
                    (int)((bounds.minZ + bounds.maxZ) / 2)
            );

            // Target dummies — hay bales with armor stands
            for (int i = -2; i <= 2; i++) {
                BlockPos base = findSurface(level,
                        center.offset(i * 3, 0, 5));
                if (canPlace(level, base)) {
                    level.setBlock(base,
                            Blocks.HAY_BLOCK.defaultBlockState(), 3);
                    event.addDecoration(base);
                }
            }

            // Weapon racks — item frames would need entities
            // Use fences as weapon rack placeholders
            for (int i = -1; i <= 1; i++) {
                BlockPos pos = findSurface(level,
                        center.offset(i * 2, 0, -5));
                if (canPlace(level, pos)) {
                    level.setBlock(pos,
                            Blocks.OAK_FENCE.defaultBlockState(), 3);
                    event.addDecoration(pos);
                }
            }
        });
    }

    private static void placeFairDecorations(ServerLevel level,
                                             VillageEvent event, Village village, VillageSavedData data) {

        village.getBounds(data).ifPresent(bounds -> {
            BlockPos center = new BlockPos(
                    (int)((bounds.minX + bounds.maxX) / 2),
                    (int) bounds.minY,
                    (int)((bounds.minZ + bounds.maxZ) / 2)
            );

            // Colorful banners and wool
            BlockState[] colors = {
                    Blocks.RED_WOOL.defaultBlockState(),
                    Blocks.YELLOW_WOOL.defaultBlockState(),
                    Blocks.BLUE_WOOL.defaultBlockState(),
                    Blocks.GREEN_WOOL.defaultBlockState()
            };

            for (int i = 0; i < 8; i++) {
                double angle = i * Math.PI / 4;
                int x = (int)(Math.cos(angle) * 6);
                int z = (int)(Math.sin(angle) * 6);
                BlockPos pos = findSurface(level,
                        center.offset(x, 0, z));
                if (canPlace(level, pos)) {
                    level.setBlock(pos, colors[i % colors.length], 3);
                    event.addDecoration(pos);
                    // Post on top
                    BlockPos post = pos.above();
                    if (level.getBlockState(post).isAir()) {
                        level.setBlock(post,
                                Blocks.OAK_FENCE.defaultBlockState(), 3);
                        event.addDecoration(post);
                    }
                }
            }

            placeEventLanterns(level, event, center, 10, 4);
        });
    }

    private static void placeEventLanterns(ServerLevel level,
                                           VillageEvent event, BlockPos center,
                                           int radius, int spacing) {
        for (int x = -radius; x <= radius; x += spacing) {
            for (int z = -radius; z <= radius; z += spacing) {
                if (Math.abs(x) + Math.abs(z) < radius
                        && level.getRandom().nextFloat() < 0.5f) {
                    BlockPos pos = findSurface(level,
                            center.offset(x, 1, z));
                    if (canPlace(level, pos)) {
                        level.setBlock(pos,
                                Blocks.LANTERN.defaultBlockState(), 3);
                        event.addDecoration(pos);
                    }
                }
            }
        }
    }

    // =========================================================================
    // CLEANUP
    // =========================================================================

    private static void removeDecorations(ServerLevel level,
                                          VillageEvent event) {
        for (BlockPos pos : event.getDecorations()) {
            // Only remove blocks we placed — don't remove if player
            // has built something there
            BlockState state = level.getBlockState(pos);
            if (isEventBlock(state)) {
                level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
            }
        }
    }

    private static boolean isEventBlock(BlockState state) {
        return state.is(Blocks.HAY_BLOCK)
                || state.is(Blocks.CARVED_PUMPKIN)
                || state.is(Blocks.LANTERN)
                || state.is(Blocks.GLOWSTONE)
                || state.is(Blocks.BARREL)
                || state.is(Blocks.OAK_FENCE)
                || state.is(BlockTags.WOOL)
                || state.getBlock() instanceof net.minecraft.world.level.block.CarpetBlock;
    }

    private static void removeNpcEffects(ServerLevel level,
                                         Village village,
                                         VillageSavedData data) {
        getVillageNpcs(level, village, data).forEach(mob -> {
            mob.clearEventOverride();
            mob.setEventTradeDiscount(1.0f);
            mob.removeEffect(MobEffects.SPEED);
            mob.removeEffect(MobEffects.HASTE);
            mob.removeEffect(MobEffects.STRENGTH);
        });
    }

    // =========================================================================
    // PLAYER EFFECTS
    // =========================================================================

    public static void applyPlayerBuff(Player player,
                                       VillageEvent event) {
        switch (event.getType()) {
            case HARVEST_FESTIVAL -> {
                player.addEffect(new MobEffectInstance(
                        MobEffects.SATURATION, 6000, 0));
                player.addEffect(new MobEffectInstance(
                        MobEffects.LUCK, 6000, 0));
            }
            case MARKET_DAY -> {
                // Player gets better prices — handled in TradeHandler
                player.addEffect(new MobEffectInstance(
                        MobEffects.LUCK, 6000, 1));
            }
            case FESTIVAL_OF_LIGHTS -> {
                player.addEffect(new MobEffectInstance(
                        MobEffects.NIGHT_VISION, 6000, 0));
                player.addEffect(new MobEffectInstance(
                        MobEffects.GLOWING, 6000, 0));
            }
            case TRAINING_DAY -> {
                player.addEffect(new MobEffectInstance(
                        MobEffects.STRENGTH, 6000, 0));
            }
            case VILLAGE_FAIR -> {
                player.addEffect(new MobEffectInstance(
                        MobEffects.SPEED, 6000, 0));
                player.addEffect(new MobEffectInstance(
                        MobEffects.JUMP_BOOST, 6000, 0));
            }
            // Phase 5 doc 32: every type added in Phase 5 has no
            // dedicated player buff for v1. The flavor / message
            // still fires below so the player gets a notification.
            default -> {}
        }
        player.displayClientMessage(
                net.minecraft.network.chat.Component.literal(
                        "You feel the spirit of the "
                                + formatEventName(event.getType()) + "!"),
                true);
    }

    // =========================================================================
    // HELPERS
    // =========================================================================

    private static void announceEvent(ServerLevel level,
                                      VillageEvent event, Village village,
                                      VillageSavedData data, boolean starting) {

        String msg = starting
                ? "The " + formatEventName(event.getType())
                + " has begun in " + village.getName() + "!"
                : "The " + formatEventName(event.getType())
                + " in " + village.getName() + " has ended.";

        village.getBounds(data).ifPresent(bounds ->
                level.players().stream()
                        .filter(p -> bounds.inflate(64).contains(
                                p.getX(), p.getY(), p.getZ()))
                        .forEach(p -> p.displayClientMessage(
                                net.minecraft.network.chat.Component.literal(msg)
                                        .withStyle(net.minecraft.ChatFormatting.GOLD),
                                false))
        );
    }

    private static String formatEventName(VillageEvent.EventType type) {
        return switch (type) {
            case HARVEST_FESTIVAL   -> "Harvest Festival";
            case MARKET_DAY         -> "Market Day";
            case FESTIVAL_OF_LIGHTS -> "Festival of Lights";
            case TRAINING_DAY       -> "Training Day";
            case VILLAGE_FAIR       -> "Village Fair";
            // Phase 5 doc 32: title-case the enum name for new event types.
            // Per-type display names belong to a content pass (Phase 5 doc 34).
            default                 -> defaultDisplayName(type);
        };
    }

    static String defaultDisplayName(VillageEvent.EventType type) {
        String[] parts = type.name().toLowerCase(java.util.Locale.ROOT).split("_");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) sb.append(' ');
            sb.append(Character.toUpperCase(parts[i].charAt(0)))
              .append(parts[i].substring(1));
        }
        return sb.toString();
    }

    private static List<TownspersonMob> getVillageNpcs(
            ServerLevel level, Village village, VillageSavedData data) {
        return level.getEntitiesOfClass(
                TownspersonMob.class,
                village.getBounds(data)
                        .map(b -> b.inflate(32))
                        .orElse(new net.minecraft.world.phys.AABB(0,0,0,0,0,0)),
                mob -> mob.getAssignedVillageName()
                        .map(n -> n.equals(village.getName()))
                        .orElse(false)
        );
    }

    private static BlockPos findSurface(ServerLevel level, BlockPos pos) {
        for (int y = pos.getY() + 8; y > pos.getY() - 8; y--) {
            BlockPos check = new BlockPos(pos.getX(), y, pos.getZ());
            if (level.getBlockState(check).isSolidRender()
                    && level.getBlockState(check.above()).isAir()) {
                return check.above();
            }
        }
        return pos;
    }

    private static boolean canPlace(ServerLevel level, BlockPos pos) {
        return level.getBlockState(pos).isAir()
                && level.getBlockState(pos.below()).isSolidRender();
    }
}