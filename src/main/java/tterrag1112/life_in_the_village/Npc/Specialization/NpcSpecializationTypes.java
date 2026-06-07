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

    // ── Farmer family (Phase 6.3.3.i.3) ──────────────────────────────────
    // Bias-not-gate. mixed is the generalist (default for new farmers);
    // crop_focus and animal_focus gate on CROP_FARMING / ANIMAL_HUSBANDRY
    // ≥ FOUNDATION (20). ORCHARDIST / VINTNER / APIARIST emerge from
    // skill levels (no formal spec) per the Option A design discussion.
    public static final SpecializationDef FARMER_MIXED = register(new SpecializationDef(
            id("farmer/mixed"), Profession.FARMER,
            Component.literal("Mixed Farmer"),
            List.of(), true, SpecializationData.None.INSTANCE));

    public static final SpecializationDef FARMER_CROP_FOCUS = register(new SpecializationDef(
            id("farmer/crop_focus"), Profession.FARMER,
            Component.literal("Crop Farmer"),
            List.of(new SkillRequirement(Skill.CROP_FARMING, FOUNDATION)),
            false, SpecializationData.None.INSTANCE));

    public static final SpecializationDef FARMER_ANIMAL_FOCUS = register(new SpecializationDef(
            id("farmer/animal_focus"), Profession.FARMER,
            Component.literal("Animal Husbandry Farmer"),
            List.of(new SkillRequirement(Skill.ANIMAL_HUSBANDRY, FOUNDATION)),
            false, SpecializationData.None.INSTANCE));

    // Phase 6.7.1 — concrete animal-husbandry specializations. Siblings to
    // FARMER_ANIMAL_FOCUS (which remains the generalist-tier animal spec).
    // Both gated on the species-specific sub-skill ≥ FOUNDATION (20).
    // Initial inhabitant assignment with the locked flag bypasses the gate
    // via force=true so a fresh shepherd / beekeeper can start their craft.
    public static final SpecializationDef FARMER_SHEPHERD = register(new SpecializationDef(
            id("farmer/shepherd"), Profession.FARMER,
            Component.literal("Shepherd"),
            List.of(new SkillRequirement(Skill.SHEPHERDING, FOUNDATION)),
            false, SpecializationData.None.INSTANCE));

    public static final SpecializationDef FARMER_BEEKEEPER = register(new SpecializationDef(
            id("farmer/beekeeper"), Profession.FARMER,
            Component.literal("Beekeeper"),
            List.of(new SkillRequirement(Skill.BEEKEEPING, FOUNDATION)),
            false, SpecializationData.None.INSTANCE));

    // ── Priest family (Religion Rework R1b) ───────────────────────────────
    // Generalist-only this phase. Concrete religion-specific orders vary
    // per religion and land in the content/multi-religion phase; they will
    // register as gated siblings here and assign over the locked generalist
    // via force=true (same pattern the combat-role / admin paths use).
    public static final SpecializationDef PRIEST_CLERIC = register(new SpecializationDef(
            id("priest/cleric"), Profession.PRIEST,
            Component.literal("Cleric"),
            List.of(), true, SpecializationData.None.INSTANCE));

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

    // ── Inhabitant-spawn locked-generalist assignment (R1b) ───────────────

    /** Professions whose freshly-spawned inhabitants receive their
     *  generalist spec, <em>locked</em>, at spawn — worldgen/operator
     *  intent that survives skill drift and is the seam later content
     *  branches on. Bias-not-gate professions (BLACKSMITH / FARMER) are
     *  deliberately NOT here: they spawn spec-less and auto-promote via
     *  {@code trySetSpecialization}, which a lock would block. */
    private static final java.util.Set<Profession> LOCK_GENERALIST_AT_SPAWN =
            java.util.EnumSet.of(Profession.PRIEST);

    /**
     * Religion Rework R1b — the single inhabitant-spawn specialization
     * route. For a profession in {@link #LOCK_GENERALIST_AT_SPAWN}, assigns
     * its generalist {@link SpecializationDef} via the canonical component
     * API ({@code assign(force=true)} + {@code setLocked}). No-op for every
     * other profession. Called once per spawned NPC from
     * {@code VillageInhabitantPopulator}. Centralized here (the
     * specialization registry) rather than open-coded at the call site so
     * future professions/orders extend the set, not the populator.
     */
    public static void assignInitialSpawnSpec(
            tterrag1112.life_in_the_village.Entities.custom.TownspersonMob npc,
            Profession profession) {
        if (npc == null || !LOCK_GENERALIST_AT_SPAWN.contains(profession)) return;
        defaultFor(profession).ifPresent(def -> {
            var comp = npc.getSpecializationComponent();
            if (comp.assign(def, npc, true)) comp.setLocked(true);
        });
    }
}
