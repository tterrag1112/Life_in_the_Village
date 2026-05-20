package tterrag1112.life_in_the_village.Entities.client;

import net.minecraft.client.animation.KeyframeAnimation;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.*;
import net.minecraft.util.Mth;
import tterrag1112.life_in_the_village.Npc.Animations.NpcAnimations;
import tterrag1112.life_in_the_village.Npc.Brain.Gesture;

public class TownspersonModel<S extends TownspersonRenderer.TownspersonRenderState> extends HumanoidModel<TownspersonRenderer.TownspersonRenderState> {

    // ── Phase 6.1.5 — animations baked against this model's root. ──────────
    // 1.21.11 changed the API: AnimationDefinition.bake(root) returns a
    // KeyframeAnimation pre-bound to this model's ModelPart instances;
    // each frame setupAnim calls bakedAnim.apply(state, ageInTicks).
    private final KeyframeAnimation waveAnim;
    private final KeyframeAnimation stretchAnim;
    private final KeyframeAnimation lookAroundAnim;
    private final KeyframeAnimation yawnAnim;
    private final KeyframeAnimation nodAnim;
    private final KeyframeAnimation friendlyWaveAnim;
    private final KeyframeAnimation headShakeAnim;
    private final KeyframeAnimation sighAnim;
    private final KeyframeAnimation slouchAnim;
    private final KeyframeAnimation leanAnim;
    private final KeyframeAnimation sitDownAnim;
    private final KeyframeAnimation standUpAnim;

    public TownspersonModel(ModelPart root) {
        super(root);
        this.waveAnim          = NpcAnimations.WAVE.bake(root);
        this.stretchAnim       = NpcAnimations.STRETCH.bake(root);
        this.lookAroundAnim    = NpcAnimations.LOOK_AROUND.bake(root);
        this.yawnAnim          = NpcAnimations.YAWN.bake(root);
        this.nodAnim           = NpcAnimations.NOD.bake(root);
        this.friendlyWaveAnim  = NpcAnimations.FRIENDLY_WAVE.bake(root);
        this.headShakeAnim     = NpcAnimations.HEAD_SHAKE.bake(root);
        this.sighAnim          = NpcAnimations.SIGH.bake(root);
        this.slouchAnim        = NpcAnimations.SLOUCH.bake(root);
        this.leanAnim          = NpcAnimations.LEAN.bake(root);
        this.sitDownAnim       = NpcAnimations.SIT_DOWN.bake(root);
        this.standUpAnim       = NpcAnimations.STAND_UP.bake(root);
    }

    public static LayerDefinition createBodyLayer(CubeDeformation cubeDeformation) {
        MeshDefinition meshdefinition = HumanoidModel.createMesh(cubeDeformation, 0.0F);
        PartDefinition partdefinition = meshdefinition.getRoot();
        partdefinition.addOrReplaceChild("left_arm", CubeListBuilder.create().texOffs(32, 48).addBox(-1.0F, -2.0F, -2.0F, 4.0F, 12.0F, 4.0F, cubeDeformation), PartPose.offset(5.0F, 2.0F, 0.0F));
        partdefinition.addOrReplaceChild("left_leg", CubeListBuilder.create().texOffs(16, 48).addBox(-2.0F, 0.0F, -2.0F, 4.0F, 12.0F, 4.0F, cubeDeformation), PartPose.offset(1.9F, 12.0F, 0.0F));
        return LayerDefinition.create(meshdefinition, 64, 64);
    }

    @Override
    public void setupAnim(TownspersonRenderer.TownspersonRenderState renderState) {
        super.setupAnim(renderState);

        // ── Existing arm-pose overrides (trident throw, swim) ────────────
        if (renderState.leftArmPose == ArmPose.THROW_TRIDENT) {
            this.leftArm.xRot = this.leftArm.xRot * 0.5F - 3.1415927F;
            this.leftArm.yRot = 0.0F;
        }
        if (renderState.rightArmPose == ArmPose.THROW_TRIDENT) {
            this.rightArm.xRot = this.rightArm.xRot * 0.5F - 3.1415927F;
            this.rightArm.yRot = 0.0F;
        }

        float f = renderState.swimAmount;
        if (f > 0.0F) {
            this.rightArm.xRot = Mth.rotLerpRad(f, this.rightArm.xRot, -2.5132742F) + f * 0.35F * Mth.sin((double)(0.1F * renderState.ageInTicks));
            this.leftArm.xRot  = Mth.rotLerpRad(f, this.leftArm.xRot,  -2.5132742F) - f * 0.35F * Mth.sin((double)(0.1F * renderState.ageInTicks));
            this.rightArm.zRot = Mth.rotLerpRad(f, this.rightArm.zRot, -0.15F);
            this.leftArm.zRot  = Mth.rotLerpRad(f, this.leftArm.zRot,   0.15F);
            this.leftLeg.xRot -= f * 0.55F * Mth.sin((double)(0.1F * renderState.ageInTicks));
            this.rightLeg.xRot += f * 0.55F * Mth.sin((double)(0.1F * renderState.ageInTicks));
            this.head.xRot = 0.0F;
        }

        // ── Phase 6.1.5 conditional pose overrides ───────────────────────
        if (renderState.sitting) {
            applySeatedPose();
        }
        if (renderState.carryHoldState.isStarted()) {
            applyCarryHoldPose();
        }

        // ── Phase 6.1.5 baked animations ─────────────────────────────────
        // Call order = precedence for shared parts. Each KeyframeAnimation
        // is a no-op when its AnimationState isn't started.
        KeyframeAnimation idleBake = idleGestureBakeFor(renderState.lastGestureFired);
        if (idleBake != null) {
            idleBake.apply(renderState.idleGestureState, renderState.ageInTicks);
        }
        yawnAnim.apply(renderState.yawnState,                 renderState.ageInTicks);
        nodAnim.apply(renderState.nodState,                   renderState.ageInTicks);
        friendlyWaveAnim.apply(renderState.friendlyWaveState, renderState.ageInTicks);
        headShakeAnim.apply(renderState.headShakeState,       renderState.ageInTicks);
        sighAnim.apply(renderState.sighState,                 renderState.ageInTicks);
        slouchAnim.apply(renderState.slouchState,             renderState.ageInTicks);
        leanAnim.apply(renderState.leanState,                 renderState.ageInTicks);
        sitDownAnim.apply(renderState.sitDownState,           renderState.ageInTicks);
        standUpAnim.apply(renderState.standUpState,           renderState.ageInTicks);
    }

    /** Maps the idleGestureState's last-fired gesture to one of the three
     *  baked animations that share that state. */
    private KeyframeAnimation idleGestureBakeFor(Gesture g) {
        if (g == null) return null;
        return switch (g) {
            case WAVE        -> waveAnim;
            case STRETCH     -> stretchAnim;
            case LOOK_AROUND -> lookAroundAnim;
            default          -> null;
        };
    }

    /** Seated pose — legs fold forward at the hip, arms relax. Pure
     *  rotation overrides (no position offsets) so the pose cleanly
     *  reverts when {@code sitting} flips back to false. */
    private void applySeatedPose() {
        this.leftLeg.xRot  = -((float) Math.PI / 2f);
        this.rightLeg.xRot = -((float) Math.PI / 2f);
        this.leftLeg.yRot  = 0f;
        this.rightLeg.yRot = 0f;
        this.leftLeg.zRot  = 0f;
        this.rightLeg.zRot = 0f;
        this.body.xRot = 0.1f;
        this.leftArm.xRot  = 0f;
        this.rightArm.xRot = 0f;
        this.leftArm.zRot  =  0.05f;
        this.rightArm.zRot = -0.05f;
    }

    /** Carry-hold pose — both arms forward at ~90°, slight inward roll. */
    private void applyCarryHoldPose() {
        float forward = -((float) Math.PI / 2f);
        this.rightArm.xRot = forward;
        this.leftArm.xRot  = forward;
        this.rightArm.zRot = -0.15f;
        this.leftArm.zRot  =  0.15f;
    }
}
