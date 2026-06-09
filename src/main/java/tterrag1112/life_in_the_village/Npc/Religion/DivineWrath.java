package tterrag1112.life_in_the_village.Npc.Religion;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import tterrag1112.life_in_the_village.Npc.Religion.DivineFavour.DispleasureTier;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Divine Layer V4 — <b>curses &amp; wrath</b>: the consequence side. Sacrilege drives
 * a <b>god's</b> favour DOWN into displeasure; displeasure escalates — omen → curse
 * ({@link Curses}, by the god's {@link DeityDomain}) → wrath
 * (severe, non-fatal). The V1 earning acts climb favour back out — never permanent.
 *
 * <p>F1a sub-stage 3b — the <b>god</b> is the subject: the escalation ticks iterate
 * the player's gods ({@link GodRegistry#playerGods}), read each god's favour (3a),
 * select curse content by {@code god.domain()}, and voice via the god
 * ({@link DivineVision#speak(God, ServerPlayer, String)}). Sacrilege checks the
 * GOD's taboos.</p>
 */
public final class DivineWrath {

    private DivineWrath() {}

    /** Favour lost per sacrilegious act (a taboo of the player's own god). */
    private static final float SACRILEGE_HIT = 15f;
    private static final int   CHECK_INTERVAL = 200;
    private static final long  OMEN_CD  = 6000L;
    private static final long  CURSE_CD = 4800L;
    private static final long  WRATH_CD = 4800L;

    /** playerId → (god id → tick of the last applied consequence). Transient. */
    private static final Map<UUID, Map<String, Long>> LAST_CONSEQUENCE = new HashMap<>();

    // ── Sacrilege trigger (from the crime system, D2 taboo → the player's god) ──

    /**
     * A player committed an act whose {@link FaithConcept} is a taboo. If it is a
     * taboo of the player's primary GOD (faith-relative), that god is offended:
     * favour drops toward displeasure + an immediate warning. From {@code CrimeReporter}.
     */
    public static void onPlayerSacrilege(ServerLevel level, ServerPlayer player,
                                         FaithConcept concept, long now) {
        UUID pid = player.getUUID();
        String faith = RiteSavedData.get(level).getPlayerPiety(pid)
                .flatMap(PietyComponent::primaryReligion).orElse(null);
        Religion religion = faith == null ? null : ReligionRegistry.get(faith);
        if (religion == null) return;

        // F1a 4a — offense targets the SPECIFIC god(s) whose taboo was broken (not a
        // blanket fan-out). Single-god → the one god if it forbids the concept.
        for (God god : GodRegistry.godsTabooing(religion, concept)) {
            DivineFavour.offend(level, pid, god.id(), SACRILEGE_HIT, now);
            String reproach = god.taboos().stream()
                    .filter(t -> t.concept() == concept).findFirst()
                    .map(t -> t.text()).orElse("");
            DivineVision.speak(god, player, "You profane what I hold. " + reproach
                    + " Turn back, lest you know my displeasure.");
        }
    }

    // ── Escalating consequences (per-player tick; iterates the player's gods) ──

    /** For each god the player is displeased with, apply the band consequence. */
    public static void tick(ServerPlayer player) {
        if (!(player.level() instanceof ServerLevel level)) return;
        long now = level.getGameTime();
        if (now % CHECK_INTERVAL != 0) return;
        UUID pid = player.getUUID();

        for (God god : GodRegistry.playerGods(level, pid)) {
            float fav = DivineFavour.current(level, pid, god.id(), now);
            DispleasureTier tier = DivineFavour.displeasureOf(fav);
            if (tier == DispleasureTier.NONE) continue;
            if (now - lastConsequence(pid, god.id()) < cooldownFor(tier)) continue;
            applyConsequence(level, player, god, tier);
            LAST_CONSEQUENCE.computeIfAbsent(pid, k -> new HashMap<>()).put(god.id(), now);
        }
    }

    /** Divine Layer V5 — a wrath theophany arms this (per god) so the normal curse
     *  tick won't also fire the same moment (theophany IS the amplified peak). */
    public static void armConsequenceCooldown(UUID playerId, String godId, long now) {
        LAST_CONSEQUENCE.computeIfAbsent(playerId, k -> new HashMap<>()).put(godId, now);
    }

    private static long lastConsequence(UUID pid, String godId) {
        return LAST_CONSEQUENCE.getOrDefault(pid, Map.of()).getOrDefault(godId, Long.MIN_VALUE);
    }

    private static long cooldownFor(DispleasureTier tier) {
        return switch (tier) {
            case WRATH -> WRATH_CD;
            case CURSE -> CURSE_CD;
            case OMEN  -> OMEN_CD;
            case NONE  -> Long.MAX_VALUE;
        };
    }

    private static void applyConsequence(ServerLevel level, ServerPlayer player,
                                         God god, DispleasureTier tier) {
        switch (tier) {
            case OMEN -> DivineVision.speak(god, player,
                    "I am angered with you. Make amends — offerings, rites, devotion — "
                            + "lest worse follow.");
            case CURSE, WRATH -> {
                Curse curse = Curses.forDomain(god.domain(), tier);
                if (curse != null) {
                    curse.effect().apply(level, player);
                    DivineVision.speak(god, player, curse.flavour());
                } else {
                    DivineVision.speak(god, player, "My displeasure is upon you.");
                }
            }
            case NONE -> { /* unreachable — guarded by the caller */ }
        }
    }

}
