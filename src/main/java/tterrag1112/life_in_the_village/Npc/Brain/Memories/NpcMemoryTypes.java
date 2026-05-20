package tterrag1112.life_in_the_village.Npc.Brain.Memories;

import com.mojang.serialization.Codec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import tterrag1112.life_in_the_village.Life_in_the_village;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * MemoryModuleType registry for the Life in the Village Brain stack
 * (Phase 6.0).
 *
 * <p>Two groups: (1) memories the Phase 6.0 sensors and behaviors
 * read/write today, and (2) forward-declared memories for Phase 6.1+
 * that we register now so the constants are stable.
 *
 * <p>Memories that hold {@link LivingEntity} or {@link List} of entities
 * cannot be persisted, matching vanilla {@link MemoryModuleType#INTERACTION_TARGET}
 * — those pass {@code Optional.empty()} as the codec.
 */
public final class NpcMemoryTypes {

    private NpcMemoryTypes() {}

    public static final DeferredRegister<MemoryModuleType<?>> MEMORIES =
            DeferredRegister.create(BuiltInRegistries.MEMORY_MODULE_TYPE,
                    Life_in_the_village.MODID);

    // ── Phase 6.0 — actively used ───────────────────────────────────────────

    public static final DeferredHolder<MemoryModuleType<?>, MemoryModuleType<Integer>>
            CURRENT_MOOD_SNAPSHOT = register("current_mood_snapshot", Codec.INT);

    public static final DeferredHolder<MemoryModuleType<?>, MemoryModuleType<Long>>
            IDLE_GESTURE_COOLDOWN = register("idle_gesture_cooldown", Codec.LONG);

    public static final DeferredHolder<MemoryModuleType<?>, MemoryModuleType<ItemStack>>
            CARRYING_DISPLAY_ITEM = register("carrying_display_item", ItemStack.CODEC);

    public static final DeferredHolder<MemoryModuleType<?>, MemoryModuleType<RecentInteractionPartners>>
            RECENT_INTERACTION_PARTNERS =
                    register("recent_interaction_partners", RecentInteractionPartners.CODEC);

    /** Sensor populates this; behaviors in 6.1 consume it. */
    public static final DeferredHolder<MemoryModuleType<?>, MemoryModuleType<List<LivingEntity>>>
            CONVERSATION_CANDIDATES = registerNoCodec("conversation_candidates");

    // ── Phase 6.1.b — first navigating behaviors ────────────────────────────

    public static final DeferredHolder<MemoryModuleType<?>, MemoryModuleType<BlockPos>>
            NEAREST_FREE_SEAT = register("nearest_free_seat", BlockPos.CODEC);

    /** TTL cool-down memory: present → too soon to sit again. */
    public static final DeferredHolder<MemoryModuleType<?>, MemoryModuleType<Long>>
            SIT_COOLDOWN = register("sit_cooldown", Codec.LONG);

    /** TTL cool-down memory: present → recently completed an internal wander. */
    public static final DeferredHolder<MemoryModuleType<?>, MemoryModuleType<Long>>
            LAST_INTERIOR_WANDER = register("last_interior_wander", Codec.LONG);

    /** TTL cool-down memory: present → recently nudged for personal space. */
    public static final DeferredHolder<MemoryModuleType<?>, MemoryModuleType<Long>>
            LAST_PERSONAL_SPACE_NUDGE = register("last_personal_space_nudge", Codec.LONG);

    // ── Phase 6.1.c — coordinated behaviors ─────────────────────────────────

    public static final DeferredHolder<MemoryModuleType<?>, MemoryModuleType<BlockPos>>
            SHELTER_TARGET = register("shelter_target", BlockPos.CODEC);

    // ── Phase 6.1+ — forward-declared ───────────────────────────────────────

    public static final DeferredHolder<MemoryModuleType<?>, MemoryModuleType<BlockPos>>
            WORKSTATION_TARGET = register("workstation_target", BlockPos.CODEC);

    public static final DeferredHolder<MemoryModuleType<?>, MemoryModuleType<BlockPos>>
            STALL_SPOT = register("stall_spot", BlockPos.CODEC);

    public static final DeferredHolder<MemoryModuleType<?>, MemoryModuleType<GlobalPos>>
            MARKET_TARGET = register("market_target", GlobalPos.CODEC);

    public static final DeferredHolder<MemoryModuleType<?>, MemoryModuleType<BlockPos>>
            QUEUED_DESTINATION = register("queued_destination", BlockPos.CODEC);

    public static final DeferredHolder<MemoryModuleType<?>, MemoryModuleType<LivingEntity>>
            ESCORT_LEADER = registerNoCodec("escort_leader");

    public static final DeferredHolder<MemoryModuleType<?>, MemoryModuleType<LivingEntity>>
            CONVERSATION_PARTNER = registerNoCodec("conversation_partner");

    public static final DeferredHolder<MemoryModuleType<?>, MemoryModuleType<ConversationRole>>
            CONVERSATION_ROLE = register("conversation_role", ConversationRole.CODEC);

    public static final DeferredHolder<MemoryModuleType<?>, MemoryModuleType<UUID>>
            GIFT_PRIMED_BY_PLAYER = register("gift_primed_by_player", UUIDUtil.CODEC);

    public static final DeferredHolder<MemoryModuleType<?>, MemoryModuleType<WorkPhase>>
            WORK_PHASE = register("work_phase", WorkPhase.CODEC);

    public static final DeferredHolder<MemoryModuleType<?>, MemoryModuleType<UUID>>
            PENDING_COMMISSION = register("pending_commission", UUIDUtil.CODEC);

    public static final DeferredHolder<MemoryModuleType<?>, MemoryModuleType<GlobalPos>>
            CARGO_DESTINATION = register("cargo_destination", GlobalPos.CODEC);

    public static final DeferredHolder<MemoryModuleType<?>, MemoryModuleType<Long>>
            LAST_SHOPPING_TICK = register("last_shopping_tick", Codec.LONG);

    public static final DeferredHolder<MemoryModuleType<?>, MemoryModuleType<Long>>
            LAST_SELL_TICK = register("last_sell_tick", Codec.LONG);

    public static final DeferredHolder<MemoryModuleType<?>, MemoryModuleType<InvestigationTarget>>
            INVESTIGATION_TARGET = register("investigation_target", InvestigationTarget.CODEC);

    public static final DeferredHolder<MemoryModuleType<?>, MemoryModuleType<GlobalPos>>
            EMERGENCY_ALERT = register("emergency_alert", GlobalPos.CODEC);

    public static final DeferredHolder<MemoryModuleType<?>, MemoryModuleType<BlockPos>>
            PATROL_CHECKPOINT = register("patrol_checkpoint", BlockPos.CODEC);

    public static final DeferredHolder<MemoryModuleType<?>, MemoryModuleType<UUID>>
            UPCOMING_EVENT_TARGET = register("upcoming_event_target", UUIDUtil.CODEC);

    public static final DeferredHolder<MemoryModuleType<?>, MemoryModuleType<GlobalPos>>
            EVENT_ATTENDANCE_POS = register("event_attendance_pos", GlobalPos.CODEC);

    // ── helpers ─────────────────────────────────────────────────────────────

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static <U> DeferredHolder<MemoryModuleType<?>, MemoryModuleType<U>>
            register(String name, Codec<U> codec) {
        return (DeferredHolder) MEMORIES.register(name,
                () -> new MemoryModuleType<U>(Optional.of(codec)));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static <U> DeferredHolder<MemoryModuleType<?>, MemoryModuleType<U>>
            registerNoCodec(String name) {
        return (DeferredHolder) MEMORIES.register(name,
                () -> new MemoryModuleType<U>(Optional.empty()));
    }

    public static void register(IEventBus bus) {
        MEMORIES.register(bus);
    }
}
