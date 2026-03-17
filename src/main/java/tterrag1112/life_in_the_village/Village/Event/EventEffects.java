package tterrag1112.life_in_the_village.Village.Event;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
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
        }

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

        // Remove NPC effects
        removeNpcEffects(level, village, data);

        // Announce end
        announceEvent(level, event, village, data, false);
    }

    // =========================================================================
    // EVENT STARTS
    // =========================================================================

    private static void startHarvestFestival(ServerLevel level,
                                             VillageEvent event, Village village, VillageSavedData data) {

        // Give farmers speed and strength boost
        getVillageNpcs(level, village, data).forEach(mob -> {
            if (mob.getProfession() == TownspersonMob.Profession.FARMER
                    || mob.getProfession() ==
                    TownspersonMob.Profession.FARMHAND) {
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
                                       VillageEvent event, Village village, VillageSavedData data) {

        // Merchants get a trade price reduction flag
        getVillageNpcs(level, village, data).forEach(mob -> {
            mob.setEventOverride(VillageEvent.EventType.MARKET_DAY);
            if (mob.getProfession() == TownspersonMob.Profession.MERCHANT) {
                // Signal merchant to offer discounts
                mob.setEventTradeDiscount(0.8f); // 20% off
            }
        });

        // Place market stalls
        placeMarketDecorations(level, event, village, data);
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
            if (mob.getProfession() == TownspersonMob.Profession.GUARD) {
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
        };
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