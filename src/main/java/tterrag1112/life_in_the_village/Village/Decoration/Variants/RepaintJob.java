package tterrag1112.life_in_the_village.Village.Decoration.Variants;

import net.minecraft.world.item.DyeColor;
import org.jetbrains.annotations.Nullable;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Doc 15 §"Player-requested repaint" — pending or in-progress
 * repaint state attached to a builder NPC.
 *
 * <p>Stored as plain primitive fields on {@link
 * tterrag1112.life_in_the_village.Entities.custom.TownspersonMob}'s
 * NBT (via {@code addAdditionalSaveData} / {@code
 * readAdditionalSaveData}) so the job survives save/load. The
 * builder's {@link
 * tterrag1112.life_in_the_village.Entities.Goals.Profession.Builder
 * .BuilderRepaintGoal} reads + mutates the same record while it
 * works through visits.</p>
 */
public final class RepaintJob {

    /**
     * Doc 15 §"Player-requested repaint" — state machine values.
     */
    public enum State { PLANNED, EN_ROUTE, PAINTING, COMPLETE }

    private final UUID buildingId;
    private final UUID requesterId;
    private final Set<ColorSlot> slots;
    @Nullable private final DyeColor primary;
    @Nullable private final DyeColor accent;
    @Nullable private final DyeColor roof;

    private State state;
    /** Number of in-game days the goal has visited. Doc 15: one
     *  visit per day. */
    private int visitsCompleted;
    /** Server tick at which the next visit is allowed. {@code 0L}
     *  means "as soon as possible" (e.g. fresh job). */
    private long nextVisitTick;

    public RepaintJob(UUID buildingId, UUID requesterId,
                      Set<ColorSlot> slots,
                      @Nullable DyeColor primary,
                      @Nullable DyeColor accent,
                      @Nullable DyeColor roof,
                      State state, int visitsCompleted, long nextVisitTick) {
        this.buildingId = buildingId;
        this.requesterId = requesterId;
        this.slots = slots == null ? EnumSet.noneOf(ColorSlot.class)
                : EnumSet.copyOf(slots);
        this.primary = primary;
        this.accent = accent;
        this.roof = roof;
        this.state = state == null ? State.PLANNED : state;
        this.visitsCompleted = Math.max(0, visitsCompleted);
        this.nextVisitTick = nextVisitTick;
    }

    public static RepaintJob fresh(UUID buildingId, UUID requesterId,
                                   Set<ColorSlot> slots,
                                   @Nullable DyeColor primary,
                                   @Nullable DyeColor accent,
                                   @Nullable DyeColor roof) {
        return new RepaintJob(buildingId, requesterId, slots,
                primary, accent, roof,
                State.PLANNED, 0, 0L);
    }

    // ── Accessors ─────────────────────────────────────────────────────────

    public UUID buildingId() { return buildingId; }
    public UUID requesterId() { return requesterId; }
    public Set<ColorSlot> slots() { return slots; }
    @Nullable public DyeColor primary() { return primary; }
    @Nullable public DyeColor accent()  { return accent; }
    @Nullable public DyeColor roof()    { return roof; }

    public State state() { return state; }
    public int visitsCompleted() { return visitsCompleted; }
    public long nextVisitTick() { return nextVisitTick; }

    public void setState(State s) { this.state = s; }
    public void incrementVisits() { this.visitsCompleted++; }
    public void setNextVisitTick(long t) { this.nextVisitTick = t; }

    /**
     * Doc 15: "1 visit per in-game day, footprint / 20 blocks
     * repainted per visit (rounded up). A 9×7 = 63-block house takes
     * ceil(63/20) = 4 visits."
     */
    public int totalVisits(int footprintArea) {
        return Math.max(1, (int) Math.ceil(footprintArea / 20.0));
    }

    public boolean isComplete(int footprintArea) {
        return state == State.COMPLETE
                || visitsCompleted >= totalVisits(footprintArea);
    }

    @Nullable public DyeColor colorFor(ColorSlot slot) {
        return switch (slot) {
            case PRIMARY -> primary;
            case ACCENT  -> accent;
            case ROOF    -> roof;
        };
    }

    // ── Persistence helpers (NBT key prefix) ──────────────────────────────

    /** NBT key prefix for repaint-job fields under TownspersonMob. */
    public static final String NBT_PREFIX = "repaint.";

    public static String slotsCsv(Set<ColorSlot> slots) {
        if (slots == null || slots.isEmpty()) return "";
        StringBuilder b = new StringBuilder();
        for (ColorSlot s : slots) {
            if (b.length() > 0) b.append(',');
            b.append(s.name());
        }
        return b.toString();
    }

    public static Set<ColorSlot> parseSlotsCsv(String csv) {
        if (csv == null || csv.isEmpty()) return EnumSet.noneOf(ColorSlot.class);
        Set<ColorSlot> out = EnumSet.noneOf(ColorSlot.class);
        for (String s : csv.split(",")) {
            try { out.add(ColorSlot.valueOf(s.trim().toUpperCase(Locale.ROOT))); }
            catch (IllegalArgumentException ignored) {}
        }
        return out;
    }
}
