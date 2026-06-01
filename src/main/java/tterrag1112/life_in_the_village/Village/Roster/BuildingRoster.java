package tterrag1112.life_in_the_village.Village.Roster;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.World.WeatherContext;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Phase 6.3.3.d — building-fixed roster of {@link RosterSlot}s.
 *
 * <p>Mirrors {@code TravellingGroup}'s hook shape but for a fixed
 * building position. The roster ticks even when chunks are unloaded
 * (production / breeding advance via the simulated path); when chunks
 * load, {@link #realizeNear} converts simulated slots into world
 * entities; when chunks unload, {@link #derealizeAll} converts back.
 *
 * <h3>Lifecycle hooks</h3>
 * <p>Overrideable for content-pass customization without subclassing
 * via composition. Default behavior in 6.3.3.d:
 * <ul>
 *   <li>{@link #onProductionCycle} drops the definition's
 *       productionOutputs into the sink per adult</li>
 *   <li>{@link #onBreed} / {@link #onSlaughter} default no-op beyond
 *       slot mutation</li>
 *   <li>{@link #onRealizeSlot} / {@link #onDerealizeSlot} default no-op
 *       — concrete RosterDefinition consumers override for breed-state
 *       sync, name preservation, etc.</li>
 * </ul>
 *
 * <h3>Phase 6.3.3.j / k extensions (full state model)</h3>
 * <ul>
 *   <li>{@code boundPlotId} (j.3) — optional FarmPlot or AdjunctPlot
 *       UUID; {@link #resolveRealizationAnchor} uses it to spawn
 *       livestock at the bound pen during good weather and at the
 *       building center during storms ({@link WeatherContext#isStorm}).</li>
 *   <li>{@code hazardCounter} (k.5) — predator-pressure counter (0–10)
 *       bumped by {@code PredatorScanBehavior} and consumed by the
 *       simulated-mode attrition roll in {@link #tick}. Decays one
 *       point per cycle so a single wolf sighting fades naturally.
 *       Realized animal deaths are handled out-of-band by
 *       {@code RosterChunkLoadHandler}'s entity-death bridge (k.4).</li>
 *   <li>{@code diseaseLevel} (k.6) — herd disease severity (0–10).
 *       Risk factors compose daily (overcrowding, hazard pressure,
 *       biome-temperature discomfort); production scales by
 *       (1 - 0.1 × level); slot mortality fires at level ≥ 7. HEALER
 *       and FARMER tending paths both reduce the level.</li>
 * </ul>
 *
 * <p>{@link #onProductionCycle} now scales item counts by health
 * (1 - diseaseLevel × DISEASE_OUTPUT_PENALTY_PER_LEVEL); subclasses
 * overriding the hook should respect that semantics or apply their
 * own health-aware scaling.</p>
 *
 * <h3>Phase 6.7.1.5 — chunk-aware production gate</h3>
 * <p>For species in {@link #GATED_SPECIES}, the per-cycle production
 * loop only fires for {@code Simulated} slots. {@code Realized} slots
 * are expected to have their production driven by an NPC behavior
 * that physically interacts with the entity (e.g., SHEPHERD shearing
 * a sheep, BEEKEEPER harvesting honeycomb), so summing both paths
 * would double-count. Non-gated species ({@code CHICKEN}, {@code PIG},
 * {@code COW}) retain the legacy "every adult produces" semantics
 * until their commercial behaviors land — at which point they should
 * be added to the gated set.</p>
 */
public class BuildingRoster {

    /**
     * Phase 6.7.1.5 — species whose Realized slots are EXCLUDED from
     * the simulation-cycle production count. A commercial NPC behavior
     * is expected to drive production for realized members of these
     * species (SHEPHERD for sheep in 6.7.2, BEEKEEPER for bees in
     * 6.7.3). Other species keep the legacy slot-type-agnostic path
     * until they get their own commercial behavior.
     */
    private static final Set<Identifier> GATED_SPECIES = Set.of(
            AnimalRosterDefinitions.SHEEP,
            AnimalRosterDefinitions.BEE);

    private final UUID buildingId;
    private final Identifier rosterDefinitionId;
    private final List<RosterSlot> slots = new ArrayList<>();
    private long lastProductionTick;
    private long lastBreedTick;
    /** Phase 6.3.3.j.3 — optional bound plot the roster's livestock
     *  should appear near when realized. Spec-named "adjunctPlotId"
     *  but holds either a FarmPlot or AdjunctPlot UUID since both
     *  are physically pens / homestead facilities. {@code null} means
     *  no binding — realize at the building center (legacy). */
    private UUID boundPlotId;
    /** Phase 6.3.3.k.5 — predator-pressure counter. Incremented by
     *  PredatorScanBehavior when wolves / foxes are detected near
     *  this building's animal-keeping rosters. Decays a tick per
     *  roster cycle (per 200t) when not refreshed; drives the
     *  simulated-mode attrition roll in {@link #tick}. Capped at
     *  {@link #HAZARD_COUNTER_MAX} so a long siege doesn't infinitely
     *  inflate the kill chance. */
    private int hazardCounter;

    /** Hard cap on {@link #hazardCounter}. */
    public static final int HAZARD_COUNTER_MAX = 10;
    /** Per-roster-cycle attrition chance multiplier per hazard point.
     *  At hazardCounter == 10 this gives a 50% chance per cycle per
     *  simulated adult animal, which is severe enough to register
     *  but not instantly catastrophic on a 200t cadence. */
    public static final float HAZARD_ATTRITION_PER_POINT = 0.05f;

    /** Phase 6.3.3.k.6 — animal disease severity (0 = healthy,
     *  10 = epidemic). Drives production scaling and the mortality
     *  roll above {@link #DISEASE_MORTALITY_THRESHOLD}. */
    private int diseaseLevel;
    public static final int DISEASE_LEVEL_MAX = 10;
    /** Production output scales by (1 - diseaseLevel / DISEASE_LEVEL_MAX). */
    public static final float DISEASE_OUTPUT_PENALTY_PER_LEVEL = 0.1f;
    /** Disease level at and above which the per-cycle mortality roll fires. */
    public static final int DISEASE_MORTALITY_THRESHOLD = 7;
    /** Per-cycle chance of one animal death when diseaseLevel ≥ threshold. */
    public static final float DISEASE_MORTALITY_CHANCE = 0.10f;
    /** Population fraction at-and-above which overcrowding raises disease risk. */
    public static final float OVERCROWDING_FRACTION = 0.80f;
    /** Daily disease-risk increments per factor. */
    public static final float DISEASE_BASE_DAILY_RISK   = 0.005f;
    public static final float DISEASE_OVERCROWD_ADD     = 0.010f;
    public static final float DISEASE_HAZARD_ADD_PER_PT = 0.002f;
    public static final float DISEASE_TEMP_DISCOMFORT_ADD = 0.010f;
    /** Biome temp range outside which animals are uncomfortable. */
    public static final float TEMP_COMFORT_MIN = 0.10f;
    public static final float TEMP_COMFORT_MAX = 1.20f;

    public BuildingRoster(UUID buildingId, Identifier rosterDefinitionId) {
        this.buildingId = buildingId;
        this.rosterDefinitionId = rosterDefinitionId;
    }

    private BuildingRoster(UUID buildingId, Identifier rosterDefinitionId,
                           List<RosterSlot> initial,
                           long lastProductionTick, long lastBreedTick,
                           Optional<UUID> boundPlotId,
                           int hazardCounter, int diseaseLevel) {
        this(buildingId, rosterDefinitionId);
        if (initial != null) this.slots.addAll(initial);
        this.lastProductionTick = lastProductionTick;
        this.lastBreedTick      = lastBreedTick;
        this.boundPlotId        = boundPlotId.orElse(null);
        this.hazardCounter      = Math.max(0, Math.min(HAZARD_COUNTER_MAX, hazardCounter));
        this.diseaseLevel       = Math.max(0, Math.min(DISEASE_LEVEL_MAX, diseaseLevel));
    }

    public UUID buildingId()                 { return buildingId; }
    public Identifier rosterDefinitionId()   { return rosterDefinitionId; }
    public List<RosterSlot> slots()          { return Collections.unmodifiableList(slots); }
    public long lastProductionTick()         { return lastProductionTick; }
    public long lastBreedTick()              { return lastBreedTick; }
    public Optional<UUID> boundPlotId()      { return Optional.ofNullable(boundPlotId); }
    public int hazardCounter()               { return hazardCounter; }
    public int diseaseLevel()                { return diseaseLevel; }

    /** Phase 6.3.3.j.3 — bind the roster to a specific plot. Set by
     *  {@code PastureRotation.chooseAndBindActivePen} on rotation;
     *  {@code null} clears the binding. */
    public void bindToPlot(UUID plotId)      { this.boundPlotId = plotId; }
    public void clearPlotBinding()           { this.boundPlotId = null; }

    /** Phase 6.3.3.k.5 — bumps the hazard counter (capped). Called by
     *  PredatorScanBehavior when a wolf / fox is detected within
     *  range of the building. */
    public void recordHazard() {
        if (hazardCounter < HAZARD_COUNTER_MAX) hazardCounter++;
    }

    /** Phase 6.3.3.k.6 — explicit disease change (HEALER visit /
     *  FARMER passive recovery). Clamps to [0, DISEASE_LEVEL_MAX]. */
    public void adjustDiseaseLevel(int delta) {
        diseaseLevel = Math.max(0, Math.min(DISEASE_LEVEL_MAX, diseaseLevel + delta));
    }

    /** Resolves the definition from {@link RosterRegistry}. */
    public Optional<RosterDefinition> definition() {
        return RosterRegistry.get(rosterDefinitionId);
    }

    // ── Population queries ────────────────────────────────────────────

    public int countAdults() {
        int n = 0;
        for (RosterSlot s : slots) if (s.isAdult()) n++;
        return n;
    }

    /**
     * Phase 6.7.1.5 — adult count restricted to {@code Simulated}
     * slots. Used by the production cycle for gated species so
     * realized animals (driven by commercial behaviors) don't
     * double-count against simulation-cycle output.
     */
    public int countSimulatedAdults() {
        int n = 0;
        for (RosterSlot s : slots) {
            if (s.isAdult() && s instanceof RosterSlot.Simulated) n++;
        }
        return n;
    }

    /** Phase 6.7.1.5 — true iff this roster's species opts into the
     *  simulation/realization mutual exclusion. */
    public boolean isProductionGated() {
        return GATED_SPECIES.contains(rosterDefinitionId);
    }

    public int countBabies() {
        int n = 0;
        for (RosterSlot s : slots) if (!s.isAdult()) n++;
        return n;
    }

    public boolean hasCapacity() {
        return definition().map(d -> slots.size() < d.maxPopulation()).orElse(false);
    }

    // ── Mutation ──────────────────────────────────────────────────────

    /** Adds a simulated slot (initial seeding or breeding result). */
    public void addSimulated(boolean isAdult, long birthTick) {
        slots.add(new RosterSlot.Simulated(isAdult, birthTick));
    }

    /** Removes the slot at {@code index}; used by slaughter. */
    public RosterSlot removeAt(int index) {
        if (index < 0 || index >= slots.size()) return null;
        return slots.remove(index);
    }

    /** Replaces a slot in-place (e.g. when growing baby → adult). */
    public void replaceAt(int index, RosterSlot newSlot) {
        if (index < 0 || index >= slots.size() || newSlot == null) return;
        slots.set(index, newSlot);
    }

    /**
     * Phase 6.3.3.k.4 — removes the realized slot whose entity UUID
     * matches {@code uuid}. Returns true when a removal happened.
     * Used by the entity-death bridge so a wolf-killed animal stops
     * being counted in production / breeding cycles instead of
     * leaving the roster holding a ghost slot pointing at a dead
     * entity. No-op when the slot has already been derealized — see
     * the spec note on event ordering.
     */
    public boolean removeRealizedByUuid(UUID uuid) {
        if (uuid == null) return false;
        for (int i = 0; i < slots.size(); i++) {
            if (slots.get(i) instanceof RosterSlot.Realized r
                    && uuid.equals(r.entityUuid())) {
                slots.remove(i);
                return true;
            }
        }
        return false;
    }

    // ── Tick ──────────────────────────────────────────────────────────

    /**
     * Advances roster state: growth check, production cycle, optional
     * breeding. Should fire on the host building's tick path.
     */
    public void tick(ServerLevel level, ProductionOutputSink sink) {
        RosterDefinition def = definition().orElse(null);
        if (def == null) return;
        long now = level.getGameTime();

        // Growth: baby → adult after growthPeriodTicks.
        if (def.growthPeriodTicks() > 0L) {
            for (int i = 0; i < slots.size(); i++) {
                RosterSlot s = slots.get(i);
                if (s.isAdult()) continue;
                if (now - s.birthTick() < def.growthPeriodTicks()) continue;
                slots.set(i, switch (s) {
                    case RosterSlot.Realized r -> new RosterSlot.Realized(r.entityUuid(), true, r.birthTick());
                    case RosterSlot.Simulated z -> new RosterSlot.Simulated(true, z.birthTick());
                });
            }
        }

        // Production cycle.
        // Phase 6.7.1.5 — gated species (SHEEP, BEE) only count Simulated
        // adults; realized members are expected to produce via a
        // commercial NPC behavior (SHEPHERD in 6.7.2, BEEKEEPER in
        // 6.7.3). lastProductionTick still advances on the cycle
        // boundary so all-realized rosters don't accumulate a debt that
        // would dump on the next derealize.
        if (now - lastProductionTick >= def.productionPeriodTicks()) {
            int adults = isProductionGated() ? countSimulatedAdults() : countAdults();
            for (int i = 0; i < adults; i++) onProductionCycle(def, sink);
            lastProductionTick = now;
        }

        // Breeding.
        def.breedingRule().ifPresent(rule -> {
            if (!hasCapacity()) return;
            if (countAdults() < rule.minAdultPairs()) return;
            if (now - lastBreedTick < rule.cooldownTicks()) return;
            addSimulated(false, now);
            onBreed(slots.get(slots.size() - 1));
            lastBreedTick = now;
        });

        // Phase 6.3.3.k.5 — predator attrition (simulated-mode).
        // When the building has accumulated predator pressure and at
        // least one slot, roll for a single-animal loss. Realized
        // animals don't go through this path — the entity-death
        // bridge in RosterChunkLoadHandler handles real predator
        // kills. Each tick also bleeds 1 hazard point so a single
        // wolf sighting fades naturally over time.
        if (hazardCounter > 0) {
            if (!slots.isEmpty()) {
                var rng = new java.util.Random(now ^ buildingId.getMostSignificantBits());
                float lossChance = HAZARD_ATTRITION_PER_POINT * hazardCounter;
                if (rng.nextFloat() < lossChance) {
                    // Prefer simulated slots — realized animals are
                    // handled by the death bridge.
                    int targetIdx = -1;
                    for (int i = 0; i < slots.size(); i++) {
                        if (slots.get(i) instanceof RosterSlot.Simulated) {
                            targetIdx = i;
                            break;
                        }
                    }
                    if (targetIdx >= 0) {
                        removeAt(targetIdx);
                    }
                }
            }
            hazardCounter--;
        }

        // Phase 6.3.3.k.6 — disease risk + mortality.
        // Risk-roll is daily-paced even though tick() fires every
        // 200t — gate the roll behind a tick boundary so the per-cycle
        // probability matches the spec's "daily" calibration.
        if (now % 24000L < 200L) {
            tickDiseaseRisk(def, level, now);
        }
        if (diseaseLevel >= DISEASE_MORTALITY_THRESHOLD && !slots.isEmpty()) {
            var rng = new java.util.Random(now ^ (buildingId.getLeastSignificantBits() << 1));
            if (rng.nextFloat() < DISEASE_MORTALITY_CHANCE) {
                int targetIdx = -1;
                for (int i = 0; i < slots.size(); i++) {
                    if (slots.get(i) instanceof RosterSlot.Simulated) {
                        targetIdx = i;
                        break;
                    }
                }
                if (targetIdx >= 0) removeAt(targetIdx);
            }
        }
    }

    /**
     * Phase 6.3.3.k.6 — daily disease onset / progression roll.
     * Risk factors:
     * <ul>
     *   <li>Base 0.5%/day baseline</li>
     *   <li>+1.0% when population ≥ 80% of maxPopulation (overcrowding)</li>
     *   <li>+0.2% per hazard point (predator stress)</li>
     *   <li>+1.0% when biome temperature at the building is outside
     *       the comfort range</li>
     * </ul>
     * A successful roll increments diseaseLevel by 1.
     */
    private void tickDiseaseRisk(RosterDefinition def, ServerLevel level, long now) {
        if (diseaseLevel >= DISEASE_LEVEL_MAX) return;
        float risk = DISEASE_BASE_DAILY_RISK;
        if (def.maxPopulation() > 0
                && slots.size() >= OVERCROWDING_FRACTION * def.maxPopulation()) {
            risk += DISEASE_OVERCROWD_ADD;
        }
        if (hazardCounter > 0) {
            risk += DISEASE_HAZARD_ADD_PER_PT * hazardCounter;
        }
        var anchorPos = VillageSavedData.get(level)
                .getBuildingById(buildingId)
                .map(b -> b.getShape().getOrigin())
                .orElse(null);
        if (anchorPos != null) {
            float temp = level.getBiome(anchorPos).value().getBaseTemperature();
            if (temp < TEMP_COMFORT_MIN || temp > TEMP_COMFORT_MAX) {
                risk += DISEASE_TEMP_DISCOMFORT_ADD;
            }
        }
        var rng = new java.util.Random(now ^ buildingId.getMostSignificantBits() ^ 0xD15EA5EL);
        if (rng.nextFloat() < risk) diseaseLevel++;
    }

    /**
     * Phase 6.3.3.j.3 — picks the realize anchor with storm override.
     *
     * <ul>
     *   <li>If a plot binding exists AND the plot resolves AND
     *       {@code !WeatherContext.isStorm(level)} → returns the
     *       bound plot's origin so livestock appear at the pen.</li>
     *   <li>During a storm OR no binding OR the bound plot is missing
     *       → returns {@code fallback} (the building center; livestock
     *       retreat to the safe anchor).</li>
     * </ul>
     *
     * <p>Lookup uses the FarmPlot index — ANIMAL_PEN FarmPlots are what
     * {@code PastureRotation} binds. (Stage 2.5 removed the AdjunctPlot
     * fallback with the adjunct system; {@code boundPlotId} now only ever
     * binds a FarmPlot.)</p>
     */
    public BlockPos resolveRealizationAnchor(ServerLevel level, BlockPos fallback) {
        if (level == null || fallback == null) return fallback;
        if (boundPlotId == null) return fallback;
        if (WeatherContext.isStorm(level)) return fallback;
        VillageSavedData data = VillageSavedData.get(level);
        BlockPos origin = data.getFarmPlotById(boundPlotId)
                .map(p -> p.getOrigin())
                .orElse(null);
        return origin != null ? origin : fallback;
    }

    // ── Realize / derealize ───────────────────────────────────────────

    /**
     * Converts all {@link RosterSlot.Simulated} slots into realized
     * entities spawned near {@code anchor}. No-op for already-realized
     * slots.
     */
    public void realizeNear(ServerLevel level, BlockPos anchor) {
        RosterDefinition def = definition().orElse(null);
        if (def == null || anchor == null) return;
        for (int i = 0; i < slots.size(); i++) {
            RosterSlot s = slots.get(i);
            if (s instanceof RosterSlot.Simulated z) {
                var entity = def.entityType().create(level,
                        net.minecraft.world.entity.EntitySpawnReason.NATURAL);
                if (entity == null) continue;
                entity.setPos(anchor.getX() + 0.5, anchor.getY() + 1.0, anchor.getZ() + 0.5);
                // Phase 6.7.2.3 — invoke vanilla finalizeSpawn so
                // species-specific spawn-time initialization runs.
                // For SheepEntity this picks wool DyeColor from the
                // biome's color table (1.21.5+ mechanic); for other
                // mobs it's the standard finalize hook (no-op or
                // species-specific). Matches the established pattern
                // in VillageInhabitantPopulator / KingdomOfficeBootstrap
                // / ChildBirthBehavior.
                if (entity instanceof net.minecraft.world.entity.Mob mob) {
                    mob.finalizeSpawn(level,
                            level.getCurrentDifficultyAt(anchor),
                            net.minecraft.world.entity.EntitySpawnReason.NATURAL, null);
                }
                if (entity instanceof net.minecraft.world.entity.AgeableMob mob) {
                    if (z.isAdult()) mob.setAge(0); else mob.setAge(-24000);
                }
                // Phase 6.3.3.g.1 — flag as persistent so the entity
                // survives chunk unload / reload cycles without
                // despawning. Roster slot is the source of truth for
                // population; entity death triggers slot removal.
                if (entity instanceof net.minecraft.world.entity.Mob m) {
                    m.setPersistenceRequired();
                }
                level.addFreshEntity(entity);
                RosterSlot.Realized realized = new RosterSlot.Realized(
                        entity.getUUID(), z.isAdult(), z.birthTick());
                slots.set(i, realized);
                onRealizeSlot(realized, entity);
            }
        }
    }

    /**
     * Converts all {@link RosterSlot.Realized} slots back to simulated,
     * despawning the world entities. No-op for already-simulated slots.
     */
    public void derealizeAll(ServerLevel level) {
        if (level == null) return;
        for (int i = 0; i < slots.size(); i++) {
            RosterSlot s = slots.get(i);
            if (s instanceof RosterSlot.Realized r) {
                var entity = level.getEntity(r.entityUuid());
                if (entity instanceof net.minecraft.world.entity.LivingEntity le) {
                    onDerealizeSlot(le, r);
                    le.discard();
                }
                slots.set(i, new RosterSlot.Simulated(r.isAdult(), r.birthTick()));
            }
        }
    }

    // ── Hooks (overrideable) ──────────────────────────────────────────

    /** Default: drop the definition's productionOutputs into the sink.
     *  Phase 6.3.3.k.6: scales counts by health (1 - diseaseLevel/MAX);
     *  a fully diseased herd produces nothing, a level-5 herd produces
     *  half. Items with count 1 may drop to 0; that's the intended
     *  feel of sick animals not laying / shedding. */
    protected void onProductionCycle(RosterDefinition def, ProductionOutputSink sink) {
        if (sink == null) return;
        float healthMult = 1.0f
                - DISEASE_OUTPUT_PENALTY_PER_LEVEL * diseaseLevel;
        if (healthMult <= 0f) return;
        for (var stack : def.productionOutputs()) {
            var copy = stack.copy();
            int scaled = Math.round(copy.getCount() * healthMult);
            if (scaled <= 0) continue;
            copy.setCount(scaled);
            sink.accept(copy);
        }
    }

    protected void onBreed(RosterSlot addedSlot) { /* override */ }

    protected void onSlaughter(RosterSlot removedSlot, ProductionOutputSink sink) {
        if (sink == null) return;
        definition().ifPresent(d -> {
            for (var stack : d.slaughterOutputs()) sink.accept(stack.copy());
        });
    }

    protected void onRealizeSlot(RosterSlot.Realized slot,
                                 net.minecraft.world.entity.Entity entity) { /* override */ }

    protected void onDerealizeSlot(net.minecraft.world.entity.LivingEntity entity,
                                   RosterSlot.Realized slot) { /* override */ }

    // ── Codec ─────────────────────────────────────────────────────────

    public static final Codec<BuildingRoster> CODEC = RecordCodecBuilder.create(i -> i.group(
            UUIDUtil.CODEC.fieldOf("buildingId").forGetter(BuildingRoster::buildingId),
            Identifier.CODEC.fieldOf("rosterDefinitionId").forGetter(BuildingRoster::rosterDefinitionId),
            RosterSlot.CODEC.listOf().optionalFieldOf("slots", List.of())
                    .forGetter(b -> List.copyOf(b.slots)),
            Codec.LONG.optionalFieldOf("lastProductionTick", 0L)
                    .forGetter(BuildingRoster::lastProductionTick),
            Codec.LONG.optionalFieldOf("lastBreedTick", 0L)
                    .forGetter(BuildingRoster::lastBreedTick),
            // Phase 6.3.3.j.3 — optional bound plot. optionalFieldOf
            // with default Optional.empty() so existing saves load
            // cleanly without the field and round-trip without it
            // until a binding is set.
            UUIDUtil.CODEC.optionalFieldOf("boundPlotId")
                    .forGetter(BuildingRoster::boundPlotId),
            // Phase 6.3.3.k.5 — predator-pressure counter. Pre-k
            // rosters load as 0; the predator scan re-populates as
            // wolves / foxes are observed.
            Codec.INT.optionalFieldOf("hazardCounter", 0)
                    .forGetter(BuildingRoster::hazardCounter),
            // Phase 6.3.3.k.6 — animal disease severity. Pre-k
            // rosters load as 0 (healthy); risk roll re-populates
            // over time when overcrowding / hazard / temperature
            // factors apply.
            Codec.INT.optionalFieldOf("diseaseLevel", 0)
                    .forGetter(BuildingRoster::diseaseLevel)
    ).apply(i, BuildingRoster::new));
}
