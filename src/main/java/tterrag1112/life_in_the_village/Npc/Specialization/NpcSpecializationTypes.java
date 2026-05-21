package tterrag1112.life_in_the_village.Npc.Specialization;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import tterrag1112.life_in_the_village.Life_in_the_village;
import tterrag1112.life_in_the_village.Npc.Skills.Skill;
import tterrag1112.life_in_the_village.Npc.Skills.SkillRequirement;
import tterrag1112.life_in_the_village.Npc.Skills.SkillThresholds;
import tterrag1112.life_in_the_village.Profession.Profession;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Phase 6.3.2.c — built-in specialization registry. Mirrors the
 * {@link tterrag1112.life_in_the_village.Npc.Roles.NpcRoleTypes}
 * pattern: static-init registration, ResourceLocation keys, lookup
 * by id for codec round-trips.
 *
 * <p>Two specialization families ship in 6.3.2.c:
 * <ul>
 *   <li>BLACKSMITH ({@code lit:blacksmith/...}) — generalist +
 *       toolsmith / armorer / weaponsmith. Bias-not-gate (see
 *       BlacksmithProductionBehavior).</li>
 *   <li>ADVENTURER ({@code lit:adventurer/...}) — rookie + 6 combat
 *       roles. Gate-style (you ARE a swordsman / archer / ...).</li>
 * </ul>
 *
 * <p>Skill-threshold defaults: {@link SkillThresholds#APPRENTICE_MILESTONE_FOUNDATION}
 * (20) — "you've done enough work to claim you specialize in this".
 */
public final class NpcSpecializationTypes {

    private NpcSpecializationTypes() {}

    private static final Map<Identifier, SpecializationDef> BY_ID = new LinkedHashMap<>();
    private static final Map<Profession, List<SpecializationDef>> BY_PROFESSION = new LinkedHashMap<>();

    private static SpecializationDef register(SpecializationDef def) {
        BY_ID.put(def.name(), def);
        BY_PROFESSION.computeIfAbsent(def.profession(), p -> new java.util.ArrayList<>())
                .add(def);
        return def;
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(Life_in_the_village.MODID, path);
    }

    private static final int FOUNDATION = SkillThresholds.APPRENTICE_MILESTONE_FOUNDATION;

    // ── Blacksmith family ─────────────────────────────────────────────────
    public static final SpecializationDef BLACKSMITH_GENERALIST = register(new SpecializationDef(
            id("blacksmith/generalist"), Profession.BLACKSMITH,
            Component.literal("Generalist Smith"),
            List.of(), true, SpecializationData.BlacksmithData.INSTANCE));

    public static final SpecializationDef BLACKSMITH_TOOLSMITH = register(new SpecializationDef(
            id("blacksmith/toolsmith"), Profession.BLACKSMITH,
            Component.literal("Toolsmith"),
            List.of(new SkillRequirement(Skill.TOOLSMITHING, FOUNDATION)),
            false, SpecializationData.BlacksmithData.INSTANCE));

    public static final SpecializationDef BLACKSMITH_ARMORER = register(new SpecializationDef(
            id("blacksmith/armorer"), Profession.BLACKSMITH,
            Component.literal("Armorer"),
            List.of(new SkillRequirement(Skill.ARMORSMITHING, FOUNDATION)),
            false, SpecializationData.BlacksmithData.INSTANCE));

    public static final SpecializationDef BLACKSMITH_WEAPONSMITH = register(new SpecializationDef(
            id("blacksmith/weaponsmith"), Profession.BLACKSMITH,
            Component.literal("Weaponsmith"),
            List.of(new SkillRequirement(Skill.WEAPONSMITHING, FOUNDATION)),
            false, SpecializationData.BlacksmithData.INSTANCE));

    // ── Adventurer family ─────────────────────────────────────────────────
    public static final SpecializationDef ADVENTURER_ROOKIE = register(new SpecializationDef(
            id("adventurer/rookie"), Profession.ADVENTURER,
            Component.literal("Rookie"),
            List.of(), true,
            SpecializationData.CombatRoleData.defaultRookie()));

    public static final SpecializationDef ADVENTURER_SWORDSMAN = register(new SpecializationDef(
            id("adventurer/swordsman"), Profession.ADVENTURER,
            Component.literal("Swordsman"),
            List.of(new SkillRequirement(Skill.MELEE, FOUNDATION)),
            false,
            new SpecializationData.CombatRoleData(
                    net.minecraft.world.item.Items.IRON_SWORD, 2, 1.0f, 1.2f,
                    "Frontline fighter. Draws enemy attention.", "⚔")));

    public static final SpecializationDef ADVENTURER_SPEARMAN = register(new SpecializationDef(
            id("adventurer/spearman"), Profession.ADVENTURER,
            Component.literal("Spearman"),
            List.of(new SkillRequirement(Skill.MELEE, FOUNDATION)),
            false,
            new SpecializationData.CombatRoleData(
                    net.minecraft.world.item.Items.IRON_SPEAR, 5, 1.0f, 1.2f,
                    "", "⚔")));

    public static final SpecializationDef ADVENTURER_ARCHER = register(new SpecializationDef(
            id("adventurer/archer"), Profession.ADVENTURER,
            Component.literal("Archer"),
            List.of(new SkillRequirement(Skill.RANGED, FOUNDATION)),
            false,
            new SpecializationData.CombatRoleData(
                    net.minecraft.world.item.Items.BOW, 10, 1.1f, 1.0f,
                    "Ranged attacker. Keeps distance from enemies.", "🏹")));

    public static final SpecializationDef ADVENTURER_MAGE = register(new SpecializationDef(
            id("adventurer/mage"), Profession.ADVENTURER,
            Component.literal("Mage"),
            List.of(new SkillRequirement(Skill.MAGIC, FOUNDATION)),
            false,
            new SpecializationData.CombatRoleData(
                    net.minecraft.world.item.Items.BLAZE_ROD, 6, 0.9f, 1.5f,
                    "Area damage. Fragile but devastating.", "✦")));

    public static final SpecializationDef ADVENTURER_HEALER = register(new SpecializationDef(
            id("adventurer/healer"), Profession.ADVENTURER,
            Component.literal("Healer"),
            List.of(new SkillRequirement(Skill.COMBAT_MEDICINE, FOUNDATION)),
            false,
            new SpecializationData.CombatRoleData(
                    net.minecraft.world.item.Items.POTION, 4, 0.95f, 0.5f,
                    "Heals party members. Avoids direct combat.", "+")));

    public static final SpecializationDef ADVENTURER_SCOUT = register(new SpecializationDef(
            id("adventurer/scout"), Profession.ADVENTURER,
            Component.literal("Scout"),
            List.of(new SkillRequirement(Skill.SURVIVAL, FOUNDATION)),
            false,
            new SpecializationData.CombatRoleData(
                    net.minecraft.world.item.Items.STONE_AXE, 3, 1.3f, 0.8f,
                    "Fast and perceptive. Detects threats early.", "◈")));

    // ── Lookup ───────────────────────────────────────────────────────────

    public static Optional<SpecializationDef> byId(Identifier id) {
        return Optional.ofNullable(BY_ID.get(id));
    }

    public static List<SpecializationDef> forProfession(Profession profession) {
        return BY_PROFESSION.getOrDefault(profession, List.of())
                .stream().collect(Collectors.toUnmodifiableList());
    }

    /** Default specialization for new NPCs of {@code profession} (the
     *  generalist / rookie). Used by adventurer member setup so a new
     *  party member without specific skill gets a sensible default. */
    public static Optional<SpecializationDef> defaultFor(Profession profession) {
        return forProfession(profession).stream()
                .filter(SpecializationDef::isGeneralist)
                .findFirst();
    }
}
