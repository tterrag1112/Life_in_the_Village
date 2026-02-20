package tterrag1112.life_in_the_village.Entities.custom;

import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.npc.InventoryCarrier;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;

import java.util.OptionalInt;

public class TownspersonMob extends AgeableMob implements InventoryCarrier, ContainerListener, HasCustomInventoryScreen {
    private static final double DEFAULT_MOVE_SPEED = 0.5;
    private static final EntityDataAccessor<OptionalInt> DATA_SHOULDER_PARROT_LEFT;
    private static final EntityDataAccessor<OptionalInt> DATA_SHOULDER_PARROT_RIGHT;
    private final SimpleContainer inventory = new SimpleContainer(32);


    public TownspersonMob(EntityType<TownspersonMob> entityType, Level level) {
        super(entityType, level);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return LivingEntity.createLivingAttributes()
                .add(Attributes.ATTACK_DAMAGE, 1.0)
                .add(Attributes.MOVEMENT_SPEED, 0.10000000149011612)
                .add(Attributes.ATTACK_SPEED)
                .add(Attributes.LUCK)
                .add(Attributes.BLOCK_INTERACTION_RANGE, 4.5)
                .add(Attributes.ENTITY_INTERACTION_RANGE, 3.0)
                .add(Attributes.BLOCK_BREAK_SPEED)
                .add(Attributes.SUBMERGED_MINING_SPEED)
                .add(Attributes.SNEAKING_SPEED)
                .add(Attributes.MINING_EFFICIENCY)
                .add(Attributes.SWEEPING_DAMAGE_RATIO)
                .add(Attributes.WAYPOINT_TRANSMIT_RANGE, 6.0E7)
                .add(Attributes.WAYPOINT_RECEIVE_RANGE, 6.0E7);
    }

    @Override
    public @Nullable AgeableMob getBreedOffspring(ServerLevel serverLevel, AgeableMob ageableMob) {
        return null;
    }

    protected void defineSynchedData(SynchedEntityData.Builder p_326117_) {
        super.defineSynchedData(p_326117_);
        p_326117_.define(DATA_SHOULDER_PARROT_LEFT, OptionalInt.empty());
        p_326117_.define(DATA_SHOULDER_PARROT_RIGHT, OptionalInt.empty());
    }

    @Override
    public HumanoidArm getMainArm() {
        return null;
    }

    protected ItemStack addToInventory(ItemStack stack) {
        return this.inventory.addItem(stack);
    }

    protected boolean canAddToInventory(ItemStack stack) {
        return this.inventory.canAddItem(stack);
    }

    @Override
    public SimpleContainer getInventory() {
        return this.inventory;
    }



    static {
        DATA_SHOULDER_PARROT_LEFT = SynchedEntityData.defineId(Player.class, EntityDataSerializers.OPTIONAL_UNSIGNED_INT);
        DATA_SHOULDER_PARROT_RIGHT = SynchedEntityData.defineId(Player.class, EntityDataSerializers.OPTIONAL_UNSIGNED_INT);
    }



    @Override
    public void containerChanged(Container container) {

    }

    @Override
    public void openCustomInventoryScreen(Player player) {

    }


}
