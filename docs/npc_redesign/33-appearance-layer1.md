# 33 — Appearance Layer 1

## Purpose

Currently NPCs differentiate visually by life-stage scale and
profession texture. After Phase 4 there are ~20 professions, 4
cultures, 3+ life stages, office-holders, and several special roles
(priest, healer, scholar). Without appearance differentiation they
blend together — a Highmarch blacksmith looks identical to a Plainfolk
blacksmith, a scholar and a librarian look the same.

Appearance Layer 1 adds a modular appearance system that composes:

1. Base skin — culture-driven palette.
2. Profession layer — already exists; retained.
3. Office indicator layer — visible mark of office (sash, badge,
   headpiece).
4. Cultural accessory layer — a culture-characteristic accessory.
5. Life-stage layer — adjustments beyond scale (elderly posture,
   child proportions).

Layers 2-3 (hairstyle variation, face diversity, detailed garment
variants) are Phase 6 future.

## Data model

### AppearanceComponent (existing, extended)

```java
public class AppearanceComponent {
    // Existing fields
    private String professionTextureId;
    private float scale;

    // New fields
    private String cultureBaseId;       // from culture aesthetics
    private List<String> officeMarks;   // overlay mark IDs
    private List<String> accessoryIds;  // culture + personal items
    private LifeStageDecoration lifeStageDecoration;
    private long lastRebuildTick;

    public static final Codec<AppearanceComponent> CODEC;
}
```

### Appearance layer registry

```java
public final class AppearanceLayerRegistry {
    public static void registerCultureBase(String cultureId, CultureBase base);
    public static void registerOfficeMark(OfficeId office, OfficeMark mark);
    public static void registerAccessory(String id, AccessoryDefinition def);
    public static Optional<CultureBase> getCultureBase(String cultureId);
    // ...
}

public record CultureBase(
    String cultureId,
    ResourceLocation baseTexture,
    Map<SkinTone, ResourceLocation> variants    // 3-4 per culture
) {}

public record OfficeMark(
    OfficeId office,
    ResourceLocation overlayTexture,    // e.g. mayor's sash
    ModelAddon attachment              // optional 3D element
) {}

public record AccessoryDefinition(
    String id,
    ResourceLocation texture,
    AccessorySlot slot,                  // HEAD / SHOULDER / BELT
    Optional<ModelAddon> model
) {}

public enum AccessorySlot { HEAD, SHOULDER, BELT, BACK }
```

### LifeStageDecoration

```java
public record LifeStageDecoration(
    float postureOffset,          // slight forward lean for elderly
    float limbProportion,         // child/elderly scaling
    boolean usesCane              // rare; elderly + frailty
) {}
```

## Rendering pipeline

Each TownspersonMob entity renders:

1. Base model layer from `cultureBaseId` texture.
2. Profession layer applied over base (existing mechanism).
3. Office overlays (additive textures) for each mark in `officeMarks`.
4. Accessory model parts rendered at their slots.
5. Life-stage scale + posture adjustment.

Layer order and alpha blending follow existing mod rendering rules.

## Layer compositions

### Culture base

From each culture's aesthetics tokens in `31-cultures.md`:

- Plainfolk: warm earth tones, tan/brown base skin, linen-tone
  clothing.
- Highmarch: cool slate tones, weathered wool in dark blues and grays.
- Silkwood: olive-muted base, green/teal silk clothing.
- Tidereach: sun-bronzed base, canvas and blue-white tones.

Variant count: 3-4 skin tone variants per culture. Picked at spawn
based on seeded RNG; persists across saves.

### Office marks

Primary office marks:

- **village_leader**: sash or collar in kingdom color
- **village_bailiff**: badge (small overlay on chest)
- **village_constable**: belt item + staff accessory
- **village_scribe**: quill accessory + ink-stained apron overlay
- **village_priest**: ceremonial collar / stole
- **village_healer**: herb pouch belt accessory + apron
- **guild_master**: guild color sash + guild insignia overlay
- **guild_treasurer, registrar**: minor color stripe
- **company_owner**: shoulder mantle
- **kingdom_king / chancellor**: crown / chain of office (kingdom
  scope)

Multiple office marks composite cleanly (can hold multiple offices
visibly).

### Cultural accessories

One common accessory per culture plus rare variants:

- **Plainfolk**: sturdy leather cap (HEAD).
- **Highmarch**: shoulder cloak with heraldic pin (SHOULDER).
- **Silkwood**: bound book or scroll (BELT) for literate roles;
  patterned sash otherwise.
- **Tidereach**: carved shell pendant (chest area — mapped to SHOULDER
  slot with lowered positioning).

Minor variants (5% of NPCs get unique accessory): culture-
appropriate hat styles, belts, etc. Generated at spawn.

### Life-stage treatment

- Child: existing smaller scale.
- Teen: between child and adult scale; slightly thinner.
- Adult: standard.
- Elderly: slight posture offset; occasional cane; paler palette
  variant.

Existing scale-by-age code remains; decoration adds posture + cane +
palette shift on top.

## Appearance generation

### On spawn

```java
public static AppearanceComponent generateForNewNpc(
        TownspersonMob npc, ServerLevel level) {
    Culture culture = CultureResolver.of(npc);
    AppearanceComponent comp = new AppearanceComponent();

    // Base
    comp.setCultureBaseId(culture.id());

    // Profession (existing path)
    comp.setProfessionTextureId(resolveProfessionTexture(npc.getProfession()));

    // Accessories — cultural + chance of variant
    comp.getAccessoryIds().add(culture.aesthetics().accessoryPool().get(0));
    if (rng.nextFloat() < 0.05f) {
        comp.getAccessoryIds().add(rareAccessory(culture, rng));
    }

    // Office marks (empty initially; updated on office hold)
    // Life-stage (computed dynamically)

    return comp;
}
```

### On state change

Triggered rebuild events:
- Profession change.
- Office take / release.
- Life-stage advancement.
- Culture migration (rare — e.g. refugee becomes citizen).

`AppearanceComponent.rebuild(npc)` recomputes full layer list.

### On gift

Giving certain clothing or accessory items adds them to the NPC's
accessory pool:

- Circlet: adds HEAD slot accessory.
- Pendant: adds SHOULDER slot accessory.

This creates persistent visible gifts.

## Performance considerations

- Layer composition cached per NPC; only rebuilt on state change.
- Texture atlas / batching via existing mod rendering pipeline.
- No per-frame recomputation.

## Client-server sync

Appearance data is state: sync via entity data serializer or custom
packet on rebuild. Server authoritative.

## Persistence

NBT structure on entity under `appearance`:

```
appearance: {
    cultureBaseId: "plainfolk",
    skinToneVariant: 2,
    professionTextureId: "blacksmith",
    officeMarks: ["village_scribe"],
    accessoryIds: ["plainfolk_cap", "inkstained_apron"],
    lifeStageDecoration: {
        postureOffset: 0.1,
        usesCane: false
    }
}
```

## Integration points

### Phase 5 integration

- `AppearanceComponent` extended with new fields.
- `AppearanceLayerRegistry` added at mod init.
- Culture base textures authored for each of 4 starter cultures.
- Office mark overlays authored.
- Accessory models authored.
- Rebuild hooks wired:
  - Office framework fires `OfficeChange` event → rebuild.
  - Life-stage transition → rebuild.
  - Profession change → rebuild.
- `AppearanceComponent.rebuild` integrates layers.
- Rendering pipeline updated to composite layers.
- `NpcProfileSnapshot` shows "Wearing: ..." line with key items.
- `/appearance rebuild <npc>` debug command forces rebuild.

### Phase 6 future

- Layer 2: hairstyle variation, beard/face variation, body shape.
- Layer 3: damage/wear (armor chips, apron stains), seasonal clothing.
- Procedural detailed garment variants.
- Player wardrobe mirroring (if player-character customization ties in).

## Behavior contract

### Does

- Composite NPC appearance from culture + profession + office +
  accessory + life-stage layers.
- Provide visible differentiation for all offices and cultures.
- Persist appearance state across saves.
- Rebuild on state changes (not per-frame).

### Does not

- Author custom hair/face mesh variation (Phase 6).
- Support player-model appearance (player unchanged).
- Simulate clothing wear / damage (Phase 6).
- Generate procedural textures at runtime; uses authored atlases.
- Support mod-added cultures via JSON in v1 (hardcoded).

## Edge cases

- **Missing texture for a layer.** Render falls back to default base;
  log a warning.
- **NPC holds 5 offices simultaneously** (rare — player in many
  roles). Composite all marks; if unreadable, cap visual display
  to 3 most prominent.
- **Accessory slot conflict.** Pick cultural accessory first;
  profession override second; office third if competing.
- **Undead / mob variant (future stretch).** Outside scope of v1
  appearance layer.
- **Texture rebuilding on every save/load.** Cache validated on
  load; only rebuild if source data changed.

## Ordering dependencies

Phase 5 depends on:
- Cultures (same phase) — aesthetic tokens.
- Office framework (Phase 3) — office marks.
- Existing appearance/profession texture system.
- Existing entity rendering pipeline.

## Open decisions

- Should office marks be visible across large distances (LOD)?
  **Proposed: yes — marks are high-contrast for recognition at
  mid-range; detail reduced at far LOD.**
- Gift-added accessories — permanent or time-limited? **Proposed:
  permanent in v1; wear/damage in Phase 6.**
- Should the player's appearance reflect their office too? **Proposed:
  no for v1 — player model unchanged; hold-office effects visible
  via UI only.**
- Culture migration (refugee settles) — does appearance migrate?
  **Proposed: slowly; culture-base shifts over months after settling.
  Phase 6 culture drift supports this fully.**

## Does-not-include

- Facial animation (blinking, expressions).
- Hairstyle variation.
- Gendered appearance differentiation beyond existing.
- Custom player outfits.
- Dynamic cloth simulation.
- Season-driven clothing changes (future).

## Revision Notes

(changes recorded here as the spec evolves after testing)
