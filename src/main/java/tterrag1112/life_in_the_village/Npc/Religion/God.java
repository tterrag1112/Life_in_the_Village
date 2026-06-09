package tterrag1112.life_in_the_village.Npc.Religion;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import tterrag1112.life_in_the_village.Npc.Religion.ReligionIdentity.DeityDomain;
import tterrag1112.life_in_the_village.Npc.Religion.ReligionIdentity.Taboo;
import tterrag1112.life_in_the_village.Npc.Religion.ReligionIdentity.Virtue;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Foundation 1 (F1a) — a first-class <b>God</b>: a divine identity separated from
 * the religion that venerates it. Today a {@link Religion} fuses exactly one deity
 * (a name string in {@link Religion#deity()} + the rich layer in
 * {@link ReligionIdentity}); the target model is a standalone God that a religion
 * references (one or many). This sub-stage only introduces the type + the registry
 * ({@link GodRegistry}); nothing existing is re-keyed yet.
 *
 * <p><b>Type reuse (deliberate).</b> Lives in {@code Npc.Religion} so it can reuse
 * the existing nested types {@link DeityDomain} / {@link Virtue} / {@link Taboo}
 * <i>as-is</i> — a later cleanup sub-stage relocates those onto {@code God}; moving
 * them now would churn consumers. The {@code virtues}/{@code taboos} here are
 * <i>the god's</i> demanded virtues / forbidden acts (mirrored from the religion's
 * {@link ReligionIdentity} for now; the god becomes the sole source in sub-stage 2).</p>
 *
 * @param name     the personal name ("the Sun-Mother"); <b>empty for an impersonal
 *                 god</b> (the Pattern) — the future home of {@link Religion#deity()}
 * @param virtues  the god's demanded virtues (reused {@link Virtue})
 * @param taboos   the god's forbidden acts (reused {@link Taboo})
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
