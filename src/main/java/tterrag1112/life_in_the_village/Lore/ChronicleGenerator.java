package tterrag1112.life_in_the_village.Lore;

import tterrag1112.life_in_the_village.Kingdom.Kingdom;
import tterrag1112.life_in_the_village.Lore.KingdomHistoryData;

import java.util.List;
import java.util.stream.Collectors;

public class ChronicleGenerator {

    public static String buildPrompt(Kingdom kingdom) {
        KingdomHistoryData history = kingdom.getHistory();
        StringBuilder prompt = new StringBuilder();

        prompt.append("You are a royal scribe writing ")
                .append("the official chronicle of a ")
                .append("medieval kingdom in a Minecraft ")
                .append("world. Write a flowing narrative ")
                .append("history in the style of a medieval ")
                .append("chronicle — third person, past tense, ")
                .append("formal but readable. Weave the events ")
                .append("into connected paragraphs, not a list. ")
                .append("Keep it under 200 words.\n\n");

        prompt.append("Kingdom: ")
                .append(kingdom.getName()).append("\n");
        prompt.append("Culture: ")
                .append(kingdom.getCulture()).append("\n");

        // Origin
        history.getOrigin().ifPresent(origin -> {
            prompt.append("Founded by: ")
                    .append(origin.founderName())
                    .append(" via ")
                    .append(origin.origin().name()
                            .replace("_", " ").toLowerCase())
                    .append(" on day ")
                    .append(origin.foundingTick() / 24000L)
                    .append(" from ")
                    .append(origin.foundingVillageName())
                    .append(".\n");

            if (!origin.foundingStatement().isEmpty()) {
                prompt.append("Founding motto: ")
                        .append(origin.foundingStatement())
                        .append("\n");
            }
        });

        // Events
        List<KingdomHistoryData.KingdomHistoryEvent> events
                = history.getEvents();
        if (!events.isEmpty()) {
            prompt.append("\nKey events:\n");
            events.forEach(e ->
                    prompt.append("- Day ")
                            .append(e.tick() / 24000L)
                            .append(": ")
                            .append(e.title())
                            .append(" — ")
                            .append(e.description())
                            .append(e.involvedParty().isEmpty()
                                    ? "" : " (involving "
                                    + e.involvedParty() + ")")
                            .append("\n"));
        }

        prompt.append("\nWrite the chronicle now:");
        return prompt.toString();
    }
}