package tterrag1112.life_in_the_village.Entities.custom;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import tterrag1112.life_in_the_village.Entities.NpcNameRegistry;
import tterrag1112.life_in_the_village.Entities.custom.Appearance.AppearanceLayerRegistry;
import tterrag1112.life_in_the_village.Entities.custom.Appearance.LifeStageDecoration;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

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

    // ── Phase 5 doc 33: layer 1 (culture / office / accessories / stage) ─────
    /** Lower-case culture id (e.g. "plainfolk"). Empty = use registry default. */
    private String cultureBaseId = "";
    /** Index into {@link tterrag1112.life_in_the_village.Entities.custom.Appearance.CultureBase#variants()}. */
    private int    skinToneVariant = 0;
    /** Office ids whose visual marks render on this NPC. Highest priority first when capped. */
    private final List<String> officeMarks  = new ArrayList<>();
    /** Accessory ids worn / carried by this NPC. Includes culture, profession, and gift accessories. */
    private final List<String> accessoryIds = new ArrayList<>();
    /** Posture / proportion adjustments derived from current life stage. */
    private LifeStageDecoration lifeStageDecoration = LifeStageDecoration.NEUTRAL;
    /** Game-time tick of the most recent {@link #rebuild} call. 0 = never built. */
    private long lastRebuildTick = 0L;

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
    // Personality traits — load-only post-6.4.1.4
    // =========================================================================

    /**
     * Phase 6.4.1.4 — these three methods survive the legacy enum
     * retirement because the NBT load path still parses a legacy
     * {@code traits} string field (when present on pre-6.4.1.4 saves)
     * and feeds the list to {@link tterrag1112.life_in_the_village.Npc
     * .Traits.TraitVector#migrateFromLegacy} before clearing it.
     * {@code hasTrait} and {@code randomizeTraits} were removed —
     * neither had any callers after retirement.
     */
    public List<PersonalityTrait> getTraits() {
        return Collections.unmodifiableList(traits);
    }

    public void addTrait(PersonalityTrait trait) {
        if (!traits.contains(trait)) traits.add(trait);
    }

    public void clearTraits() {
        traits.clear();
    }

    // =========================================================================
    // Phase 5 doc 33: Layer 1 accessors / mutators
    // =========================================================================

    public String getCultureBaseId()           { return cultureBaseId; }
    public int    getSkinToneVariant()         { return skinToneVariant; }
    public List<String> getOfficeMarks()       { return Collections.unmodifiableList(officeMarks); }
    public List<String> getAccessoryIds()      { return Collections.unmodifiableList(accessoryIds); }
    public LifeStageDecoration getLifeStageDecoration() { return lifeStageDecoration; }
    public long   getLastRebuildTick()         { return lastRebuildTick; }

    public void setCultureBaseId(String id) {
        this.cultureBaseId = id == null ? "" : id.toLowerCase(Locale.ROOT);
    }

    public void setSkinToneVariant(int v) { this.skinToneVariant = Math.max(0, v); }
    public void setLifeStageDecoration(LifeStageDecoration d) {
        this.lifeStageDecoration = d == null ? LifeStageDecoration.NEUTRAL : d;
    }
    public void setLastRebuildTick(long tick) { this.lastRebuildTick = tick; }

    public boolean addOfficeMark(String officeId) {
        if (officeId == null || officeId.isEmpty()) return false;
        if (officeMarks.contains(officeId)) return false;
        officeMarks.add(officeId);
        return true;
    }

    public boolean removeOfficeMark(String officeId) {
        return officeMarks.remove(officeId);
    }

    public void clearOfficeMarks() {
        officeMarks.clear();
    }

    public boolean addAccessory(String accessoryId) {
        if (accessoryId == null || accessoryId.isEmpty()) return false;
        if (accessoryIds.contains(accessoryId)) return false;
        accessoryIds.add(accessoryId);
        return true;
    }

    public boolean removeAccessory(String accessoryId) {
        return accessoryIds.remove(accessoryId);
    }

    public void clearAccessories() {
        accessoryIds.clear();
    }

    /**
     * Maximum number of office marks the renderer should display
     * simultaneously. Spec edge case "5 offices simultaneously" caps
     * the visual to the top 3 by priority.
     */
    public static final int MAX_VISIBLE_OFFICE_MARKS = 3;

    /**
     * Returns the top-N office mark ids by registered priority,
     * suitable for handing to the renderer. Marks with no registry
     * entry sort last (priority 0).
     */
    public List<String> visibleOfficeMarks() {
        if (officeMarks.size() <= MAX_VISIBLE_OFFICE_MARKS) {
            return List.copyOf(officeMarks);
        }
        List<String> sorted = new ArrayList<>(officeMarks);
        sorted.sort((a, b) -> {
            int pa = AppearanceLayerRegistry.getOfficeMark(a)
                    .map(m -> m.priority()).orElse(0);
            int pb = AppearanceLayerRegistry.getOfficeMark(b)
                    .map(m -> m.priority()).orElse(0);
            return Integer.compare(pb, pa); // descending
        });
        return List.copyOf(sorted.subList(0, MAX_VISIBLE_OFFICE_MARKS));
    }

    /**
     * Single-line human-readable summary of what the NPC is wearing /
     * carrying — used by the {@code /appearance show} command and the
     * profile-snapshot integration when it lands.
     */
    public String describeAppearance() {
        StringBuilder sb = new StringBuilder();
        sb.append("culture=").append(cultureBaseId.isEmpty() ? "default" : cultureBaseId);
        sb.append("/v").append(skinToneVariant);
        if (!accessoryIds.isEmpty()) {
            sb.append(", accessories=[");
            for (int i = 0; i < accessoryIds.size(); i++) {
                if (i > 0) sb.append(", ");
                String id = accessoryIds.get(i);
                sb.append(AppearanceLayerRegistry.getAccessory(id)
                        .map(d -> d.shortLabel()).orElse(id));
            }
            sb.append("]");
        }
        if (!officeMarks.isEmpty()) {
            sb.append(", offices=[");
            List<String> visible = visibleOfficeMarks();
            for (int i = 0; i < visible.size(); i++) {
                if (i > 0) sb.append(", ");
                String id = visible.get(i);
                sb.append(AppearanceLayerRegistry.getOfficeMark(id)
                        .map(m -> m.shortLabel()).orElse(id));
            }
            if (officeMarks.size() > visible.size()) {
                sb.append(", +").append(officeMarks.size() - visible.size()).append(" more");
            }
            sb.append("]");
        }
        if (lifeStageDecoration.usesCane()) sb.append(", cane");
        return sb.toString();
    }

    // =========================================================================
    // Phase 5 doc 33: spawn-time generation
    // =========================================================================

    /**
     * Initial Layer-1 fields for a freshly-spawned NPC.
     * {@code cultureId} comes from {@code CultureResolver.of(npc).id()}
     * — the caller resolves it (avoids circular dependency between
     * appearance and culture packages).
     */
    public void generateLayer1(String cultureId, String lifeStage,
                               long npcSeed, RandomSource rng) {
        AppearanceLayerRegistry.ensureInit();
        setCultureBaseId(cultureId);
        // Skin tone variant: deterministic from a stable seed so the
        // NPC keeps the same skin across reloads even if rng state
        // diverges. cultureId variant count is registry-driven.
        int variantCount = AppearanceLayerRegistry.getCultureBase(cultureId)
                .map(b -> b.variantCount()).orElse(1);
        skinToneVariant = (int) Math.floorMod(npcSeed, Math.max(1, variantCount));

        accessoryIds.clear();
        List<String> pool = AppearanceLayerRegistry.accessoryPoolFor(cultureId);
        if (!pool.isEmpty()) {
            accessoryIds.add(pool.get(0));
            // 5% chance of a rare variant from the same pool.
            if (pool.size() > 1 && rng.nextFloat() < 0.05f) {
                accessoryIds.add(pool.get(1 + rng.nextInt(pool.size() - 1)));
            }
        }
        officeMarks.clear(); // populated when offices are taken
        lifeStageDecoration = LifeStageDecoration.forStage(lifeStage);
        lastRebuildTick = 0L;
    }

    /**
     * Recomputes the Layer-1 fields for an existing NPC after a
     * state change (profession change / office take or release /
     * life-stage advance / culture migration). The caller passes
     * the resolved culture id and the office ids the NPC currently
     * holds; this keeps {@code AppearanceComponent} free of
     * package dependencies on Office / Culture.
     */
    public void rebuild(String cultureId, String lifeStage,
                        List<String> currentOfficeIds, long tick) {
        AppearanceLayerRegistry.ensureInit();
        setCultureBaseId(cultureId);

        // Office marks: full replace with the caller's snapshot.
        officeMarks.clear();
        if (currentOfficeIds != null) {
            // Dedupe via LinkedHashSet to preserve caller order.
            for (String id : new LinkedHashSet<>(currentOfficeIds)) {
                if (id == null || id.isEmpty()) continue;
                if (AppearanceLayerRegistry.getOfficeMark(id).isEmpty()) continue;
                officeMarks.add(id);
            }
        }

        // Accessories: keep gift-added accessories (they live across
        // rebuilds), refresh culture accessory if it was lost or if
        // culture changed.
        ensureCultureAccessoryAfterRebuild(cultureId);

        lifeStageDecoration = LifeStageDecoration.forStage(lifeStage);
        lastRebuildTick = tick;
    }

    private void ensureCultureAccessoryAfterRebuild(String cultureId) {
        List<String> pool = AppearanceLayerRegistry.accessoryPoolFor(cultureId);
        if (pool.isEmpty()) return;
        String canonical = pool.get(0);
        // If no accessory from the new pool is present, prepend the
        // canonical one. Existing gift accessories stay.
        boolean hasFromPool = false;
        for (String id : accessoryIds) {
            if (pool.contains(id)) { hasFromPool = true; break; }
        }
        if (!hasFromPool) accessoryIds.add(0, canonical);
    }

    // =========================================================================
    // Persistence
    // =========================================================================

    private static final String TAG_NAME = "npcName";
    private static final String TAG_SKIN = "skinTone";
    private static final String TAG_HAIR_STYLE = "hairStyle";
    private static final String TAG_HAIR_COLOR = "hairColor";
    private static final String TAG_TRAITS = "personalityTraits";

    // Phase 5 doc 33 NBT keys.
    private static final String TAG_CULTURE_BASE      = "cultureBaseId";
    private static final String TAG_SKIN_VARIANT      = "skinToneVariant";
    private static final String TAG_OFFICE_MARKS      = "officeMarks";
    private static final String TAG_ACCESSORY_IDS     = "accessoryIds";
    private static final String TAG_LIFE_DECORATION   = "lifeStageDecoration";
    private static final String TAG_LIFE_POSTURE      = "postureOffset";
    private static final String TAG_LIFE_LIMB_PROP    = "limbProportion";
    private static final String TAG_LIFE_USES_CANE    = "usesCane";
    private static final String TAG_LAST_REBUILD_TICK = "lastRebuildTick";

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
        // Phase 5 doc 33 — every field is optional on load so older
        // saves without these keys still work.
        if (!cultureBaseId.isEmpty()) tag.putString(TAG_CULTURE_BASE, cultureBaseId);
        tag.putInt(TAG_SKIN_VARIANT, skinToneVariant);
        if (!officeMarks.isEmpty()) tag.putString(TAG_OFFICE_MARKS, String.join(",", officeMarks));
        if (!accessoryIds.isEmpty()) tag.putString(TAG_ACCESSORY_IDS, String.join(",", accessoryIds));
        CompoundTag deco = new CompoundTag();
        deco.putFloat(TAG_LIFE_POSTURE,   lifeStageDecoration.postureOffset());
        deco.putFloat(TAG_LIFE_LIMB_PROP, lifeStageDecoration.limbProportion());
        deco.putBoolean(TAG_LIFE_USES_CANE, lifeStageDecoration.usesCane());
        tag.put(TAG_LIFE_DECORATION, deco);
        if (lastRebuildTick != 0L) tag.putLong(TAG_LAST_REBUILD_TICK, lastRebuildTick);
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
        // Phase 5 doc 33
        if (tag.contains(TAG_CULTURE_BASE)) {
            cultureBaseId = tag.getString(TAG_CULTURE_BASE).orElse("");
        }
        if (tag.contains(TAG_SKIN_VARIANT)) {
            skinToneVariant = tag.getInt(TAG_SKIN_VARIANT).orElse(0);
        }
        officeMarks.clear();
        if (tag.contains(TAG_OFFICE_MARKS)) {
            String raw = tag.getString(TAG_OFFICE_MARKS).orElse("");
            if (!raw.isEmpty()) {
                for (String id : raw.split(",")) if (!id.isEmpty()) officeMarks.add(id);
            }
        }
        accessoryIds.clear();
        if (tag.contains(TAG_ACCESSORY_IDS)) {
            String raw = tag.getString(TAG_ACCESSORY_IDS).orElse("");
            if (!raw.isEmpty()) {
                for (String id : raw.split(",")) if (!id.isEmpty()) accessoryIds.add(id);
            }
        }
        if (tag.contains(TAG_LIFE_DECORATION)) {
            CompoundTag deco = tag.getCompound(TAG_LIFE_DECORATION).orElse(new CompoundTag());
            float posture = deco.getFloat(TAG_LIFE_POSTURE).orElse(0f);
            float limb    = deco.getFloat(TAG_LIFE_LIMB_PROP).orElse(1.0f);
            boolean cane  = deco.getBoolean(TAG_LIFE_USES_CANE).orElse(false);
            lifeStageDecoration = new LifeStageDecoration(posture, limb, cane);
        }
        if (tag.contains(TAG_LAST_REBUILD_TICK)) {
            lastRebuildTick = tag.getLong(TAG_LAST_REBUILD_TICK).orElse(0L);
        }
    }

    // =========================================================================
    // PersonalityTrait enum — RETIRED in Phase 6.4.1.4
    // =========================================================================

    /**
     * Phase 6.4.1.4 — retired in favour of {@link
     * tterrag1112.life_in_the_village.Npc.Traits.TraitVector}.
     *
     * <p>Pre-retirement this enum carried per-trait work-speed and
     * price modifiers (DILIGENT=1.15× etc.) but had zero consumers
     * via {@code getWorkSpeedModifier} / {@code getPriceModifier} /
     * {@code getDetectionRange} — they were dead code. The same
     * effects now flow through {@code TraitVector} axes (INDUSTRY →
     * production speed, GENEROSITY → channel quote price, AMBITION
     * → XP rate), all wired in 6.4.1.2/.3.</p>
     *
     * <p>The enum stays as a load-only name carrier for one release.
     * Legacy NBT saves contain a comma-separated string of these
     * names; on load, {@link TownspersonMob}'s NBT path migrates
     * them into TraitVector axis adjustments via
     * {@link tterrag1112.life_in_the_village.Npc.Traits.TraitVector
     * #migrateFromLegacy} and clears the legacy list. New saves do
     * NOT persist this enum any more.</p>
     */
    public enum PersonalityTrait {
        DILIGENT, LAZY, GREEDY, GENEROUS, BRAVE, TIMID,
        FRIENDLY, SUSPICIOUS, CHEERFUL, GRUMPY
    }
}