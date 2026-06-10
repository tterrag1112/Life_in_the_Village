package tterrag1112.life_in_the_village.Npc.Religion.Saints;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Npc.Religion.BuildingFaith;
import tterrag1112.life_in_the_village.Npc.Religion.CalendarView;
import tterrag1112.life_in_the_village.Npc.Religion.FaithConcept;
import tterrag1112.life_in_the_village.Npc.Religion.God;
import tterrag1112.life_in_the_village.Npc.Religion.GodRegistry;
import tterrag1112.life_in_the_village.Npc.Religion.PietyTier;
import tterrag1112.life_in_the_village.Npc.Religion.Religion;
import tterrag1112.life_in_the_village.Npc.Religion.Religions;
import tterrag1112.life_in_the_village.Npc.Religion.ReligionSavedData;
import tterrag1112.life_in_the_village.Npc.Religion.ReligiousCalendar;
import tterrag1112.life_in_the_village.Npc.Religion.Sacred.SacredSpaceSavedData;
import tterrag1112.life_in_the_village.Village.Graveyard.GraveyardSavedData;
import tterrag1112.life_in_the_village.Village.History.HistoryEventType;
import tterrag1112.life_in_the_village.Village.History.VillageHistoryLog;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Saints &amp; Relics SR2 — the <b>canonization</b> of the dead, the one home for the
 * three paths and the shared tie-in routine. The dead become saints by:
 * <ol>
 *   <li><b>living saint who dies → auto-canonized</b> (moved off the SR1 living roster,
 *       patron god carried over);</li>
 *   <li><b>martyr → auto-canonized</b> (a DEVOUT+ believer killed by another entity);</li>
 *   <li><b>Venerable → recorded</b> (a PIOUS / DEVOUT-mentor believer), elevated later
 *       by a high priest of the faith ({@link #dailyVenerableSweep}).</li>
 * </ol>
 *
 * <p>Canonization reuses the existing machinery (no reinvention): it inscribes the
 * {@code Grave} epitaph, lays a <b>permanent</b> consecrated imprint at the grave (S3),
 * adds a <b>saint's day</b> to the faith's calendar via {@link ReligionSavedData#put}
 * (the first real per-world-religion mutation — copy-modify-put), and chronicles a
 * {@code SAINT_CANONIZED} entry. The saint's day then flows through {@code SacredTime}
 * automatically; the grave imprint through {@code SacredSpace.sacrednessAt}.</p>
 */
public final class Canonization {

    private Canonization() {}

    /** A saint's grave is strongly sacred (MAJOR-tier) and durable (permanent imprint). */
    private static final float SAINT_GRAVE_STRENGTH = 2.0f;

    // ── Death entry point (the three paths) ──────────────────────────────────

    /**
     * Called at NPC death (after burial). Classifies the deceased into one of the
     * three paths, or none. {@code killer} is the death-source entity (from
     * {@code LivingDeathEvent.getSource().getEntity()}), null for an environmental
     * death.
     */
    public static void onNpcDeath(ServerLevel level, TownspersonMob deceased,
                                  Entity killer, long now) {
        if (deceased == null) return;
        String faith = deceased.getPiety().primaryReligion().orElse(null);
        if (faith == null) return;                          // atheist — no canonization
        BlockPos gravePos = GraveyardSavedData.get(level).graveOf(deceased.getUUID())
                .map(g -> g.slot()).orElse(null);
        if (gravePos == null) return;                       // no grave → no sacred site
        UUID id = deceased.getUUID();
        String name = deceased.getNpcName();
        SaintsSavedData saints = SaintsSavedData.get(level);

        // PATH 1 — a living saint who dies is auto-canonized (patron god carried over).
        Optional<String> livingGod = saints.livingSaintGod(id);
        if (livingGod.isPresent()) {
            saints.remove(id);                              // off the living roster
            canonizeNow(level, id, name, faith, livingGod.get(), false, now, gravePos, now);
            return;
        }

        PietyTier tier = deceased.getPiety().primaryTier();
        God primary = primaryGodOf(level, faith);
        if (primary == null) return;                        // god-less faith — no patron

        // PATH 2 — martyr: a DEVOUT+ believer slain by another (living) entity.
        boolean slainByAnother = killer instanceof LivingEntity && killer != deceased;
        if (slainByAnother && tier.ordinal() >= PietyTier.DEVOUT.ordinal()) {
            canonizeNow(level, id, name, faith, primary.id(), true, now, gravePos, now);
            return;
        }

        // PATH 3 — Venerable: PIOUS, or DEVOUT with a mentorship legacy. Recorded; a
        // high priest elevates later. (Conservative bar — canonization stays rare.)
        boolean venerable = tier == PietyTier.PIOUS
                || (tier == PietyTier.DEVOUT && !deceased.getRetirementState().mentoredInPast().isEmpty());
        if (venerable) {
            saints.putSaint(buildSaint(level, id, name, faith, primary.id(), false, false, now, gravePos));
        }
    }

    // ── Venerable elevation (clergy-gated, daily) ────────────────────────────

    /**
     * SR2 — a high priest of the faith canonizes its pending Venerables. A light daily
     * sweep: a Venerable is elevated when any village has a seated priest of its faith
     * (the church recognizing them). No per-tick scan.
     */
    public static void dailyVenerableSweep(ServerLevel level, VillageSavedData data, long now) {
        SaintsSavedData saints = SaintsSavedData.get(level);
        for (SaintsSavedData.Saint v : saints.venerables()) {
            if (hasHighPriestOfFaith(level, data, v.religionId())) {
                canonizeNow(level, v.saintId(), v.name(), v.religionId(), v.godId(),
                        v.martyr(), v.deathTick(), v.gravePos(), now);
            }
        }
    }

    /** SR3 — elevate a Venerable to a canonized saint by POPULAR devotion (the
     *  veneration threshold), running the same tie-in routine as the clergy/auto paths.
     *  No-op if already canonized. */
    public static void canonizeVenerable(ServerLevel level, SaintsSavedData.Saint v, long now) {
        if (v == null || v.canonized()) return;
        canonizeNow(level, v.saintId(), v.name(), v.religionId(), v.godId(),
                v.martyr(), v.deathTick(), v.gravePos(), now);
    }

    private static boolean hasHighPriestOfFaith(ServerLevel level, VillageSavedData data, String faith) {
        for (Village v : data.getAllVillages()) {
            if (BuildingFaith.hasSeatedPriestOfFaith(level, v, faith)) return true;
        }
        return false;
    }

    // ── Canonize + the shared tie-in routine ─────────────────────────────────

    /** Builds the canonized {@link SaintsSavedData.Saint}, stores it, and runs the four
     *  reuse tie-ins (epitaph, durable imprint, saint's day, chronicle). */
    private static void canonizeNow(ServerLevel level, UUID id, String name, String faith,
                                    String godId, boolean martyr, long deathTick,
                                    BlockPos gravePos, long now) {
        SaintsSavedData saints = SaintsSavedData.get(level);
        SaintsSavedData.Saint s = buildSaint(level, id, name, faith, godId, martyr, true, deathTick, gravePos);
        saints.putSaint(s);
        applyTieIns(level, s, now);
    }

    /** Assembles a saint record. {@code canonized} ones get a free saint's-day slot
     *  (Venerables get −1 until elevation). The embodied virtue is the patron god's
     *  first virtue. */
    private static SaintsSavedData.Saint buildSaint(ServerLevel level, UUID id, String name,
                                                    String faith, String godId, boolean martyr,
                                                    boolean canonized, long deathTick, BlockPos gravePos) {
        God god = GodRegistry.get(godId);
        FaithConcept virtue = (god != null && !god.virtues().isEmpty())
                ? god.virtues().get(0).concept() : FaithConcept.values()[0];
        String godName = god != null ? god.displayName() : godId;
        String epitaph = "Here lies " + name + ", "
                + (martyr ? "Martyr" : "Saint") + " of " + godName + ".";
        int saintDay = canonized ? freeSaintDay(level, faith, deathTick) : -1;
        return new SaintsSavedData.Saint(id, name, faith, godId, virtue, epitaph, martyr,
                canonized, deathTick, gravePos, saintDay, Optional.empty(), 0);
    }

    /** The reuse tie-ins (one routine — both auto-canonize and elevation call it). */
    private static void applyTieIns(ServerLevel level, SaintsSavedData.Saint s, long now) {
        // 1. Inscribe the grave epitaph (the formerly-unused Grave.epitaph).
        GraveyardSavedData.get(level).inscribeEpitaph(s.saintId(), s.epitaph());
        // 2. Durable consecrated imprint at the grave (permanent — does not fade).
        SacredSpaceSavedData.get(level)
                .addPermanentImprint(s.godId(), s.gravePos(), s.deathTick(), SAINT_GRAVE_STRENGTH);
        // 2b. SR4 — mint the saint's relic (one per saint): set the relicId seam +
        //     enshrine it at the grave (best-effort spawn when the chunk is loaded).
        mintRelic(level, s);
        // 3. Add the saint's day to the faith's calendar — the first real per-world
        //    religion mutation: copy the religion, add the day, put it back (no clobber).
        addSaintDay(level, s);
        // 4. Chronicle it (MAJOR → never pruned) in the village that holds the grave.
        Village village = VillageSavedData.get(level).getVillageAt(s.gravePos()).orElse(null);
        if (village != null) {
            VillageHistoryLog.get(level).record(HistoryEventType.SAINT_CANONIZED,
                    village.getId(), now,
                    Map.of("name", s.name(), "faith", s.religionId(),
                            "martyr", String.valueOf(s.martyr()), "day", String.valueOf(s.saintDay())),
                    List.of(s.saintId()));
        }
    }

    /** SR4 — mints the saint's relic (one per saint): records the {@code relicId} seam
     *  on the saint, then best-effort spawns the relic item at the grave (the first
     *  reliquary) when the chunk is loaded. The grave is already permanently sacred. */
    private static void mintRelic(ServerLevel level, SaintsSavedData.Saint s) {
        if (s.relicId().isPresent()) return;                // already minted (idempotent)
        SaintsSavedData.Saint withRelic = s.withRelicId(java.util.Optional.of(s.saintId().toString()));
        SaintsSavedData.get(level).putSaint(withRelic);
        net.minecraft.world.item.ItemStack relic = new net.minecraft.world.item.ItemStack(
                tterrag1112.life_in_the_village.Items.ModItems.RELIC.get());
        relic.set(tterrag1112.life_in_the_village.Components.ModDataComponents.RELIC_DATA.get(),
                new RelicData(s.saintId(), s.name(), s.godId()));
        BlockPos at = s.gravePos().above();
        if (level.isLoaded(at)) {
            net.minecraft.world.entity.item.ItemEntity drop =
                    new net.minecraft.world.entity.item.ItemEntity(level,
                            at.getX() + 0.5, at.getY() + 0.2, at.getZ() + 0.5, relic);
            drop.setUnlimitedLifetime();                    // an enshrined relic does not despawn
            drop.setDeltaMovement(0, 0, 0);
            level.addFreshEntity(drop);
        }
    }

    /** Adds "St. &lt;name&gt;'s Day" → the saint's day-of-year to the faith's calendar via
     *  {@link ReligionSavedData#put} (copy-modify-put; other religion fields untouched). */
    private static void addSaintDay(ServerLevel level, SaintsSavedData.Saint s) {
        ReligionSavedData store = ReligionSavedData.get(level);
        Religion r = store.get(s.religionId());
        if (r == null || s.saintDay() < 0) return;
        Map<String, Integer> days = new LinkedHashMap<>(r.calendar().holyDaysByName());
        days.put("St. " + s.name() + "'s Day", s.saintDay());
        Religion updated = new Religion(r.id(), r.displayName(), r.coreTenets(), r.rites(),
                r.sacredLocations(), new ReligiousCalendar(days),
                r.preferredBookCategories(), r.godIds());
        store.put(updated);
    }

    /** The death-day's day-of-year, or the next free day if that one is already a holy
     *  day (so a saint's day never collides with an existing one). */
    private static int freeSaintDay(ServerLevel level, String faith, long deathTick) {
        int start = CalendarView.dayOfYear(deathTick);
        Religion r = ReligionSavedData.get(level).get(faith);
        ReligiousCalendar cal = r == null ? null : r.calendar();
        if (cal == null) return start;
        for (int i = 0; i < ReligiousCalendar.DAYS_PER_YEAR; i++) {
            int d = (start + i) % ReligiousCalendar.DAYS_PER_YEAR;
            if (!cal.isHolyDay(d)) return d;
        }
        return start;                                       // calendar full (degenerate)
    }

    private static God primaryGodOf(ServerLevel level, String faith) {
        Religion r = Religions.get(level, faith);
        return r == null ? null : GodRegistry.primaryGod(r).orElse(null);
    }
}
