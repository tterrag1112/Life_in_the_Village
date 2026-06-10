package tterrag1112.life_in_the_village.Quests;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * F2a-1 — the pluggable completion unit and <b>the extension point of the quest base</b>.
 * Each objective is a small immutable record carrying its own {@code current/target}
 * progress and a {@link #matches} predicate over a {@link QuestContext}; advancing
 * returns a NEW objective ({@link #advanced}). Adding a kind (F2a-2's three, later grand
 * stages) is a new {@code permits} entry + a {@code MAP_CODEC} arm — <b>no engine
 * change</b>; {@link QuestEvents#notify} is the single completion path.
 *
 * <p>F2a-1 ships exactly one kind: {@link MakeOffering} (make N offerings to a god).</p>
 */
public sealed interface Objective permits Objective.MakeOffering {

    /** Stable type tag for the dispatch codec. */
    String type();

    /** Does {@code ctx} (a {@link QuestEvents#notify} event) advance this objective? */
    boolean matches(QuestContext ctx);

    /** This objective with one unit of progress applied (clamped to target). */
    Objective advanced();

    boolean isComplete();

    /** A short player-facing line for the readout. */
    String describe();

    /** Dispatch codec over the sealed kinds (one arm in F2a-1; add arms per kind). */
    Codec<Objective> CODEC = Codec.STRING.dispatch("type", Objective::type, t -> switch (t) {
        case MakeOffering.TYPE -> MakeOffering.MAP_CODEC;
        default -> throw new IllegalStateException("Unknown objective type: " + t);
    });

    // ── Kinds ────────────────────────────────────────────────────────────────

    /** Make {@code target} offerings to {@code godId}'s faith. Advances on an
     *  {@link QuestEventKind#OFFERING} event whose resolved god matches. */
    record MakeOffering(String godId, int current, int target) implements Objective {
        public static final String TYPE = "make_offering";
        public static final MapCodec<MakeOffering> MAP_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                Codec.STRING.fieldOf("godId").forGetter(MakeOffering::godId),
                Codec.INT.optionalFieldOf("current", 0).forGetter(MakeOffering::current),
                Codec.INT.fieldOf("target").forGetter(MakeOffering::target)
        ).apply(i, MakeOffering::new));

        public String type() { return TYPE; }

        public boolean matches(QuestContext ctx) {
            return ctx.kind() == QuestEventKind.OFFERING
                    && godId != null && godId.equals(ctx.godId());
        }

        public Objective advanced() {
            return new MakeOffering(godId, Math.min(target, current + 1), target);
        }

        public boolean isComplete() { return current >= target; }

        public String describe() {
            return "Make offerings to " + godId + " (" + Math.min(current, target) + "/" + target + ")";
        }
    }
}
