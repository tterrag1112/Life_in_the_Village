package tterrag1112.life_in_the_village.Village.Roster;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
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
 */
public class BuildingRoster {

    private final UUID buildingId;
    private final Identifier rosterDefinitionId;
    private final List<RosterSlot> slots = new ArrayList<>();
    private long lastProductionTick;
    private long lastBreedTick;

    public BuildingRoster(UUID buildingId, Identifier rosterDefinitionId) {
        this.buildingId = buildingId;
        this.rosterDefinitionId = rosterDefinitionId;
    }

    private BuildingRoster(UUID buildingId, Identifier rosterDefinitionId,
                           List<RosterSlot> initial,
                           long lastProductionTick, long lastBreedTick) {
        this(buildingId, rosterDefinitionId);
        if (initial != null) this.slots.addAll(initial);
        this.lastProductionTick = lastProductionTick;
        this.lastBreedTick      = lastBreedTick;
    }

    public UUID buildingId()                 { return buildingId; }
    public Identifier rosterDefinitionId()   { return rosterDefinitionId; }
    public List<RosterSlot> slots()          { return Collections.unmodifiableList(slots); }
    public long lastProductionTick()         { return lastProductionTick; }
    public long lastBreedTick()              { return lastBreedTick; }

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
        if (now - lastProductionTick >= def.productionPeriodTicks()) {
            int adults = countAdults();
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
                if (entity instanceof net.minecraft.world.entity.AgeableMob mob) {
                    if (z.isAdult()) mob.setAge(0); else mob.setAge(-24000);
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

    /** Default: drop the definition's productionOutputs into the sink. */
    protected void onProductionCycle(RosterDefinition def, ProductionOutputSink sink) {
        if (sink == null) return;
        for (var stack : def.productionOutputs()) sink.accept(stack.copy());
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
                    .forGetter(BuildingRoster::lastBreedTick)
    ).apply(i, BuildingRoster::new));
}
