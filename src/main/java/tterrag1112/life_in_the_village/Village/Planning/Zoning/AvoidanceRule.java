// src/main/java/tterrag1112/life_in_the_village/Village/Planning/Zoning/AvoidanceRule.java
package tterrag1112.life_in_the_village.Village.Planning.Zoning;

/**
 * Soft spread constraint. Placing a second instance of the same type
 * within {@code minDistance} of an existing one applies a scoring
 * penalty — it bends placement away from clustering but does not
 * forbid it when no alternative exists.
 *
 * @param sameType    if true, the rule compares against same-type peers
 * @param minDistance distance in blocks below which the penalty applies
 */
public record AvoidanceRule(boolean sameType, int minDistance) {
    public static AvoidanceRule sameTypeMin(int dist) {
        return new AvoidanceRule(true, dist);
    }
}