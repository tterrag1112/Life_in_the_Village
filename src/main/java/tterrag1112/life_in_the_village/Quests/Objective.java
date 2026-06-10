package tterrag1112.life_in_the_village.Quests;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;

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
public sealed interface Objective
        permits Objective.MakeOffering, Objective.VisitSacredSite,
                Objective.EnshrineRelic, Objective.PerformRites,
                Objective.Hunt, Objective.Escort,
                Objective.Gather, Objective.Deliver, Objective.Explore {

    /** Stable type tag for the dispatch codec. */
    String type();

    /** Does {@code ctx} (a {@link QuestEvents#notify} event) advance this objective?
     *  (EVENT-mode only; POLL kinds return false here and are checked by {@link
     *  #isSatisfied}.) */
    boolean matches(QuestContext ctx);

    /** This objective with one unit of progress applied (clamped to target). */
    Objective advanced();

    boolean isComplete();

    /** A short player-facing line for the readout. */
    String describe();

    /** F2b-1 — EVENT (advanced by {@link QuestEvents#notify}) or POLL (checked on demand
     *  by {@link QuestEvents#evaluate}, e.g. at turn-in). EVENT by default. */
    default EvalMode mode() { return EvalMode.EVENT; }

    /** F2b-1 — is this objective satisfied right now? EVENT objectives are satisfied when
     *  complete; POLL objectives override with a live check (inventory / location / biome). */
    default boolean isSatisfied(ServerLevel level, ServerPlayer player) { return isComplete(); }

    /** Dispatch codec over the sealed kinds — one inline {@code MAP_CODEC} arm per kind. */
    Codec<Objective> CODEC = Codec.STRING.dispatch("type", Objective::type, t -> switch (t) {
        case MakeOffering.TYPE    -> MakeOffering.MAP_CODEC;
        case VisitSacredSite.TYPE -> VisitSacredSite.MAP_CODEC;
        case EnshrineRelic.TYPE   -> EnshrineRelic.MAP_CODEC;
        case PerformRites.TYPE    -> PerformRites.MAP_CODEC;
        case Hunt.TYPE            -> Hunt.MAP_CODEC;
        case Escort.TYPE          -> Escort.MAP_CODEC;
        case Gather.TYPE          -> Gather.MAP_CODEC;
        case Deliver.TYPE         -> Deliver.MAP_CODEC;
        case Explore.TYPE         -> Explore.MAP_CODEC;
        default -> throw new IllegalStateException("Unknown objective type: " + t);
    });

    // ── Poll-check helpers (shared by the POLL kinds) ────────────────────────

    /** Count of {@code itemId} (a registry id) in the player's inventory. */
    private static int playerItemCount(ServerPlayer player, String itemId) {
        ResourceLocation id = ResourceLocation.tryParse(itemId);
        Item item = id == null ? null : BuiltInRegistries.ITEM.getOptional(id).orElse(null);
        if (item == null) return 0;
        int n = 0;
        var inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty() && s.is(item)) n += s.getCount();
        }
        return n;
    }

    /** True when the player currently stands in the village with id {@code villageId}. */
    private static boolean inVillage(ServerLevel level, ServerPlayer player, String villageId) {
        return VillageSavedData.get(level).getVillageAt(player.blockPosition())
                .map(v -> v.getId().toString().equals(villageId)).orElse(false);
    }

    /** The registry id of the biome the player currently stands in. */
    private static String currentBiomeId(ServerLevel level, ServerPlayer player) {
        return level.getBiome(player.blockPosition()).unwrapKey()
                .map(k -> k.location().toString()).orElse("");
    }

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

    /** F2a-2 — pilgrimage: pray at {@code godId}'s sacred site (a saint grave). Advances
     *  on a {@link QuestEventKind#VISIT_SITE} event for the god. */
    record VisitSacredSite(String godId, int current, int target) implements Objective {
        public static final String TYPE = "visit_sacred_site";
        public static final MapCodec<VisitSacredSite> MAP_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                Codec.STRING.fieldOf("godId").forGetter(VisitSacredSite::godId),
                Codec.INT.optionalFieldOf("current", 0).forGetter(VisitSacredSite::current),
                Codec.INT.optionalFieldOf("target", 1).forGetter(VisitSacredSite::target)
        ).apply(i, VisitSacredSite::new));

        public String type() { return TYPE; }
        public boolean matches(QuestContext ctx) {
            return ctx.kind() == QuestEventKind.VISIT_SITE && godId != null && godId.equals(ctx.godId());
        }
        public Objective advanced() { return new VisitSacredSite(godId, Math.min(target, current + 1), target); }
        public boolean isComplete() { return current >= target; }
        public String describe() {
            return "Pilgrimage to " + godId + "'s sacred ground (" + Math.min(current, target) + "/" + target + ")";
        }
    }

    /** F2a-2 — enshrine {@code target} relic(s) of {@code godId}'s faith. Advances on an
     *  {@link QuestEventKind#ENSHRINE} event for the god. */
    record EnshrineRelic(String godId, int current, int target) implements Objective {
        public static final String TYPE = "enshrine_relic";
        public static final MapCodec<EnshrineRelic> MAP_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                Codec.STRING.fieldOf("godId").forGetter(EnshrineRelic::godId),
                Codec.INT.optionalFieldOf("current", 0).forGetter(EnshrineRelic::current),
                Codec.INT.optionalFieldOf("target", 1).forGetter(EnshrineRelic::target)
        ).apply(i, EnshrineRelic::new));

        public String type() { return TYPE; }
        public boolean matches(QuestContext ctx) {
            return ctx.kind() == QuestEventKind.ENSHRINE && godId != null && godId.equals(ctx.godId());
        }
        public Objective advanced() { return new EnshrineRelic(godId, Math.min(target, current + 1), target); }
        public boolean isComplete() { return current >= target; }
        public String describe() {
            return "Enshrine a relic of " + godId + " (" + Math.min(current, target) + "/" + target + ")";
        }
    }

    /** F2a-2 — perform (attend/commission) {@code target} rites of {@code godId}'s faith.
     *  Advances on a {@link QuestEventKind#RITE} event for the god. */
    record PerformRites(String godId, int current, int target) implements Objective {
        public static final String TYPE = "perform_rites";
        public static final MapCodec<PerformRites> MAP_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                Codec.STRING.fieldOf("godId").forGetter(PerformRites::godId),
                Codec.INT.optionalFieldOf("current", 0).forGetter(PerformRites::current),
                Codec.INT.optionalFieldOf("target", 1).forGetter(PerformRites::target)
        ).apply(i, PerformRites::new));

        public String type() { return TYPE; }
        public boolean matches(QuestContext ctx) {
            return ctx.kind() == QuestEventKind.RITE && godId != null && godId.equals(ctx.godId());
        }
        public Objective advanced() { return new PerformRites(godId, Math.min(target, current + 1), target); }
        public boolean isComplete() { return current >= target; }
        public String describe() {
            return "Perform rites of " + godId + " (" + Math.min(current, target) + "/" + target + ")";
        }
    }

    // ── Guild kinds (F2b-1 — the guild vocabulary on the base) ───────────────

    /** F2b-1 — slay {@code target} of mob {@code mobId} (a registry id). EVENT: advanced
     *  by a {@link QuestEventKind#MOB_DEATH} notify. */
    record Hunt(String mobId, int current, int target) implements Objective {
        public static final String TYPE = "hunt";
        public static final MapCodec<Hunt> MAP_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                Codec.STRING.fieldOf("mobId").forGetter(Hunt::mobId),
                Codec.INT.optionalFieldOf("current", 0).forGetter(Hunt::current),
                Codec.INT.fieldOf("target").forGetter(Hunt::target)
        ).apply(i, Hunt::new));

        public String type() { return TYPE; }
        public boolean matches(QuestContext ctx) {
            return ctx.kind() == QuestEventKind.MOB_DEATH && mobId != null && mobId.equals(ctx.subject());
        }
        public Objective advanced() { return new Hunt(mobId, Math.min(target, current + 1), target); }
        public boolean isComplete() { return current >= target; }
        public String describe() {
            return "Hunt " + mobId + " (" + Math.min(current, target) + "/" + target + ")";
        }
    }

    /** F2b-1 — escort a target to {@code destination}. EVENT: advanced by an
     *  {@link QuestEventKind#ESCORT_ARRIVED} notify (the escort mechanic's source is
     *  F2b-2, so this is defined but not yet completable). */
    record Escort(String targetMob, String destination, boolean done) implements Objective {
        public static final String TYPE = "escort";
        public static final MapCodec<Escort> MAP_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                Codec.STRING.optionalFieldOf("targetMob", "").forGetter(Escort::targetMob),
                Codec.STRING.fieldOf("destination").forGetter(Escort::destination),
                Codec.BOOL.optionalFieldOf("done", false).forGetter(Escort::done)
        ).apply(i, Escort::new));

        public String type() { return TYPE; }
        public boolean matches(QuestContext ctx) {
            return ctx.kind() == QuestEventKind.ESCORT_ARRIVED
                    && destination != null && destination.equals(ctx.subject());
        }
        public Objective advanced() { return new Escort(targetMob, destination, true); }
        public boolean isComplete() { return done; }
        public String describe() { return "Escort to " + destination + (done ? " ✓" : ""); }
    }

    /** F2b-1 — gather {@code target} of {@code itemId}. POLL: satisfied when the player
     *  holds that many (checked at turn-in). */
    record Gather(String itemId, int target) implements Objective {
        public static final String TYPE = "gather";
        public static final MapCodec<Gather> MAP_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                Codec.STRING.fieldOf("itemId").forGetter(Gather::itemId),
                Codec.INT.fieldOf("target").forGetter(Gather::target)
        ).apply(i, Gather::new));

        public String type() { return TYPE; }
        public EvalMode mode() { return EvalMode.POLL; }
        public boolean matches(QuestContext ctx) { return false; }   // poll-only
        public Objective advanced() { return this; }                 // not event-advanced
        public boolean isComplete() { return false; }                // satisfied via poll
        public boolean isSatisfied(ServerLevel level, ServerPlayer player) {
            return playerItemCount(player, itemId) >= target;
        }
        public String describe() { return "Gather " + target + "× " + itemId; }
    }

    /** F2b-1 — deliver {@code itemId} to the village {@code villageId}. POLL: satisfied
     *  when the player stands in that village holding the item (checked at turn-in). */
    record Deliver(String itemId, String villageId) implements Objective {
        public static final String TYPE = "deliver";
        public static final MapCodec<Deliver> MAP_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                Codec.STRING.fieldOf("itemId").forGetter(Deliver::itemId),
                Codec.STRING.optionalFieldOf("villageId", "").forGetter(Deliver::villageId)
        ).apply(i, Deliver::new));

        public String type() { return TYPE; }
        public EvalMode mode() { return EvalMode.POLL; }
        public boolean matches(QuestContext ctx) { return false; }
        public Objective advanced() { return this; }
        public boolean isComplete() { return false; }
        public boolean isSatisfied(ServerLevel level, ServerPlayer player) {
            boolean atVillage = villageId == null || villageId.isEmpty() || inVillage(level, player, villageId);
            return atVillage && playerItemCount(player, itemId) >= 1;
        }
        public String describe() {
            return "Deliver " + itemId + (villageId == null || villageId.isEmpty() ? "" : " to the village");
        }
    }

    /** F2b-1 — explore (reach) the biome {@code biomeId} (a registry id). POLL: satisfied
     *  when the player currently stands in that biome. */
    record Explore(String biomeId) implements Objective {
        public static final String TYPE = "explore";
        public static final MapCodec<Explore> MAP_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                Codec.STRING.fieldOf("biomeId").forGetter(Explore::biomeId)
        ).apply(i, Explore::new));

        public String type() { return TYPE; }
        public EvalMode mode() { return EvalMode.POLL; }
        public boolean matches(QuestContext ctx) { return false; }
        public Objective advanced() { return this; }
        public boolean isComplete() { return false; }
        public boolean isSatisfied(ServerLevel level, ServerPlayer player) {
            return biomeId != null && biomeId.equals(currentBiomeId(level, player));
        }
        public String describe() { return "Explore " + biomeId; }
    }
}
