package tterrag1112.life_in_the_village.Village.Decoration.Variants;

import java.util.Locale;
import java.util.Optional;

/**
 * Concrete folder/village style. Each authored variant lives under a
 * {@code rural/} or {@code urban/} folder and a village picks one
 * {@code Style} for its placement run.
 *
 * <p>Distinct from {@link StylePreference} which is the variant-side
 * soft bias and may be {@code ANY}; {@link Style} is always one of
 * {@link #RURAL} / {@link #URBAN}.</p>
 */
public enum Style {
    RURAL, URBAN;

    /** Folder-segment representation: lower-case enum name. */
    public String folder() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Parses the folder-segment representation. Returns empty for
     *  unknown values (e.g. {@code "default"} or future style folders
     *  that don't yet have an enum value). */
    public static Optional<Style> fromFolder(String folder) {
        if (folder == null) return Optional.empty();
        for (Style s : values()) if (s.folder().equals(folder)) {
            return Optional.of(s);
        }
        return Optional.empty();
    }
}
