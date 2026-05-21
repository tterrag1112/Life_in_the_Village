package tterrag1112.life_in_the_village.Npc.Brain.Behaviors;

import com.google.common.collect.ImmutableMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemoryStatus;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import tterrag1112.life_in_the_village.Components.ModDataComponents;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Items.ModItems;
import tterrag1112.life_in_the_village.Npc.Brain.BrainNavGuard;
import tterrag1112.life_in_the_village.Npc.Brain.Memories.NpcMemoryTypes;
import tterrag1112.life_in_the_village.Npc.Letters.LetterContent;
import tterrag1112.life_in_the_village.Npc.Letters.LetterDelivery;
import tterrag1112.life_in_the_village.Npc.Letters.LetterEffects;
import tterrag1112.life_in_the_village.Npc.Skills.Skill;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Phase 6.2.b — migrated from {@code PostalGoal}. SCRIBE-only,
 * registered via {@link tterrag1112.life_in_the_village.Npc.Brain.ProfessionBrainFactory}
 * — first profession-specific behavior. SOCIAL @ 7 (low — letter
 * delivery is non-urgent personal admin).
 *
 * <p>Scans nearby ItemEntities for {@code WRITTEN_LETTER} items
 * addressed to a loaded recipient, walks the letter to them, runs
 * {@link LetterEffects#onLetterReceived} (or drops unread for
 * illiterate recipients), awards SOCIAL XP. All external letter APIs
 * preserved 1:1.
 *
 * <p>Cool-down: {@code POSTAL_RUN_COOLDOWN} TTL memory (600t) replaces
 * the goal's {@code SCAN_INTERVAL} field.
 */
public class PostalBehavior extends Behavior<TownspersonMob> {

    public static final double OUTBOX_RADIUS = 16.0;
    public static final double DELIVERY_SEARCH_RADIUS = 64.0;
    public static final int XP_PER_DELIVERY = 1;
    private static final double DELIVER_DIST_SQ = 9.0;
    private static final long COOLDOWN_TICKS = 600L;
    private static final float WALK_SPEED = 0.9f;
    private static final int CLOSE_ENOUGH = 1;
    private static final int MAX_RUN = 2400;

    private enum Phase { WALKING, DELIVERED }

    private Phase phase = Phase.DELIVERED;
    private ItemEntity carriedLetter;
    private TownspersonMob deliveryTarget;

    public PostalBehavior() {
        super(ImmutableMap.of(
                NpcMemoryTypes.POSTAL_RUN_COOLDOWN.get(), MemoryStatus.VALUE_ABSENT,
                MemoryModuleType.WALK_TARGET, MemoryStatus.VALUE_ABSENT
        ), MAX_RUN);
    }

    @Override
    protected boolean checkExtraStartConditions(ServerLevel level, TownspersonMob entity) {
        if (!BrainNavGuard.canSteerNavigation(entity)) return false;
        return findNextDelivery(level, entity);
    }

    @Override
    protected boolean canStillUse(ServerLevel level, TownspersonMob entity, long gameTime) {
        return phase == Phase.WALKING
                && carriedLetter != null && carriedLetter.isAlive()
                && deliveryTarget != null && deliveryTarget.isAlive();
    }

    @Override
    protected void start(ServerLevel level, TownspersonMob entity, long gameTime) {
        phase = Phase.WALKING;
        entity.setCurrentActivity("Delivering a letter");
        if (deliveryTarget != null) {
            entity.getBrain().setMemory(MemoryModuleType.WALK_TARGET,
                    new WalkTarget(deliveryTarget.blockPosition(), WALK_SPEED, CLOSE_ENOUGH));
        }
        if (carriedLetter != null) {
            entity.getBrain().setMemory(NpcMemoryTypes.CARRYING_DISPLAY_ITEM.get(),
                    carriedLetter.getItem().copy());
        }
    }

    @Override
    protected void tick(ServerLevel level, TownspersonMob entity, long gameTime) {
        if (phase != Phase.WALKING) return;
        if (deliveryTarget == null || carriedLetter == null) return;

        double d2 = entity.distanceToSqr(deliveryTarget);
        if (d2 > DELIVER_DIST_SQ) {
            // Refresh WALK_TARGET — recipient may move.
            entity.getBrain().setMemory(MemoryModuleType.WALK_TARGET,
                    new WalkTarget(deliveryTarget.blockPosition(), WALK_SPEED, CLOSE_ENOUGH));
            return;
        }

        // Within hand-off range — deliver.
        ItemStack stack = carriedLetter.getItem().copy();
        carriedLetter.discard();
        carriedLetter = null;
        if (stack.is(ModItems.WRITTEN_LETTER.get())) {
            LetterContent content = stack.get(ModDataComponents.LETTER_CONTENT.get());
            if (content != null && content.isAddressedTo(deliveryTarget.getUUID())) {
                int literacy = deliveryTarget.getSkills().getLevel(Skill.LITERACY);
                if (literacy >= LetterDelivery.READ_LITERACY_THRESHOLD) {
                    LetterEffects.onLetterReceived(deliveryTarget, content, level);
                } else {
                    ItemEntity drop = new ItemEntity(level,
                            deliveryTarget.getX(), deliveryTarget.getY() + 0.5,
                            deliveryTarget.getZ(), stack);
                    level.addFreshEntity(drop);
                }
                tterrag1112.life_in_the_village.Npc.Skills.SkillXp.award(entity, Skill.SOCIAL, XP_PER_DELIVERY, level.getGameTime());
            }
        }
        deliveryTarget = null;
        phase = Phase.DELIVERED;
    }

    @Override
    protected void stop(ServerLevel level, TownspersonMob entity, long gameTime) {
        entity.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
        entity.getBrain().eraseMemory(NpcMemoryTypes.CARRYING_DISPLAY_ITEM.get());
        entity.clearCurrentActivity();
        entity.getBrain().setMemoryWithExpiry(
                NpcMemoryTypes.POSTAL_RUN_COOLDOWN.get(), gameTime, COOLDOWN_TICKS);
        carriedLetter = null;
        deliveryTarget = null;
        phase = Phase.DELIVERED;
    }

    private boolean findNextDelivery(ServerLevel level, TownspersonMob entity) {
        List<ItemEntity> nearby = level.getEntitiesOfClass(
                ItemEntity.class,
                entity.getBoundingBox().inflate(OUTBOX_RADIUS),
                ie -> ie.isAlive() && ie.getItem().is(ModItems.WRITTEN_LETTER.get()));
        for (ItemEntity ie : nearby) {
            LetterContent c = ie.getItem().get(ModDataComponents.LETTER_CONTENT.get());
            if (c == null || c.recipientId().equals(LetterContent.NIL_UUID)) continue;
            Optional<TownspersonMob> recipient = findRecipient(level, entity, c.recipientId());
            if (recipient.isEmpty()) continue;
            this.carriedLetter = ie;
            this.deliveryTarget = recipient.get();
            return true;
        }
        return false;
    }

    private static Optional<TownspersonMob> findRecipient(ServerLevel level,
                                                          TownspersonMob entity, UUID id) {
        return level.getEntitiesOfClass(TownspersonMob.class,
                        entity.getBoundingBox().inflate(DELIVERY_SEARCH_RADIUS),
                        m -> m.getUUID().equals(id))
                .stream().findFirst();
    }
}
