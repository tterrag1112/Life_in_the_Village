package tterrag1112.life_in_the_village.Events;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.StructureBlockEditScreen;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.renderer.entity.EntityRenderers;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.joml.Matrix4f;
import tterrag1112.life_in_the_village.Blocks.Entity.custom.VillageFoundationBlockEntity;
import tterrag1112.life_in_the_village.Blocks.ModBlocks;
import tterrag1112.life_in_the_village.DataAttachments.ModData;
import tterrag1112.life_in_the_village.Entities.ModEntities;
import tterrag1112.life_in_the_village.Entities.ModModelLayers;
import tterrag1112.life_in_the_village.Entities.client.TownspersonModel;
import tterrag1112.life_in_the_village.Entities.client.TownspersonRenderer;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Life_in_the_village;
import tterrag1112.life_in_the_village.Magic.ManaSystem.ModManaHandler;
import tterrag1112.life_in_the_village.Networking.KeyData;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Utilities.ModKeymappings;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.Buildings.ModBuildings;

import java.util.Optional;

@EventBusSubscriber(modid = Life_in_the_village.MODID, value = Dist.CLIENT)
public class ModClientEvents {

    @SubscribeEvent
    public static void registerKeymappings(RegisterKeyMappingsEvent event){
        event.register(ModKeymappings.PRESS_V.get());
        event.register(ModKeymappings.PRESS_C.get());
        event.register(ModKeymappings.PRESS_X.get());
    }

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        EntityRenderers.register(ModEntities.TOWNSPERSON.get(), TownspersonRenderer::new);
        tterrag1112.life_in_the_village.Api.ModConfiguration.load();

    }

    @SubscribeEvent
    public static void onEntityAttributeCreation(EntityAttributeCreationEvent event) {
        event.put(ModEntities.TOWNSPERSON.get(), TownspersonMob.createAttributes().build());
    }

    @SubscribeEvent
    public static void onRegisterLayerDefinitions(EntityRenderersEvent.RegisterLayerDefinitions event) {
        event.registerLayerDefinition(
                ModModelLayers.TOWNSPERSON,
                () -> TownspersonModel.createBodyLayer(CubeDeformation.NONE)
        );
    }
    @SubscribeEvent
    public static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(ModEntities.TOWNSPERSON.get(), TownspersonRenderer::new);
    }


    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event){
        while (ModKeymappings.PRESS_V.get().consumeClick()){
            ClientPacketDistributor.sendToServer(new KeyData("tterrag1112", 67));
        }
        while (ModKeymappings.PRESS_C.get().consumeClick()){
            ClientPacketDistributor.sendToServer(new KeyData("tterrag1112", 42));
        }
        while (ModKeymappings.PRESS_X.get().consumeClick()){
            ClientPacketDistributor.sendToServer(new KeyData("tterrag1112", 13));
        }
    }


}

