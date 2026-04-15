// src/main/java/tterrag1112/life_in_the_village/Village/Planning/Zoning/AnchorPolicy.java
package tterrag1112.life_in_the_village.Village.Planning.Zoning;

/**
 * How strictly a building type must be placed.
 *
 * <ul>
 *   <li>{@code CORE} — participates in matcher pass 1. The building's
 *       {@code minCount} must be met; failure is logged loudly and
 *       indicates a content bug (e.g. a docks-requiring building in
 *       a layout with no water).</li>
 *   <li>{@code FILLER} — matcher pass 2 only. Silently declines if no
 *       matching slot is available. Used for houses, decorative wells,
 *       and other "fill remaining space" types.</li>
 * </ul>
 */
public enum AnchorPolicy { CORE, FILLER }