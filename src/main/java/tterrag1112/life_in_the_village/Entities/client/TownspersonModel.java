package tterrag1112.life_in_the_village.Entities.client;

import net.minecraft.client.animation.AnimationDefinition;
import net.minecraft.client.animation.KeyframeAnimations;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.*;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.AnimationState;
import org.joml.Vector3f;
import tterrag1112.life_in_the_village.Npc.Animations.NpcAnimations;
import tterrag1112.life_in_the_village.Npc.Brain.Gesture;

public class TownspersonModel<S extends TownspersonRenderer.TownspersonRenderState> extends HumanoidModel<TownspersonRenderer.TownspersonRenderState> {

    /** Reusable scratch vector for KeyframeAnimations.animate — the
     *  static helper requires one. Safe as a static field because
     *  setupAnim only runs on the client render thread. */
    private static final Vector3f ANIM_CACHE = new Vector3f();

    public TownspersonModel(ModelPart root) {
        super(root);
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
        // Seated pose: applied before AnimationState animations so a
        // sitting NPC can still nod / wave from the chest up.
        if (renderState.sitting) {
            applySeatedPose();
        }
        // Carry-hold pose: applied while the carry-hold state is active.
        // Will visually clash with arm gestures fired during carrying —
        // accepted per Phase 6.1.5 conflict policy.
        if (renderState.carryHoldState.isStarted()) {
            applyCarryHoldPose();
        }

        // ── Phase 6.1.5 AnimationState-driven gestures ───────────────────
        // Call order = precedence for shared parts. Per-gesture states
        // come after the shared idleGestureState so a dedicated gesture
        // (NOD on head, YAWN on head) wins over a stale LOOK_AROUND.
        AnimationDefinition idleDef = idleGestureDefFor(renderState.lastGestureFired);
        if (idleDef != null) {
            playAnimation(renderState.idleGestureState, idleDef, renderState.ageInTicks);
        }
        playAnimation(renderState.yawnState,         NpcAnimations.YAWN,          renderState.ageInTicks);
        playAnimation(renderState.nodState,          NpcAnimations.NOD,           renderState.ageInTicks);
        playAnimation(renderState.friendlyWaveState, NpcAnimations.FRIENDLY_WAVE, renderState.ageInTicks);
        playAnimation(renderState.headShakeState,    NpcAnimations.HEAD_SHAKE,    renderState.ageInTicks);
        playAnimation(renderState.sighState,         NpcAnimations.SIGH,          renderState.ageInTicks);
        playAnimation(renderState.slouchState,       NpcAnimations.SLOUCH,        renderState.ageInTicks);
        playAnimation(renderState.leanState,         NpcAnimations.LEAN,          renderState.ageInTicks);
        playAnimation(renderState.sitDownState,      NpcAnimations.SIT_DOWN,      renderState.ageInTicks);
        playAnimation(renderState.standUpState,      NpcAnimations.STAND_UP,      renderState.ageInTicks);
    }

    /** HumanoidModel's ancestor chain in 1.21.11 does NOT expose
     *  {@code Model.animate(AnimationState, AnimationDefinition, float)},
     *  so we use the static {@link KeyframeAnimations#animate} helper
     *  directly. No-op when the state isn't started. */
    private void playAnimation(AnimationState state, AnimationDefinition def, float ageInTicks) {
        state.ifStarted(s -> KeyframeAnimations.animate(
                this, def, s.getTimeInMillis(ageInTicks), 1.0F, ANIM_CACHE));
    }

    /** Maps the idleGestureState's last-fired gesture to one of the three
     *  animations that share that state (WAVE / STRETCH / LOOK_AROUND).
     *  Other gestures have dedicated AnimationStates and are not handled
     *  via the shared idle state, so they return null here. */
    private static AnimationDefinition idleGestureDefFor(Gesture g) {
        if (g == null) return null;
        return switch (g) {
            case WAVE        -> NpcAnimations.WAVE;
            case STRETCH     -> NpcAnimations.STRETCH;
            case LOOK_AROUND -> NpcAnimations.LOOK_AROUND;
            default          -> null;
        };
    }

    /** Seated pose — legs fold forward at the hip, arms relax. Pure
     *  rotation overrides (no position offsets) so the pose cleanly
     *  reverts on the next frame when {@code sitting} flips back to
     *  false. The slight body z-tilt sells the "perched" silhouette
     *  without needing to lower the model origin. */
    private void applySeatedPose() {
        // Legs fold forward 90° at the hip.
        this.leftLeg.xRot  = -((float) Math.PI / 2f);
        this.rightLeg.xRot = -((float) Math.PI / 2f);
        this.leftLeg.yRot  = 0f;
        this.rightLeg.yRot = 0f;
        this.leftLeg.zRot  = 0f;
        this.rightLeg.zRot = 0f;
        // Slight forward body lean to read as seated rather than levitating.
        this.body.xRot = 0.1f;
        // Relax arms at sides.
        this.leftArm.xRot  = 0f;
        this.rightArm.xRot = 0f;
        this.leftArm.zRot  =  0.05f;
        this.rightArm.zRot = -0.05f;
    }

    /** Carry-hold pose — both arms forward at ~90° down then forward,
     *  hands roughly at waist height in front. */
    private void applyCarryHoldPose() {
        float forward = -((float) Math.PI / 2f); // 90° forward at shoulder
        this.rightArm.xRot = forward;
        this.leftArm.xRot  = forward;
        // Slight inward roll so hands meet in front.
        this.rightArm.zRot = -0.15f;
        this.leftArm.zRot  =  0.15f;
    }
}
