package tterrag1112.life_in_the_village.Npc.Religion;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import tterrag1112.life_in_the_village.Npc.Religion.DivineFavour.DispleasureTier;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Divine Layer V4 — <b>curses &amp; wrath</b>: the consequence side. Devotion earns
 * favour/miracles/visions (V1–V3); <b>sacrilege earns the opposite.</b> A player who
 * commits their faith's taboos drives that deity's favour DOWN into displeasure
 * (signed favour, V4); displeasure escalates — <b>omen</b> (warning vision) →
 * <b>curse</b> (misfortune, {@link Curses}) → <b>wrath</b> (severe, non-fatal). There
 * is always a road back: the V1 earning acts (offerings/rites/…) climb favour back
 * out of the negative and the consequences lift — never permanent damnation.
 *
 * <p>Player-primary. Reuses {@link DivineFavour} (the signed scale), D2's
 * sacrilege detection (faith taboos), the V3 {@link DivineVision} voice (omens), and
 * vanilla negative effects ({@link Curses}). Miracles are naturally unavailable while
 * displeased — favour is negative, so it never meets a miracle's positive cost.</p>
 */
public final class DivineWrath {

    private DivineWrath() {}

    /** Favour lost per sacrilegious act (a taboo of the player's own faith). */
    private static final float SACRILEGE_HIT = 15f;
    /** Consequence re-application cadence host (the per-player tick gates finer). */
    private static final int   CHECK_INTERVAL = 200;
    /** Per-band cooldown between applied consequences (anti-spam, signalled). */
    private static final long  OMEN_CD  = 6000L;
    private static final long  CURSE_CD = 4800L;
    private static final long  WRATH_CD = 4800L;

    /** playerId → tick of the last applied consequence (transient anti-spam). */
    private static final Map<UUID, Long> LAST_CONSEQUENCE = new HashMap<>();

    // ── Sacrilege trigger (from the crime system, D2 taboo → player's faith) ──

    /**
     * A player committed an act whose {@link FaithConcept} is a taboo. If it is a
     * taboo of the player's OWN faith (faith-relative, mirroring D2), the deity is
     * offended: favour drops toward displeasure + an immediate warning. Called from
     * {@code CrimeReporter} for a player perpetrator.
     */
    public static void onPlayerSacrilege(ServerLevel level, ServerPlayer player,
                                         FaithConcept concept, long now) {
        String faith = primaryFaith(level, player.getUUID());
        if (faith == null) return;
        ReligionIdentity id = ReligionIdentity.get(faith);
        if (id == null) return;
        boolean taboo = id.taboos().stream().anyMatch(t -> t.concept() == concept);
        if (!taboo) return;                                  // not YOUR deity's taboo

        DivineFavour.offendForReligion(level, player.getUUID(), faith, SACRILEGE_HIT, now);
        String reproach = id.taboos().stream()
                .filter(t -> t.concept() == concept).findFirst()
                .map(t -> t.text()).orElse("");
        DivineVision.speak(faith, player,
                "You profane what I hold. " + reproach + " Turn back, lest you know my displeasure.");
    }

    // ── Escalating consequences (driven from the per-player tick) ─────────────

    /** While a player is displeased with their deity, periodically apply the
     *  band-appropriate consequence (omen / curse / wrath), cooldown-bounded. */
    public static void tick(ServerPlayer player) {
        if (!(player.level() instanceof ServerLevel level)) return;
        long now = level.getGameTime();
        if (now % CHECK_INTERVAL != 0) return;
        UUID pid = player.getUUID();
        String faith = primaryFaith(level, pid);
        if (faith == null) return;

        float fav = DivineFavour.currentForReligion(level, pid, faith, now);
        DispleasureTier tier = DivineFavour.displeasureOf(fav);
        if (tier == DispleasureTier.NONE) return;

        Long last = LAST_CONSEQUENCE.get(pid);
        if (last != null && now - last < cooldownFor(tier)) return;
        applyConsequence(level, player, faith, tier);
        LAST_CONSEQUENCE.put(pid, now);
    }

    /** Divine Layer V5 — a wrath theophany arms this so the normal curse tick won't
     *  also fire the same moment (theophany IS the amplified peak, not an addition). */
    public static void armConsequenceCooldown(UUID playerId, long now) {
        LAST_CONSEQUENCE.put(playerId, now);
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
                                         String faith, DispleasureTier tier) {
        switch (tier) {
            case OMEN -> DivineVision.speak(faith, player,
                    "I am angered with you. Make amends — offerings, rites, devotion — "
                            + "lest worse follow.");
            case CURSE, WRATH -> {
                Curse curse = Curses.forReligion(faith, tier);
                if (curse != null) {
                    curse.effect().apply(level, player);
                    DivineVision.speak(faith, player, curse.flavour());
                } else {
                    // Faith without an authored curse at this band — at least warn.
                    DivineVision.speak(faith, player, "My displeasure is upon you.");
                }
            }
            case NONE -> { /* unreachable — guarded by the caller */ }
        }
    }

    private static String primaryFaith(ServerLevel level, UUID playerId) {
        return RiteSavedData.get(level).getPlayerPiety(playerId)
                .flatMap(PietyComponent::primaryReligion).orElse(null);
    }
}
