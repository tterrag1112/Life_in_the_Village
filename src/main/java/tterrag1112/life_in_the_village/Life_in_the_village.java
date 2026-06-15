package tterrag1112.life_in_the_village;

import com.mojang.logging.LogUtils;
import net.minecraft.client.renderer.entity.EntityRenderers;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.CreativeModeTab;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.slf4j.Logger;
import tterrag1112.life_in_the_village.Blocks.Entity.ModBlockEntities;
import tterrag1112.life_in_the_village.Blocks.ModBlocks;
import tterrag1112.life_in_the_village.Components.ModDataComponents;
import tterrag1112.life_in_the_village.DataAttachments.ModData;
import tterrag1112.life_in_the_village.Entities.ModEntities;
import tterrag1112.life_in_the_village.Entities.client.TownspersonRenderer;
import tterrag1112.life_in_the_village.Items.ModItems;
import tterrag1112.life_in_the_village.Npc.Brain.NpcActivities;
import tterrag1112.life_in_the_village.Npc.Brain.Memories.NpcMemoryTypes;
import tterrag1112.life_in_the_village.Npc.Brain.Sensors.NpcSensorTypes;
import tterrag1112.life_in_the_village.Village.Buildings.Inhabitants.BuildingInhabitantRegistry;
import tterrag1112.life_in_the_village.Village.Buildings.ModBuildings;
import tterrag1112.life_in_the_village.Village.Roads.Events.PlaceholderEvents;

import static net.neoforged.neoforge.common.NeoForgeMod.MOD_ID;

// The value here should match an entry in the META-INF/neoforge.mods.toml file
@Mod(Life_in_the_village.MODID)
public class Life_in_the_village {
    // Define mod id in a common place for everything to reference
    public static final String MODID = "life_in_the_village";
    // Directly reference a slf4j logger
    private static final Logger LOGGER = LogUtils.getLogger();
    // Create a Deferred Register to hold Blocks which will all be registered under the "life_in_the_village" namespace
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MODID);
    // Create a Deferred Register to hold Items which will all be registered under the "life_in_the_village" namespace
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MODID);
    // Create a Deferred Register to hold CreativeModeTabs which will all be registered under the "life_in_the_village" namespace
    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MODID);


    // The constructor for the mod class is the first code that is run when your mod is loaded.
    // FML will recognize some parameter types like IEventBus or ModContainer and pass them in automatically.
    public Life_in_the_village(IEventBus modEventBus, ModContainer modContainer) {
        // Register the commonSetup method for modloading
        modEventBus.addListener(this::commonSetup);

        // Register the Deferred Register to the mod event bus so blocks get registered
        BLOCKS.register(modEventBus);
        // Register the Deferred Register to the mod event bus so items get registered
        ITEMS.register(modEventBus);
        ModBlockEntities.BLOCK_ENTITIES.register(modEventBus);
        ModData.register(modEventBus);
        ModBuildings.register(modEventBus);
        ModEntities.ENTITY_TYPES.register(modEventBus);

        ModItems.ITEMS.register(modEventBus);
        ModBlocks.BLOCKS.register(modEventBus);
        ModDataComponents.DATA_COMPONENT_TYPES.register(modEventBus);
        // Register the Deferred Register to the mod event bus so tabs get registered
        CREATIVE_MODE_TABS.register(modEventBus);

        // Phase 6.0 — Brain infrastructure (memories, activities, sensors).
        // Schedules are driven by NpcSchedules.tick() at the entity level —
        // vanilla Schedule was removed in 1.21.11 in favour of Timeline.
        NpcMemoryTypes.register(modEventBus);
        NpcActivities.register(modEventBus);
        NpcSensorTypes.register(modEventBus);

        // Register ourselves for server and other game events we are interested in.
        // Note that this is necessary if and only if we want *this* class (Life_in_the_village) to respond directly to events.
        // Do not add this line if there are no @SubscribeEvent-annotated functions in this class, like onServerStarting() below.
        NeoForge.EVENT_BUS.register(this);




    }


    private void commonSetup(final FMLCommonSetupEvent event) {
        // Some common setup code
        LOGGER.info("HELLO FROM COMMON SETUP");
        BuildingInhabitantRegistry.registerDefaults();

        // Phase 18 doc 04 — DefaultTownSquareKits.registerAll() removed
        // alongside the TownSquareComposer + TownSquareKit deletion.
        // Plaza decoration content registers as ordinary
        // DecorationProfiles in later phases; the polygon plaza model
        // doesn't use a per-tier kit registry.

        // B2.2 — register street furniture + welcome marker +
        // notice_board DecorationProfiles. NBTs are user-authored;
        // the registry survives missing files (DecorationPass logs
        // and burns the slot).
        tterrag1112.life_in_the_village.Village.Decoration.StreetFurniture
                .StreetFurnitureProfiles.registerDefaults();
        tterrag1112.life_in_the_village.Village.Decoration.StreetFurniture
                .WelcomeMarkerProfiles.registerDefaults();

        // Phase 10 — register placeholder road events when -Dlitv.testEvents=true
        PlaceholderEvents.registerIfEnabled();

        // Task System — populate the single shared FulfillmentRegistry once.
        // Unconditional: DoTaskBehavior is always wired into the brain; the
        // registry must be ready before any NPC brain is constructed.
        tterrag1112.life_in_the_village.Npc.Tasks.Fulfillments.install();
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        // Do something when the server starts
        LOGGER.info("HELLO from server starting");
        // Phase 6.3.3.f.6 — FARMHAND → FARMER+APPRENTICE one-shot migration.
        // Idempotent: runs every start; second-and-later runs find no work
        // since the per-NPC profession rewrite happens at NBT-load via
        // TownspersonMob.readAdditionalSaveData.
        tterrag1112.life_in_the_village.Guilds.Companies.Ai
                .FarmhandConsolidationMigration.migrateAll(event.getServer());
        // Phase 6.3.3.g.2 — register animal roster definitions. Idempotent;
        // RosterRegistry.register overwrites by id, so re-registration on
        // each server start is harmless.
        tterrag1112.life_in_the_village.Village.Roster
                .AnimalRosterDefinitions.registerAll();
    }



}
