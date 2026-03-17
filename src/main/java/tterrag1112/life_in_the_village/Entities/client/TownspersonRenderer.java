package tterrag1112.life_in_the_village.Entities.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.ArmorModelSet;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.ZombieRenderer;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.client.renderer.entity.layers.ItemInHandLayer;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.client.renderer.state.CameraRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.item.ItemStack;
import tterrag1112.life_in_the_village.Entities.ModModelLayers;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Life_in_the_village;

public class TownspersonRenderer extends HumanoidMobRenderer<TownspersonMob, TownspersonRenderer.TownspersonRenderState, TownspersonModel<TownspersonRenderer.TownspersonRenderState>> {

    private static final Identifier TEXTURE = Identifier.fromNamespaceAndPath(
            Life_in_the_village.MODID, "textures/entity/townsperson.png"
    );

    public TownspersonRenderer(EntityRendererProvider.Context context) {
        super(context, new TownspersonModel(context.bakeLayer(ModModelLayers.TOWNSPERSON)), 0.5f);
        this.addLayer(new HumanoidArmorLayer<>(
                this,
                new ArmorModelSet<>(
                        new TownspersonModel<>(context.bakeLayer(ModelLayers.PLAYER_ARMOR.head())),
                        new TownspersonModel<>(context.bakeLayer(ModelLayers.PLAYER_ARMOR.chest())),
                        new TownspersonModel<>(context.bakeLayer(ModelLayers.PLAYER_ARMOR.legs())),
                        new TownspersonModel<>(context.bakeLayer(ModelLayers.PLAYER_ARMOR.feet()))),
                        context.getEquipmentRenderer()));

       // this.addLayer(new ItemInHandLayer<>(this));
    }
    @Override
    public TownspersonRenderState createRenderState() {
        return new TownspersonRenderState();

    }

    @Override
    public void extractRenderState(TownspersonMob townspersonMob, TownspersonRenderState renderState, float partialTick) {
        super.extractRenderState(townspersonMob, renderState, partialTick);
        renderState.mainHandItem = townspersonMob.getMainHandItem();
        renderState.offHandItem = townspersonMob.getOffhandItem();
    }

    @Override
    public Identifier getTextureLocation(TownspersonRenderState state) {
        return TEXTURE;
    }

    public static class TownspersonRenderState extends HumanoidRenderState {
        public ItemStack mainHandItem = ItemStack.EMPTY;
        public ItemStack offHandItem = ItemStack.EMPTY;
    }
}
