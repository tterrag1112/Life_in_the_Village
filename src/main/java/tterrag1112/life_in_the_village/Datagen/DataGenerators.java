package tterrag1112.life_in_the_village.Datagen;

import net.minecraft.data.loot.LootTableProvider;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.data.event.GatherDataEvent;
import tterrag1112.life_in_the_village.Life_in_the_village;

import java.util.List;
import java.util.Set;

@EventBusSubscriber(modid = Life_in_the_village.MODID)
public class DataGenerators {
    @SubscribeEvent
    public static void gatherData(GatherDataEvent.Client event) {
        event.createProvider(ModDatamapProvider::new);
        event.createProvider(ModModelProvider::new);
        event.createProvider(ModItemTagProvider::new);
        event.createProvider(ModLanguageProvider::new);

    }
}
