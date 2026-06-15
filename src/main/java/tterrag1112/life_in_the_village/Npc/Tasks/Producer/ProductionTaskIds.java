package tterrag1112.life_in_the_village.Npc.Tasks.Producer;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import tterrag1112.life_in_the_village.Npc.Tasks.IssuerRef;
import tterrag1112.life_in_the_village.Npc.Tasks.TaskId;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Stable {@link TaskId} derivation for producer tasks, generalized from the
 * blacksmith pilot's {@code BlacksmithTaskSource.stableId}. A task id is a
 * deterministic name-UUID of {@code issuer.key() | objectiveKey} so a source
 * refresh updates the existing task in place rather than piling duplicates.
 */
public final class ProductionTaskIds {

    private ProductionTaskIds() {}

    /** Deterministic id for {@code objKey} on {@code issuer}'s board. */
    public static TaskId stable(IssuerRef issuer, String objKey) {
        String seed = issuer.key() + "|" + objKey;
        return new TaskId(UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8)));
    }

    /** The registry-key string of {@code item}, for use inside an object key. */
    public static String key(Item item) {
        return BuiltInRegistries.ITEM.getKey(item).toString();
    }
}
