package tterrag1112.life_in_the_village.Entities.custom;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import tterrag1112.life_in_the_village.Entities.NpcNameRegistry;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Manages all appearance and identity state for a TownspersonMob:
 * name, skin tone, hair style/color, and personality traits.
 *
 * <h3>Why extract this?</h3>
 * TownspersonMob mixed appearance fields (skinTone, hairStyle, hairColor),
 * name management (getNpcName, setNpcName, getSurname, adoptSurname), and
 * personality traits (trait list, work speed modifier, price modifier,
 * detection range) inline with combat, family, and economy logic. This
 * component groups all "who is this NPC and what do they look like" state.
 *
 * <h3>Personality traits</h3>
 * Traits are stored here because they are an intrinsic characteristic of
 * the NPC that affects how they appear to the player (future dialogue)
 * and modify numeric behaviors. The component exposes aggregate modifiers
 * so callers don't need to iterate the trait list themselves.
 */
public class AppearanceComponent {

    // ── Identity ──────────────────────────────────────────────────────────────
    private String npcName = "Townsperson";

    // ── Visual ────────────────────────────────────────────────────────────────
    private int skinTone = 0;
    private int hairStyle = 0;
    private int hairColor = 0;

    // ── Personality ───────────────────────────────────────────────────────────
    private final List<PersonalityTrait> traits = new ArrayList<>();

    // =========================================================================
    // Name management
    // =========================================================================

    public String getName()              { return npcName; }
    public void setName(String name)     { this.npcName = name; }

    /**
     * Extracts the surname (last word) from the full NPC name.
     * Returns empty string if the name has no space.
     */
    public String getSurname() {
        if (npcName == null || npcName.isEmpty()) return "";
        String[] parts = npcName.split(" ");
        return parts.length > 1 ? parts[parts.length - 1] : "";
    }

    /**
     * Replaces the surname portion of the name.
     * If the name has no surname, appends the new one.
     */
    public void setSurname(String surname) {
        String[] parts = npcName.split(" ", 2);
        npcName = parts[0] + " " + surname;
    }

    /**
     * Generates a random name using the name registry.
     */
    public void generateRandomName(Boolean isMale, RandomSource random) {
        String first = NpcNameRegistry.INSTANCE.generateFirstName(isMale, random);
        String last = NpcNameRegistry.INSTANCE.generateSurname(random);
        this.npcName = first + " " + last;
    }

    // =========================================================================
    // Visual appearance
    // =========================================================================

    public int getSkinTone()  { return skinTone; }
    public int getHairStyle() { return hairStyle; }
    public int getHairColor() { return hairColor; }

    public void setSkinTone(int v)  { this.skinTone = v; }
    public void setHairStyle(int v) { this.hairStyle = v; }
    public void setHairColor(int v) { this.hairColor = v; }

    /**
     * Randomizes all visual appearance fields.
     * Call once at spawn time.
     */
    public void randomize(RandomSource random,
                          int maxSkinTones, int maxHairStyles,
                          int maxHairColors) {
        skinTone  = random.nextInt(maxSkinTones);
        hairStyle = random.nextInt(maxHairStyles);
        hairColor = random.nextInt(maxHairColors);
    }

    // =========================================================================
    // Personality traits
    // =========================================================================

    public List<PersonalityTrait> getTraits() {
        return Collections.unmodifiableList(traits);
    }

    public boolean hasTrait(PersonalityTrait trait) {
        return traits.contains(trait);
    }

    public void addTrait(PersonalityTrait trait) {
        if (!traits.contains(trait)) traits.add(trait);
    }

    /**
     * Assigns random traits at spawn time.
     * Typically 1-3 traits per NPC.
     */
    public void randomizeTraits(RandomSource random, int count) {
        traits.clear();
        PersonalityTrait[] values = PersonalityTrait.values();
        for (int i = 0; i < count && traits.size() < values.length; i++) {
            PersonalityTrait candidate = values[random.nextInt(values.length)];
            if (!traits.contains(candidate)) {
                traits.add(candidate);
            }
        }
    }

    public void clearTraits(){
        traits.clear();
    }

    // ── Aggregate modifiers ──────────────────────────────────────────────────

    /**
     * Multiplier applied to work tick rates.
     * DILIGENT = faster (lower ticks), LAZY = slower (higher ticks).
     */
    public double getWorkSpeedModifier() {
        return traits.stream()
                .mapToDouble(PersonalityTrait::workSpeedModifier)
                .reduce(1.0, (a, b) -> a * b);
    }

    /**
     * Returns the effective tick rate for an action, modified by traits.
     */
    public int getActionTickRate(int baseRate) {
        double modifier = getWorkSpeedModifier();
        return Math.max(1, (int) (baseRate / modifier));
    }

    /** Aggregate price modifier from all traits. */
    public double getPriceModifier() {
        return traits.stream()
                .mapToDouble(PersonalityTrait::priceModifier)
                .reduce(1.0, (a, b) -> a * b);
    }

    /** Detection range modified by brave/timid traits. */
    public double getDetectionRange() {
        if (hasTrait(PersonalityTrait.BRAVE))  return 24.0;
        if (hasTrait(PersonalityTrait.TIMID))  return 8.0;
        return 16.0;
    }

    // =========================================================================
    // Persistence
    // =========================================================================

    private static final String TAG_NAME = "npcName";
    private static final String TAG_SKIN = "skinTone";
    private static final String TAG_HAIR_STYLE = "hairStyle";
    private static final String TAG_HAIR_COLOR = "hairColor";
    private static final String TAG_TRAITS = "personalityTraits";

    public void save(CompoundTag tag) {
        tag.putString(TAG_NAME, npcName);
        tag.putInt(TAG_SKIN, skinTone);
        tag.putInt(TAG_HAIR_STYLE, hairStyle);
        tag.putInt(TAG_HAIR_COLOR, hairColor);
        if (!traits.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < traits.size(); i++) {
                if (i > 0) sb.append(",");
                sb.append(traits.get(i).name());
            }
            tag.putString(TAG_TRAITS, sb.toString());
        }
    }

    public void load(CompoundTag tag) {
        if (tag.contains(TAG_NAME)) npcName = tag.getString(TAG_NAME).orElse("");
        if (tag.contains(TAG_SKIN)) skinTone = tag.getInt(TAG_SKIN).orElse(0);
        if (tag.contains(TAG_HAIR_STYLE)) hairStyle = tag.getInt(TAG_HAIR_STYLE).orElse(0);
        if (tag.contains(TAG_HAIR_COLOR)) hairColor = tag.getInt(TAG_HAIR_COLOR).orElse(0);
        if (tag.contains(TAG_TRAITS)) {
            traits.clear();
            String raw = tag.getString(TAG_TRAITS).orElse("");
            if (!raw.isEmpty()) {
                for (String name : raw.split(",")) {
                    try {
                        traits.add(PersonalityTrait.valueOf(name));
                    } catch (IllegalArgumentException ignored) {}
                }
            }
        }
    }

    // =========================================================================
    // PersonalityTrait enum (moved here from TownspersonMob inner scope)
    // =========================================================================

    /**
     * Intrinsic personality traits that affect NPC behavior modifiers.
     *
     * Each trait defines multipliers for work speed and price negotiation.
     * Multiple traits stack multiplicatively.
     */
    public enum PersonalityTrait {
        DILIGENT  (1.15, 1.0),   // works faster
        LAZY      (0.85, 1.0),   // works slower
        GREEDY    (1.0,  1.15),  // charges more
        GENEROUS  (1.0,  0.9),   // charges less
        BRAVE     (1.0,  1.0),   // wider detection range
        TIMID     (1.0,  1.0),   // narrower detection range
        FRIENDLY  (1.0,  0.95),  // slightly lower prices
        SUSPICIOUS(1.0,  1.05),  // slightly higher prices
        CHEERFUL  (1.05, 1.0),   // slightly faster work
        GRUMPY    (0.95, 1.0);   // slightly slower work

        private final double workSpeed;
        private final double price;

        PersonalityTrait(double workSpeed, double price) {
            this.workSpeed = workSpeed;
            this.price = price;
        }

        public double workSpeedModifier() { return workSpeed; }
        public double priceModifier()     { return price; }
    }
}