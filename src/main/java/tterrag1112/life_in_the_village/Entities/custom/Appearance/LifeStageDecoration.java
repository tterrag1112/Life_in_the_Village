package tterrag1112.life_in_the_village.Entities.custom.Appearance;

/**
 * Per-life-stage posture / proportion adjustments rendered on top
 * of the existing scale-by-life-stage code path. Phase 5 doc 33 §
 * Life-stage treatment.
 *
 * <ul>
 *   <li>{@code postureOffset} — small forward-lean translation for
 *       elderly NPCs (radians or fraction-of-block; renderer maps).</li>
 *   <li>{@code limbProportion} — multiplier on limb length for
 *       child / elderly variation (1.0 = adult).</li>
 *   <li>{@code usesCane} — rare elderly + frailty visual.</li>
 * </ul>
 *
 * <p>v1 ships the data; the renderer reads it once Layer 2 lands.</p>
 */
public record LifeStageDecoration(
        float postureOffset,
        float limbProportion,
        boolean usesCane
) {
    public static final LifeStageDecoration NEUTRAL = new LifeStageDecoration(0f, 1.0f, false);

    public static LifeStageDecoration forStage(String lifeStage) {
        if (lifeStage == null) return NEUTRAL;
        return switch (lifeStage.toUpperCase(java.util.Locale.ROOT)) {
            case "CHILD"   -> new LifeStageDecoration(0f, 0.85f, false);
            case "TEEN"    -> new LifeStageDecoration(0f, 0.95f, false);
            case "ELDERLY" -> new LifeStageDecoration(0.10f, 0.95f, false);
            default        -> NEUTRAL;
        };
    }
}
