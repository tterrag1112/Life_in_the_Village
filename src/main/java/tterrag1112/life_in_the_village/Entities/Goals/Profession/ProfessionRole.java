package tterrag1112.life_in_the_village.Profession.Roles;

/**
 * Marker interface implemented by all profession role enums.
 *
 * <p>Each profession's roles are defined in their own enum
 * (e.g. {@code FarmRole}, {@code WorkshopRole}) which implements
 * this interface. {@link ProfessionRoleManager} stores and retrieves
 * roles using a single PersistentData key — no per-profession NBT
 * keys needed.</p>
 *
 * <h3>Implementing a new role enum</h3>
 * <pre>
 * public enum MyRole implements ProfessionRole {
 *     GENERALIST, SPECIALIST, MARKET_SELLER;
 *
 *     static {
 *         ProfessionRoleManager.register("MyRole", MyRole::valueOf);
 *     }
 *
 *     public boolean isGeneralist()   { return this == GENERALIST; }
 *     public boolean isMarketSeller() { return this == MARKET_SELLER; }
 *     public boolean splitsTime()     { return this == GENERALIST; }
 * }
 * </pre>
 */
public interface ProfessionRole {

    /** Enum constant name — provided automatically by all enums. */
    String name();

    /**
     * True if this role performs all tasks for their profession.
     * Generalists split time between production and selling.
     */
    boolean isGeneralist();

    /**
     * True if this role is dedicated to manning a market stall.
     * Market sellers never use the workstation goal.
     */
    boolean isMarketSeller();

    /**
     * True if this role splits work and sell time rather than being
     * fully dedicated to one or the other.
     * Generalists return true; all specialised roles return false.
     */
    boolean splitsTime();

    /**
     * True if this NPC is in the learner/apprentice tier for their profession.
     * Apprentices work at reduced speed and are candidates for promotion when
     * a senior worker retires or vacates their slot.
     *
     * <p>Defaults to {@code false} so existing role enums that predate this
     * method compile without changes until they are individually updated.</p>
     */
    default boolean isApprentice() { return false; }
}