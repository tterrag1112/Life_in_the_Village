package tterrag1112.life_in_the_village.Components;

import com.mojang.serialization.Codec;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.codec.ByteBufCodecs;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import tterrag1112.life_in_the_village.Life_in_the_village;

import java.util.function.UnaryOperator;

public class ModDataComponents {
    public static final DeferredRegister<DataComponentType<?>> DATA_COMPONENT_TYPES =
            DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, Life_in_the_village.MODID);

    public static final DeferredHolder<DataComponentType<?>, DataComponentType<ItemWrapperComponent>> STORED_ITEM = register("stored_item",
            builder -> builder.persistent(ItemWrapperComponent.CODEC).networkSynchronized(ItemWrapperComponent.STREAM_CODEC));

    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Integer>> ITEM_COUNT = register("item_count",
            builder -> builder.persistent(Codec.INT).networkSynchronized(ByteBufCodecs.INT));

    /**
     * Letter payload (Phase 2 task 18). Lives on
     * {@link tterrag1112.life_in_the_village.Items.WrittenLetterItem}
     * stacks; carries author / recipient / pages / sealed state.
     */
    public static final DeferredHolder<DataComponentType<?>,
            DataComponentType<tterrag1112.life_in_the_village.Npc.Letters.LetterContent>>
            LETTER_CONTENT = register("letter_content",
                    builder -> builder
                            .persistent(tterrag1112.life_in_the_village.Npc.Letters.LetterContent.CODEC)
                            .networkSynchronized(tterrag1112.life_in_the_village.Npc.Letters.LetterContent.STREAM_CODEC));

    /**
     * Mod-side metadata for books — rides alongside vanilla
     * {@code WRITTEN_BOOK_CONTENT}. Carries topicsCovered, optional
     * skill buff, and the canonical bookId so the LibraryCatalogue
     * stays consistent across copies.
     */
    public static final DeferredHolder<DataComponentType<?>,
            DataComponentType<tterrag1112.life_in_the_village.Npc.Letters.ExtendedBookContent>>
            EXTENDED_BOOK_CONTENT = register("extended_book_content",
                    builder -> builder
                            .persistent(tterrag1112.life_in_the_village.Npc.Letters.ExtendedBookContent.CODEC)
                            .networkSynchronized(tterrag1112.life_in_the_village.Npc.Letters.ExtendedBookContent.STREAM_CODEC));

    /** Track 4 — structured terms attached to a JOB_CONTRACT stack. */
    public static final DeferredHolder<DataComponentType<?>,
            DataComponentType<tterrag1112.life_in_the_village.Items.JobContractTerms>>
            JOB_CONTRACT_TERMS = register("job_contract_terms",
                    builder -> builder
                            .persistent(tterrag1112.life_in_the_village.Items.JobContractTerms.CODEC)
                            .networkSynchronized(tterrag1112.life_in_the_village.Items.JobContractTerms.STREAM_CODEC));

    /** Track 4 — bound stall lease terms on a STALL_LEASE stack. */
    public static final DeferredHolder<DataComponentType<?>,
            DataComponentType<tterrag1112.life_in_the_village.Items.StallLeaseTerms>>
            STALL_LEASE_TERMS = register("stall_lease_terms",
                    builder -> builder
                            .persistent(tterrag1112.life_in_the_village.Items.StallLeaseTerms.CODEC)
                            .networkSynchronized(tterrag1112.life_in_the_village.Items.StallLeaseTerms.STREAM_CODEC));

    /** SR4 — a saint's relic identity (saintId / name / patron godId) on a RELIC stack. */
    public static final DeferredHolder<DataComponentType<?>,
            DataComponentType<tterrag1112.life_in_the_village.Npc.Religion.Saints.RelicData>>
            RELIC_DATA = register("relic_data",
                    builder -> builder
                            .persistent(tterrag1112.life_in_the_village.Npc.Religion.Saints.RelicData.CODEC)
                            .networkSynchronized(tterrag1112.life_in_the_village.Npc.Religion.Saints.RelicData.STREAM_CODEC));

    private static <T>DeferredHolder<DataComponentType<?>, DataComponentType<T>> register(String name, UnaryOperator<DataComponentType.Builder<T>> builderOperator) {

        return DATA_COMPONENT_TYPES.register(name, () -> builderOperator.apply(DataComponentType.builder()).build());
    }
    public static void register(IEventBus eventBus) {
        DATA_COMPONENT_TYPES.register(eventBus);
    }

}
