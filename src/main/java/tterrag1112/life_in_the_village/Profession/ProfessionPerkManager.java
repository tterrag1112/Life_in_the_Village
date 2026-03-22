// src/main/java/tterrag1112/life_in_the_village/Profession/Perks/ProfessionPerkManager.java
package tterrag1112.life_in_the_village.Profession;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import tterrag1112.life_in_the_village.DataAttachments.ModData;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Profession.PlayerProfession;
import tterrag1112.life_in_the_village.Profession.PlayerProfessionData;
import tterrag1112.life_in_the_village.Profession.Profession;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.Optional;

/**
 * Applies the active and passive effects of {@link ProfessionPerk}s.
 *
 * <h3>Called from</h3>
 * <ul>
 *   <li>{@link #checkUnlocks} — called by {@code ProfessionEvents.awardXp}
 *       whenever a player levels up.</li>
 *   <li>{@link #tickPassiveEffects} — called from
 *       {@code ServerTickDispatcher} every 20 ticks per online player.</li>
 *   <li>Individual query methods — called inline from trade handlers,
 *       farm goals, and event hooks at the point where the effect applies.</li>
 * </ul>
 */
public final class ProfessionPerkManager {

    /** Duration for passive effects — refreshed every 20 ticks so they never expire. */
    private static final int PASSIVE_DURATION = 600;

    private ProfessionPerkManager() {}

    // =========================================================================
    // Level-up unlock
    // =========================================================================

    public static void checkUnlocks(ServerPlayer player,
                                    PlayerProfession profession,
                                    int newLevel) {
        PlayerProfessionData profData = player.getData(ModData.PROFESSION_DATA);
        ProfessionPerk perk = ProfessionPerk.atLevel(profession, newLevel).get();
        if (perk == null) return;

        profData.unlockPerk(perk);
        player.setData(ModData.PROFESSION_DATA, profData);

        player.displayClientMessage(
                Component.literal("✦ Perk unlocked: " + perk.displayName)
                        .withStyle(net.minecraft.ChatFormatting.GOLD), false);
        player.displayClientMessage(
                Component.literal("  " + perk.description)
                        .withStyle(net.minecraft.ChatFormatting.YELLOW), false);
    }

    // =========================================================================
    // Passive tick — every 20 ticks per online player
    // =========================================================================

    public static void tickPassiveEffects(ServerPlayer player) {
        if (!(player.level() instanceof ServerLevel level)) return;
        PlayerProfessionData profData = player.getData(ModData.PROFESSION_DATA);

        // ── BLACKSMITH: Haste near lit furnace ────────────────────────────────
        if (profData.hasUnlockedPerk(ProfessionPerk.BLACKSMITH_HASTE)) {
            if (isNearActiveFurnace(player, level, 6)) {
                player.addEffect(new MobEffectInstance(
                        MobEffects.HASTE, PASSIVE_DURATION, 0, false, false));
            }
        }

        // ── CARPENTER: Haste while holding an axe ────────────────────────────
        if (profData.hasUnlockedPerk(ProfessionPerk.CARPENTER_WOODCUTTER)) {
            if (player.getMainHandItem().is(net.minecraft.tags.ItemTags.AXES)) {
                player.addEffect(new MobEffectInstance(
                        MobEffects.HASTE, PASSIVE_DURATION, 0, false, false));
            }
        }

        // ── GUARD_TOUGHNESS: Resistance I while in any village ───────────────
        if (profData.hasUnlockedPerk(ProfessionPerk.GUARD_TOUGHNESS)) {
            if (isInVillage(player, level)) {
                player.addEffect(new MobEffectInstance(
                        MobEffects.RESISTANCE, PASSIVE_DURATION, 0, false, false));
            }
        }

        // ── GUARD_EXTENDED_PATROL: Speed I in assigned village ────────────────
        if (profData.hasUnlockedPerk(ProfessionPerk.GUARD_EXTENDED_PATROL)) {
            if (isInAssignedVillage(player, level, profData)) {
                player.addEffect(new MobEffectInstance(
                        MobEffects.SPEED, PASSIVE_DURATION, 0, false, false));
            }
        }
    }

    // =========================================================================
    // Event-driven guard perks
    // =========================================================================

    /**
     * GUARD_RALLY — call from a {@code LivingDamageEvent} handler when
     * the player takes damage. Alerts nearby guard NPCs to target the attacker.
     */
    public static void onPlayerDamaged(ServerPlayer player,
                                       net.minecraft.world.entity.LivingEntity attacker) {
        if (!(player.level() instanceof ServerLevel level)) return;
        PlayerProfessionData profData = player.getData(ModData.PROFESSION_DATA);
        if (!profData.hasUnlockedPerk(ProfessionPerk.GUARD_RALLY)) return;

        level.getEntitiesOfClass(TownspersonMob.class,
                player.getBoundingBox().inflate(24),
                mob -> mob.getProfession() == Profession.GUARD && mob.isAlive()
        ).forEach(guard -> {
            guard.setTarget(attacker);
            guard.setCurrentActivity("Defending " + player.getName().getString());
        });
    }

    /**
     * GUARD_INSPIRE — call from {@code VillageWarningSystem} when a village
     * alarm is raised. Grants Strength I to nearby players with the perk.
     */
    public static void onVillageAlarm(ServerLevel level, Village village) {
        VillageSavedData data = VillageSavedData.get(level);

        level.getServer().getPlayerList().getPlayers().stream()
                .filter(player -> player.getData(ModData.PROFESSION_DATA)
                        .hasUnlockedPerk(ProfessionPerk.GUARD_INSPIRE))
                .filter(player -> village.getBounds(data)
                        .map(b -> b.inflate(64).contains(
                                player.getX(), player.getY(), player.getZ()))
                        .orElse(false))
                .forEach(player -> {
                    player.addEffect(new MobEffectInstance(
                            MobEffects.STRENGTH, 600, 0, false, true));
                    player.displayClientMessage(
                            Component.literal("Your guard training kicks in — Strength I!")
                                    .withStyle(net.minecraft.ChatFormatting.GOLD), true);
                });
    }

    // =========================================================================
    // Inline query methods
    // =========================================================================

    /** FARMER_BULK_HARVEST — should adjacent mature crops also be broken? */
    public static boolean shouldBulkHarvest(ServerPlayer player) {
        return player.getData(ModData.PROFESSION_DATA)
                .hasUnlockedPerk(ProfessionPerk.FARMER_BULK_HARVEST);
    }

    /**
     * FARMER_SEED_RETURN — should the seed be consumed on replant?
     * Returns false (don't consume) 50% of the time if perk is active.
     */
    public static boolean shouldConsumeSeed(ServerPlayer player) {
        if (!player.getData(ModData.PROFESSION_DATA)
                .hasUnlockedPerk(ProfessionPerk.FARMER_SEED_RETURN)) {
            return true;
        }
        return player.getRandom().nextBoolean();
    }

    /** FARMER_YIELD — harvest yield multiplier (extra drop chance). */
    public static double getHarvestYieldMultiplier(ServerPlayer player) {
        return getHarvestYieldMultiplier(player, 0);
    }
    public static double getHarvestYieldMultiplier(ServerPlayer player, double seasonModifier) {
        PlayerProfessionData profData = player.getData(ModData.PROFESSION_DATA);
        double base = profData.getYieldBonus(PlayerProfession.FARMER);
        if (profData.hasUnlockedPerk(ProfessionPerk.FARMER_YIELD)) {
            base = Math.max(base, 0.25);
        }
        return base;
    }

    /** MERCHANT_BETTER_PRICES — NPC buy price multiplier (1.0 or 1.1). */
    public static long applyMerchantBuyPricePerk(ServerPlayer player, long basePrice) {
        if (player.getData(ModData.PROFESSION_DATA)
                .hasUnlockedPerk(ProfessionPerk.MERCHANT_BETTER_PRICES)) {
            return Math.round(basePrice * 1.10);
        }
        return basePrice;
    }

    /** MERCHANT_JOB_BONUS — extra coins to add to job posting reward (+25%). */
    public static long getJobPostingCoinBonus(ServerPlayer player, long baseReward) {
        if (player.getData(ModData.PROFESSION_DATA)
                .hasUnlockedPerk(ProfessionPerk.MERCHANT_JOB_BONUS)) {
            return Math.round(baseReward * 0.25);
        }
        return 0L;
    }

    /** MERCHANT_REPUTATION_TRADER — double reputation per completed trade? */
    public static boolean hasDoubleReputationTrade(ServerPlayer player) {
        return player.getData(ModData.PROFESSION_DATA)
                .hasUnlockedPerk(ProfessionPerk.MERCHANT_REPUTATION_TRADER);
    }

    /** MERCHANT_TRADE_EXEMPTION — exempt from kingdom trade tax penalty? */
    public static boolean hasTradeExemption(ServerPlayer player) {
        return player.getData(ModData.PROFESSION_DATA)
                .hasUnlockedPerk(ProfessionPerk.MERCHANT_TRADE_EXEMPTION);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static boolean isNearActiveFurnace(ServerPlayer player,
                                               ServerLevel level,
                                               int radius) {
        BlockPos pos = player.blockPosition();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos check = pos.offset(dx, dy, dz);
                    var state = level.getBlockState(check);
                    if (state.is(Blocks.FURNACE)
                            || state.is(Blocks.BLAST_FURNACE)
                            || state.is(Blocks.SMOKER)) {
                        if (level.getBlockState(check)
                                .getOptionalValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.LIT)
                                .orElse(false)) return true;
                    }}
            }
        }
        return false;
    }

    private static boolean isInVillage(ServerPlayer player, ServerLevel level) {
        return VillageSavedData.get(level)
                .getVillageAt(player.blockPosition())
                .isPresent();
    }

    private static boolean isInAssignedVillage(ServerPlayer player,
                                               ServerLevel level,
                                               PlayerProfessionData profData) {
        Optional<String> villageName = profData.getAllWorkplaces().values().stream()
                .filter(e -> e.profession() == PlayerProfession.GUARD)
                .findFirst()
                .flatMap(e -> VillageSavedData.get(level)
                        .getVillageById(e.villageId())
                        .map(Village::getName));

        if (villageName.isEmpty()) return false;

        VillageSavedData data = VillageSavedData.get(level);
        return data.getVillageByName(villageName.get())
                .flatMap(v -> v.getBounds(data))
                .map(b -> b.inflate(32).contains(
                        player.getX(), player.getY(), player.getZ()))
                .orElse(false);
    }
}