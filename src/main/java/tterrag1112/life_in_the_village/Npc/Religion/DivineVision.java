package tterrag1112.life_in_the_village.Npc.Religion;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Npc.Religion.DivineFavour.FavourAct;
import tterrag1112.life_in_the_village.Npc.Religion.ReligionIdentity.DeityDomain;
import tterrag1112.life_in_the_village.Npc.Religion.ReligionIdentity.HistoryEvent;
import tterrag1112.life_in_the_village.Npc.Religion.ReligionIdentity.Taboo;
import tterrag1112.life_in_the_village.Npc.Religion.ReligionIdentity.Virtue;
import tterrag1112.life_in_the_village.Village.Reputation.ReputationManager;
import tterrag1112.life_in_the_village.Village.Reputation.VillageReputation;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Divine Layer V3 — <b>visions</b>: where the divine becomes a relationship. A
 * high-favour player's deity occasionally <i>speaks</i> — revealing lore (D1),
 * affirming recent virtue or admonishing recent transgression, warning of a coming
 * holy day, and (lightly) calling them to a sacred act. Per-deity voiced straight
 * from the authored {@link ReligionIdentity} (the same source D3b {@code FaithVoice}
 * / D3 {@code ScriptureFactory} draw on), so the Sun-Mother and the Loom speak
 * utterly differently. Player-primary.
 *
 * <h3>Cadence (bounded — special, not spammy)</h3>
 * Driven off the existing per-player tick; only the devout + favoured (tier ≥
 * {@link #MIN_TIER}, favour ≥ {@link #HIGH_FAVOUR}) are eligible, rolled at most
 * once per {@link #CHECK_INTERVAL} ticks, behind a {@link #VISION_COOLDOWN} and a
 * modest chance. The per-player last-vision tick is in-memory (transient — no
 * persistence needed).
 *
 * <h3>The light "calling" (quest-lite)</h3>
 * A vision may lay a {@link PlayerCalling} — a sacred task fulfilled by an existing
 * religious act (the V1 favour hooks). {@link #onFavourAct} (called from
 * {@link DivineFavour}) fulfils it on the matching act: bonus favour + a lore
 * vision. Persisted (one per player); NOT wired into the quest/Requests board
 * (flagged).
 */
public final class DivineVision {

    private DivineVision() {}

    private static final int   CHECK_INTERVAL   = 200;     // roll at most every ~10s
    private static final long  VISION_COOLDOWN  = 12000L;  // ≥10 min between visions
    private static final int   VISION_CHANCE    = 6;       // 1/6 per eligible roll
    private static final float HIGH_FAVOUR      = 40f;     // the favoured are eligible
    private static final PietyTier MIN_TIER     = PietyTier.DEVOUT;
    private static final int   OMEN_WINDOW_DAYS = 20;      // warn of a holy day this near
    private static final float CALLING_REWARD   = 15f;     // bonus favour on fulfilment
    private static final int   CALLING_CHANCE   = 3;       // 1/3 of visions lay a calling

    /** playerId → tick of the last delivered vision (transient anti-spam). */
    private static final Map<UUID, Long> LAST_VISION = new HashMap<>();

    /** The player-doable acts a calling can ask for (pilgrimage is NPC-only — V1). */
    private static final FavourAct[] CALLABLE = {
            FavourAct.OFFERING, FavourAct.ATTEND_RITE, FavourAct.COMMISSION_RITE };

    // ── Trigger (driven from the per-player tick) ────────────────────────────

    /** Bounded periodic vision roll for the devout + favoured. */
    public static void tick(ServerPlayer player) {
        if (!(player.level() instanceof ServerLevel level)) return;
        long now = level.getGameTime();
        if (now % CHECK_INTERVAL != 0) return;
        UUID pid = player.getUUID();
        Long last = LAST_VISION.get(pid);
        if (last != null && now - last < VISION_COOLDOWN) return;

        String faith = primaryFaith(level, pid);
        if (faith == null) return;
        if (DivineFavour.tierIn(level, pid, faith).ordinal() < MIN_TIER.ordinal()) return;
        if (DivineFavour.current(level, pid, faith, now) < HIGH_FAVOUR) return;
        if (level.getRandom().nextInt(VISION_CHANCE) != 0) return;

        deliver(level, player, faith, now);
        LAST_VISION.put(pid, now);
    }

    // ── Delivery ─────────────────────────────────────────────────────────────

    private static void deliver(ServerLevel level, ServerPlayer player, String faith, long now) {
        Religion rel = ReligionRegistry.get(faith);
        if (rel == null) return;
        ReligionIdentity id = ReligionIdentity.get(faith);
        String deity = rel.deity().orElse(rel.displayName());
        RiteSavedData data = RiteSavedData.get(level);

        // Sometimes lay a calling instead of a plain vision (only if none active).
        if (data.getPlayerCalling(player.getUUID()).isEmpty()
                && level.getRandom().nextInt(CALLING_CHANCE) == 0) {
            FavourAct act = CALLABLE[level.getRandom().nextInt(CALLABLE.length)];
            PlayerCalling calling = new PlayerCalling(faith, act, now);
            data.setPlayerCalling(player.getUUID(), calling);
            send(player, deity, id, "I would have you serve. " + calling.describe()
                    + " — and I shall remember it.");
            return;
        }

        send(player, deity, id, composeVision(level, player, faith, rel, id, now));
    }

    /** Picks one of the available vision texts (lore weighted; guidance/omen when
     *  their signal is present). */
    private static String composeVision(ServerLevel level, ServerPlayer player, String faith,
                                        Religion rel, ReligionIdentity id, long now) {
        List<String> pool = new ArrayList<>();
        pool.add(lore(id, rel));            // lore is always available…
        pool.add(lore(id, rel));            // …and weighted up (the staple of devotion)

        // Omen — the next holy day of this faith within the window.
        for (CalendarView.Entry e : CalendarView.upcomingFor(rel, now)) {
            if (e.daysAway() <= OMEN_WINDOW_DAYS) {
                pool.add("A day approaches — " + pretty(e.dayLabel()) + ", in "
                        + e.daysAway() + (e.daysAway() == 1 ? " day" : " days")
                        + ". Be present, and honour it.");
            }
            break; // nearest only
        }

        // Guidance — admonish recent transgression (low standing) or affirm service.
        VillageReputation.Tier rep = reputationHere(level, player);
        if (rep != null && rep.ordinal() <= VillageReputation.Tier.DISTRUSTED.ordinal()
                && id != null && !id.taboos().isEmpty()) {
            Taboo t = id.taboos().get(level.getRandom().nextInt(id.taboos().size()));
            pool.add("You have strayed. " + t.text());
        } else if (DivineFavour.current(level, player.getUUID(), faith, now) >= 60f
                && id != null && !id.virtues().isEmpty()) {
            Virtue v = id.virtues().get(level.getRandom().nextInt(id.virtues().size()));
            pool.add("You have served well, and I see it. " + v.text());
        }

        return pool.get(level.getRandom().nextInt(pool.size()));
    }

    /** A lore fragment — cosmology / founding myth / the deity's nature / a key
     *  event (D1), or the faith's core tenets when unauthored. */
    private static String lore(ReligionIdentity id, Religion rel) {
        if (id == null) {
            var tenets = rel.coreTenets();
            return tenets.isEmpty() ? "Keep faith with me." : tenets.get(0);
        }
        List<String> bits = new ArrayList<>();
        bits.add(id.cosmology());
        bits.add(id.history().foundingMyth());
        bits.add(id.deity().character());
        if (!id.history().events().isEmpty()) {
            HistoryEvent e = id.history().events().get(0);
            bits.add(e.title() + " — " + e.text());
        }
        // Stable-ish pick (no RandomSource here; the caller's weighting varies it).
        return bits.get((int) (System.nanoTime() % bits.size()));
    }

    // ── The light calling — fulfilment from the V1 act hooks ─────────────────

    /**
     * Called from {@link DivineFavour} when a player completes a favour-earning act.
     * Fulfils a matching standing calling: bonus favour (capped, no re-trigger) + a
     * lore vision. No-op otherwise.
     */
    public static void onFavourAct(ServerLevel level, UUID playerId, String religionId,
                                   FavourAct act, long now) {
        RiteSavedData data = RiteSavedData.get(level);
        PlayerCalling c = data.getPlayerCalling(playerId).orElse(null);
        if (c == null || !c.religionId().equals(religionId) || c.act() != act) return;

        data.clearPlayerCalling(playerId);
        DivineFavour.addCapped(level, playerId, religionId, CALLING_REWARD, now);

        ServerPlayer player = level.getServer().getPlayerList().getPlayer(playerId);
        if (player == null) return;
        Religion rel = ReligionRegistry.get(religionId);
        if (rel == null) return;
        ReligionIdentity id = ReligionIdentity.get(religionId);
        String deity = rel.deity().orElse(rel.displayName());
        send(player, deity, id, "You have answered my call. Favour is yours — and a "
                + "truth: " + lore(id, rel));
    }

    /** Divine Layer V4 — deliver a deity-voiced line to a player (the negative side
     *  reuses the same styled message for omens / curse pronouncements). */
    public static void speak(String faith, ServerPlayer player, String text) {
        Religion rel = ReligionRegistry.get(faith);
        if (rel == null) return;
        send(player, rel.deity().orElse(rel.displayName()), ReligionIdentity.get(faith), text);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static String primaryFaith(ServerLevel level, UUID playerId) {
        return RiteSavedData.get(level).getPlayerPiety(playerId)
                .flatMap(PietyComponent::primaryReligion).orElse(null);
    }

    private static VillageReputation.Tier reputationHere(ServerLevel level, ServerPlayer player) {
        Village v = VillageSavedData.get(level).getVillageAt(player.blockPosition()).orElse(null);
        if (v == null) return null;
        return ReputationManager.getTier(player, v.getId(), level);
    }

    /** The styled two-line vision message: deity name + their italic words. */
    private static void send(ServerPlayer player, String deity, ReligionIdentity id, String text) {
        player.sendSystemMessage(Component.literal("✦ " + capitalize(deity) + " speaks ✦")
                .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
        player.sendSystemMessage(Component.literal("“" + text + "”")
                .withStyle(ChatFormatting.ITALIC, domainColor(id)));
    }

    /** Domain-flavoured ink for the deity's words (exhaustive over DeityDomain). */
    private static ChatFormatting domainColor(ReligionIdentity id) {
        if (id == null) return ChatFormatting.WHITE;
        return switch (id.deity().domain()) {
            case SUN   -> ChatFormatting.YELLOW;
            case SEA   -> ChatFormatting.AQUA;
            case FORGE -> ChatFormatting.RED;
            case FATE  -> ChatFormatting.LIGHT_PURPLE;
        };
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static String pretty(String raw) {
        return raw == null ? "" : raw.replace('_', ' ').replace('.', ' ').trim();
    }
}
