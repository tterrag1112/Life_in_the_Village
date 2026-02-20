package tterrag1112.life_in_the_village.Blocks.Entity.custom;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.FurnaceBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.FurnaceBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.neoforged.neoforge.transfer.energy.EnergyHandler;
import org.jspecify.annotations.Nullable;
import tterrag1112.life_in_the_village.Blocks.Entity.ModBlockEntities;

public class VillageFoundationBlockEntity extends BlockEntity implements Container {
    private final NonNullList<ItemStack> inventory = NonNullList.withSize(1, ItemStack.EMPTY);
    private String buildingName = "";



    public VillageFoundationBlockEntity(BlockPos pos, BlockState blockState) {
        super(ModBlockEntities.VILLAGE_FOUNDATION_BE.get(), pos, blockState);
    }

    public String getBuildingName() {
        return buildingName; }
    public void setBuildingName(String name) {
        this.buildingName = name;
        setChanged();
    }


    @Override
    public int getContainerSize() {
        return inventory.size();
    }

    @Override
    public boolean isEmpty() {
        for(int i = 0; i < getContainerSize(); i++){
            ItemStack stack = getItem(i);
            if(!stack.isEmpty()){
                return false;
            }
        }
        return true;
    }



    @Override
    public ItemStack getItem(int slot) {
        setChanged();
        return inventory.get(slot);
    }

    @Override
    public ItemStack removeItem(int slot, int amount) {
        setChanged();
        ItemStack stack = inventory.get(slot);
        stack.shrink(amount);
        return null;
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        setChanged();
        return ContainerHelper.takeItem(inventory, slot);
    }

    @Override
    public void setItem(int slot, ItemStack itemStack) {
        setChanged();
        inventory.set(slot, itemStack.copyWithCount(1));

    }

    @Override
    public boolean stillValid(Player player) {
        return Container.stillValidBlockEntity(this, player);
    }

    @Override
    public void clearContent() {
        inventory.clear();
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        ContainerHelper.saveAllItems(output, inventory);
        output.putString("buildingName", buildingName);

    }

    @Override
    public @Nullable Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);

    }
    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return saveWithoutMetadata(registries);
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        ContainerHelper.loadAllItems(input, inventory);
        buildingName = input.getString("buildingName").get();

    }
}
