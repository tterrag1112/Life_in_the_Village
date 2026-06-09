package tterrag1112.life_in_the_village.Npc.Religion;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Npc.Religion.DivineFavour.FavourAct;
import tterrag1112.life_in_the_village.Npc.Religion.ReligionIdentity.HistoryEvent;
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
 * high-favour player's <b>god</b> occasionally <i>speaks</i> — revealing lore,
 * affirming recent virtue or admonishing recent transgression, warning of a coming
 * holy day, and (lightly) calling them to a sacred act.
 *
 * <p>F1a sub-stage 3b — the <b>god</b> is the subject: visions iterate the player's
 * gods ({@link GodRegistry#playerGods}); the voice (name + colour) and the
 * deity-attribute content (character / virtues / taboos) come from the {@link God};
 * the RELIGION narrative (cosmology / sacred history / the holy-day calendar) still
 * comes from a religion that venerates the god. Favour is per-god (3a).</p>
 */
public final class DivineVision {

    private DivineVision() {}

    private static final int   CHECK_INTERVAL   = 200;     // roll at most every ~10s
    private static final long  VISION_COOLDOWN  = 12000L;  // ≥10 min between visions (per god)
    private static final int   VISION_CHANCE    = 6;       // 1/6 per eligible roll
    private static final float HIGH_FAVOUR      = 40f;     // the favoured are eligible
    private static final PietyTier MIN_TIER     = PietyTier.DEVOUT;
    private static final int   OMEN_WINDOW_DAYS = 20;      // warn of a holy day this near
    private static final float CALLING_REWARD   = 15f;     // bonus favour on fulfilment
    private static final int   CALLING_CHANCE   = 3;       // 1/3 of visions lay a calling

    /** playerId → (god id → tick of the last vision from that god). Transient. */
    private static final Map<UUID, Map<String, Long>> LAST_VISION = new HashMap<>();

    /** The player-doable acts a calling can ask for (pilgrimage is NPC-only — V1). */
    private static final FavourAct[] CALLABLE = {
            FavourAct.OFFERING, FavourAct.ATTEND_RITE, FavourAct.COMMISSION_RITE };

    // ── Trigger (driven from the per-player tick; iterates the player's gods) ──

    /** Bounded periodic vision roll: the first eligible god speaks (≤1 vision/tick). */
    public static void tick(ServerPlayer player) {
        if (!(player.level() instanceof ServerLevel level)) return;
        long now = level.getGameTime();
        if (now % CHECK_INTERVAL != 0) return;
        UUID pid = player.getUUID();

        for (God god : GodRegistry.playerGods(level, pid)) {
            if (now - lastVision(pid, god.id()) < VISION_COOLDOWN) continue;
            if (DivineFavour.tierForGod(level, pid, god.id()).ordinal() < MIN_TIER.ordinal()) continue;
            if (DivineFavour.current(level, pid, god.id(), now) < HIGH_FAVOUR) continue;
            if (level.getRandom().nextInt(VISION_CHANCE) != 0) continue;

            deliver(level, player, god, now);
            LAST_VISION.computeIfAbsent(pid, k -> new HashMap<>()).put(god.id(), now);
            return;   // one vision per tick
        }
    }

    private static long lastVision(UUID pid, String godId) {
        return LAST_VISION.getOrDefault(pid, Map.of()).getOrDefault(godId, Long.MIN_VALUE);
    }

    // ── Delivery ─────────────────────────────────────────────────────────────

    private static void deliver(ServerLevel level, ServerPlayer player, God god, long now) {
        // RELIGION narrative source: a religion that venerates this god.
        Religion rel = GodRegistry.primaryReligionOf(god.id()).orElse(null);
        ReligionIdentity id = rel == null ? null : ReligionIdentity.get(rel.id());
        RiteSavedData data = RiteSavedData.get(level);

        // Sometimes lay a calling instead of a plain vision (only if none active).
        if (rel != null && data.getPlayerCalling(player.getUUID()).isEmpty()
                && level.getRandom().nextInt(CALLING_CHANCE) == 0) {
            FavourAct act = CALLABLE[level.getRandom().nextInt(CALLABLE.length)];
            PlayerCalling calling = new PlayerCalling(rel.id(), act, now);
            data.setPlayerCalling(player.getUUID(), calling);
            send(player, god, "I would have you serve. " + calling.describe()
                    + " — and I shall remember it.");
            return;
        }

        send(player, god, composeVision(level, player, god, rel, id, now));
    }

    /** Picks one vision text (lore weighted; guidance/omen when their signal exists).
     *  Deity attributes from the GOD; narrative (cosmology/holy days) from the religion. */
    private static String composeVision(ServerLevel level, ServerPlayer player, God god,
                                        Religion rel, ReligionIdentity id, long now) {
        List<String> pool = new ArrayList<>();
        pool.add(lore(god, id, rel));       // lore is always available…
        pool.add(lore(god, id, rel));       // …and weighted up (the staple of devotion)

        // Omen — the next holy day of this RELIGION within the window.
        if (rel != null) {
            for (CalendarView.Entry e : CalendarView.upcomingFor(rel, now)) {
                if (e.daysAway() <= OMEN_WINDOW_DAYS) {
                    pool.add("A day approaches — " + pretty(e.dayLabel()) + ", in "
                            + e.daysAway() + (e.daysAway() == 1 ? " day" : " days")
                            + ". Be present, and honour it.");
                }
                break; // nearest only
            }
        }

        // Guidance — admonish recent transgression (low standing) or affirm service,
        // by the GOD's taboos / virtues.
        VillageReputation.Tier rep = reputationHere(level, player);
        if (rep != null && rep.ordinal() <= VillageReputation.Tier.DISTRUSTED.ordinal()
                && !god.taboos().isEmpty()) {
            Taboo t = god.taboos().get(level.getRandom().nextInt(god.taboos().size()));
            pool.add("You have strayed. " + t.text());
        } else if (DivineFavour.current(level, player.getUUID(), god.id(), now) >= 60f
                && !god.virtues().isEmpty()) {
            Virtue v = god.virtues().get(level.getRandom().nextInt(god.virtues().size()));
            pool.add("You have served well, and I see it. " + v.text());
        }

        return pool.get(level.getRandom().nextInt(pool.size()));
    }

    /** A lore fragment — the GOD's nature ({@code character}) + the RELIGION's
     *  cosmology / founding myth / a key event; core tenets when unauthored. */
    private static String lore(God god, ReligionIdentity id, Religion rel) {
        List<String> bits = new ArrayList<>();
        bits.add(god.character());                          // a deity attribute → the god
        if (id != null) {                                   // narrative → the religion
            bits.add(id.cosmology());
            bits.add(id.history().foundingMyth());
            if (!id.history().events().isEmpty()) {
                HistoryEvent e = id.history().events().get(0);
                bits.add(e.title() + " — " + e.text());
            }
        } else if (rel != null && !rel.coreTenets().isEmpty()) {
            bits.add(rel.coreTenets().get(0));
        }
        return bits.get((int) (System.nanoTime() % bits.size()));
    }

    // ── The light calling — fulfilment from the V1 act hooks (religion-keyed) ──

    /**
     * Called from {@link DivineFavour#awardForReligion} when a player completes a
     * favour-earning act. Fulfils a matching standing calling: bonus favour + a lore
     * vision (voiced by the religion's god). No-op otherwise.
     */
    public static void onFavourAct(ServerLevel level, UUID playerId, String religionId,
                                   FavourAct act, long now) {
        RiteSavedData data = RiteSavedData.get(level);
        PlayerCalling c = data.getPlayerCalling(playerId).orElse(null);
        if (c == null || !c.religionId().equals(religionId) || c.act() != act) return;

        data.clearPlayerCalling(playerId);
        DivineFavour.addCappedForReligion(level, playerId, religionId, CALLING_REWARD, now);

        ServerPlayer player = level.getServer().getPlayerList().getPlayer(playerId);
        if (player == null) return;
        Religion rel = ReligionRegistry.get(religionId);
        God god = rel == null ? null : GodRegistry.primaryGod(rel).orElse(null);
        if (god == null) return;
        ReligionIdentity id = ReligionIdentity.get(religionId);
        send(player, god, "You have answered my call. Favour is yours — and a truth: "
                + lore(god, id, rel));
    }

    /** F1a sub-stage 3b — deliver a god-voiced line (the negative side reuses this
     *  styled message for omens / curse pronouncements). The god is the subject. */
    public static void speak(God god, ServerPlayer player, String text) {
        if (god == null) return;
        send(player, god, text);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static VillageReputation.Tier reputationHere(ServerLevel level, ServerPlayer player) {
        Village v = VillageSavedData.get(level).getVillageAt(player.blockPosition()).orElse(null);
        if (v == null) return null;
        return ReputationManager.getTier(player, v.getId(), level);
    }

    /** The styled two-line vision message: the god's name + their italic words. */
    private static void send(ServerPlayer player, God god, String text) {
        player.sendSystemMessage(Component.literal("✦ " + capitalize(god.displayName()) + " speaks ✦")
                .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
        player.sendSystemMessage(Component.literal("“" + text + "”")
                .withStyle(ChatFormatting.ITALIC, domainColor(god.domain())));
    }

    /** Domain-flavoured ink for the god's words (exhaustive over DeityDomain). */
    private static ChatFormatting domainColor(DeityDomain domain) {
        return switch (domain) {
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
