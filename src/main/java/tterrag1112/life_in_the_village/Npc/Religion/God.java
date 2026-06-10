package tterrag1112.life_in_the_village.Npc.Religion;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Foundation 1 (F1a) — a first-class <b>God</b>: a divine identity separated from
 * the religion that venerates it. A {@link Religion} references its god(s) by id
 * ({@link Religion#godIds()}); a god is authored once, in {@link GodRegistry}, and
 * may be shared across faiths.
 *
 * <p><b>The authored source (F1a cleanup).</b> The god carries the full deity
 * identity — name, {@link DeityDomain}, character, demands, rewards, the demanded
 * {@link Virtue}s and forbidden {@link Taboo}s. {@link ReligionIdentity} keeps only
 * the religion NARRATIVE (cosmology / sacred history / aesthetics / practices); the
 * deity content was inverted onto the god and the old {@code Religion.deity()} /
 * identity deity fields deleted. The deity TYPES ({@link DeityDomain}, {@link
 * Virtue}, {@link Taboo}) are top-level in {@code Npc.Religion}.</p>
 *
 * @param name     the personal name ("the Sun-Mother"); <b>empty for an impersonal
 *                 god</b> (the Pattern)
 * @param virtues  the god's demanded virtues
 * @param taboos   the god's forbidden acts
 */
public record God(
        String id,
        Optional<String> name,
        DeityDomain domain,
        String character,
        String demands,
        String rewards,
        List<Virtue> virtues,
        List<Taboo> taboos
) {
    public God {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("god id required");
        name    = name    == null ? Optional.empty() : name;
        virtues = virtues == null ? List.of() : List.copyOf(virtues);
        taboos  = taboos  == null ? List.of() : List.copyOf(taboos);
    }

    /** The personal name, or a domain-sensible fallback for an impersonal god. */
    public String displayName() {
        return name.orElseGet(() -> switch (domain) {
            case SUN   -> "the Sun";
            case SEA   -> "the Sea";
            case FORGE -> "the Forge";
            case FATE  -> "the Pattern";
        });
    }

    /** True for an impersonal god (no personified name — the Pattern). */
    public boolean isImpersonal() { return name.isEmpty(); }

    // ── Codec (forward-compat for later content-pack gods; mirrors Religion.CODEC).
    //    8 fields — well under the 16-field RecordCodecBuilder ceiling; gods will
    //    later gain a sacred-space rule / miracle set / holy days / oaths. ──────

    private static final Codec<DeityDomain> DOMAIN_CODEC = Codec.STRING.xmap(
            s -> DeityDomain.valueOf(s.toUpperCase(Locale.ROOT)), DeityDomain::name);
    private static final Codec<FaithConcept> CONCEPT_CODEC = Codec.STRING.xmap(
            s -> FaithConcept.valueOf(s.toUpperCase(Locale.ROOT)), FaithConcept::name);
    private static final Codec<Virtue> VIRTUE_CODEC = RecordCodecBuilder.create(i -> i.group(
            CONCEPT_CODEC.fieldOf("concept").forGetter(Virtue::concept),
            Codec.STRING.fieldOf("text").forGetter(Virtue::text)
    ).apply(i, Virtue::new));
    private static final Codec<Taboo> TABOO_CODEC = RecordCodecBuilder.create(i -> i.group(
            CONCEPT_CODEC.fieldOf("concept").forGetter(Taboo::concept),
            Codec.STRING.fieldOf("text").forGetter(Taboo::text)
    ).apply(i, Taboo::new));

    public static final Codec<God> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.fieldOf("id").forGetter(God::id),
            Codec.STRING.optionalFieldOf("name").forGetter(God::name),
            DOMAIN_CODEC.fieldOf("domain").forGetter(God::domain),
            Codec.STRING.optionalFieldOf("character", "").forGetter(God::character),
            Codec.STRING.optionalFieldOf("demands", "").forGetter(God::demands),
            Codec.STRING.optionalFieldOf("rewards", "").forGetter(God::rewards),
            VIRTUE_CODEC.listOf().optionalFieldOf("virtues", List.of()).forGetter(God::virtues),
            TABOO_CODEC.listOf().optionalFieldOf("taboos", List.of()).forGetter(God::taboos)
    ).apply(i, God::new));
}
