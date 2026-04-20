// src/main/java/tterrag1112/life_in_the_village/Entities/Party/CombatRole.java
package tterrag1112.life_in_the_village.Guilds.Adventurer;

import net.minecraft.world.item.Items;
import net.minecraft.world.item.Item;

/**
 * Defines the combat specialisation of an adventurer party member.
 *
 * <h3>Role design</h3>
 * Each role has:
 * <ul>
 *   <li>A primary weapon item for equipment setup</li>
 *   <li>A preferred engagement range — used by
 *       {@link PartyRoleGoal} to decide how close to stand</li>
 *   <li>A movement speed modifier relative to base NPC speed</li>
 *   <li>A damage multiplier for combat calculations</li>
 *   <li>A description shown in the party GUI</li>
 * </ul>
 */
public enum CombatRole {

    /**
     * Front-line fighter. High damage, medium range.
     * Equips iron/diamond sword and full plate.
     * Taunts nearby enemies to draw aggro away from squishier members.
     */
    SWORDSMAN(
            Items.IRON_SWORD,
            2,      // engagement range (blocks — stays close)
            1.0f,   // movement speed modifier
            1.2f,   // damage multiplier
            "Frontline fighter. Draws enemy attention.",
            "⚔"
    ),
    SPEARMAN(
            Items.IRON_SPEAR,
            5,
            1.0f,   // movement speed modifier
            1.2f,   // damage multiplier
            "",
            "⚔"
    ),

    /**
     * Ranged damage dealer. Stays 8–14 blocks from target.
     * Equips bow with infinite arrows.
     * Repositions to maintain optimal range.
     */
    ARCHER(
            Items.BOW,
            10,
            1.1f,
            1.0f,
            "Ranged attacker. Keeps distance from enemies.",
            "🏹"
    ),

    /**
     * Area-effect damage. Fragile but powerful.
     * Uses splash potions of harming.
     * Stays at medium range, retreats when hit.
     */
    MAGE(
            Items.BLAZE_ROD,  // visual stand-in for staff
            6,
            0.9f,
            1.5f,
            "Area damage. Fragile but devastating.",
            "✦"
    ),

    /**
     * Support role. Periodically heals nearby party members.
     * Uses splash potions of healing.
     * Prioritises staying alive over dealing damage.
     */
    HEALER(
            Items.POTION,
            4,
            0.95f,
            0.5f,
            "Heals party members. Avoids direct combat.",
            "+"
    ),

    /**
     * Mobility specialist. Fast, detects mobs early.
     * Uses stone axe (fast light weapon).
     * Scouts ahead and alerts party to danger.
     */
    SCOUT(
            Items.STONE_AXE,
            3,
            1.3f,
            0.8f,
            "Fast and perceptive. Detects threats early.",
            "◈"
    ),

    ;

    public final Item  primaryWeapon;
    /** Preferred blocks from target before engaging. */
    public final int   engagementRange;
    public final float speedModifier;
    public final float damageModifier;
    public final String description;
    /** Short symbol shown in the party GUI member list. */
    public final String symbol;

    CombatRole(Item primaryWeapon, int engagementRange,
               float speedModifier, float damageModifier,
               String description, String symbol) {
        this.primaryWeapon    = primaryWeapon;
        this.engagementRange  = engagementRange;
        this.speedModifier    = speedModifier;
        this.damageModifier   = damageModifier;
        this.description      = description;
        this.symbol           = symbol;
    }

    public String getDisplayName() {
        return name().charAt(0) + name().substring(1).toLowerCase();
    }

    /**
     * Returns the armour tier appropriate for this role based on
     * whether the party has completed elite quests.
     */
    public boolean prefersHeavyArmour() {
        return this == SWORDSMAN;
    }

    public boolean prefersLightArmour() {
        return this == SCOUT || this == ARCHER;
    }

    public boolean isRanged() {
        return this == ARCHER || this == MAGE;
    }
}