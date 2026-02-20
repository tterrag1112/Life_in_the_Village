package tterrag1112.life_in_the_village.Currency;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.registries.datamaps.DataMapType;
import tterrag1112.life_in_the_village.Life_in_the_village;


public record ModCurrency(String name, Integer value) {
    public static final Codec<ModCurrency> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.fieldOf("name").forGetter(ModCurrency::name),
            Codec.INT.fieldOf("value").forGetter(ModCurrency::value)

    ).apply(instance, ModCurrency::new));

    public static final DataMapType<Item, ModCurrency> MOD_CURRENCY = DataMapType.builder(
            Identifier.fromNamespaceAndPath(Life_in_the_village.MODID, "currency"),
            Registries.ITEM,
            ModCurrency.CODEC
    ).build();



}

