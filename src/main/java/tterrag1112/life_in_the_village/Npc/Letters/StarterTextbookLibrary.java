package tterrag1112.life_in_the_village.Npc.Letters;

import net.minecraft.world.item.ItemStack;
import tterrag1112.life_in_the_village.Npc.Scribal.ScribalItems;
import tterrag1112.life_in_the_village.Npc.Skills.Skill;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Phase 2 ships four starter textbooks per spec line 263. Each
 * carries a {@link SkillBuff} and a small topics list. Phase 5
 * content pass expands to 20+ titles with richer prose.
 *
 * <p>Library code authoring (kingdom histories, village ledgers) is
 * procedural — see {@code ProceduralBookFactory}. The starter set
 * here is the "bookshelf at world-start" library that scholars and
 * librarians can already cite.</p>
 */
public final class StarterTextbookLibrary {

    public record StarterBook(String id, String title, String author,
                              BookCategory category,
                              List<String> topics,
                              SkillBuff buff,
                              String body) {}

    private static final Map<String, StarterBook> BOOKS = new LinkedHashMap<>();

    static {
        register(new StarterBook(
                "treatise_on_forges",
                "A Treatise on Forges",
                "Master Aldric the Smith",
                BookCategory.GUIDE,
                List.of("crafting.smelting", "crafting.alloy", "crafting.tempering"),
                new SkillBuff(Skill.CRAFTING, 50, 1.15f, 7),
                "On the steady heat of a coal forge: keep thy bellows even, "
                        + "and thy iron will yield. Strike thrice on the rim, "
                        + "twice on the spine. The smith's hand learns the "
                        + "tempering by feel alone — books cannot teach the "
                        + "moment of quench, only the years before it."));

        register(new StarterBook(
                "herbal_remedies_lowlands",
                "Herbal Remedies of the Lowlands",
                "Cleric Mira of the Vale",
                BookCategory.MEDICAL,
                List.of("medicine.fever", "medicine.poultice", "medicine.dosing"),
                new SkillBuff(Skill.MEDICINE, 50, 1.10f, 14),
                "For the fever that comes with the autumn rains, brew "
                        + "elderflower with honey. For wounds that weep, "
                        + "yarrow leaf bound tight; change the binding at "
                        + "each evening bell. Where the herb does not grow, "
                        + "ask the scholars — they have read further than I."));

        register(new StarterBook(
                "swordsmanship_primer",
                "A Swordsmanship Primer",
                "Captain Hael of the Watch",
                BookCategory.GUIDE,
                List.of("combat.guard_stance", "combat.parry"),
                new SkillBuff(Skill.COMBAT, 30, 1.0f, 0),
                "The first lesson of the blade is patience. Stand square. "
                        + "Watch the shoulders, not the steel. Most blows "
                        + "are foretold by a breath."));

        register(new StarterBook(
                "ledger_keeping",
                "On the Keeping of Ledgers",
                "Scribe Owin of the Town Hall",
                BookCategory.GUIDE,
                List.of("commerce.tally", "commerce.receipts", "literacy.shorthand"),
                new SkillBuff(Skill.COMMERCE, 40, 1.10f, 10),
                "A ledger is a memory the village shares. Date every entry. "
                        + "Cross-reference before crossing out. The honest "
                        + "scribe writes plainly so the next scribe may "
                        + "follow."));
    }

    private StarterTextbookLibrary() {}

    public static void register(StarterBook book) {
        BOOKS.put(book.id(), book);
    }

    public static Optional<StarterBook> get(String id) {
        return Optional.ofNullable(BOOKS.get(id));
    }

    public static List<StarterBook> all() { return List.copyOf(BOOKS.values()); }

    /** Mints an ItemStack copy of the named textbook. */
    public static Optional<ItemStack> mint(String id, long currentTick) {
        return get(id).map(b -> ScribalItems.book(
                b.title(), b.author(), b.body(),
                b.category(), b.topics(),
                Optional.empty(), Optional.of(b.buff()), currentTick));
    }
}
