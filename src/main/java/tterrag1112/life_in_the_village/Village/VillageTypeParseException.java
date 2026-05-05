// FILE: src/main/java/tterrag1112/life_in_the_village/Village/VillageTypeParseException.java
package tterrag1112.life_in_the_village.Village;

/**
 * Thrown by {@code VillageTypeRegistry} when a village type JSON has a
 * malformed kingdom-rework field value (Phase 21 schema). The
 * registry's top-level {@code catch (Exception e)} drops the bad type
 * and logs the exception message, so message strings should include
 * the village type name and the offending field.
 *
 * <p>Phase 21 specifically chose to throw rather than warn-and-default
 * for these fields because their values are load-bearing for kingdom
 * logic — a silently-defaulted {@code settlement_tier} would cause
 * harder-to-diagnose downstream bugs than a missing village type.
 */
public class VillageTypeParseException extends RuntimeException {
    public VillageTypeParseException(String message) {
        super(message);
    }
}
