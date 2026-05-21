package tterrag1112.life_in_the_village.Entities.custom.Appearance;

import net.minecraft.resources.Identifier;
import tterrag1112.life_in_the_village.Life_in_the_village;
import tterrag1112.life_in_the_village.Npc.Office.OfficeRegistry;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Phase 5 doc 33 § Appearance layer registry.
 *
 * <p>Single static registry for all appearance ingredients —
 * culture base textures, office marks, and accessories. Populated
 * once via {@link #ensureInit()} (idempotent) on first read.</p>
 *
 * <p>Phase 5 v1 ships infrastructure only — every registered entry
 * carries the resource path the renderer <em>will</em> read once
 * art lands. Missing-texture handling at render time falls back to
 * the existing default base; warnings are logged but the NPC still
 * renders.</p>
 */
public final class AppearanceLayerRegistry {

    private static final String NS = Life_in_the_village.MODID;

    private static final Map<String, CultureBase> CULTURE_BASES = new HashMap<>();
    private static final Map<String, OfficeMark>  OFFICE_MARKS  = new HashMap<>();
    private static final Map<String, AccessoryDefinition> ACCESSORIES = new HashMap<>();
    /** Per-culture pool of accessories, keyed by culture id. */
    private static final Map<String, List<String>> CULTURE_ACCESSORY_POOL = new HashMap<>();

    private static volatile boolean initialized = false;

    private AppearanceLayerRegistry() {}

    public static synchronized void ensureInit() {
        if (initialized) return;
        registerDefaults();
        initialized = true;
    }

    // ── Public API ──────────────────────────────────────────────────

    public static void registerCultureBase(CultureBase base) {
        CULTURE_BASES.put(base.cultureId().toLowerCase(Locale.ROOT), base);
    }

    public static void registerOfficeMark(OfficeMark mark) {
        OFFICE_MARKS.put(mark.officeId(), mark);
    }

    public static void registerAccessory(AccessoryDefinition def) {
        ACCESSORIES.put(def.id(), def);
    }

    public static void registerCultureAccessoryPool(String cultureId, List<String> accessoryIds) {
        CULTURE_ACCESSORY_POOL.put(cultureId.toLowerCase(Locale.ROOT),
                List.copyOf(accessoryIds));
    }

    public static Optional<CultureBase> getCultureBase(String cultureId) {
        ensureInit();
        if (cultureId == null) return Optional.empty();
        return Optional.ofNullable(CULTURE_BASES.get(cultureId.toLowerCase(Locale.ROOT)));
    }

    public static Optional<OfficeMark> getOfficeMark(String officeId) {
        ensureInit();
        return Optional.ofNullable(OFFICE_MARKS.get(officeId));
    }

    public static Optional<AccessoryDefinition> getAccessory(String id) {
        ensureInit();
        return Optional.ofNullable(ACCESSORIES.get(id));
    }

    public static List<String> accessoryPoolFor(String cultureId) {
        ensureInit();
        if (cultureId == null) return List.of();
        return CULTURE_ACCESSORY_POOL.getOrDefault(
                cultureId.toLowerCase(Locale.ROOT), List.of());
    }

    /** Returns every registered culture base id (lowercase). */
    public static List<String> allCultureIds() {
        ensureInit();
        return new ArrayList<>(CULTURE_BASES.keySet());
    }

    /** Returns every registered office mark id. */
    public static List<String> allOfficeMarkIds() {
        ensureInit();
        return new ArrayList<>(OFFICE_MARKS.keySet());
    }

    /** Returns every registered accessory id. */
    public static List<String> allAccessoryIds() {
        ensureInit();
        return new ArrayList<>(ACCESSORIES.keySet());
    }

    // ── Defaults: 4 cultures + standard office marks + accessories ──

    private static void registerDefaults() {
        registerCultureBases();
        registerOfficeMarkDefaults();
        registerAccessoryDefaults();
    }

    private static void registerCultureBases() {
        // Each culture: 1 default base + 4 skin-tone variants. The
        // resource paths follow the convention documented on
        // CultureBase. Files are not authored in v1; the paths are
        // canonical for the future art pass.
        registerCultureBase(culture("plainfolk", 4));
        registerCultureBase(culture("highmarch", 4));
        registerCultureBase(culture("silkwood",  4));
        registerCultureBase(culture("tidereach", 4));
        registerCultureBase(culture("default",   3));
    }

    private static CultureBase culture(String id, int variantCount) {
        Identifier base = textureOf("entity/townsperson/culture/" + id + "/base");
        List<Identifier> variants = new ArrayList<>();
        for (int i = 0; i < variantCount; i++) {
            variants.add(textureOf("entity/townsperson/culture/" + id + "/skin_" + i));
        }
        return new CultureBase(id, base, variants);
    }

    private static void registerOfficeMarkDefaults() {
        // Per spec § Office marks line 116-130. Priority drives the
        // visual cap to top-3 when an NPC holds many offices.
        registerOfficeMark(OfficeMark.overlayOnly(OfficeRegistry.VILLAGE_LEADER,
                officeOverlay("village_leader_sash"), 100, "Sash"));
        registerOfficeMark(OfficeMark.overlayOnly(OfficeRegistry.VILLAGE_BAILIFF,
                officeOverlay("village_bailiff_badge"), 80, "Badge"));
        registerOfficeMark(OfficeMark.withAttachment(OfficeRegistry.VILLAGE_CONSTABLE,
                officeOverlay("village_constable_belt"),
                attachmentOf("village_constable_staff"), 75, "Staff"));
        registerOfficeMark(OfficeMark.withAttachment(OfficeRegistry.VILLAGE_SCRIBE,
                officeOverlay("village_scribe_apron"),
                attachmentOf("village_scribe_quill"), 70, "Quill"));
        registerOfficeMark(OfficeMark.overlayOnly(OfficeRegistry.VILLAGE_PRIEST,
                officeOverlay("village_priest_stole"), 85, "Stole"));
        registerOfficeMark(OfficeMark.withAttachment(OfficeRegistry.VILLAGE_HEALER,
                officeOverlay("village_healer_apron"),
                attachmentOf("village_healer_pouch"), 65, "Healer Pouch"));
        registerOfficeMark(OfficeMark.overlayOnly(OfficeRegistry.VILLAGE_TREASURER,
                officeOverlay("village_treasurer_stripe"), 60, "Stripe"));
        registerOfficeMark(OfficeMark.overlayOnly(OfficeRegistry.GUILD_MASTER,
                officeOverlay("guild_master_sash"), 90, "Guild Sash"));
        registerOfficeMark(OfficeMark.overlayOnly(OfficeRegistry.GUILD_TREASURER,
                officeOverlay("guild_treasurer_stripe"), 50, "Guild Stripe"));
        registerOfficeMark(OfficeMark.overlayOnly(OfficeRegistry.GUILD_REGISTRAR,
                officeOverlay("guild_registrar_stripe"), 50, "Registrar Stripe"));
        registerOfficeMark(OfficeMark.overlayOnly(OfficeRegistry.MASTER_OF_APPRENTICES,
                officeOverlay("master_of_apprentices_collar"), 55, "Collar"));
        registerOfficeMark(OfficeMark.overlayOnly(OfficeRegistry.BUSINESS_OWNER,
                officeOverlay("business_owner_mantle"), 70, "Mantle"));
        registerOfficeMark(OfficeMark.withAttachment(OfficeRegistry.KINGDOM_KING,
                officeOverlay("kingdom_king_chain"),
                attachmentOf("kingdom_king_crown"), 200, "Crown"));
        registerOfficeMark(OfficeMark.withAttachment(OfficeRegistry.KINGDOM_CHANCELLOR,
                officeOverlay("kingdom_chancellor_chain"),
                attachmentOf("kingdom_chancellor_chain_model"), 150, "Chain of Office"));
        registerOfficeMark(OfficeMark.overlayOnly(OfficeRegistry.KINGDOM_TREASURER,
                officeOverlay("kingdom_treasurer_stripe"), 70, "Royal Stripe"));
        registerOfficeMark(OfficeMark.overlayOnly(OfficeRegistry.KINGDOM_COUNCIL_SEAT,
                officeOverlay("kingdom_council_pin"), 60, "Council Pin"));
        registerOfficeMark(OfficeMark.overlayOnly(OfficeRegistry.TEMPLE_HIGH_PRIEST,
                officeOverlay("temple_high_priest_stole"), 100, "High Stole"));
    }

    private static void registerAccessoryDefaults() {
        // Per spec § Cultural accessories line 137-148.
        register("plainfolk_cap",        AccessorySlot.HEAD,     "Leather Cap");
        register("plainfolk_belt",       AccessorySlot.BELT,     "Worn Belt");
        register("highmarch_cloak",      AccessorySlot.SHOULDER, "Heraldic Cloak");
        register("highmarch_arm_guard",  AccessorySlot.SHOULDER, "Arm Guard");
        register("silkwood_scroll",      AccessorySlot.BELT,     "Bound Scroll");
        register("silkwood_sash",        AccessorySlot.BELT,     "Patterned Sash");
        register("tidereach_pendant",    AccessorySlot.SHOULDER, "Shell Pendant");
        register("tidereach_cap",        AccessorySlot.HEAD,     "Sailor's Cap");

        // Gift-driven accessories. Spec § "On gift" lists circlet
        // and pendant. The slot keeps Layer 1 conflict resolution
        // simple — a head circlet doesn't compete with a culture
        // shoulder accessory.
        register("gift_circlet",         AccessorySlot.HEAD,     "Circlet");
        register("gift_pendant",         AccessorySlot.SHOULDER, "Pendant");

        // Per-culture pools — entry 0 is the canonical accessory,
        // remaining entries are rare variants picked by the 5% roll.
        registerCultureAccessoryPool("plainfolk", List.of("plainfolk_cap", "plainfolk_belt"));
        registerCultureAccessoryPool("highmarch", List.of("highmarch_cloak", "highmarch_arm_guard"));
        registerCultureAccessoryPool("silkwood",  List.of("silkwood_scroll", "silkwood_sash"));
        registerCultureAccessoryPool("tidereach", List.of("tidereach_pendant", "tidereach_cap"));
        registerCultureAccessoryPool("default",   List.of());
    }

    private static void register(String id, AccessorySlot slot, String label) {
        registerAccessory(AccessoryDefinition.textureOnly(
                id, accessoryTexture(id), slot, label));
    }

    // ── Path conventions ────────────────────────────────────────────

    private static Identifier textureOf(String relPath) {
        return Identifier.fromNamespaceAndPath(NS, "textures/" + relPath + ".png");
    }

    private static Identifier officeOverlay(String name) {
        return textureOf("entity/townsperson/office/" + name);
    }

    private static Identifier accessoryTexture(String name) {
        return textureOf("entity/townsperson/accessory/" + name);
    }

    private static Identifier attachmentOf(String name) {
        return Identifier.fromNamespaceAndPath(NS,
                "models/entity/townsperson/attachment/" + name + ".json");
    }
}
