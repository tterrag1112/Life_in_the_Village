package tterrag1112.life_in_the_village.Npc.Religion;

import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import tterrag1112.life_in_the_village.Npc.Religion.DivineFavour.DispleasureTier;
import tterrag1112.life_in_the_village.Npc.Religion.ReligionIdentity.DeityDomain;

import java.util.ArrayList;
import java.util.List;

/**
 * Divine Layer V4 — the per-deity <b>curse registry</b>. The negative mirror of the
 * V2 {@code Miracles} registry: a flat authored list + a by-(faith, severity) pick,
 * reusing vanilla negative {@link MobEffects} + weather — no parallel framework.
 * Each faith carries a domain-flavoured {@code CURSE} (serious misfortune) and a
 * {@code WRATH} (severe — but deliberately <b>non-fatal</b>: debuffs + POISON, which
 * stops at half a heart, never WITHER/fire that could kill).
 *
 * <p>Sun → blight/scorch; Sea → storm/drowning; Forge → frailty/dishonour;
 * Loom → misfortune/tangled fate. Starting sets, to refine.</p>
 */
public final class Curses {

    private Curses() {}

    private static final int CURSE_DUR = 2400;   // 2m of misfortune
    private static final int WRATH_DUR = 4800;   // 4m of severe consequence

    private static final List<Curse> CURSES = build();

    /** The faith's curse for a displeasure band (CURSE / WRATH), or null. */
    public static Curse forReligion(String religionId, DispleasureTier severity) {
        for (Curse c : CURSES) {
            if (c.religionId().equals(religionId) && c.severity() == severity) return c;
        }
        return null;
    }

    public static List<Curse> all() { return CURSES; }

    private static MobEffectInstance fx(Holder<MobEffect> e, int dur, int amp) {
        return new MobEffectInstance(e, dur, Math.max(0, amp));
    }

    private static List<Curse> build() {
        List<Curse> m = new ArrayList<>();

        // ── Sun-Mother (SUN) — blight / scorch ────────────────────────────────
        m.add(new Curse(ReligionRegistry.SUNSTEAD, DeityDomain.SUN, DispleasureTier.CURSE,
                "Blight", "The Sun-Mother withholds her warmth; your strength withers.",
                (level, p) -> {
                    p.addEffect(fx(MobEffects.WEAKNESS, CURSE_DUR, 0));
                    p.addEffect(fx(MobEffects.HUNGER, CURSE_DUR, 0));
                }));
        m.add(new Curse(ReligionRegistry.SUNSTEAD, DeityDomain.SUN, DispleasureTier.WRATH,
                "Scorching", "Her light turns against you — a withering drought of the body.",
                (level, p) -> {
                    p.addEffect(fx(MobEffects.WEAKNESS, WRATH_DUR, 1));
                    p.addEffect(fx(MobEffects.HUNGER, WRATH_DUR, 1));
                    p.addEffect(fx(MobEffects.POISON, 200, 0));
                }));

        // ── Sea-Mother (SEA) — storm / drowning ───────────────────────────────
        m.add(new Curse(ReligionRegistry.TIDECALL, DeityDomain.SEA, DispleasureTier.CURSE,
                "Storm's Rebuke", "The Sea-Mother sends her storm against the heedless.",
                (level, p) -> {
                    level.setWeatherParameters(0, CURSE_DUR, true, true);
                    p.addEffect(fx(MobEffects.SLOWNESS, CURSE_DUR, 0));
                    p.addEffect(fx(MobEffects.MINING_FATIGUE, CURSE_DUR, 0));
                }));
        m.add(new Curse(ReligionRegistry.TIDECALL, DeityDomain.SEA, DispleasureTier.WRATH,
                "The Drowning Deep", "The deep reaches for you — the world reels and drags.",
                (level, p) -> {
                    level.setWeatherParameters(0, WRATH_DUR, true, true);
                    p.addEffect(fx(MobEffects.SLOWNESS, WRATH_DUR, 1));
                    p.addEffect(fx(MobEffects.WEAKNESS, WRATH_DUR, 1));
                    p.addEffect(fx(MobEffects.NAUSEA, 600, 0));
                    p.addEffect(fx(MobEffects.POISON, 200, 0));
                }));

        // ── Forge-Father (FORGE) — frailty / dishonour ────────────────────────
        m.add(new Curse(ReligionRegistry.FORGE_CREED, DeityDomain.FORGE, DispleasureTier.CURSE,
                "Frailty", "The iron at your back rusts; your arm and craft fail you.",
                (level, p) -> {
                    p.addEffect(fx(MobEffects.WEAKNESS, CURSE_DUR, 0));
                    p.addEffect(fx(MobEffects.MINING_FATIGUE, CURSE_DUR, 0));
                }));
        m.add(new Curse(ReligionRegistry.FORGE_CREED, DeityDomain.FORGE, DispleasureTier.WRATH,
                "Dishonour", "The line that held before you turns its back — you are unmade.",
                (level, p) -> {
                    p.addEffect(fx(MobEffects.WEAKNESS, WRATH_DUR, 2));
                    p.addEffect(fx(MobEffects.SLOWNESS, WRATH_DUR, 1));
                    p.addEffect(fx(MobEffects.POISON, 200, 0));
                }));

        // ── The Loom (FATE) — misfortune / tangled fate ───────────────────────
        m.add(new Curse(ReligionRegistry.THE_LOOM, DeityDomain.FATE, DispleasureTier.CURSE,
                "Misfortune", "Your thread snags — the Pattern leans against you.",
                (level, p) -> {
                    p.addEffect(fx(MobEffects.UNLUCK, CURSE_DUR, 0));
                    p.addEffect(fx(MobEffects.NAUSEA, 600, 0));
                }));
        m.add(new Curse(ReligionRegistry.THE_LOOM, DeityDomain.FATE, DispleasureTier.WRATH,
                "Tangled Fate", "Knot upon knot — the weave closes dark around you.",
                (level, p) -> {
                    p.addEffect(fx(MobEffects.UNLUCK, WRATH_DUR, 1));
                    p.addEffect(fx(MobEffects.BLINDNESS, 400, 0));
                    p.addEffect(fx(MobEffects.SLOWNESS, WRATH_DUR, 1));
                    p.addEffect(fx(MobEffects.NAUSEA, 600, 0));
                }));

        return m;
    }
}
