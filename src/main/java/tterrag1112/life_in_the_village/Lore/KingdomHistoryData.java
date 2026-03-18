package tterrag1112.life_in_the_village.Lore;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import tterrag1112.life_in_the_village.Kingdom.DiplomaticRelation;
import tterrag1112.life_in_the_village.Kingdom.Kingdom;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class KingdomHistoryData {

    public record KingdomOriginData(
            KingdomOrigins origin,
            String founderName,       // player name or NPC name
            UUID founderPlayerId,     // null if NPC founded
            String foundingVillageName,
            long foundingTick,
            int villageCountAtFounding,
            String foundingStatement  // a short lore blurb
    ) {
        public static final Codec<KingdomOriginData> CODEC =
                RecordCodecBuilder.create(i -> i.group(
                        Codec.STRING.xmap(KingdomOrigins::valueOf,
                                        KingdomOrigins::name)
                                .fieldOf("origin")
                                .forGetter(KingdomOriginData::origin),
                        Codec.STRING.fieldOf("founderName")
                                .forGetter(KingdomOriginData::founderName),
                        Codec.STRING.xmap(UUID::fromString,
                                        UUID::toString)
                                .optionalFieldOf("founderPlayerId",
                                        new UUID(0, 0))
                                .forGetter(KingdomOriginData::founderPlayerId),
                        Codec.STRING.fieldOf("foundingVillageName")
                                .forGetter(KingdomOriginData::foundingVillageName),
                        Codec.LONG.fieldOf("foundingTick")
                                .forGetter(KingdomOriginData::foundingTick),
                        Codec.INT.fieldOf("villageCountAtFounding")
                                .forGetter(KingdomOriginData::villageCountAtFounding),
                        Codec.STRING.fieldOf("foundingStatement")
                                .forGetter(KingdomOriginData::foundingStatement)
                ).apply(i, KingdomOriginData::new));
    }
    public record KingdomHistoryEvent(
            UUID eventId,
            HistoryEventType type,
            String title,
            String description,
            long tick,
            String involvedParty,
            String generatedText  // NEW — persisted narrative text
    ) {
        public static final Codec<KingdomHistoryEvent> CODEC =
                RecordCodecBuilder.create(i -> i.group(
                        Codec.STRING.xmap(UUID::fromString,
                                        UUID::toString)
                                .fieldOf("eventId")
                                .forGetter(KingdomHistoryEvent::eventId),
                        Codec.STRING.xmap(
                                        HistoryEventType::valueOf,
                                        HistoryEventType::name)
                                .fieldOf("type")
                                .forGetter(KingdomHistoryEvent::type),
                        Codec.STRING.fieldOf("title")
                                .forGetter(KingdomHistoryEvent::title),
                        Codec.STRING.fieldOf("description")
                                .forGetter(KingdomHistoryEvent::description),
                        Codec.LONG.fieldOf("tick")
                                .forGetter(KingdomHistoryEvent::tick),
                        Codec.STRING.fieldOf("involvedParty")
                                .forGetter(KingdomHistoryEvent::involvedParty),
                        Codec.STRING.optionalFieldOf(
                                        "generatedText", "")
                                .forGetter(KingdomHistoryEvent::generatedText)
                ).apply(i, KingdomHistoryEvent::new));

        public static KingdomHistoryEvent create(
                HistoryEventType type, String title,
                String description, long tick,
                String involvedParty) {
            return new KingdomHistoryEvent(
                    UUID.randomUUID(), type, title,
                    description, tick, involvedParty,
                    ""); // text generated on first view
        }

        public KingdomHistoryEvent withGeneratedText(
                String text) {
            return new KingdomHistoryEvent(
                    eventId, type, title, description,
                    tick, involvedParty, text);
        }
    }
    public record KingdomDiplomacyData(
            List<DiplomacyRecord> relationHistory,
            int totalWars,
            int warsWon,
            int totalAlliances,
            String mostTrustedKingdom,  // name of longest alliance
            String greatestEnemy        // name of most warred kingdom
    ) {
        public record DiplomacyRecord(
                String kingdomName,
                DiplomaticRelation previousRelation,
                DiplomaticRelation newRelation,
                long tick,
                String initiatedBy  // player name or "NPC"
        ) {
            public static final Codec<DiplomacyRecord> CODEC =
                    RecordCodecBuilder.create(i -> i.group(
                            Codec.STRING.fieldOf("kingdomName")
                                    .forGetter(DiplomacyRecord::kingdomName),
                            Codec.STRING.xmap(
                                            DiplomaticRelation::valueOf,
                                            DiplomaticRelation::name)
                                    .fieldOf("previousRelation")
                                    .forGetter(DiplomacyRecord::previousRelation),
                            Codec.STRING.xmap(
                                            DiplomaticRelation::valueOf,
                                            DiplomaticRelation::name)
                                    .fieldOf("newRelation")
                                    .forGetter(DiplomacyRecord::newRelation),
                            Codec.LONG.fieldOf("tick")
                                    .forGetter(DiplomacyRecord::tick),
                            Codec.STRING.fieldOf("initiatedBy")
                                    .forGetter(DiplomacyRecord::initiatedBy)
                    ).apply(i, DiplomacyRecord::new));
        }

        public static final Codec<KingdomDiplomacyData> CODEC =
                RecordCodecBuilder.create(i -> i.group(
                        DiplomacyRecord.CODEC.listOf()
                                .optionalFieldOf("relationHistory",
                                        new ArrayList<>())
                                .forGetter(KingdomDiplomacyData
                                        ::relationHistory),
                        Codec.INT.optionalFieldOf("totalWars", 0)
                                .forGetter(KingdomDiplomacyData::totalWars),
                        Codec.INT.optionalFieldOf("warsWon", 0)
                                .forGetter(KingdomDiplomacyData::warsWon),
                        Codec.INT.optionalFieldOf(
                                        "totalAlliances", 0)
                                .forGetter(KingdomDiplomacyData
                                        ::totalAlliances),
                        Codec.STRING.optionalFieldOf(
                                        "mostTrustedKingdom", "")
                                .forGetter(KingdomDiplomacyData
                                        ::mostTrustedKingdom),
                        Codec.STRING.optionalFieldOf(
                                        "greatestEnemy", "")
                                .forGetter(KingdomDiplomacyData
                                        ::greatestEnemy)
                ).apply(i, KingdomDiplomacyData::new));
    }



    public record KingdomEventsData(
            List<KingdomHistoryEvent> events,
            int warCount,
            int allianceCount,
            int villagesEverOwned
    ) {
        public static final Codec<KingdomEventsData> CODEC =
                RecordCodecBuilder.create(i -> i.group(
                        KingdomHistoryEvent.CODEC.listOf()
                                .optionalFieldOf("events",
                                        new ArrayList<>())
                                .forGetter(KingdomEventsData::events),
                        Codec.INT.optionalFieldOf("warCount", 0)
                                .forGetter(KingdomEventsData::warCount),
                        Codec.INT.optionalFieldOf(
                                        "allianceCount", 0)
                                .forGetter(KingdomEventsData::allianceCount),
                        Codec.INT.optionalFieldOf(
                                        "villagesEverOwned", 0)
                                .forGetter(KingdomEventsData::villagesEverOwned)
                ).apply(i, KingdomEventsData::new));

        public KingdomEventsData withEvent(
                KingdomHistoryEvent event) {
            List<KingdomHistoryEvent> updated =
                    new ArrayList<>(events);
            updated.add(event);
            // Keep last 100 events
            /*if (updated.size() > 100) {
                updated = updated.subList(
                        updated.size() - 100, updated.size());
            }

             */
            return new KingdomEventsData(updated,
                    warCount, allianceCount,
                    villagesEverOwned);
        }
    }



    public enum KingdomOrigins {
        FOUNDED_BY_PLAYER,      // player used the founding path
        DIPLOMATIC_ASCENSION,   // player earned rulership through reputation
        CONQUEST,               // player defeated the NPC ruler
        ANCIENT,                // existed before the player arrived
        TRADE_FEDERATION,       // formed from a group of merchant villages
        TRIBAL_UNION,           // several small hamlets unified
        SPLINTER_KINGDOM,       // broke away from a larger kingdom
        RESTORED_KINGDOM        // rebuilt after collapse
    }
    public enum HistoryEventType {
        // Political
        KINGDOM_FOUNDED,
        RULER_CROWNED,
        RULER_DIED,
        RULER_DEPOSED,
        LAW_ENACTED,
        LAW_REPEALED,
        DECREE_ISSUED,

        // Military
        WAR_DECLARED,
        WAR_ENDED,
        BATTLE_WON,
        BATTLE_LOST,
        VILLAGE_CONQUERED,
        VILLAGE_LIBERATED,

        // Diplomacy
        ALLIANCE_FORMED,
        ALLIANCE_BROKEN,
        TRADE_PACT_SIGNED,
        TRIBUTE_PAID,
        TRIBUTE_RECEIVED,

        // Economy
        TRADE_ROUTE_ESTABLISHED,
        TRADE_ROUTE_COLLAPSED,
        TREASURY_BANKRUPT,
        GREAT_PROSPERITY,

        // Village
        VILLAGE_JOINED,
        VILLAGE_LEFT,
        VILLAGE_FOUNDED,
        VILLAGE_DESTROYED,

        // Events
        FESTIVAL_HELD,
        PLAGUE,
        GREAT_HARVEST,
        FAMINE,

        // Player
        PLAYER_BECAME_RULER,
        PLAYER_ABDICATED,
        HERO_AROSE          // player reached LEGEND adventurer tier
    }



    public static final Codec<KingdomHistoryData> CODEC =
            RecordCodecBuilder.create(i -> i.group(
                    KingdomOriginData.CODEC
                            .optionalFieldOf("origin")
                            .forGetter(d -> Optional.ofNullable(
                                    d.origin)),
                    KingdomEventsData.CODEC
                            .optionalFieldOf("events",
                                    new KingdomEventsData(
                                            new ArrayList<>(),
                                            0, 0, 0))
                            .forGetter(d -> d.events),
                    KingdomDiplomacyData.CODEC
                            .optionalFieldOf("diplomacy",
                                    new KingdomDiplomacyData(
                                            new ArrayList<>(),
                                            0, 0, 0, "", ""))
                            .forGetter(d -> d.diplomacy)
            ).apply(i, KingdomHistoryData::fromCodec));

    private static KingdomHistoryData fromCodec(
            Optional<KingdomOriginData> origin,
            KingdomEventsData events,
            KingdomDiplomacyData diplomacy) {
        KingdomHistoryData data = new KingdomHistoryData();
        data.origin    = origin.orElse(null);
        data.events    = events;
        data.diplomacy = diplomacy;
        return data;
    }

    private KingdomOriginData origin       = null;
    private KingdomEventsData events       =
            new KingdomEventsData(
                    new ArrayList<>(), 0, 0, 0);
    private KingdomDiplomacyData diplomacy =
            new KingdomDiplomacyData(
                    new ArrayList<>(), 0, 0, 0, "", "");

    // -------------------------------------------------------------------------
    // API
    // -------------------------------------------------------------------------

    public void setOrigin(KingdomOriginData origin) {
        this.origin = origin;
    }

    public Optional<KingdomOriginData> getOrigin() {
        return Optional.ofNullable(origin);
    }

    public void recordEvent(KingdomHistoryEvent event,
                            String kingdomName,
                            String rulerName) {
        // Generate and persist text immediately
        String text = HistoryTextGenerator.generateForEvent(
                event, kingdomName, rulerName);
        KingdomHistoryEvent withText =
                event.withGeneratedText(text);
        this.events = events.withEvent(withText);
    }

    public List<KingdomHistoryEvent> getEvents() {
        return events.events();
    }

    public List<KingdomHistoryEvent> getEventsByType(
            HistoryEventType type) {
        return events.events().stream()
                .filter(e -> e.type() == type)
                .collect(java.util.stream.Collectors.toList());
    }

    public void recordDiplomacy(
            KingdomDiplomacyData.DiplomacyRecord record) {
        List<KingdomDiplomacyData.DiplomacyRecord> updated =
                new ArrayList<>(diplomacy.relationHistory());
        updated.add(record);
        this.diplomacy = new KingdomDiplomacyData(
                updated,
                record.newRelation() == DiplomaticRelation.WAR
                        ? diplomacy.totalWars() + 1
                        : diplomacy.totalWars(),
                diplomacy.warsWon(),
                record.newRelation()
                        == DiplomaticRelation.ALLIANCE
                        ? diplomacy.totalAlliances() + 1
                        : diplomacy.totalAlliances(),
                diplomacy.mostTrustedKingdom(),
                diplomacy.greatestEnemy());
    }

    public KingdomDiplomacyData getDiplomacy() {
        return diplomacy;
    }


    public KingdomHistoryData() {
        this.origin    = null;
        this.events    = new KingdomEventsData(
                new ArrayList<>(), 0, 0, 0);
        this.diplomacy = new KingdomDiplomacyData(
                new ArrayList<>(), 0, 0, 0, "", "");
    }
}
