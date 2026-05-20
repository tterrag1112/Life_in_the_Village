package tterrag1112.life_in_the_village.Entities.client;

import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.ArmorModelSet;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.AnimationState;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.item.ItemStack;
import tterrag1112.life_in_the_village.Entities.ModModelLayers;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Life_in_the_village;
import tterrag1112.life_in_the_village.Npc.Brain.Gesture;

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

        // Phase 6.1.5 — copy Brain-driven visual state into the render state.
        renderState.lastGestureFired = townspersonMob.lastGestureFired;
        renderState.sitting = townspersonMob.getPose() == Pose.SITTING;

        renderState.idleGestureState.copyFrom(townspersonMob.idleGestureState);
        renderState.carryHoldState.copyFrom(townspersonMob.carryHoldState);
        renderState.yawnState.copyFrom(townspersonMob.yawnState);
        renderState.nodState.copyFrom(townspersonMob.nodState);
        renderState.friendlyWaveState.copyFrom(townspersonMob.friendlyWaveState);
        renderState.headShakeState.copyFrom(townspersonMob.headShakeState);
        renderState.sighState.copyFrom(townspersonMob.sighState);
        renderState.slouchState.copyFrom(townspersonMob.slouchState);
        renderState.leanState.copyFrom(townspersonMob.leanState);
        renderState.sitDownState.copyFrom(townspersonMob.sitDownState);
        renderState.standUpState.copyFrom(townspersonMob.standUpState);
    }

    @Override
    public Identifier getTextureLocation(TownspersonRenderState state) {
        return TEXTURE;
    }

    public static class TownspersonRenderState extends HumanoidRenderState {
        public ItemStack mainHandItem = ItemStack.EMPTY;
        public ItemStack offHandItem = ItemStack.EMPTY;

        // Phase 6.1.5 — visual state mirrored from the entity each frame.
        public Gesture lastGestureFired = Gesture.LOOK_AROUND;
        public boolean sitting = false;

        public final AnimationState idleGestureState  = new AnimationState();
        public final AnimationState carryHoldState    = new AnimationState();
        public final AnimationState yawnState         = new AnimationState();
        public final AnimationState nodState          = new AnimationState();
        public final AnimationState friendlyWaveState = new AnimationState();
        public final AnimationState headShakeState    = new AnimationState();
        public final AnimationState sighState         = new AnimationState();
        public final AnimationState slouchState       = new AnimationState();
        public final AnimationState leanState         = new AnimationState();
        public final AnimationState sitDownState      = new AnimationState();
        public final AnimationState standUpState      = new AnimationState();
    }
}
