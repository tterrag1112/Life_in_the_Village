package tterrag1112.life_in_the_village.Npc.Skills;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import tterrag1112.life_in_the_village.Profession.Profession;

import java.util.Arrays;
import java.util.List;

/**
 * Eight-slot skill component. Stores cumulative XP per skill and the
 * tick at which XP was last added; level is always derived from XP via
 * a log-linear interpolation through spec anchor points.
 *
 * <p>See {@code docs/npc_redesign/05-skill-system.md} for the XP source
 * table, level thresholds, and decay rules. Phase 0 ships storage and
 * level derivation only — no automatic XP grants, no decay (deferred
 * to Phase 1+).</p>
 *
 * <h3>Persistence layout</h3>
 * <p>Stored as a single {@code npcSkill} subtree containing two parallel
 * 8-element arrays: {@code xp} (cumulative XP per skill) and
 * {@code lastXpTick}. Cumulative XP is the source of truth — level and
 * tier are derived. The spec's NBT example uses per-skill named fields
 * plus an {@code xpProgress[]} for sub-level position, which is
 * functionally redundant; flagged in {@code 05-skill-system.md} Revision
 * Notes.</p>
 */
public final class SkillComponent {

    public static final int MIN_LEVEL = 0;
    public static final int MAX_LEVEL = 100;

    private static final String SAVE_KEY = "npcSkill";

    /**
     * Spec anchor points: cumulative XP required to reach each level.
     * Intermediate levels interpolated log-linearly (see
     * {@link #buildLevelXpTable}).
     */
    private static final int[][] ANCHORS = {
            {  0,      0 },
            {  1,     50 },
            {  5,    300 },
            { 10,    900 },
            { 20,   2500 },
            { 40,   7500 },
            { 60,  15000 },
            { 80,  26000 },
            {100,  40000 }
    };

    /** Cumulative XP required for each level 0..100, derived once at class load. */
    private static final float[] LEVEL_XP = buildLevelXpTable();

    private static float[] buildLevelXpTable() {
        float[] table = new float[MAX_LEVEL + 1];
        table[0] = 0f;
        for (int seg = 0; seg + 1 < ANCHORS.length; seg++) {
            int la = ANCHORS[seg][0];
            int lb = ANCHORS[seg + 1][0];
            float xa = ANCHORS[seg][1];
            float xb = ANCHORS[seg + 1][1];
            for (int L = la; L <= lb; L++) {
                if (L == 0) { table[0] = 0f; continue; }
                if (la == 0) {
                    // Linear interp on the (0,0)→(1,50) segment to avoid log(0).
                    float t = (float) L / (float) lb;
                    table[L] = xa + t * (xb - xa);
                } else {
                    double t = (double) (L - la) / (double) (lb - la);
                    double interp = Math.exp(Math.log(xa) + t * (Math.log(xb) - Math.log(xa)));
                    table[L] = (float) interp;
                }
            }
        }
        return table;
    }

    /** Cumulative XP needed to be exactly level {@code level}. */
    public static float xpRequiredFor(int level) {
        return LEVEL_XP[clampLevel(level)];
    }

    /** Highest level whose XP threshold is at or below the supplied total. */
    public static int levelFromXp(float totalXp) {
        if (totalXp <= 0f) return 0;
        if (totalXp >= LEVEL_XP[MAX_LEVEL]) return MAX_LEVEL;
        int lo = 0, hi = MAX_LEVEL;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (LEVEL_XP[mid] <= totalXp) lo = mid;
            else hi = mid - 1;
        }
        return lo;
    }

    /** Spec tier names: 0–30 amateur, 30–60 journeyman, 60–85 expert, 85–100 master. */
    public static String tierFor(int level) {
        if (level < 30) return "Amateur";
        if (level < 60) return "Journeyman";
        if (level < 85) return "Expert";
        return "Master";
    }

    private static int clampLevel(int level) {
        return Math.max(MIN_LEVEL, Math.min(MAX_LEVEL, level));
    }

    // ── Per-NPC state ───────────────────────────────────────────────────────

    private final float[] xp = new float[Skill.values().length];
    private final long[] lastXpTick = new long[Skill.values().length];
    /**
     * Active skill-rate buffs (Phase 2 task 18). Each entry adds a
     * multiplicative factor to {@link #addXp} for the buff's skill
     * until {@link tterrag1112.life_in_the_village.Npc.Letters.ActiveSkillBuff#expiresTick}.
     * Expired entries are pruned lazily on the next {@code addXp}
     * call for the matching skill.
     */
    private final java.util.List<tterrag1112.life_in_the_village.Npc.Letters.ActiveSkillBuff>
            activeBuffs = new java.util.ArrayList<>();

    public SkillComponent() {}

    public float getXp(Skill skill) { return xp[skill.ordinal()]; }
    public int getLevel(Skill skill) { return levelFromXp(xp[skill.ordinal()]); }
    public long getLastXpTick(Skill skill) { return lastXpTick[skill.ordinal()]; }

    public java.util.List<tterrag1112.life_in_the_village.Npc.Letters.ActiveSkillBuff> getActiveBuffs() {
        return java.util.Collections.unmodifiableList(activeBuffs);
    }

    /** Cumulative XP, clamped to {@code [0, xpRequiredFor(MAX_LEVEL)]}. */
    public void setXp(Skill skill, float newXp, long currentTick) {
        xp[skill.ordinal()] = Math.max(0f, Math.min(LEVEL_XP[MAX_LEVEL], newXp));
        lastXpTick[skill.ordinal()] = currentTick;
    }

    /** Snaps the skill to the XP threshold for {@code level}. Test-only. */
    public void setLevel(Skill skill, int level, long currentTick) {
        setXp(skill, LEVEL_XP[clampLevel(level)], currentTick);
    }

    public void addXp(Skill skill, float amount, long currentTick) {
        if (amount == 0f) return;
        float multiplier = pruneAndComputeMultiplier(skill, currentTick);
        float effective = amount * multiplier;
        setXp(skill, xp[skill.ordinal()] + effective, currentTick);
        // Phase 6.3.2.b — propagate upward at the child's propRate. Bounded
        // depth (3 tiers); the post-buff amount cascades so per-skill buffs
        // flow through naturally without re-application at each level.
        Skill parent = skill.parent();
        if (parent != null) {
            float propagated = effective * (float) skill.propRate();
            if (propagated > 0f) addXp(parent, propagated, currentTick);
        }
    }

    /** Stacks an additional buff. Returns the buff actually stored. */
    public tterrag1112.life_in_the_village.Npc.Letters.ActiveSkillBuff addBuff(
            tterrag1112.life_in_the_village.Npc.Letters.ActiveSkillBuff buff) {
        if (buff == null) return null;
        activeBuffs.add(buff);
        return buff;
    }

    /**
     * Returns the product of every non-expired buff's multiplier for
     * {@code skill}. Side-effect: removes expired buffs.
     */
    private float pruneAndComputeMultiplier(Skill skill, long currentTick) {
        if (activeBuffs.isEmpty()) return 1f;
        float product = 1f;
        var it = activeBuffs.iterator();
        while (it.hasNext()) {
            var b = it.next();
            if (b.expired(currentTick)) { it.remove(); continue; }
            if (b.skill() == skill) product *= b.rateMultiplier();
        }
        return product;
    }

    /** Returns the highest-level skill (ties broken by enum order). */
    public Skill primary() {
        Skill best = Skill.values()[0];
        float bestXp = xp[0];
        for (Skill s : Skill.values()) {
            if (xp[s.ordinal()] > bestXp) {
                best = s;
                bestXp = xp[s.ordinal()];
            }
        }
        return best;
    }

    /**
     * Spawn-time initialization per spec: primary skill in level
     * {@code [15, 35]}, secondary in {@code [5, 20]}, others in
     * {@code [0, 10]}. Clears any existing XP first.
     */
    public void initializeFromProfession(Profession profession,
                                         RandomSource random,
                                         long currentTick) {
        Arrays.fill(xp, 0f);
        Arrays.fill(lastXpTick, currentTick);

        var ps = ProfessionSkills.of(profession).orElse(null);
        for (Skill s : Skill.values()) {
            int lvl;
            if (ps != null && s == ps.primary()) {
                lvl = 15 + random.nextInt(21); // [15, 35]
            } else if (ps != null && s == ps.secondary()) {
                lvl = 5 + random.nextInt(16);  // [5, 20]
            } else {
                lvl = random.nextInt(11);      // [0, 10]
            }
            xp[s.ordinal()] = LEVEL_XP[lvl];
        }
    }

    // ── Persistence ─────────────────────────────────────────────────────────

    public void save(ValueOutput output) {
        output.store(SAVE_KEY, CODEC, this);
    }

    /** Returns {@code true} when stored data was found and loaded. */
    public boolean load(ValueInput input) {
        var read = input.read(SAVE_KEY, CODEC);
        if (read.isEmpty()) return false;
        SkillComponent loaded = read.get();
        System.arraycopy(loaded.xp, 0, this.xp, 0, this.xp.length);
        System.arraycopy(loaded.lastXpTick, 0, this.lastXpTick, 0, this.lastXpTick.length);
        this.activeBuffs.clear();
        this.activeBuffs.addAll(loaded.activeBuffs);
        return true;
    }

    // ── Codec ──────────────────────────────────────────────────────────────

    public static final Codec<SkillComponent> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.FLOAT.listOf().optionalFieldOf("xp", List.of())
                    .forGetter(c -> floatArrayToList(c.xp)),
            Codec.LONG.listOf().optionalFieldOf("lastXpTick", List.of())
                    .forGetter(c -> longArrayToList(c.lastXpTick)),
            tterrag1112.life_in_the_village.Npc.Letters.ActiveSkillBuff.CODEC.listOf()
                    .optionalFieldOf("buffs", List.of())
                    .forGetter(c -> List.copyOf(c.activeBuffs))
    ).apply(i, SkillComponent::fromCodec));

    public static SkillComponent fromCodec(List<Float> xpList, List<Long> tickList,
                                           List<tterrag1112.life_in_the_village.Npc.Letters.ActiveSkillBuff> buffs) {
        SkillComponent c = new SkillComponent();
        for (int i = 0; i < c.xp.length && i < xpList.size(); i++) {
            c.xp[i] = Math.max(0f, Math.min(LEVEL_XP[MAX_LEVEL], xpList.get(i)));
        }
        for (int i = 0; i < c.lastXpTick.length && i < tickList.size(); i++) {
            c.lastXpTick[i] = tickList.get(i);
        }
        if (buffs != null) c.activeBuffs.addAll(buffs);
        return c;
    }

    private static List<Float> floatArrayToList(float[] a) {
        List<Float> out = new java.util.ArrayList<>(a.length);
        for (float f : a) out.add(f);
        return out;
    }

    private static List<Long> longArrayToList(long[] a) {
        List<Long> out = new java.util.ArrayList<>(a.length);
        for (long l : a) out.add(l);
        return out;
    }
}
