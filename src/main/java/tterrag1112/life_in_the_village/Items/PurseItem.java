package tterrag1112.life_in_the_village.Items;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.stats.Stats;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.SlotAccess;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickAction;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.inventory.tooltip.BundleTooltip;
import net.minecraft.world.inventory.tooltip.TooltipComponent;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.item.component.Weapon;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.neoforge.capabilities.Capabilities;
import org.apache.commons.lang3.math.Fraction;
import org.jetbrains.annotations.NotNull;
import tterrag1112.life_in_the_village.Components.ItemWrapperComponent;
import tterrag1112.life_in_the_village.Components.ModDataComponents;
import tterrag1112.life_in_the_village.Events.IUsageTickerOverride;
import tterrag1112.life_in_the_village.Utilities.ModTags;

import javax.swing.text.AttributeSet;
import javax.swing.text.Style;
import java.awt.*;
import java.util.*;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Stream;

public class PurseItem extends Item implements IUsageTickerOverride {
    public static final int MAX_SHOWN_GRID_ITEMS_X = 4;
    public static final int MAX_SHOWN_GRID_ITEMS_Y = 3;
    public static final int MAX_SHOWN_GRID_ITEMS = 12;
    public static final int OVERFLOWING_MAX_SHOWN_GRID_ITEMS = 11;
    private static final int FULL_BAR_COLOR = ARGB.colorFromFloat(1.0F, 1.0F, 0.33F, 0.33F);
    private static final int BAR_COLOR = ARGB.colorFromFloat(1.0F, 0.44F, 0.53F, 1.0F);
    private static final int TICKS_AFTER_FIRST_THROW = 10;
    private static final int TICKS_BETWEEN_THROWS = 2;
    private static final int TICKS_MAX_THROW_DURATION = 200;
    private static final int MAX_ITEMS = 640;

    public PurseItem(Item.Properties properties) {
        super(properties);
    }

    @Override
    public boolean overrideOtherStackedOnMe(@NotNull ItemStack purse, @NotNull ItemStack incoming, @NotNull Slot slot, @NotNull ClickAction action, @NotNull Player player, @NotNull SlotAccess carriedSlotAccessor) {
        if(purse.getCount() != 1 || action != ClickAction.SECONDARY || !slot.allowModification(player))
            return false;

        if(incoming.isEmpty())
            return dropOntoEmptyCursor(player, purse, carriedSlotAccessor);
        else
            return absorbFromCursor(player, purse, carriedSlotAccessor);
    }

    @Override
    public boolean overrideStackedOnOther(@NotNull ItemStack pouch, @NotNull Slot slot, @NotNull ClickAction action, @NotNull Player player) {
        if(pouch.getCount() != 1 || action != ClickAction.SECONDARY || !slot.allowModification(player))
            return false;

        ItemStack droppedOnto = slot.getItem();
        if(droppedOnto.isEmpty())
            //right click an empty slot with a pouch -> take items from the pouch and drop them into the empty slot
            return dropIntoEmptySlot(player, pouch, slot);
        else
            //right click on something with a pouch -> take items from the slot and try to put them in the pouch
            return absorbFromSlot(player, pouch, slot);
    }

    private boolean absorbFromCursor(Player player, ItemStack purse, SlotAccess cursorAccess) {
        return mutateContents(purse, contents -> {
            ItemStack onCursor = cursorAccess.get();

            if(!contents.absorb(onCursor))
                return false;

            cursorAccess.set(onCursor); //IDK if you need to call "set" on the SlotAccess here but it doesn't hurt
            playInsertSound(player);
            return true;
        });
    }

    private boolean absorbFromSlot(Player player, ItemStack purse, Slot pickupFrom) {
        return mutateContents(purse, contents -> {
            if(!contents.absorb(pickupFrom.getItem()))
                return false;

            pickupFrom.setChanged();
            playInsertSound(player);
            return true;
        });
    }

    private boolean dropOntoEmptyCursor(Player player, ItemStack pouch, SlotAccess cursorAccess) {
        return mutateContents(pouch, contents -> {
            if(contents.isEmpty())
                return false;

            cursorAccess.set(contents.splitOneStack());
            playRemoveOneSound(player);
            return true;
        });
    }

    private boolean dropIntoEmptySlot(Player player, ItemStack pouch, Slot depositInto) {
        return mutateContents(pouch, contents -> {
            if(contents.isEmpty())
                return false;

            depositInto.set(contents.splitOneStack());
            playRemoveOneSound(player);
            return true;
        });
    }


    // vanilla copy
    private static void playRemoveOneSound(Entity entity) {
        entity.playSound(SoundEvents.BUNDLE_REMOVE_ONE, 0.8F, 0.8F + entity.level().getRandom().nextFloat() * 0.4F);
    }

    private static void playInsertSound(Entity entity) {
        entity.playSound(SoundEvents.BUNDLE_INSERT, 0.8F, 0.8F + entity.level().getRandom().nextFloat() * 0.4F);
    }

    @Override
    public boolean isBarVisible(@NotNull ItemStack stack) {
        return true;
    }

    @Override
    public int getBarWidth(@NotNull ItemStack stack) {
        int count = getCount(stack);
        return Math.round(count * 13.0F / MAX_ITEMS);
    }


    public static PurseItem.PouchContents getContents(ItemStack stack) {
        return PouchContents.readFromStack(stack);
    }

    public static int getCount(ItemStack stack) {
        return PouchContents.readCountOnlyFromStack(stack);
    }

    public static <T> T mutateContents(ItemStack pouch, Function<PouchContents, T> func) {
        return PouchContents.mutate(pouch, func);
    }

    @Override
    public Optional<TooltipComponent> getTooltipImage(ItemStack stack) {
        return super.getTooltipImage(stack);
    }

    // Add this helper class inside PurseItem
    private static class ItemCountEntry {
        ItemStack item;
        int count;

        ItemCountEntry(ItemStack item, int count) {
            this.item = item;
            this.count = count;
        }
    }



    @Override
    public ItemStack getUsageTickerItem(ItemStack stack, RegistryAccess access) {
        PouchContents contents = getContents(stack);
        if(contents.isEmpty())
            return stack;

        // Return the first non-empty stack, or the stack with the most items
        return contents.getContents().stream()
                .max((a, b) -> Integer.compare(a.getCount(), b.getCount()))
                .orElse(stack);
    }

    @Override
    public int getUsageTickerCountForItem(ItemStack stack, Predicate<ItemStack> target) {
        PouchContents contents = getContents(stack);
        if(contents.isEmpty())
            return 0;

        // Sum up the counts of all stacks that match the predicate
        return contents.getContents().stream()
                .filter(target)
                .mapToInt(ItemStack::getCount)
                .sum();
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay tooltipDisplay, Consumer<Component> tooltipAdder, TooltipFlag flag) {
        PurseItem.PouchContents contents = getContents(stack);
        List<ItemStack> items = contents.getContents();

        // Group items by type and sum their counts
        java.util.Map<String, ItemCountEntry> itemCounts = new java.util.LinkedHashMap<>();
        for(ItemStack item : items) {
            String key = item.getItem().getName().getString();
            if(itemCounts.containsKey(key)) {
                itemCounts.get(key).count += item.getCount();
            } else {
                itemCounts.put(key, new ItemCountEntry(item, item.getCount()));
            }
        }

        List<ItemCountEntry> entries = new java.util.ArrayList<>(itemCounts.values());

        if(entries.size() <= 3) {
            // Show all items with counts
            for(int i = 0; i < entries.size(); i++) {
                MutableComponent comp = Component.empty();
                comp.withColor(-171);
                comp.append(Component.literal(" "));
                ItemCountEntry entry = entries.get(i);
                comp.append(entry.item.getHoverName());
                comp.append(Component.literal(" x " + entry.count));
                tooltipAdder.accept(comp);
            }
        }
    }



    public static class PouchItemUseContext extends UseOnContext {

        protected PouchItemUseContext(UseOnContext parent, ItemStack stack, BlockPos targetPos) {
            super(parent.getLevel(), parent.getPlayer(), parent.getHand(), stack,
                    new BlockHitResult(parent.getClickLocation(), parent.getClickedFace(), targetPos, parent.isInside()));
        }

    }

    public static class PouchContents {

        public static final String TAG_STORED_ITEM = "storedItem";
        public static final String TAG_COUNT = "itemCount";

        private List<ItemStack> contents = new ArrayList<>();
        private static final int MAX_WEIGHT = MAX_ITEMS;
        private int count = 0;

        public ItemStack writeToStack(ItemStack target) {
            CompoundTag tag = new CompoundTag();
            //target.save(access, tag);

            if(isEmpty()) {
                //If we are empty, and the target doesn't have any NBT tag, that's cool. Nothing to do.
                //If the target *does* have an NBT tag, ensure we remove our tags.
                target.remove(ModDataComponents.STORED_ITEM.get());
                target.remove(ModDataComponents.ITEM_COUNT.get());

                //And, if we just removed *all* the tags on the target, erase its NBT tag entirely.;
            } else {
                ItemContainerContents containerContents = ItemContainerContents.fromItems(contents);
                target.set(ModDataComponents.STORED_ITEM.get(), new ItemWrapperComponent(containerContents));
                target.set(ModDataComponents.ITEM_COUNT.get(), count);
            }

            return target;
        }

        /**
         * Reads the target's components and spits out the content
         * @param target What is hopefully a seed pouch
         * @return The contents of the pouch
         */
        public static PouchContents readFromStack(ItemStack target) {
            PouchContents contents = new PouchContents();

            if (target.has(ModDataComponents.STORED_ITEM.get()) && target.has(ModDataComponents.ITEM_COUNT.get())) {
                ItemWrapperComponent wrapper = target.get(ModDataComponents.STORED_ITEM.get());
                contents.contents = new ArrayList<>(wrapper.stack().stream().toList());
                contents.count = target.get(ModDataComponents.ITEM_COUNT.get()); // ADD THIS LINE
            }

            return contents;
        }

        //Invariant: readFromStack(target).getCount() == readCountOnlyFromStack().
        //Reading itemstacks is expensive, often times we don't care about them.
        public static int readCountOnlyFromStack(ItemStack target) {
            if (target.has(ModDataComponents.ITEM_COUNT.get()))
                return target.get(ModDataComponents.ITEM_COUNT.get());
            else return 0;
        }

        //Ensures you can't read a PouchContents, mutate it, then forget to write it back to the stack.
        public static <T> T mutate(ItemStack pouch, Function<PouchContents, T> action) {
            PouchContents contents = readFromStack(pouch);
            T result = action.apply(contents);
            contents.writeToStack(pouch);

            return result;
        }

        public boolean isEmpty() {
            return contents.isEmpty() || count == 0;
        }

        public List<ItemStack> getContents() {
            return contents;
        }

        public int getCount() {
            return count; // Use the field instead of recalculating
        }

        public int getWeight() {
            // Calculate weight similar to bundles (64 for full stack, less for partial)
            return contents.stream()
                    .mapToInt(stack -> {
                        int fraction = (stack.getCount() * 64) / stack.getMaxStackSize();
                        return Math.max(1, fraction);
                    })
                    .sum();
        }

        // Moves items from 'other' into self (mutating both). returns whether it moved any items
        public boolean absorb(ItemStack other) {
            if(!canFit(other))
                return false;

            int initialCount = other.getCount();

            // Try to merge with existing stacks first
            for(ItemStack existing : contents) {
                if(ItemStack.isSameItemSameComponents(existing, other)) {
                    int spaceLeft = existing.getMaxStackSize() - existing.getCount();
                    if(spaceLeft > 0) {
                        int toMove = Math.min(spaceLeft, other.getCount());
                        existing.grow(toMove);
                        other.shrink(toMove);
                        count += toMove; // UPDATE COUNT

                        if(other.isEmpty())
                            return true;
                    }
                }
            }

            // If there's still items left and we have space, add as new stack
            if(!other.isEmpty() && getWeight() < MAX_WEIGHT) {
                int toMove = other.getCount();
                contents.add(other.copy());
                other.setCount(0);
                count += toMove; // UPDATE COUNT
                return true;
            }

            return other.getCount() < initialCount; // Return true if we moved any
        }

        // Mutates self - removes one stack from contents
        public ItemStack splitOneStack() {
            if(isEmpty())
                return ItemStack.EMPTY;

            // Remove the last stack
            ItemStack removed = contents.remove(contents.size() - 1);
            count -= removed.getCount(); // UPDATE COUNT
            return removed;
        }

        public boolean canFit(ItemStack other) {
            if(!other.is(ModTags.Items.IS_CURRENCY))
                return false;

            // Check if adding this would exceed weight limit
            int additionalWeight = Math.max(1, (other.getCount() * 64) / other.getMaxStackSize());
            return getWeight() + additionalWeight <= MAX_WEIGHT;
        }

    }





}
