package tterrag1112.life_in_the_village.Components;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import org.jetbrains.annotations.NotNull;

public record ItemWrapperComponent(ItemContainerContents stack) {
    public static final Codec<ItemWrapperComponent> CODEC = RecordCodecBuilder.create(
            itemWrapperComponentInstance -> itemWrapperComponentInstance.group(
                    ItemContainerContents.CODEC.fieldOf("stack").forGetter(ItemWrapperComponent::stack)
            ).apply(itemWrapperComponentInstance, ItemWrapperComponent::new)
    );

    public static final StreamCodec<RegistryFriendlyByteBuf, ItemWrapperComponent> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public @NotNull ItemWrapperComponent decode(@NotNull RegistryFriendlyByteBuf buf) {
            ItemContainerContents realStack = ItemContainerContents.STREAM_CODEC.decode(buf);
            return new ItemWrapperComponent(realStack);
        }

        @Override
        public void encode(@NotNull RegistryFriendlyByteBuf buf, ItemWrapperComponent component) {
            ItemContainerContents.STREAM_CODEC.encode(buf, component.stack);
        }
    };
}
