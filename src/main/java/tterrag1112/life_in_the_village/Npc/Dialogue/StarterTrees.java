package tterrag1112.life_in_the_village.Npc.Dialogue;

import tterrag1112.life_in_the_village.Npc.Memory.MemoryType;
import tterrag1112.life_in_the_village.Npc.Mood.MoodCategory;
import tterrag1112.life_in_the_village.Npc.Traits.TraitAxis;

import java.util.List;

import static tterrag1112.life_in_the_village.Npc.Dialogue.DialogueNode.branch;
import static tterrag1112.life_in_the_village.Npc.Dialogue.DialogueNode.lines;

/**
 * Phase 1 starter trees per spec
 * {@code 08-dialogue-tree.md} line 261. 25 trees total covering
 * greeting, farewell, trade-related, gossip, goal-related,
 * verb-triggered, event-related, and job-related contexts.
 *
 * <p>Content is intentionally minimal — three lines per pool
 * (placeholders carry the load of variation). Phase 5 content pass
 * expands every pool to 15-20 lines and adds culture-specific
 * variants.</p>
 *
 * <p>Tree IDs follow the spec's hierarchy
 * {@code intent.listenerType.role.modifier}; the registry's
 * fallback walk drops the rightmost segment until something matches.</p>
 */
public final class StarterTrees {

    private StarterTrees() {}

    static synchronized void registerAll() {
        // Universal fallback first — guarantees the registry can always
        // satisfy a lookup.
        DialogueRegistry.register(new DialogueTree(
                DialogueRegistry.UNIVERSAL_FALLBACK,
                lines("...",
                        "Hm.",
                        "What is it, {listener.name}?")));

        // ── Greetings (3) ────────────────────────────────────────────────
        DialogueRegistry.register(greetingPlayerDefault());
        DialogueRegistry.register(greetingPlayerShopkeeper());
        DialogueRegistry.register(greetingPlayerLeader());

        // ── Farewells (3) ────────────────────────────────────────────────
        DialogueRegistry.register(farewellPlayerDefault());
        DialogueRegistry.register(farewellPlayerFriend());
        DialogueRegistry.register(farewellPlayerDistressed());

        // ── Trade-related (4) ────────────────────────────────────────────
        DialogueRegistry.register(tradeOpen());
        DialogueRegistry.register(tradeRefuse());
        DialogueRegistry.register(tradeThank());
        DialogueRegistry.register(tradeHaggle());

        // ── Gossip (3) ───────────────────────────────────────────────────
        DialogueRegistry.register(gossipTell());
        DialogueRegistry.register(gossipHear());
        DialogueRegistry.register(gossipDeny());

        // ── Goal-related (3) ─────────────────────────────────────────────
        DialogueRegistry.register(askAboutLife());
        DialogueRegistry.register(offerHelp());
        DialogueRegistry.register(acceptHelp());

        // ── Verb-triggered (3) ───────────────────────────────────────────
        DialogueRegistry.register(complimentResponse());
        DialogueRegistry.register(insultResponse());
        DialogueRegistry.register(askAboutOther());

        // ── Event-related (2) ────────────────────────────────────────────
        DialogueRegistry.register(festivalGreeting());
        DialogueRegistry.register(mourning());

        // ── Job-related (4) ──────────────────────────────────────────────
        DialogueRegistry.register(jobAccept());
        DialogueRegistry.register(jobRefuse());
        DialogueRegistry.register(jobDeliver());
        DialogueRegistry.register(jobFire());
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private static DialogueLine line(String text) { return DialogueLine.of(text); }
    private static DialogueLine line(String text, DialogueEffect... effects) {
        return DialogueLine.of(text, List.of(effects));
    }
    private static DialogueNode pool(String... texts) { return lines(texts); }

    private static DialoguePredicate hasMemoryOf(MemoryType type) {
        return new DialoguePredicate.HasMemoryOf(type, Target.LISTENER);
    }
    private static DialoguePredicate moodIs(MoodCategory cat) {
        return new DialoguePredicate.MoodAtCategory(cat);
    }
    private static DialoguePredicate relationshipAtLeast(int min) {
        return new DialoguePredicate.RelationshipAtLeast(Target.LISTENER, min);
    }
    private static DialoguePredicate traitGreater(TraitAxis axis, float t) {
        return new DialoguePredicate.TraitGreater(axis, t);
    }
    private static DialoguePredicate traitLess(TraitAxis axis, float t) {
        return new DialoguePredicate.TraitLess(axis, t);
    }

    // ── Greetings ─────────────────────────────────────────────────────────

    private static DialogueTree greetingPlayerDefault() {
        // Saved-by trumps everything; insulted-by next; otherwise mood/relationship branches.
        return new DialogueTree("greeting.player.default",
                branch(hasMemoryOf(MemoryType.SAVED_BY),
                        pool("My savior returns. What brings you, {listener.name}?",
                                "You again, {listener.name}. I owe you my life.",
                                "I haven't forgotten what you did, {listener.name}."),
                        branch(hasMemoryOf(MemoryType.INSULTED_BY),
                                pool("What do you want now?",
                                        "Haven't you said enough?",
                                        "Make it quick, {listener.name}."),
                                branch(relationshipAtLeast(40),
                                        pool("{listener.name}! Always good to see you.",
                                                "Ah, friend — what's on your mind?",
                                                "Welcome back, {listener.name}."),
                                        branch(moodIs(MoodCategory.DISTRESSED),
                                                pool("I... I haven't been myself.",
                                                        "Forgive me. It's been a hard week.",
                                                        "Not now, please."),
                                                branch(traitGreater(TraitAxis.SOCIABILITY, 0.4f),
                                                        pool("Hello there, {listener.name}!",
                                                                "Lovely day, isn't it?",
                                                                "Well met, {listener.name}!"),
                                                        pool("Yes?",
                                                                "What brings you here?",
                                                                "Hm. Hello.")))))));
    }

    private static DialogueTree greetingPlayerShopkeeper() {
        return new DialogueTree("greeting.player.shopkeeper",
                branch(relationshipAtLeast(40),
                        pool("Ah, {listener.name}! Back for more?",
                                "Always a pleasure, {listener.name}. What'll it be?",
                                "Welcome back. Same as last time?"),
                        branch(moodIs(MoodCategory.DISTRESSED),
                                pool("Hmph. What do you want.",
                                        "If you're buying, get on with it.",
                                        "I'm not in the mood today."),
                                pool("Welcome to {village.name}. What can I get you?",
                                        "Looking for something specific?",
                                        "Browse if you like. Prices are firm."))));
    }

    private static DialogueTree greetingPlayerLeader() {
        return new DialogueTree("greeting.player.leader",
                branch(relationshipAtLeast(20),
                        pool("Welcome to {village.name}, friend.",
                                "Good to see a familiar face.",
                                "What can {village.name} do for you, {listener.name}?"),
                        pool("State your business in {village.name}.",
                                "I'm responsible for this village. Speak clearly.",
                                "What is it, traveler?")));
    }

    // ── Farewells ─────────────────────────────────────────────────────────

    private static DialogueTree farewellPlayerDefault() {
        return new DialogueTree("farewell.player.default",
                pool("Safe travels.",
                        "Take care, {listener.name}.",
                        "Good day."));
    }

    private static DialogueTree farewellPlayerFriend() {
        return new DialogueTree("farewell.player.friend",
                pool("Don't be a stranger, {listener.name}.",
                        "Until next time, friend.",
                        "Come back soon. The {village.name} could use more like you."));
    }

    private static DialogueTree farewellPlayerDistressed() {
        return new DialogueTree("farewell.player.distressed",
                pool("Just... go.",
                        "Leave me be.",
                        "I need to be alone."));
    }

    // ── Trade ─────────────────────────────────────────────────────────────

    private static DialogueTree tradeOpen() {
        return new DialogueTree("trade.open.default",
                lines(
                        DialogueLine.of("Have a look, {listener.name}.",
                                List.of(DialogueEffect.openScreen("trade"))),
                        DialogueLine.of("Coin first, then we'll talk.",
                                List.of(DialogueEffect.openScreen("trade"))),
                        DialogueLine.of("Standard prices for everyone.",
                                List.of(DialogueEffect.openScreen("trade")))));
    }

    private static DialogueTree tradeRefuse() {
        return new DialogueTree("trade.refuse.default",
                branch(hasMemoryOf(MemoryType.INSULTED_BY),
                        pool("After what you said? Not a chance.",
                                "Find another merchant.",
                                "Your coin's not welcome here."),
                        pool("Sorry — closed for the day.",
                                "I haven't the stock for that.",
                                "Come back later, {listener.name}.")));
    }

    private static DialogueTree tradeThank() {
        return new DialogueTree("trade.thank.default",
                pool("Pleasure doing business, {listener.name}.",
                        "Coin well spent. See you again.",
                        "Thank you. Tell your friends about {village.name}."));
    }

    private static DialogueTree tradeHaggle() {
        return new DialogueTree("trade.haggle.default",
                branch(traitGreater(TraitAxis.GENEROSITY, 0.3f),
                        pool("Fine, fine — for you, {listener.name}, a small discount.",
                                "Alright, alright. You drive a hard bargain.",
                                "Take it. I won't argue with a friend."),
                        branch(traitLess(TraitAxis.GENEROSITY, -0.3f),
                                pool("My prices are fair. Pay or move along.",
                                        "Haggle? Find another shop.",
                                        "The price is the price."),
                                pool("I might come down a little.",
                                        "Let's say... a little less, then.",
                                        "I can shave a few coins off. No more."))));
    }

    // ── Gossip ────────────────────────────────────────────────────────────

    private static DialogueTree gossipTell() {
        return new DialogueTree("gossip.tell.default",
                pool("Word from {village.name} — they say {goal.primary.narrative}",
                        "Heard a story you'll like. {goal.primary.narrative}",
                        "Bit of news for you: {goal.primary.narrative}"));
    }

    private static DialogueTree gossipHear() {
        return new DialogueTree("gossip.hear.default",
                branch(traitGreater(TraitAxis.SOCIABILITY, 0.3f),
                        pool("Oh? Tell me more.",
                                "Really? Where'd you hear that?",
                                "I'd not heard. Go on."),
                        pool("Hm.",
                                "If you say so.",
                                "Mind your own business, {listener.name}.")));
    }

    private static DialogueTree gossipDeny() {
        return new DialogueTree("gossip.deny.default",
                pool("That's not how it happened.",
                        "Where did you hear THAT?",
                        "People will say anything."));
    }

    // ── Goal-related ──────────────────────────────────────────────────────

    private static DialogueTree askAboutLife() {
        return new DialogueTree("ask_life.player.default",
                branch(relationshipAtLeast(20),
                        pool("Truth is, {goal.primary.narrative}",
                                "I'll tell you. {goal.primary.narrative}",
                                "Most days I'm trying to: {goal.primary.narrative}"),
                        branch(traitGreater(TraitAxis.HONESTY, 0.4f),
                                pool("Honestly? I've got my hands full with: {goal.primary.narrative}",
                                        "The plain truth: {goal.primary.narrative}",
                                        "{goal.primary.narrative}. That's about the size of it."),
                                pool("Same as anyone. Working, eating, sleeping.",
                                        "Nothing special.",
                                        "What's it to you?"))));
    }

    private static DialogueTree offerHelp() {
        return new DialogueTree("offer_help.player.default",
                pool("You'd help me with that?",
                        "I'd appreciate it, {listener.name}.",
                        "Coming from you, that means something."));
    }

    private static DialogueTree acceptHelp() {
        return new DialogueTree("accept_help.player.default",
                lines(
                        DialogueLine.of("Bless you, {listener.name}.",
                                List.of(DialogueEffect.relationshipDelta("LISTENER", 5))),
                        DialogueLine.of("I won't forget this.",
                                List.of(DialogueEffect.relationshipDelta("LISTENER", 5))),
                        DialogueLine.of("Thank you. Truly.",
                                List.of(DialogueEffect.relationshipDelta("LISTENER", 5)))));
    }

    // ── Verb-triggered ────────────────────────────────────────────────────

    private static DialogueTree complimentResponse() {
        return new DialogueTree("compliment.response.default",
                branch(traitGreater(TraitAxis.SOCIABILITY, 0.3f),
                        pool("That's kind of you to say, {listener.name}!",
                                "Why, thank you!",
                                "You flatter me — but please, go on."),
                        branch(traitLess(TraitAxis.HONESTY, -0.3f),
                                pool("Hm. Wonder what you want.",
                                        "Compliments don't come free, do they?",
                                        "Save it for someone who needs it."),
                                pool("Thank you.",
                                        "That's good of you to say.",
                                        "Mm. Kind."))));
    }

    private static DialogueTree insultResponse() {
        return new DialogueTree("insult.response.default",
                branch(traitGreater(TraitAxis.TEMPERANCE, 0.3f),
                        pool("I'll forget you said that.",
                                "If that's how you feel, fine.",
                                "Your loss, {listener.name}."),
                        pool("How DARE you.",
                                "Get out of my sight.",
                                "You'll regret saying that.")));
    }

    private static DialogueTree askAboutOther() {
        return new DialogueTree("ask_about.other.default",
                pool("I don't know much about that.",
                        "You'd have to ask someone else.",
                        "Last I heard? Nothing useful."));
    }

    // ── Event-related ─────────────────────────────────────────────────────

    private static DialogueTree festivalGreeting() {
        return new DialogueTree("greeting.player.default.festival",
                pool("It's a fine day for the festival, isn't it?",
                        "Don't miss the celebrations in {village.name}!",
                        "Come, come — the village is in good spirits today."));
    }

    private static DialogueTree mourning() {
        return new DialogueTree("greeting.player.default.mourning",
                pool("It's been a hard week. Forgive me.",
                        "We've lost too many. Speak gently.",
                        "Not today, please. {village.name} mourns."));
    }

    // ── Job-related ───────────────────────────────────────────────────────

    private static DialogueTree jobAccept() {
        return new DialogueTree("job.accept.default",
                pool("I'll take it. When do I start?",
                        "Glad for the work.",
                        "Aye. I'm your hand."));
    }

    private static DialogueTree jobRefuse() {
        return new DialogueTree("job.refuse.default",
                pool("I've enough on my plate.",
                        "Find someone else.",
                        "Not for me, sorry."));
    }

    private static DialogueTree jobDeliver() {
        return new DialogueTree("job.deliver.default",
                pool("Done. Here you are.",
                        "Finished, as promised.",
                        "Just as you asked."));
    }

    private static DialogueTree jobFire() {
        return new DialogueTree("job.fire.default",
                pool("That's it, then. We're done.",
                        "Take your tools and go.",
                        "I'll need you to move along."));
    }
}
