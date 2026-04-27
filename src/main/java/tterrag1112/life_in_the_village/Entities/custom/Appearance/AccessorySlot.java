package tterrag1112.life_in_the_village.Entities.custom.Appearance;

/**
 * Body slot for an {@link AccessoryDefinition}. Determines where the
 * accessory model attachment renders relative to the villager rig.
 *
 * <p>Phase 5 doc 33 § Data model. Phase 5 v1 ships infrastructure
 * only — actual model attachments are not authored, so the slot is
 * informational at v1 (drives the profile-summary text + accessory
 * conflict resolution in {@link AppearanceComponent}).</p>
 */
public enum AccessorySlot {
    HEAD,
    SHOULDER,
    BELT,
    BACK
}
