package tterrag1112.life_in_the_village.Quests;

import com.mojang.logging.LogUtils;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import tterrag1112.life_in_the_village.Guilds.Adventurer.PlayerGuildData;
import tterrag1112.life_in_the_village.Items.ModItems;
import tterrag1112.life_in_the_village.Npc.Religion.DivineFavour;
import tterrag1112.life_in_the_village.Npc.Religion.DivineVision;
import tterrag1112.life_in_the_village.Npc.Religion.God;
import tterrag1112.life_in_the_village.Npc.Religion.GodRegistry;
import tterrag1112.life_in_the_village.Village.Economy.Currency.CurrencyValue;

/**
 * F2a-1 — a quest reward granted on completion. Sealed + dispatch-coded (like
 * {@link Objective}); F2a-1 ships {@link Favour} + {@link Items}, F2a-3 adds
 * {@link Vision}, F2b-1 adds {@link Coins} + {@link Xp} (the guild reward vocabulary).
 */
public sealed interface QuestReward
        permits QuestReward.Favour, QuestReward.Items, QuestReward.Vision,
                QuestReward.Coins, QuestReward.Xp {

    String type();

    /** Grants this reward to {@code player}. */
    void grant(ServerLevel level, ServerPlayer player, long now);

    String describe();

    Codec<QuestReward> CODEC = Codec.STRING.dispatch("type", QuestReward::type, t -> switch (t) {
        case Favour.TYPE -> Favour.MAP_CODEC;
        case Items.TYPE  -> Items.MAP_CODEC;
        case Vision.TYPE -> Vision.MAP_CODEC;
        case Coins.TYPE  -> Coins.MAP_CODEC;
        case Xp.TYPE     -> Xp.MAP_CODEC;
        default -> throw new IllegalStateException("Unknown reward type: " + t);
    });

    // ── Kinds ────────────────────────────────────────────────────────────────

    /** Favour with {@code godId} — granted via the capped favour add (within standing,
     *  never bypassing the piety cap). */
    record Favour(String godId, float amount) implements QuestReward {
        public static final String TYPE = "favour";
        public static final MapCodec<Favour> MAP_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                Codec.STRING.fieldOf("godId").forGetter(Favour::godId),
                Codec.FLOAT.fieldOf("amount").forGetter(Favour::amount)
        ).apply(i, Favour::new));

        public String type() { return TYPE; }

        public void grant(ServerLevel level, ServerPlayer player, long now) {
            DivineFavour.addCapped(level, player.getUUID(), godId, amount, now);
        }

        public String describe() { return "+" + Math.round(amount) + " favour with " + godId; }
    }

    /** {@code count} of {@code itemId} (a registry id like "minecraft:emerald"), added
     *  to the player's inventory (dropped at their feet if full). */
    record Items(String itemId, int count) implements QuestReward {
        public static final String TYPE = "items";
        public static final MapCodec<Items> MAP_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                Codec.STRING.fieldOf("itemId").forGetter(Items::itemId),
                Codec.INT.optionalFieldOf("count", 1).forGetter(Items::count)
        ).apply(i, Items::new));

        public String type() { return TYPE; }

        public void grant(ServerLevel level, ServerPlayer player, long now) {
            Identifier id = Identifier.tryParse(itemId);
            Item item = id == null ? null : BuiltInRegistries.ITEM.getOptional(id).orElse(null);
            if (item == null) {
                LOGGER.warn("[Quest] reward item not found: {}", itemId);
                return;
            }
            ItemStack stack = new ItemStack(item, Math.max(1, count));
            if (!player.getInventory().add(stack)) player.drop(stack, false);
        }

        public String describe() { return count + "× " + itemId; }
    }

    /** F2a-3 — a god-voiced confirmation vision (the graduated-calling fulfilment line).
     *  Delivered via {@link DivineVision#speak}. */
    record Vision(String godId, String text) implements QuestReward {
        public static final String TYPE = "vision";
        public static final MapCodec<Vision> MAP_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                Codec.STRING.fieldOf("godId").forGetter(Vision::godId),
                Codec.STRING.fieldOf("text").forGetter(Vision::text)
        ).apply(i, Vision::new));

        public String type() { return TYPE; }

        public void grant(ServerLevel level, ServerPlayer player, long now) {
            God god = GodRegistry.get(godId);
            if (god != null) DivineVision.speak(god, player, text);
        }

        public String describe() { return "a vision from " + godId; }
    }

    /** F2b-1 — {@code bronze} coins, minted into the player's inventory (the guild's
     *  basic coin reward; the party multiplier is F2b-2's turn-in flow). */
    record Coins(long bronze) implements QuestReward {
        public static final String TYPE = "coins";
        public static final MapCodec<Coins> MAP_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                Codec.LONG.fieldOf("bronze").forGetter(Coins::bronze)
        ).apply(i, Coins::new));

        public String type() { return TYPE; }

        public void grant(ServerLevel level, ServerPlayer player, long now) {
            long rem = bronze;
            int gold = (int) (rem / CurrencyValue.GOLD_VALUE);   rem %= CurrencyValue.GOLD_VALUE;
            int silver = (int) (rem / CurrencyValue.SILVER_VALUE); rem %= CurrencyValue.SILVER_VALUE;
            int copper = (int) rem;
            giveStack(player, ModItems.DENIER_OR.get(), gold);
            giveStack(player, ModItems.DENIER_ARGENT.get(), silver);
            giveStack(player, ModItems.DENIER.get(), copper);
        }

        private static void giveStack(ServerPlayer player, Item coin, int count) {
            while (count > 0) {
                int n = Math.min(count, 64);
                ItemStack stack = new ItemStack(coin, n);
                if (!player.getInventory().add(stack)) player.drop(stack, false);
                count -= n;
            }
        }

        public String describe() { return bronze + " bronze"; }
    }

    /** F2b-1 — {@code xp} guild XP (no-op if the player isn't a guild member; the rank-up
     *  trigger is F2b-2's turn-in flow). */
    record Xp(int xp) implements QuestReward {
        public static final String TYPE = "xp";
        public static final MapCodec<Xp> MAP_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                Codec.INT.fieldOf("xp").forGetter(Xp::xp)
        ).apply(i, Xp::new));

        public String type() { return TYPE; }

        public void grant(ServerLevel level, ServerPlayer player, long now) {
            PlayerGuildData.get(level).addXp(player.getUUID(), xp);
        }

        public String describe() { return "+" + xp + " guild XP"; }
    }

    Logger LOGGER = LogUtils.getLogger();
}
