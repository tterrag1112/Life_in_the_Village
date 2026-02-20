package tterrag1112.life_in_the_village.DataAttachments;

import com.mojang.serialization.Codec;
import net.minecraft.world.entity.animal.fish.Cod;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;
import tterrag1112.life_in_the_village.Life_in_the_village;

import java.util.function.Supplier;

public class ModData {
    public static final DeferredRegister<AttachmentType<?>> ATTACHMENT_TYPES =
            DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, Life_in_the_village.MODID);

    public static final Supplier<AttachmentType<Integer>> MANA = ATTACHMENT_TYPES.register("mana",
            () -> AttachmentType.<Integer>builder(() -> 0).serialize(Codec.INT.fieldOf("mana")).build());
    public static final Supplier<AttachmentType<Integer>> MANA_DEATH_PENALTY = ATTACHMENT_TYPES.register("mana_death_penalty",
            () -> AttachmentType.<Integer>builder(() -> 0).serialize(Codec.INT.fieldOf("penalty")).build());

    public static void register(IEventBus eventBus){
        ATTACHMENT_TYPES.register(eventBus);
    }
}
