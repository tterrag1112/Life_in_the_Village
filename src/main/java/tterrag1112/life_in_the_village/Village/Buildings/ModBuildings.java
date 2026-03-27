package tterrag1112.life_in_the_village.Village.Buildings;

import net.minecraft.core.Registry;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;
import net.neoforged.neoforge.registries.RegistryBuilder;
import tterrag1112.life_in_the_village.Kingdom.Castle.CastleStyle;
import tterrag1112.life_in_the_village.Life_in_the_village;
import tterrag1112.life_in_the_village.Village.Building;

import java.util.function.Supplier;

public class ModBuildings {
    public static final ResourceKey<Registry<Building>> BUILDING_REGISTRY_KEY = ResourceKey.createRegistryKey(Identifier.fromNamespaceAndPath(Life_in_the_village.MODID, "buildings"));
    public static final Registry<Building> BUILDING_REGISTRY = new RegistryBuilder<>(BUILDING_REGISTRY_KEY).sync(true).create();

    public static final DeferredRegister<Building> BUILDINGS = DeferredRegister.create(BUILDING_REGISTRY, Life_in_the_village.MODID);
    //public static final Supplier<Building> TEST = BUILDINGS.register("test", () -> new Building("testing"));



    public static void register(IEventBus eventBus){
        BUILDINGS.register(eventBus);
    }

}
