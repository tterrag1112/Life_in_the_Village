package tterrag1112.life_in_the_village.Entities.custom.Appearance;


import net.minecraft.resources.Identifier;

import java.util.Optional;

/**
 * Visual mark of an office holder. Phase 5 doc 33 § Office marks.
 *
 * <p>{@code overlayTexture} renders additively over the base body
 * texture (sash / collar / badge / apron). {@code attachmentModel}
 * is an optional 3D piece attached at a body slot (constable's
 * staff, scribe's quill, king's crown). Phase 5 v1 ships the
 * registry without art; the resource paths describe what's
 * expected.</p>
 *
 * <p>{@code priority} determines render order when the NPC holds
 * multiple offices. Per spec edge case "5 offices simultaneously",
 * the renderer caps to the top 3 by priority.</p>
 */
public record OfficeMark(
        String officeId,
        Identifier overlayTexture,
        Optional<Identifier> attachmentModel,
        int priority,
        String shortLabel
) {
    public OfficeMark {
        if (attachmentModel == null) attachmentModel = Optional.empty();
        if (shortLabel == null) shortLabel = officeId;
    }

    public static OfficeMark overlayOnly(String officeId, Identifier overlay,
                                          int priority, String shortLabel) {
        return new OfficeMark(officeId, overlay, Optional.empty(), priority, shortLabel);
    }

    public static OfficeMark withAttachment(String officeId, Identifier overlay,
                                             Identifier attachment, int priority,
                                             String shortLabel) {
        return new OfficeMark(officeId, overlay, Optional.of(attachment), priority, shortLabel);
    }
}
