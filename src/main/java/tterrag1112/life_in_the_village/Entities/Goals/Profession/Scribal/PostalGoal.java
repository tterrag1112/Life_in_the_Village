package tterrag1112.life_in_the_village.Entities.Goals.Profession.Scribal;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import tterrag1112.life_in_the_village.Components.ModDataComponents;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Items.ModItems;
import tterrag1112.life_in_the_village.Npc.Letters.LetterContent;
import tterrag1112.life_in_the_village.Npc.Letters.LetterDelivery;
import tterrag1112.life_in_the_village.Npc.Letters.LetterEffects;
import tterrag1112.life_in_the_village.Npc.Skills.Skill;
import tterrag1112.life_in_the_village.Profession.Profession;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Scribes deliver locally during their SOCIAL phase. Spec line 128.
 *
 * <p>Pulls letter items from {@code ItemEntity}s on the ground inside
 * the village (a stand-in for "outbox at the workshop" — Phase 5 polish
 * could move to a real container). For each letter whose recipient is
 * in the village, walks to the recipient and runs the delivery
 * pipeline. Awards SOCIAL XP per delivery (spec line 207, line 337).</p>
 */
public class PostalGoal extends Goal {

    /** How often to scan for new outbound letters. */
    public static final int SCAN_INTERVAL = 200;
    /** Search radius (blocks) for outbox letters around the scribe. */
    public static final double OUTBOX_RADIUS = 16.0;
    /** Search radius (blocks) for the recipient around the scribe. */
    public static final double DELIVERY_SEARCH_RADIUS = 64.0;
    /** SOCIAL XP per successful delivery. */
    public static final int XP_PER_DELIVERY = 1;

    private enum Phase { IDLE, WALKING, DELIVERED }

    private final TownspersonMob entity;
    private Phase phase = Phase.IDLE;
    private int scanTimer;
    private ItemEntity carriedLetter;
    private TownspersonMob deliveryTarget;

    public PostalGoal(TownspersonMob entity) {
        this.entity = entity;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (entity.getProfession() != Profession.SCRIBE) return false;
        if (!entity.isSocialTime()) return false;
        if (!(entity.level() instanceof ServerLevel level)) return false;
        scanTimer++;
        if (scanTimer < SCAN_INTERVAL) return false;
        scanTimer = 0;
        return findNextDelivery(level);
    }

    @Override
    public boolean canContinueToUse() {
        return phase == Phase.WALKING && carriedLetter != null && deliveryTarget != null
                && carriedLetter.isAlive() && deliveryTarget.isAlive();
    }

    @Override
    public boolean requiresUpdateEveryTick() { return true; }

    @Override
    public void start() {
        phase = Phase.WALKING;
        entity.setCurrentActivity("Delivering a letter");
        if (deliveryTarget != null) {
            entity.getNavigation().moveTo(deliveryTarget, 0.9);
        }
    }

    @Override
    public void tick() {
        if (!(entity.level() instanceof ServerLevel level)) return;
        if (phase != Phase.WALKING) return;
        if (deliveryTarget == null || carriedLetter == null) return;

        double d2 = entity.distanceToSqr(deliveryTarget);
        if (d2 > 9.0) {
            if (!entity.getNavigation().isInProgress()) {
                entity.getNavigation().moveTo(deliveryTarget, 0.9);
            }
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
                    // Drop unread at recipient's feet — illiterate path.
                    ItemEntity drop = new ItemEntity(level,
                            deliveryTarget.getX(), deliveryTarget.getY() + 0.5,
                            deliveryTarget.getZ(), stack);
                    level.addFreshEntity(drop);
                }
                entity.getSkills().addXp(Skill.SOCIAL, XP_PER_DELIVERY, level.getGameTime());
            }
        }
        deliveryTarget = null;
        phase = Phase.DELIVERED;
    }

    @Override
    public void stop() {
        entity.getNavigation().stop();
        entity.clearCurrentActivity();
        carriedLetter = null;
        deliveryTarget = null;
        phase = Phase.IDLE;
    }

    private boolean findNextDelivery(ServerLevel level) {
        List<ItemEntity> nearby = level.getEntitiesOfClass(
                ItemEntity.class,
                entity.getBoundingBox().inflate(OUTBOX_RADIUS),
                ie -> ie.isAlive() && ie.getItem().is(ModItems.WRITTEN_LETTER.get()));
        for (ItemEntity ie : nearby) {
            LetterContent c = ie.getItem().get(ModDataComponents.LETTER_CONTENT.get());
            if (c == null || c.recipientId().equals(LetterContent.NIL_UUID)) continue;
            Optional<TownspersonMob> recipient = findRecipient(level, c.recipientId());
            if (recipient.isEmpty()) continue;
            this.carriedLetter = ie;
            this.deliveryTarget = recipient.get();
            return true;
        }
        return false;
    }

    private Optional<TownspersonMob> findRecipient(ServerLevel level, UUID id) {
        return level.getEntitiesOfClass(TownspersonMob.class,
                        entity.getBoundingBox().inflate(DELIVERY_SEARCH_RADIUS),
                        m -> m.getUUID().equals(id))
                .stream().findFirst();
    }
}
