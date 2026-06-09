package tterrag1112.life_in_the_village.Npc.Religion;

import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LightningBolt;
import tterrag1112.life_in_the_village.Npc.Religion.ReligionIdentity.DeityDomain;

import java.util.UUID;

/**
 * Divine Layer V5 — <b>theophany</b>: the capstone. At the extremes of the favour
 * relationship the deity <i>manifests</i> — a glorious blessing made visible at
 * peak favour, its anger incarnate at the depth of wrath. Rare, dramatic, overtly
 * fantastical, utterly per-deity (D1 domain → the manifestation's form).
 *
 * <p>Reuses everything: V1/V4 favour for the extremes (peak ≈ cap / deep past
 * wrath), V3 {@link DivineVision} for the deity's voice, the V2-miracle/V4-curse
 * effect basis (amplified here), and the vanilla visual/audio toolkit
 * (particles + visual-only {@link LightningBolt} + weather + sounds) — no custom
 * rendering. Player-primary; piety read, never written.</p>
 *
 * <h3>Bounding</h3>
 * Evaluated on the existing per-player tick, gated to the extremes, and bounded by
 * a persisted per-(deity, pole) milestone with a long {@link #COOLDOWN} — a
 * theophany is a once-in-a-long-while event, never spam. A wrath theophany arms the
 * V4 curse cooldown ({@link DivineWrath#armConsequenceCooldown}) so the amplified
 * peak doesn't double-fire with a normal curse the same moment.
 */
public final class DivineTheophany {

    private DivineTheophany() {}

    /** Peak favour (≈ the PIOUS cap of 100) that summons a theophany of favour. */
    private static final float FAVOUR_PEAK = 90f;
    /** Depth of displeasure (near the −100 floor) that summons a theophany of wrath. */
    private static final float WRATH_DEPTH = -90f;
    /** A theophany of a given pole may recur only after this long (7 in-game days). */
    private static final long  COOLDOWN = 168000L;
    private static final int   CHECK_INTERVAL = 200;

    // ── Trigger (per-player tick; rare + milestone-bounded) ──────────────────

    public static void tick(ServerPlayer player) {
        if (!(player.level() instanceof ServerLevel level)) return;
        long now = level.getGameTime();
        if (now % CHECK_INTERVAL != 0) return;
        UUID pid = player.getUUID();
        String faith = primaryFaith(level, pid);
        if (faith == null) return;

        float fav = DivineFavour.current(level, pid, faith, now);
        RiteSavedData data = RiteSavedData.get(level);

        if (fav >= FAVOUR_PEAK
                && DivineFavour.tierIn(level, pid, faith) == PietyTier.PIOUS) {
            if (now - data.getTheophanyTick(pid, faith + "|favour") >= COOLDOWN) {
                fireFavour(level, player, faith, now);
            }
        } else if (fav <= WRATH_DEPTH) {
            if (now - data.getTheophanyTick(pid, faith + "|wrath") >= COOLDOWN) {
                fireWrath(level, player, faith, now);
            }
        }
    }

    // ── The two manifestations (public so the debug command can force them) ──

    /** Theophany of favour — a glorious manifestation + a potent, lasting boon. */
    public static void fireFavour(ServerLevel level, ServerPlayer player, String faith, long now) {
        manifest(level, player, domainOf(faith), false);
        DivineVision.speak(faith, player,
                "You have given all, and I answer in glory. Behold me — and be marked as "
                        + "mine. My favour rests upon you, and shall not soon depart.");
        // A potent, lasting blessing beyond any miracle.
        player.heal(player.getMaxHealth());
        player.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 6000, 2));
        player.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, 12000, 1));
        player.addEffect(new MobEffectInstance(MobEffects.ABSORPTION, 12000, 3));
        player.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE, 12000, 0));
        // The lasting mark of favour: pinned to the cap.
        DivineFavour.addCapped(level, player.getUUID(), faith, DivineFavour.MAX_FAVOUR, now);
        RiteSavedData.get(level).setTheophanyTick(player.getUUID(), faith + "|favour", now);
    }

    /** Theophany of wrath — a dread manifestation + a severe but NON-permanent
     *  calamity (the road back, V4, still holds). */
    public static void fireWrath(ServerLevel level, ServerPlayer player, String faith, long now) {
        manifest(level, player, domainOf(faith), true);
        DivineVision.speak(faith, player,
                "You have defied me to the last — now look upon my wrath made manifest. "
                        + "Yet even now: repent, and the road remains. Defy me no further.");
        // Severe debuffs — but non-fatal (POISON stops at half a heart; the
        // lightning is visual-only). The calamity drives favour to its depth, which
        // the V1 earning can still climb back out of (never permanent damnation).
        player.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 6000, 2));
        player.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 6000, 2));
        player.addEffect(new MobEffectInstance(MobEffects.HUNGER, 6000, 2));
        player.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, 600, 0));
        player.addEffect(new MobEffectInstance(MobEffects.CONFUSION, 600, 0));
        player.addEffect(new MobEffectInstance(MobEffects.POISON, 300, 1));
        DivineFavour.offend(level, player.getUUID(), faith, DivineFavour.MAX_FAVOUR, now);
        DivineWrath.armConsequenceCooldown(player.getUUID(), now);   // no double curse
        RiteSavedData.get(level).setTheophanyTick(player.getUUID(), faith + "|wrath", now);
    }

    // ── The vanilla visual/audio toolkit (domain-flavoured) ──────────────────

    private static void manifest(ServerLevel level, ServerPlayer p, DeityDomain domain, boolean wrath) {
        double x = p.getX(), y = p.getY(), z = p.getZ();
        if (wrath) {
            level.playSound(null, x, y, z, SoundEvents.LIGHTNING_BOLT_THUNDER,
                    SoundSource.WEATHER, 4f, 0.7f);
            level.playSound(null, x, y, z, SoundEvents.WITHER_SPAWN, SoundSource.HOSTILE, 2f, 0.6f);
            level.setWeatherParameters(0, 6000, true, true);
            for (int i = 0; i < 3; i++) {                       // visual-only — no damage/fire
                double lx = x + (level.getRandom().nextDouble() * 8 - 4);
                double lz = z + (level.getRandom().nextDouble() * 8 - 4);
                LightningBolt bolt = new LightningBolt(EntityType.LIGHTNING_BOLT, level);
                bolt.moveTo(lx, y, lz);
                bolt.setVisualOnly(true);
                level.addFreshEntity(bolt);
            }
        } else {
            level.playSound(null, x, y, z, SoundEvents.BEACON_ACTIVATE, SoundSource.MASTER, 3f, 1f);
            level.playSound(null, x, y, z, SoundEvents.PLAYER_LEVELUP, SoundSource.MASTER, 1.5f, 1f);
            level.setWeatherParameters(6000, 0, false, false);  // clear, radiant
        }
        ParticleOptions[] fx = particlesFor(domain, wrath);
        burst(level, x, y, z, fx[0], 120);
        burst(level, x, y, z, fx[1], 60);
    }

    /** Domain → the manifestation's two signature particles (exhaustive). */
    private static ParticleOptions[] particlesFor(DeityDomain domain, boolean wrath) {
        return switch (domain) {
            case SUN  -> wrath ? new ParticleOptions[]{ParticleTypes.FLAME, ParticleTypes.LARGE_SMOKE}
                               : new ParticleOptions[]{ParticleTypes.END_ROD, ParticleTypes.FLAME};
            case SEA  -> wrath ? new ParticleOptions[]{ParticleTypes.SPLASH, ParticleTypes.LARGE_SMOKE}
                               : new ParticleOptions[]{ParticleTypes.SPLASH, ParticleTypes.BUBBLE};
            case FORGE-> wrath ? new ParticleOptions[]{ParticleTypes.LAVA, ParticleTypes.LARGE_SMOKE}
                               : new ParticleOptions[]{ParticleTypes.LAVA, ParticleTypes.FLAME};
            case FATE -> wrath ? new ParticleOptions[]{ParticleTypes.PORTAL, ParticleTypes.LARGE_SMOKE}
                               : new ParticleOptions[]{ParticleTypes.ENCHANT, ParticleTypes.PORTAL};
        };
    }

    private static void burst(ServerLevel level, double x, double y, double z,
                              ParticleOptions particle, int count) {
        // A spread column around the player (server-side spawn → all nearby clients).
        level.sendParticles(particle, x, y + 1.2, z, count, 1.2, 1.8, 1.2, 0.06);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static DeityDomain domainOf(String faith) {
        ReligionIdentity id = ReligionIdentity.get(faith);
        return id != null ? id.deity().domain() : DeityDomain.SUN;
    }

    private static String primaryFaith(ServerLevel level, UUID playerId) {
        return RiteSavedData.get(level).getPlayerPiety(playerId)
                .flatMap(PietyComponent::primaryReligion).orElse(null);
    }
}
