package tterrag1112.life_in_the_village.Npc.Religion;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.block.BonemealableBlock;
import net.minecraft.world.level.block.state.BlockState;
import tterrag1112.life_in_the_village.Npc.Religion.ReligionIdentity.DeityDomain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Divine Layer V2 — the per-deity <b>miracle registry</b>. Mirrors
 * {@link ReligionContent} / {@code MonasticCrafts}: a flat authored list +
 * by-id / by-religion lookups, no parallel framework. Each faith's set is
 * <b>domain-flavoured</b> (D1 — its deity's domain + rewards) and runs from a
 * grounded, low-favour boon to a fantastical, PIOUS-gated one; effects scale with
 * the caster's favour. Reuses vanilla {@link MobEffects} + environment hooks — no
 * custom effect framework.
 *
 * <p>Starting sets, to refine. Tuning lives here (costs / tiers / durations).</p>
 */
public final class Miracles {

    private Miracles() {}

    // Effect durations (ticks) — short grounded boons; the high miracles last longer.
    private static final int LOW_DUR  = 600;    // 30s
    private static final int MID_DUR  = 2400;   // 2m
    private static final int HIGH_DUR = 6000;   // 5m

    private static final List<Miracle> MIRACLES = build();
    private static final Map<String, Miracle> BY_ID = index();

    public static List<Miracle> all() { return MIRACLES; }

    public static Miracle byId(String id) { return id == null ? null : BY_ID.get(id); }

    /** The faith's miracles, low-tier first (authoring order). */
    public static List<Miracle> forReligion(String religionId) {
        List<Miracle> out = new ArrayList<>();
        for (Miracle m : MIRACLES) if (m.religionId().equals(religionId)) out.add(m);
        return out;
    }

    private static Map<String, Miracle> index() {
        Map<String, Miracle> m = new LinkedHashMap<>();
        for (Miracle x : MIRACLES) m.put(x.id(), x);
        return m;
    }

    // ── Favour-scaling helper: 0 at low favour, 1 mid, 2 high ─────────────────
    private static int magnitude(float favour) {
        if (favour >= 80f) return 2;
        if (favour >= 45f) return 1;
        return 0;
    }

    private static MobEffectInstance fx(net.minecraft.core.Holder<net.minecraft.world.effect.MobEffect> e,
                                        int duration, int amplifier) {
        return new MobEffectInstance(e, duration, Math.max(0, amplifier));
    }

    // ── Authored sets ─────────────────────────────────────────────────────────

    private static List<Miracle> build() {
        List<Miracle> m = new ArrayList<>();

        // ── Sun-Mother (SUN) — ripened fields, a warm hearth, rebirth ─────────
        m.add(new Miracle("sun_healing_light", ReligionRegistry.SUNSTEAD,
                "Healing Light", DeityDomain.SUN, 8f, PietyTier.FAITHFUL, 8f, 600,
                "The Sun-Mother's warmth knits flesh whole.",
                (level, p, fav) -> {
                    p.heal(6f + 4f * magnitude(fav));
                    p.addEffect(fx(MobEffects.REGENERATION, LOW_DUR, magnitude(fav)));
                }));
        m.add(new Miracle("sun_warmth", ReligionRegistry.SUNSTEAD,
                "Warmth", DeityDomain.SUN, 20f, PietyTier.DEVOUT, 25f, 1200,
                "Her hearth-light wards off cold and dark.",
                (level, p, fav) -> {
                    p.addEffect(fx(MobEffects.FIRE_RESISTANCE, MID_DUR, 0));
                    p.addEffect(fx(MobEffects.NIGHT_VISION, MID_DUR, 0));
                    p.setRemainingFireTicks(0);
                    p.addEffect(fx(MobEffects.REGENERATION, MID_DUR, magnitude(fav)));
                }));
        m.add(new Miracle("sun_bountiful_harvest", ReligionRegistry.SUNSTEAD,
                "Bountiful Harvest", DeityDomain.SUN, 50f, PietyTier.PIOUS, 70f, 6000,
                "The furrow runs gold — the fields leap to ripeness.",
                (level, p, fav) -> growCropsAround(level, p.blockPosition(), 4 + magnitude(fav) * 2)));

        // ── Sea-Mother (SEA) — full nets, fair passage ────────────────────────
        m.add(new Miracle("sea_the_catch", ReligionRegistry.TIDECALL,
                "The Catch", DeityDomain.SEA, 8f, PietyTier.FAITHFUL, 8f, 600,
                "The deep yields its fortune to the humble.",
                (level, p, fav) -> p.addEffect(fx(MobEffects.LUCK, MID_DUR, magnitude(fav)))));
        m.add(new Miracle("sea_tides_grace", ReligionRegistry.TIDECALL,
                "Tide's Grace", DeityDomain.SEA, 20f, PietyTier.DEVOUT, 25f, 1200,
                "She grants the water's ease to her guests.",
                (level, p, fav) -> {
                    p.addEffect(fx(MobEffects.WATER_BREATHING, HIGH_DUR, 0));
                    p.addEffect(fx(MobEffects.DOLPHINS_GRACE, MID_DUR, magnitude(fav)));
                    p.addEffect(fx(MobEffects.CONDUIT_POWER, MID_DUR, 0));
                }));
        m.add(new Miracle("sea_calm_the_waters", ReligionRegistry.TIDECALL,
                "Calm the Waters", DeityDomain.SEA, 50f, PietyTier.PIOUS, 70f, 6000,
                "The Sea-Mother stills the storm at her people's plea.",
                (level, p, fav) -> {
                    level.setWeatherParameters(HIGH_DUR + magnitude(fav) * HIGH_DUR, 0, false, false);
                    p.addEffect(fx(MobEffects.RESISTANCE, MID_DUR, magnitude(fav)));
                }));

        // ── Forge-Father (FORGE) — a name remembered as iron, courage ─────────
        m.add(new Miracle("forge_ancestral_might", ReligionRegistry.FORGE_CREED,
                "Ancestral Might", DeityDomain.FORGE, 8f, PietyTier.FAITHFUL, 8f, 600,
                "The strength of the line that held before you.",
                (level, p, fav) -> p.addEffect(fx(MobEffects.STRENGTH, MID_DUR, magnitude(fav)))));
        m.add(new Miracle("forge_ward", ReligionRegistry.FORGE_CREED,
                "Forge-Ward", DeityDomain.FORGE, 20f, PietyTier.DEVOUT, 25f, 1200,
                "Iron at your back; the ancestors stand the watch.",
                (level, p, fav) -> {
                    p.addEffect(fx(MobEffects.RESISTANCE, MID_DUR, magnitude(fav)));
                    p.addEffect(fx(MobEffects.ABSORPTION, MID_DUR, 1 + magnitude(fav)));
                }));
        m.add(new Miracle("forge_unbreaking_resolve", ReligionRegistry.FORGE_CREED,
                "Unbreaking Resolve", DeityDomain.FORGE, 50f, PietyTier.PIOUS, 70f, 6000,
                "Meet the breach standing — iron that does not break.",
                (level, p, fav) -> {
                    p.addEffect(fx(MobEffects.RESISTANCE, HIGH_DUR, 1 + magnitude(fav)));
                    p.addEffect(fx(MobEffects.ABSORPTION, HIGH_DUR, 2 + magnitude(fav)));
                    p.addEffect(fx(MobEffects.FIRE_RESISTANCE, HIGH_DUR, 0));
                    p.addEffect(fx(MobEffects.REGENERATION, MID_DUR, magnitude(fav)));
                }));

        // ── The Loom (FATE) — a life that lies true in the weave ──────────────
        m.add(new Miracle("loom_fortunes_thread", ReligionRegistry.THE_LOOM,
                "Fortune's Thread", DeityDomain.FATE, 8f, PietyTier.FAITHFUL, 8f, 600,
                "The Pattern leans, a little, your way.",
                (level, p, fav) -> p.addEffect(fx(MobEffects.LUCK, MID_DUR, 1 + magnitude(fav)))));
        m.add(new Miracle("loom_foresight", ReligionRegistry.THE_LOOM,
                "Foresight", DeityDomain.FATE, 20f, PietyTier.DEVOUT, 25f, 1200,
                "You read a few threads further down the weave.",
                (level, p, fav) -> {
                    p.addEffect(fx(MobEffects.NIGHT_VISION, MID_DUR, 0));
                    p.addEffect(fx(MobEffects.LUCK, MID_DUR, magnitude(fav)));
                    revealNearbyMobs(level, p, 16 + magnitude(fav) * 8);
                }));
        m.add(new Miracle("loom_reweave", ReligionRegistry.THE_LOOM,
                "Reweave", DeityDomain.FATE, 50f, PietyTier.PIOUS, 70f, 6000,
                "A knotted thread pulled straight — the weave made whole.",
                (level, p, fav) -> {
                    clearHarmfulEffects(p);
                    p.heal(p.getMaxHealth());
                    p.addEffect(fx(MobEffects.REGENERATION, MID_DUR, 1 + magnitude(fav)));
                    p.addEffect(fx(MobEffects.RESISTANCE, MID_DUR, magnitude(fav)));
                    p.addEffect(fx(MobEffects.ABSORPTION, HIGH_DUR, 1 + magnitude(fav)));
                }));

        return m;
    }

    // ── Environment effect helpers (reused vanilla mechanics) ─────────────────

    /** Bonemeal-style growth of every growable plant in a cube around {@code centre}. */
    private static void growCropsAround(ServerLevel level, BlockPos centre, int radius) {
        var rng = level.getRandom();
        for (BlockPos pos : BlockPos.betweenClosed(
                centre.offset(-radius, -2, -radius), centre.offset(radius, 2, radius))) {
            BlockState state = level.getBlockState(pos);
            if (!(state.getBlock() instanceof BonemealableBlock bonemealable)) continue;
            if (!bonemealable.isValidBonemealTarget(level, pos, state)) continue;
            if (bonemealable.isBonemealSuccess(level, rng, pos, state)) {
                bonemealable.performBonemeal(level, rng, pos, state);
            }
        }
    }

    /** Foresight — briefly outline nearby mobs (a reveal). */
    private static void revealNearbyMobs(ServerLevel level, ServerPlayer player, int radius) {
        var box = player.getBoundingBox().inflate(radius);
        for (var mob : level.getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class, box,
                e -> e != player && e.isAlive())) {
            mob.addEffect(fx(MobEffects.GLOWING, 200, 0));
        }
    }

    /** Reweave — strip every harmful effect (concurrent-safe copy). */
    private static void clearHarmfulEffects(ServerPlayer player) {
        List<net.minecraft.core.Holder<net.minecraft.world.effect.MobEffect>> harmful = new ArrayList<>();
        for (MobEffectInstance inst : player.getActiveEffects()) {
            if (inst.getEffect().value().getCategory() == MobEffectCategory.HARMFUL) {
                harmful.add(inst.getEffect());
            }
        }
        for (var e : harmful) player.removeEffect(e);
    }
}
