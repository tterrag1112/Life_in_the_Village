package tterrag1112.life_in_the_village.Npc.Crime;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import org.slf4j.Logger;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Npc.Memory.MemoryType;
import tterrag1112.life_in_the_village.Npc.Memory.NpcMemory;
import tterrag1112.life_in_the_village.Npc.Mood.MoodTrigger;
import tterrag1112.life_in_the_village.Npc.Office.OfficeRegistry;
import tterrag1112.life_in_the_village.Npc.Relations.RelationshipOrigin;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Single entry point for "a crime happened — record it." Detection
 * surfaces (theft, assault, vandalism, etc.) build a {@link Builder}
 * and call {@link Builder#commit}. The reporter:
 *
 * <ol>
 *   <li>Scans for witnesses (NPCs within 16 blocks, line-of-sight via
 *   AABB containment for v1).</li>
 *   <li>Writes a {@link MemoryType#WITNESSED_CRIME_BY} entry to each
 *   witness's memory log + a {@link MemoryType#VICTIM_OF_CRIME_BY}
 *   pinned entry on the victim if any.</li>
 *   <li>Files the {@link CrimeReport} on
 *   {@link CrimeSavedData} (FILED status).</li>
 *   <li>Applies immediate side effects: relationship -60 victim →
 *   perpetrator, mood shock for victim + witnesses, reputation drop
 *   for the perpetrator.</li>
 * </ol>
 *
 * <p>Detection paths never mutate {@link CrimeReport} or memory
 * directly — they go through this class so the side-effect chain
 * stays consistent. Tests / debug commands also use it to seed
 * fixtures.</p>
 */
public final class CrimeReporter {

    private static final Logger LOGGER = LogUtils.getLogger();
    /** Spec line 132 — witness line-of-sight radius. */
    public static final double WITNESS_RADIUS = 16.0;

    private CrimeReporter() {}

    public static Builder builder(CrimeType type, ServerLevel level) {
        return new Builder(type, level);
    }

    public static final class Builder {
        private final CrimeType type;
        private final ServerLevel level;
        private UUID perpetratorId;
        private UUID victimId;
        private UUID reportedById;
        private UUID villageId;
        private BlockPos location;
        private long occurredTick;
        private final List<String> notes = new ArrayList<>();

        Builder(CrimeType type, ServerLevel level) {
            this.type = type;
            this.level = level;
            this.occurredTick = level == null ? 0L : level.getGameTime();
        }

        public Builder perpetrator(UUID id)   { this.perpetratorId = id; return this; }
        public Builder victim(UUID id)        { this.victimId = id; return this; }
        public Builder reportedBy(UUID id)    { this.reportedById = id; return this; }
        public Builder location(BlockPos pos) { this.location = pos; return this; }
        public Builder village(UUID id)       { this.villageId = id; return this; }
        public Builder note(String text)      { if (text != null) notes.add(text); return this; }
        public Builder occurredAt(long tick)  { this.occurredTick = tick; return this; }

        /**
         * Files the report. Returns the persisted {@link CrimeReport},
         * or empty if the build is incomplete (no village context, no
         * level, etc.) — the detection caller usually swallows that.
         */
        public Optional<CrimeReport> commit() {
            if (level == null || location == null) return Optional.empty();
            VillageSavedData vdata = VillageSavedData.get(level);
            if (villageId == null) {
                Village atPos = vdata.getVillageAt(location).orElse(null);
                if (atPos == null) return Optional.empty();
                villageId = atPos.getId();
            }
            // Default reporter: the village leader if seated, else the
            // perpetrator (placeholder, never blank — codec requires it).
            if (reportedById == null) {
                Village v = vdata.getVillageById(villageId).orElse(null);
                if (v != null) {
                    reportedById = v.getOffices()
                            .get(OfficeRegistry.VILLAGE_LEADER)
                            .flatMap(h -> h.holderUuid())
                            .orElse(null);
                }
                if (reportedById == null) reportedById = perpetratorId != null ? perpetratorId
                        : new UUID(0L, 0L);
            }

            // Witness scan — populate the report and write WITNESSED_CRIME_BY
            // memories before the report is committed, so the constable's
            // later memory-fidelity check finds them.
            List<UUID> witnessIds = scanWitnesses();

            CrimeReport report = new CrimeReport(
                    UUID.randomUUID(), type,
                    Optional.ofNullable(perpetratorId),
                    Optional.ofNullable(victimId),
                    witnessIds,
                    reportedById,
                    location,
                    occurredTick,
                    level.getGameTime(),
                    List.copyOf(notes),
                    ReportStatus.FILED,
                    Optional.empty(),
                    Optional.empty(),
                    villageId);

            CrimeSavedData crimes = CrimeSavedData.get(level);
            crimes.addReport(report);

            applySideEffects(report);
            LOGGER.debug("[CrimeReporter] Filed {} report at {} (witnesses={})",
                    type, location, witnessIds.size());
            return Optional.of(report);
        }

        // ── Witness scan ──────────────────────────────────────────────────

        private List<UUID> scanWitnesses() {
            AABB box = new AABB(location).inflate(WITNESS_RADIUS);
            List<TownspersonMob> nearby = level.getEntitiesOfClass(
                    TownspersonMob.class, box,
                    npc -> npc.isAlive() && !npc.getUUID().equals(perpetratorId));
            List<UUID> ids = new ArrayList<>(nearby.size());
            for (TownspersonMob npc : nearby) {
                ids.add(npc.getUUID());
                writeWitnessMemory(npc);
            }
            // Also write the victim memory if the victim is an NPC.
            if (victimId != null) {
                TownspersonMob victim = TownspersonMob.findByUUID(level, victimId).orElse(null);
                if (victim != null) writeVictimMemory(victim);
            }
            return ids;
        }

        private void writeWitnessMemory(TownspersonMob witness) {
            if (perpetratorId == null) return;
            int initial = initialValueForType(type);
            // Spec line 24: WITNESSED_CRIME_BY polarity is NEGATIVE; we
            // pass a positive magnitude — polarity is handled by the
            // memory producer when routing to mood. The summary surfaces
            // in /npc memory listings.
            String summary = "Saw " + type.name().toLowerCase()
                    + (victimId == null ? "" : " against someone")
                    + " at " + location.getX() + "," + location.getZ();
            witness.getMemory().add(NpcMemory.create(
                    MemoryType.WITNESSED_CRIME_BY,
                    List.of(perpetratorId),
                    occurredTick,
                    initial,
                    summary));
        }

        private void writeVictimMemory(TownspersonMob victim) {
            if (perpetratorId == null) return;
            int initial = Math.max(initialValueForType(type), 70);
            // SERIOUS / CAPITAL crimes pin the victim's memory per spec
            // line 258. NpcMemory.create auto-pins at ≥ 90, so capital
            // crimes pin naturally; SERIOUS we bump to 90 explicitly.
            if (type.severity() == CrimeSeverity.SERIOUS
                    || type.severity() == CrimeSeverity.CAPITAL) {
                initial = Math.max(initial, 90);
            }
            String summary = "Was the victim of " + type.name().toLowerCase();
            victim.getMemory().add(NpcMemory.create(
                    MemoryType.VICTIM_OF_CRIME_BY,
                    List.of(perpetratorId),
                    occurredTick,
                    initial,
                    summary));
        }

        // ── Side effects ──────────────────────────────────────────────────

        private void applySideEffects(CrimeReport report) {
            // Victim → perpetrator -60 NPC↔NPC relationship (spec line 254).
            if (victimId != null && perpetratorId != null) {
                TownspersonMob victim = TownspersonMob.findByUUID(level, victimId).orElse(null);
                if (victim != null) {
                    victim.getNpcRelationships().adjust(perpetratorId, -60,
                            level.getGameTime(), RelationshipOrigin.MET_SOCIALLY);
                }
            }

            // Mood shock: victim + every witness.
            long now = level.getGameTime();
            if (victimId != null) {
                TownspersonMob victim = TownspersonMob.findByUUID(level, victimId).orElse(null);
                if (victim != null) {
                    victim.getMood().applyWithRawMagnitude(MoodTrigger.CRIME_VICTIM,
                            MoodTrigger.CRIME_VICTIM.defaultMagnitude(), now);
                }
            }
            for (UUID witnessId : report.witnessIds()) {
                if (witnessId.equals(victimId)) continue; // already shocked above
                TownspersonMob w = TownspersonMob.findByUUID(level, witnessId).orElse(null);
                if (w != null) {
                    w.getMood().applyWithRawMagnitude(MoodTrigger.CRIME_WITNESSED,
                            MoodTrigger.CRIME_WITNESSED.defaultMagnitude(), now);
                }
            }

            // Reputation drop for player perpetrator (spec line 257).
            if (perpetratorId != null) {
                Village v = VillageSavedData.get(level).getVillageById(villageId).orElse(null);
                if (v != null) {
                    int delta = switch (type.severity()) {
                        case MINOR    -> -5;
                        case MODERATE -> -15;
                        case SERIOUS  -> -30;
                        case CAPITAL  -> -60;
                    };
                    v.modifyReputation(perpetratorId, delta);
                }
            }

            // D2 — faith judgment overlay (on TOP of the crime effects above, not
            // replacing them). When the crime is a taboo of the NPC perpetrator's
            // OWN faith, they feel guilt + co-religionist (or same-value) witnesses
            // judge them. Player perpetrators / atheists / faith-neutral crimes:
            // no faith effect (handled inside FaithJudgment.judge).
            tterrag1112.life_in_the_village.Npc.Religion.FaithConcept sin =
                    tterrag1112.life_in_the_village.Npc.Religion.FaithJudgment.conceptForCrime(type);
            if (sin != null && perpetratorId != null) {
                TownspersonMob perp = TownspersonMob.findByUUID(level, perpetratorId).orElse(null);
                if (perp != null) {
                    List<TownspersonMob> witnesses = new ArrayList<>();
                    for (UUID wid : report.witnessIds()) {
                        TownspersonMob w = TownspersonMob.findByUUID(level, wid).orElse(null);
                        if (w != null) witnesses.add(w);
                    }
                    tterrag1112.life_in_the_village.Npc.Religion.FaithJudgment
                            .judge(perp, sin, witnesses, now);
                }
            }
        }

        /**
         * Severity-driven memory initial value. Memories at or above 90
         * auto-pin so SERIOUS / CAPITAL crimes survive eviction.
         */
        private static int initialValueForType(CrimeType type) {
            return switch (type.severity()) {
                case MINOR    -> 40;
                case MODERATE -> 60;
                case SERIOUS  -> 80;
                case CAPITAL  -> 95;
            };
        }
    }

    /** True if the attacker is currently being attacked (self-defense
     *  exemption per spec line 140). v1 checks whether the attacker has
     *  a recent {@code lastHurtByMob} reference — non-null means the
     *  vanilla LivingEntity tracker still considers them under attack.
     *  Phase 5 may refine with a tick-window check once a public
     *  timestamp accessor is available. */
    public static boolean isSelfDefense(net.minecraft.world.entity.LivingEntity attacker,
                                        long currentTick) {
        if (attacker == null) return false;
        return attacker.getLastHurtByMob() != null;
    }

    /** Convenience: the victim's village (for crimes whose location is
     *  the victim's pos rather than the crime-scene block). */
    public static UUID villageOf(ServerLevel level, BlockPos pos) {
        return VillageSavedData.get(level).getVillageAt(pos)
                .map(Village::getId).orElse(null);
    }

    /** Suppress unused warning on Player import (used by overload below). */
    @SuppressWarnings("unused")
    private static final Player DOC_HOOK = null;
}
