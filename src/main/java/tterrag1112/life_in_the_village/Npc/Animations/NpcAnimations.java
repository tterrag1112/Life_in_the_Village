package tterrag1112.life_in_the_village.Npc.Animations;

import net.minecraft.client.animation.AnimationChannel;
import net.minecraft.client.animation.AnimationDefinition;
import net.minecraft.client.animation.Keyframe;
import net.minecraft.client.animation.KeyframeAnimations;

/**
 * Keyframe-based animations for {@code TownspersonMob}. Mirrors the
 * vanilla idiom ({@code WardenAnimation}, {@code SnifferAnimation}).
 * Single visual variant per gesture — cultural / profession variants
 * are deferred to Phase 6.3+.
 *
 * <p>HumanoidModel target names: {@code head}, {@code body},
 * {@code right_arm}, {@code left_arm}, {@code right_leg}, {@code left_leg}.
 * Pivots are vanilla-standard.
 *
 * <p>Conflict policy: when two animations target the same part, the
 * last animate() call in setupAnim wins — call order in
 * {@link tterrag1112.life_in_the_village.Entities.client.TownspersonModel#setupAnim}
 * is the source of truth.
 */
public final class NpcAnimations {

    private NpcAnimations() {}

    // ── Tunable constants ───────────────────────────────────────────────────
    private static final float WAVE_LEN          = 1.5f;
    private static final float STRETCH_LEN       = 1.5f;
    private static final float LOOK_AROUND_LEN   = 2.0f;
    private static final float YAWN_LEN          = 1.25f;
    private static final float NOD_LEN           = 0.75f;
    private static final float FRIENDLY_WAVE_LEN = 1.75f;
    private static final float HEAD_SHAKE_LEN    = 1.0f;
    private static final float SIGH_LEN          = 1.5f;
    private static final float SLOUCH_LEN        = 0.5f; // ramp to held pose
    private static final float LEAN_LEN          = 0.5f;
    private static final float SIT_DOWN_LEN      = 0.75f;
    private static final float STAND_UP_LEN      = 0.75f;

    // ── IDLE GESTURES (single-shot, return to neutral) ──────────────────────

    /** Right arm raises to shoulder-height, sways left-right twice, returns. */
    public static final AnimationDefinition WAVE = AnimationDefinition.Builder.withLength(WAVE_LEN)
            .addAnimation("right_arm",
                    new AnimationChannel(AnimationChannel.Targets.ROTATION,
                            new Keyframe(0.0f,  KeyframeAnimations.degreeVec(0, 0, 0),     AnimationChannel.Interpolations.LINEAR),
                            new Keyframe(0.25f, KeyframeAnimations.degreeVec(-120, 0, 20), AnimationChannel.Interpolations.LINEAR),
                            new Keyframe(0.5f,  KeyframeAnimations.degreeVec(-120, 0, -10),AnimationChannel.Interpolations.LINEAR),
                            new Keyframe(0.75f, KeyframeAnimations.degreeVec(-120, 0, 20), AnimationChannel.Interpolations.LINEAR),
                            new Keyframe(1.0f,  KeyframeAnimations.degreeVec(-120, 0, -10),AnimationChannel.Interpolations.LINEAR),
                            new Keyframe(WAVE_LEN, KeyframeAnimations.degreeVec(0, 0, 0),  AnimationChannel.Interpolations.LINEAR)
                    ))
            .build();

    /** Both arms raise overhead; brief hold; return. */
    public static final AnimationDefinition STRETCH = AnimationDefinition.Builder.withLength(STRETCH_LEN)
            .addAnimation("right_arm",
                    new AnimationChannel(AnimationChannel.Targets.ROTATION,
                            new Keyframe(0.0f,  KeyframeAnimations.degreeVec(0, 0, 0),    AnimationChannel.Interpolations.LINEAR),
                            new Keyframe(0.5f,  KeyframeAnimations.degreeVec(-170, 0, 0), AnimationChannel.Interpolations.LINEAR),
                            new Keyframe(1.0f,  KeyframeAnimations.degreeVec(-170, 0, 0), AnimationChannel.Interpolations.LINEAR),
                            new Keyframe(STRETCH_LEN, KeyframeAnimations.degreeVec(0, 0, 0), AnimationChannel.Interpolations.LINEAR)
                    ))
            .addAnimation("left_arm",
                    new AnimationChannel(AnimationChannel.Targets.ROTATION,
                            new Keyframe(0.0f,  KeyframeAnimations.degreeVec(0, 0, 0),    AnimationChannel.Interpolations.LINEAR),
                            new Keyframe(0.5f,  KeyframeAnimations.degreeVec(-170, 0, 0), AnimationChannel.Interpolations.LINEAR),
                            new Keyframe(1.0f,  KeyframeAnimations.degreeVec(-170, 0, 0), AnimationChannel.Interpolations.LINEAR),
                            new Keyframe(STRETCH_LEN, KeyframeAnimations.degreeVec(0, 0, 0), AnimationChannel.Interpolations.LINEAR)
                    ))
            .build();

    /** Head turns ~30° right, holds, turns ~30° left, holds, returns. */
    public static final AnimationDefinition LOOK_AROUND = AnimationDefinition.Builder.withLength(LOOK_AROUND_LEN)
            .addAnimation("head",
                    new AnimationChannel(AnimationChannel.Targets.ROTATION,
                            new Keyframe(0.0f, KeyframeAnimations.degreeVec(0, 0, 0),   AnimationChannel.Interpolations.LINEAR),
                            new Keyframe(0.5f, KeyframeAnimations.degreeVec(0, -30, 0), AnimationChannel.Interpolations.LINEAR),
                            new Keyframe(1.0f, KeyframeAnimations.degreeVec(0, -30, 0), AnimationChannel.Interpolations.LINEAR),
                            new Keyframe(1.5f, KeyframeAnimations.degreeVec(0, 30, 0),  AnimationChannel.Interpolations.LINEAR),
                            new Keyframe(LOOK_AROUND_LEN, KeyframeAnimations.degreeVec(0, 0, 0), AnimationChannel.Interpolations.LINEAR)
                    ))
            .build();

    /** Head tilts back ~15°, holds, returns. */
    public static final AnimationDefinition YAWN = AnimationDefinition.Builder.withLength(YAWN_LEN)
            .addAnimation("head",
                    new AnimationChannel(AnimationChannel.Targets.ROTATION,
                            new Keyframe(0.0f,  KeyframeAnimations.degreeVec(0, 0, 0),   AnimationChannel.Interpolations.LINEAR),
                            new Keyframe(0.4f,  KeyframeAnimations.degreeVec(-15, 0, 0), AnimationChannel.Interpolations.LINEAR),
                            new Keyframe(0.85f, KeyframeAnimations.degreeVec(-15, 0, 0), AnimationChannel.Interpolations.LINEAR),
                            new Keyframe(YAWN_LEN, KeyframeAnimations.degreeVec(0, 0, 0), AnimationChannel.Interpolations.LINEAR)
                    ))
            .build();

    /** Head bobs down ~20° and back, once. */
    public static final AnimationDefinition NOD = AnimationDefinition.Builder.withLength(NOD_LEN)
            .addAnimation("head",
                    new AnimationChannel(AnimationChannel.Targets.ROTATION,
                            new Keyframe(0.0f,  KeyframeAnimations.degreeVec(0, 0, 0),  AnimationChannel.Interpolations.LINEAR),
                            new Keyframe(0.35f, KeyframeAnimations.degreeVec(20, 0, 0), AnimationChannel.Interpolations.LINEAR),
                            new Keyframe(NOD_LEN, KeyframeAnimations.degreeVec(0, 0, 0), AnimationChannel.Interpolations.LINEAR)
                    ))
            .build();

    /** Both arms raise; right arm sweeps wider — bigger energetic wave. */
    public static final AnimationDefinition FRIENDLY_WAVE = AnimationDefinition.Builder.withLength(FRIENDLY_WAVE_LEN)
            .addAnimation("right_arm",
                    new AnimationChannel(AnimationChannel.Targets.ROTATION,
                            new Keyframe(0.0f,  KeyframeAnimations.degreeVec(0, 0, 0),     AnimationChannel.Interpolations.LINEAR),
                            new Keyframe(0.3f,  KeyframeAnimations.degreeVec(-140, 0, 30), AnimationChannel.Interpolations.LINEAR),
                            new Keyframe(0.6f,  KeyframeAnimations.degreeVec(-140, 0, -25),AnimationChannel.Interpolations.LINEAR),
                            new Keyframe(0.9f,  KeyframeAnimations.degreeVec(-140, 0, 30), AnimationChannel.Interpolations.LINEAR),
                            new Keyframe(1.2f,  KeyframeAnimations.degreeVec(-140, 0, -25),AnimationChannel.Interpolations.LINEAR),
                            new Keyframe(FRIENDLY_WAVE_LEN, KeyframeAnimations.degreeVec(0, 0, 0), AnimationChannel.Interpolations.LINEAR)
                    ))
            .addAnimation("left_arm",
                    new AnimationChannel(AnimationChannel.Targets.ROTATION,
                            new Keyframe(0.0f, KeyframeAnimations.degreeVec(0, 0, 0),     AnimationChannel.Interpolations.LINEAR),
                            new Keyframe(0.4f, KeyframeAnimations.degreeVec(-90, 0, -15), AnimationChannel.Interpolations.LINEAR),
                            new Keyframe(1.2f, KeyframeAnimations.degreeVec(-90, 0, -15), AnimationChannel.Interpolations.LINEAR),
                            new Keyframe(FRIENDLY_WAVE_LEN, KeyframeAnimations.degreeVec(0, 0, 0), AnimationChannel.Interpolations.LINEAR)
                    ))
            .build();

    /** Head turns rapidly left-right-left, returns. */
    public static final AnimationDefinition HEAD_SHAKE = AnimationDefinition.Builder.withLength(HEAD_SHAKE_LEN)
            .addAnimation("head",
                    new AnimationChannel(AnimationChannel.Targets.ROTATION,
                            new Keyframe(0.0f, KeyframeAnimations.degreeVec(0, 0, 0),   AnimationChannel.Interpolations.LINEAR),
                            new Keyframe(0.2f, KeyframeAnimations.degreeVec(0, -25, 0), AnimationChannel.Interpolations.LINEAR),
                            new Keyframe(0.4f, KeyframeAnimations.degreeVec(0, 25, 0),  AnimationChannel.Interpolations.LINEAR),
                            new Keyframe(0.6f, KeyframeAnimations.degreeVec(0, -25, 0), AnimationChannel.Interpolations.LINEAR),
                            new Keyframe(0.8f, KeyframeAnimations.degreeVec(0, 25, 0),  AnimationChannel.Interpolations.LINEAR),
                            new Keyframe(HEAD_SHAKE_LEN, KeyframeAnimations.degreeVec(0, 0, 0), AnimationChannel.Interpolations.LINEAR)
                    ))
            .build();

    /** Shoulders drop slightly (right arm z-rotation), head tilts down, brief hold. */
    public static final AnimationDefinition SIGH = AnimationDefinition.Builder.withLength(SIGH_LEN)
            .addAnimation("head",
                    new AnimationChannel(AnimationChannel.Targets.ROTATION,
                            new Keyframe(0.0f, KeyframeAnimations.degreeVec(0, 0, 0),   AnimationChannel.Interpolations.LINEAR),
                            new Keyframe(0.5f, KeyframeAnimations.degreeVec(10, 0, 0),  AnimationChannel.Interpolations.LINEAR),
                            new Keyframe(1.0f, KeyframeAnimations.degreeVec(10, 0, 0),  AnimationChannel.Interpolations.LINEAR),
                            new Keyframe(SIGH_LEN, KeyframeAnimations.degreeVec(0, 0, 0), AnimationChannel.Interpolations.LINEAR)
                    ))
            .addAnimation("right_arm",
                    new AnimationChannel(AnimationChannel.Targets.ROTATION,
                            new Keyframe(0.0f, KeyframeAnimations.degreeVec(0, 0, 0),  AnimationChannel.Interpolations.LINEAR),
                            new Keyframe(0.5f, KeyframeAnimations.degreeVec(0, 0, -8), AnimationChannel.Interpolations.LINEAR),
                            new Keyframe(1.0f, KeyframeAnimations.degreeVec(0, 0, -8), AnimationChannel.Interpolations.LINEAR),
                            new Keyframe(SIGH_LEN, KeyframeAnimations.degreeVec(0, 0, 0), AnimationChannel.Interpolations.LINEAR)
                    ))
            .addAnimation("left_arm",
                    new AnimationChannel(AnimationChannel.Targets.ROTATION,
                            new Keyframe(0.0f, KeyframeAnimations.degreeVec(0, 0, 0), AnimationChannel.Interpolations.LINEAR),
                            new Keyframe(0.5f, KeyframeAnimations.degreeVec(0, 0, 8), AnimationChannel.Interpolations.LINEAR),
                            new Keyframe(1.0f, KeyframeAnimations.degreeVec(0, 0, 8), AnimationChannel.Interpolations.LINEAR),
                            new Keyframe(SIGH_LEN, KeyframeAnimations.degreeVec(0, 0, 0), AnimationChannel.Interpolations.LINEAR)
                    ))
            .build();

    // ── HELD POSES (single-shot to hold, persist while state stays active) ──

    /** Body bends forward at body bone, head down. Held — animation runs once
     *  then naturally clamps at the end values. */
    public static final AnimationDefinition SLOUCH = AnimationDefinition.Builder.withLength(SLOUCH_LEN)
            .addAnimation("body",
                    new AnimationChannel(AnimationChannel.Targets.ROTATION,
                            new Keyframe(0.0f, KeyframeAnimations.degreeVec(0, 0, 0),  AnimationChannel.Interpolations.LINEAR),
                            new Keyframe(SLOUCH_LEN, KeyframeAnimations.degreeVec(15, 0, 0), AnimationChannel.Interpolations.LINEAR)
                    ))
            .addAnimation("head",
                    new AnimationChannel(AnimationChannel.Targets.ROTATION,
                            new Keyframe(0.0f, KeyframeAnimations.degreeVec(0, 0, 0),  AnimationChannel.Interpolations.LINEAR),
                            new Keyframe(SLOUCH_LEN, KeyframeAnimations.degreeVec(10, 0, 0), AnimationChannel.Interpolations.LINEAR)
                    ))
            .build();

    /** Body tilts ~15° to the right, left hand rests at hip. Held. */
    public static final AnimationDefinition LEAN = AnimationDefinition.Builder.withLength(LEAN_LEN)
            .addAnimation("body",
                    new AnimationChannel(AnimationChannel.Targets.ROTATION,
                            new Keyframe(0.0f, KeyframeAnimations.degreeVec(0, 0, 0),  AnimationChannel.Interpolations.LINEAR),
                            new Keyframe(LEAN_LEN, KeyframeAnimations.degreeVec(0, 0, 15), AnimationChannel.Interpolations.LINEAR)
                    ))
            .addAnimation("left_arm",
                    new AnimationChannel(AnimationChannel.Targets.ROTATION,
                            new Keyframe(0.0f, KeyframeAnimations.degreeVec(0, 0, 0),    AnimationChannel.Interpolations.LINEAR),
                            new Keyframe(LEAN_LEN, KeyframeAnimations.degreeVec(0, 0, 40), AnimationChannel.Interpolations.LINEAR)
                    ))
            .build();

    // ── TRANSITIONS (single-shot, quick) ────────────────────────────────────

    /** Body lowers, legs lift forward. Final pose dovetails into the
     *  sitting conditional applied by setupAnim. */
    public static final AnimationDefinition SIT_DOWN = AnimationDefinition.Builder.withLength(SIT_DOWN_LEN)
            .addAnimation("left_leg",
                    new AnimationChannel(AnimationChannel.Targets.ROTATION,
                            new Keyframe(0.0f, KeyframeAnimations.degreeVec(0, 0, 0),    AnimationChannel.Interpolations.LINEAR),
                            new Keyframe(SIT_DOWN_LEN, KeyframeAnimations.degreeVec(-90, 0, 0), AnimationChannel.Interpolations.LINEAR)
                    ))
            .addAnimation("right_leg",
                    new AnimationChannel(AnimationChannel.Targets.ROTATION,
                            new Keyframe(0.0f, KeyframeAnimations.degreeVec(0, 0, 0),    AnimationChannel.Interpolations.LINEAR),
                            new Keyframe(SIT_DOWN_LEN, KeyframeAnimations.degreeVec(-90, 0, 0), AnimationChannel.Interpolations.LINEAR)
                    ))
            .build();

    /** Reverse of SIT_DOWN — legs return to standing. */
    public static final AnimationDefinition STAND_UP = AnimationDefinition.Builder.withLength(STAND_UP_LEN)
            .addAnimation("left_leg",
                    new AnimationChannel(AnimationChannel.Targets.ROTATION,
                            new Keyframe(0.0f, KeyframeAnimations.degreeVec(-90, 0, 0), AnimationChannel.Interpolations.LINEAR),
                            new Keyframe(STAND_UP_LEN, KeyframeAnimations.degreeVec(0, 0, 0), AnimationChannel.Interpolations.LINEAR)
                    ))
            .addAnimation("right_leg",
                    new AnimationChannel(AnimationChannel.Targets.ROTATION,
                            new Keyframe(0.0f, KeyframeAnimations.degreeVec(-90, 0, 0), AnimationChannel.Interpolations.LINEAR),
                            new Keyframe(STAND_UP_LEN, KeyframeAnimations.degreeVec(0, 0, 0), AnimationChannel.Interpolations.LINEAR)
                    ))
            .build();
}
