package tterrag1112.life_in_the_village.Quests;

import com.mojang.logging.LogUtils;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import tterrag1112.life_in_the_village.Npc.Religion.DivineFavour;

/**
 * F2a-1 — a quest reward granted on completion. Sealed + dispatch-coded (like
 * {@link Objective}); F2a-1 ships {@link Favour} (favour with a god) and {@link Items}.
 */
public sealed interface QuestReward permits QuestReward.Favour, QuestReward.Items {

    String type();

    /** Grants this reward to {@code player}. */
    void grant(ServerLevel level, ServerPlayer player, long now);

    String describe();

    Codec<QuestReward> CODEC = Codec.STRING.dispatch("type", QuestReward::type, t -> switch (t) {
        case Favour.TYPE -> Favour.MAP_CODEC;
        case Items.TYPE  -> Items.MAP_CODEC;
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
            ResourceLocation id = ResourceLocation.tryParse(itemId);
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

    Logger LOGGER = LogUtils.getLogger();
}
