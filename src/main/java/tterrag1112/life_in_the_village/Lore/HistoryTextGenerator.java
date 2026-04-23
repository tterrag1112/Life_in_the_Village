package tterrag1112.life_in_the_village.Lore;

import java.util.*;

public class HistoryTextGenerator {

    private static final Random RNG = new Random();

    // -------------------------------------------------------------------------
    // Filler phrases — inserted between event sections
    // -------------------------------------------------------------------------

    private static final List<String> FILLERS = List.of(
            "The years that followed were marked by quiet toil and steady growth, as the villages of the realm went about their lives beneath the crown's watchful eye.",
            "For a time, peace settled over the kingdom like morning frost — fragile, but welcome.",
            "The chroniclers note a period of relative calm, during which the roads were maintained, the harvests were plentiful, and little of great consequence befell the realm.",
            "Between the great events of state, the ordinary business of the kingdom continued — taxes were levied, roads were walked, and children were born who would one day inherit what their forebears had built.",
            "Seasons turned, and with them the mood of the people. Trade brought new faces to the villages, and with them new ideas.",
            "The realm endured, as realms do, through the patient accumulation of small mercies.",
            "It was a time of neither great triumph nor great sorrow — and those who lived through it would later call it the good years.",
            "The people went about their days as people always have, little knowing what history was quietly being made around them."

    );

    // -------------------------------------------------------------------------
    // Event text templates — {kingdom}, {ruler}, {party}, {day}
    // are replaced at runtime
    // -------------------------------------------------------------------------

    private static final Map<KingdomHistoryData.HistoryEventType,
            List<String>> TEMPLATES = new EnumMap<>(
            KingdomHistoryData.HistoryEventType.class);

    static {
        put(KingdomHistoryData.HistoryEventType.KINGDOM_FOUNDED,
                "On the {day}th day, {ruler} proclaimed the founding of {kingdom}, and the banners of the new realm were raised above {party} for the first time.",
                "What had once been a collection of disparate villages was united under a single crown on day {day}, when {ruler} established the Kingdom of {kingdom}.",
                "The Kingdom of {kingdom} came into being on day {day} by the will of {ruler}, whose vision bound the villages of the realm into one.");

        put(KingdomHistoryData.HistoryEventType.RULER_CROWNED,
                "On day {day}, {ruler} was crowned sovereign of {kingdom}, and the people gathered to witness the beginning of a new age.",
                "The coronation of {ruler} on day {day} was met with celebration throughout {kingdom}, marking the dawn of their reign.",
                "With solemn ceremony on day {day}, {ruler} accepted the crown of {kingdom}, and swore to uphold the realm.");

        put(KingdomHistoryData.HistoryEventType.RULER_DIED,
                "On day {day}, the realm mourned the passing of {ruler}, whose rule over {kingdom} had shaped the land for years.",
                "Death came for {ruler} on day {day}, leaving {kingdom} without its sovereign and the people uncertain of what was to come.",
                "The crown passed on day {day}, for {ruler} had departed this world, leaving {kingdom} to find its next chapter.");

        put(KingdomHistoryData.HistoryEventType.LAW_ENACTED,
                "By royal decree on day {day}, {ruler} enacted {party} across the Kingdom of {kingdom}, reshaping the lives of all who dwelt within its borders.",
                "The {party} was established on day {day} at the command of {ruler}, who believed it necessary for the good of {kingdom}.",
                "Day {day} saw the proclamation of {party} throughout {kingdom}, a law that would endure long after those who made it.");

        put(KingdomHistoryData.HistoryEventType.LAW_REPEALED,
                "On day {day}, {ruler} struck down {party}, declaring it no longer fit to govern the people of {kingdom}.",
                "The repeal of {party} on day {day} was welcomed by many in {kingdom}, who had long chafed under its provisions.",
                "After careful deliberation, {ruler} repealed {party} on day {day}, closing a chapter in the legal history of {kingdom}.");

        put(KingdomHistoryData.HistoryEventType.WAR_DECLARED,
                "On day {day}, {kingdom} declared war upon {party}. The drums of conflict echoed across the realm, and none could say what the coming days would bring.",
                "Peace between {kingdom} and {party} collapsed on day {day}, when {ruler} declared open hostilities. The realm braced for conflict.",
                "Day {day} marked a dark turning — {ruler} raised the banner of war against {party}, and {kingdom} prepared for the trials ahead.");

        put(KingdomHistoryData.HistoryEventType.WAR_ENDED,
                "On day {day}, the conflict between {kingdom} and {party} came to an end. The cost had been great, but the realm endured.",
                "Peace was restored on day {day} as {kingdom} and {party} laid down their arms. The chroniclers noted it with quiet relief.",
                "Day {day} brought the end of hostilities with {party}. Whether it was victory or merely survival, {kingdom} emerged from the conflict changed.");

        put(KingdomHistoryData.HistoryEventType.ALLIANCE_FORMED,
                "On day {day}, {kingdom} and {party} sealed an alliance, binding their fates together in the years to come.",
                "The signing of the alliance with {party} on day {day} was celebrated throughout {kingdom} as a triumph of diplomacy.",
                "Day {day} saw {ruler} extend the hand of friendship to {party}, forging an alliance that would shape the balance of the realm.");

        put(KingdomHistoryData.HistoryEventType.ALLIANCE_BROKEN,
                "The alliance with {party} dissolved on day {day}, leaving {kingdom} and its former friend to regard each other with cold eyes.",
                "On day {day}, relations between {kingdom} and {party} soured beyond repair, and the alliance that had once bound them was no more.",
                "Day {day} marked the end of the friendship with {party}. What had been built over years was undone in a moment.");

        put(KingdomHistoryData.HistoryEventType.TRADE_PACT_SIGNED,
                "On day {day}, {kingdom} and {party} agreed to a trade pact, opening new roads of commerce between their peoples.",
                "The merchants of {kingdom} celebrated on day {day} when {ruler} signed a trade agreement with {party}, promising prosperity for both realms.",
                "Day {day} saw the establishment of formal trade ties with {party}, a practical arrangement that suited both kingdoms well.");

        put(KingdomHistoryData.HistoryEventType.VILLAGE_JOINED,
                "On day {day}, the village of {party} joined the Kingdom of {kingdom}, swelling the realm's population and extending its reach.",
                "Day {day} brought new subjects to the crown, as {party} pledged itself to {kingdom} and its ruler.",
                "The people of {party} became citizens of {kingdom} on day {day}, and their village was welcomed into the fold.");

        put(KingdomHistoryData.HistoryEventType.VILLAGE_LEFT,
                "On day {day}, {party} departed the Kingdom of {kingdom}, leaving a gap in the realm that would not soon be filled.",
                "Day {day} brought the unwelcome news that {party} had broken with {kingdom}, choosing to chart its own course.",
                "The village of {party} withdrew from {kingdom} on day {day}, and the realm was the smaller for it.");

        put(KingdomHistoryData.HistoryEventType.TRADE_ROUTE_ESTABLISHED,
                "A new road was opened on day {day}, connecting {kingdom} to {party} and carrying with it the promise of mutual prosperity.",
                "On day {day}, traders began making the journey along the newly established route to {party}, bringing goods and news from distant lands.",
                "Day {day} saw the first caravans depart along the new trade route to {party}, a sign of {kingdom}'s growing reach.");

        put(KingdomHistoryData.HistoryEventType.TRADE_ROUTE_COLLAPSED,
                "On day {day}, the trade route to {party} fell into disuse, its road unmaintained and its caravans no longer arriving.",
                "Day {day} marked the end of regular commerce with {party}, as the route between them became impassable.",
                "The road to {party} was abandoned on day {day}, and with it went the flow of trade that had sustained both peoples.");

        put(KingdomHistoryData.HistoryEventType.GREAT_HARVEST,
                "Day {day} brought tidings of an extraordinary harvest throughout {kingdom}. The storehouses were full, and the people rejoiced.",
                "The harvests of day {day} were the finest in living memory, and {kingdom} entered the season of plenty with full granaries.",
                "On day {day}, the fields of {kingdom} yielded beyond all expectation, and the realm gave thanks for its good fortune.");

        put(KingdomHistoryData.HistoryEventType.FAMINE,
                "Day {day} brought hardship to {kingdom}, as the harvests failed and the people went hungry. It was a dark season for the realm.",
                "Famine came to {kingdom} on day {day}, and the crown scrambled to feed its people. The chroniclers recorded it as one of the trials of the age.",
                "The crops withered on day {day}, and {kingdom} faced the grim prospect of shortage. Hard times had arrived.");

        put(KingdomHistoryData.HistoryEventType.GREAT_PROSPERITY,
                "Day {day} was marked by a period of remarkable prosperity in {kingdom}. The treasury swelled, the roads were busy, and the people were content.",
                "Prosperity visited {kingdom} on day {day}, and the realm flourished as it seldom had before.",
                "The kingdom of {kingdom} entered its golden days on day {day}, when wealth and stability seemed to reward the long years of effort.");

        put(KingdomHistoryData.HistoryEventType.TREASURY_BANKRUPT,
                "On day {day}, the treasury of {kingdom} ran dry, and {ruler} faced the difficult task of rebuilding the realm's finances from nothing.",
                "Day {day} brought financial crisis to {kingdom} — the coffers were empty, and creditors waited at the gates.",
                "The kingdom's wealth was spent by day {day}, and {ruler} was forced to make hard choices about the future of {kingdom}.");

        put(KingdomHistoryData.HistoryEventType.FESTIVAL_HELD,
                "On day {day}, the villages of {kingdom} gathered in celebration, and for a time the cares of the realm were set aside.",
                "Day {day} saw {kingdom} host a great festival, filling the streets with music, trade, and the laughter of its people.",
                "The festival of day {day} was long remembered by those who attended — a moment of joy in the ongoing story of {kingdom}.");

        put(KingdomHistoryData.HistoryEventType.PLAGUE,
                "Day {day} brought sickness to {kingdom}, and the healers worked without rest to stem the tide of suffering.",
                "A plague swept through {kingdom} on day {day}, testing the resolve of its people and the competence of its rulers.",
                "The illness that arrived on day {day} left its mark on {kingdom} for years afterward, in the faces of those who survived it.");

        put(KingdomHistoryData.HistoryEventType.PLAYER_BECAME_RULER,
                "On day {day}, {ruler} assumed the mantle of sovereignty over {kingdom}, and the realm entered a new chapter under mortal guidance.",
                "Day {day} marked the moment {ruler} took the crown of {kingdom}. Whether the realm would flourish or falter now depended on their choices.",
                "The throne of {kingdom} passed to {ruler} on day {day}, and the kingdom's fate was bound to a new hand.");

        put(KingdomHistoryData.HistoryEventType.PLAYER_ABDICATED,
                "On day {day}, {ruler} set aside the crown of {kingdom}, and the realm was left to find its own way once more.",
                "Day {day} saw {ruler} abdicate the throne of {kingdom}, an act that surprised many and saddened more.",
                "The reign of {ruler} over {kingdom} came to an end on day {day}, not through defeat but by their own choice.");

        put(KingdomHistoryData.HistoryEventType.HERO_AROSE,
                "On day {day}, the deeds of {party} had become legend throughout {kingdom} and beyond. The people spoke of them in hushed and reverent tones.",
                "Day {day} saw {party} attain the rank of legend — a hero whose exploits had carried the name of {kingdom} to the far corners of the world.",
                "It was on day {day} that {party} earned the title that would follow them forever, and {kingdom} claimed them proudly as one of its own.");

        put(KingdomHistoryData.HistoryEventType.DECREE_ISSUED,
                "On day {day}, {ruler} issued a decree throughout {kingdom}: {party}. The people received it with varying degrees of enthusiasm.",
                "Day {day} saw the proclamation of a royal decree by {ruler}, whose words rang out across {kingdom} and demanded to be heeded.",
                "By royal authority on day {day}, {ruler} declared: {party}. The realm adjusted itself accordingly.");

        put(KingdomHistoryData.HistoryEventType.BATTLE_WON,
                "Day {day} brought news of victory — {kingdom}'s forces had triumphed over {party}, and the realm celebrated its soldiers' courage.",
                "On day {day}, the armies of {kingdom} bested {party} on the field of battle, and the chroniclers recorded it as a proud day for the realm.",
                "The victory over {party} on day {day} lifted the spirits of {kingdom} and secured the realm's position for years to come.");

        put(KingdomHistoryData.HistoryEventType.BATTLE_LOST,
                "Day {day} was a grim one for {kingdom}, as its forces were defeated by {party}. The realm licked its wounds and regrouped.",
                "On day {day}, {kingdom}'s armies suffered defeat at the hands of {party}. It was a blow from which recovery would take time.",
                "The battle of day {day} did not go well for {kingdom}. {party} had proven the stronger force, and the realm paid the price.");

        put(KingdomHistoryData.HistoryEventType.TRIBUTE_PAID,
                "On day {day}, {kingdom} paid tribute to {party} — a bitter necessity, but one that bought peace for the realm.",
                "Day {day} saw gold leave the treasury of {kingdom} and make its way to {party}. Pride was set aside in the name of survival.",
                "The tribute paid to {party} on day {day} was a reminder of the realities of power, and {ruler} bore the indignity with practiced calm.");

        put(KingdomHistoryData.HistoryEventType.TRIBUTE_RECEIVED,
                "On day {day}, tribute arrived from {party}, swelling the treasury of {kingdom} and confirming the realm's growing influence.",
                "Day {day} brought welcome wealth to {kingdom}, as {party} delivered its tribute and acknowledged the realm's authority.",
                "The tribute from {party} on day {day} was received with quiet satisfaction by {ruler}, who had worked long to bring the realm to such a position.");

        put(KingdomHistoryData.HistoryEventType.VILLAGE_CONQUERED,
                "On day {day}, the village of {party} fell to the forces of {kingdom}, and a new banner was raised above its gates.",
                "Day {day} saw {kingdom} extend its reach by force, taking {party} and incorporating it — willingly or otherwise — into the realm.",
                "The conquest of {party} on day {day} was swift and decisive. {kingdom} had grown again, though not everyone within its new borders was pleased.");

        put(KingdomHistoryData.HistoryEventType.VILLAGE_LIBERATED,
                "On day {day}, {party} was freed from foreign rule and returned to the fold of {kingdom}, its people cautiously relieved.",
                "Day {day} brought the liberation of {party}, whose people had endured occupation and now welcomed the banners of {kingdom}.",
                "The village of {party} was reclaimed on day {day}, and {kingdom} marked the occasion with celebrations that were well-earned.");

        put(KingdomHistoryData.HistoryEventType.VILLAGE_FOUNDED,
                "On day {day}, a new settlement was established within {kingdom}'s borders. The village of {party} was young, but full of promise.",
                "Day {day} saw the founding of {party}, the newest addition to the Kingdom of {kingdom} and a sign of the realm's continuing growth.",
                "The hammers of builders rang out on day {day} as {party} was founded, adding another thread to the tapestry of {kingdom}.");

        put(KingdomHistoryData.HistoryEventType.VILLAGE_DESTROYED,
                "On day {day}, {party} was lost — burned, abandoned, or taken beyond recovery. Its people scattered, and {kingdom} mourned what had been.",
                "Day {day} brought the end of {party}. Whether by war, disaster, or simple misfortune, the village was no more.",
                "The village of {party} ceased to exist on day {day}, leaving only the memory of what it had been within the borders of {kingdom}.");
        put(KingdomHistoryData.HistoryEventType.KINGDOM_ANNIVERSARY,
                "On day {day}, the Kingdom of {kingdom} marked a milestone in its history. Those who had been there from the beginning gathered to reflect on all that had been built.",
                "Day {day} was declared a day of celebration in {kingdom}, as the realm marked another chapter in its unfolding story.",
                "The chroniclers noted day {day} as a day of significance for {kingdom} — a moment to pause and take stock of what the kingdom had become.");

        put(KingdomHistoryData.HistoryEventType.POPULATION_MILESTONE,
                "The village of {party} swelled in size on day {day}, its growing population a testament to the stability that {kingdom} had provided.",
                "Day {day} saw {party} reach a new threshold of inhabitants. The village had grown beyond what its founders might have imagined.",
                "On day {day}, the people of {party} celebrated their numbers — proof that life under {kingdom} was worth living.");

        put(KingdomHistoryData.HistoryEventType.TOWN_HALL_UPGRADED,
                "The town hall of {party} was expanded on day {day}, its new wings housing the growing business of governance in {kingdom}.",
                "On day {day}, scaffolding came down around {party}'s town hall to reveal a grander building — a sign of the village's rising status.",
                "Day {day} saw the completion of works on {party}'s town hall. {kingdom} had invested in its future.");

        put(KingdomHistoryData.HistoryEventType.CASTLE_BUILT,
                "The great castle of {party} was completed on day {day}. Its walls were thick, its towers tall, and all who saw it understood that {kingdom} had grown into something formidable.",
                "On day {day}, the last stone was laid at {party}'s castle. The builders celebrated, and so did {kingdom}.",
                "Day {day} marked the completion of a castle in {party} — the most powerful symbol yet of {kingdom}'s ambitions.");

        put(KingdomHistoryData.HistoryEventType.GUILD_HALL_FOUNDED,
                "A guild hall opened its doors in {party} on day {day}, and adventurers began to gather within {kingdom}'s borders seeking glory and coin.",
                "On day {day}, the guild hall of {party} was founded. It would become a place of legend in {kingdom}.",
                "Day {day} saw the first fires lit in {party}'s new guild hall. The realm had a new heart for its wanderers.");

        put(KingdomHistoryData.HistoryEventType.NOTABLE_BIRTH,
                "On day {day}, {party} was born in {kingdom}. The midwives remarked on the child's strong lungs, though they could not know what the years would bring.",
                "Day {day} brought a new life into {kingdom} — {party}, whose story was only just beginning.",
                "The birth of {party} on day {day} was noted by few at the time. Later, the chroniclers would return to this entry with greater interest.");

        put(KingdomHistoryData.HistoryEventType.NOTABLE_MARRIAGE,
                "On day {day}, {party} were wed in {kingdom}, and the village celebrated with them through the night.",
                "Day {day} brought joy to {kingdom} as {party} were joined in marriage before their friends and neighbours.",
                "The wedding of {party} on day {day} was a bright moment in the life of {kingdom} — a reminder that the realm was made of people, not just politics.");

        put(KingdomHistoryData.HistoryEventType.NOTABLE_DEATH,
                "On day {day}, {party} passed from this world. {kingdom} was poorer for the loss.",
                "The death of {party} on day {day} cast a shadow over {kingdom}. Those who had known them felt it keenly.",
                "Day {day} brought grief to {kingdom}, for {party} was gone. The chronicles record the fact plainly, though the loss was anything but plain to those who lived it.");

        put(KingdomHistoryData.HistoryEventType.LEGENDARY_ADVENTURER,
                "On day {day}, the deeds of {party} had become legend throughout {kingdom}. Songs were sung, and children asked to hear the stories again.",
                "Day {day} was the day {party} earned a name that would last in {kingdom} long after they were gone.",
                "The chroniclers of {kingdom} noted day {day} as the moment {party} crossed from merely remarkable into the realm of legend.");

        put(KingdomHistoryData.HistoryEventType.QUEST_COMPLETED,
                "On day {day}, {party} returned to {kingdom} victorious, their quest complete and their pockets heavier for it.",
                "Day {day} brought {party} home to {kingdom} with tales of what they had seen and done beyond its borders.",
                "The return of {party} on day {day} was welcomed in {kingdom}. The realm had need of those willing to venture into the unknown.");

        put(KingdomHistoryData.HistoryEventType.FIRST_CARAVAN,
                "The first caravan set out from {party} on day {day}, carrying the hopes of {kingdom} along with its cargo.",
                "On day {day}, {kingdom} watched its first caravan depart. It was a modest thing, but it meant the realm had become something worth trading with.",
                "Day {day} marked a turning point for {kingdom} — the first caravan had departed, and the realm was no longer isolated.");

        put(KingdomHistoryData.HistoryEventType.CARAVAN_ATTACKED,
                "On day {day}, a caravan from {party} was attacked on the road. {kingdom} took note of the threat.",
                "Day {day} brought grim tidings to {kingdom} — a caravan had been waylaid, and the roads were less safe than they had seemed.",
                "The attack on a caravan from {party} on day {day} was a reminder to {kingdom} that prosperity must be defended.");

        put(KingdomHistoryData.HistoryEventType.NOTABLE_BUILDING_BUILT,
                "A new {party} was completed in {kingdom} on day {day}, adding to the realm's growing collection of institutions.",
                "On day {day}, construction finished on a {party} in {kingdom}. The builders were paid, the doors were opened, and life went on.",
                "Day {day} saw another building rise in {kingdom} — a {party} that would serve the realm for years to come.");

        put(KingdomHistoryData.HistoryEventType.ANCIENT_ROAD_FOUNDED_NEAR,
                "The kingdom of {kingdom} was founded beside {party}, an ancient road from the age before the present realm. The road was old when the first stone was laid.",
                "Those who first settled what would become {kingdom} chose their ground well — near {party}, one of the great roads of the Old Realm whose builders are long forgotten.",
                "The chronicles note that {kingdom} traces its origins to the land beside {party}. The road predates the kingdom by ages uncounted.");
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Generates and returns narrative text for a single event.
     * The text is deterministic based on the event UUID
     * so the same event always gets the same text on
     * repeated calls before it is persisted.
     */
    public static String generateForEvent(
            KingdomHistoryData.KingdomHistoryEvent event,
            String kingdomName,
            String rulerName) {

        List<String> templates = TEMPLATES.get(event.type());
        if (templates == null || templates.isEmpty()) {
            return "On day "
                    + (event.tick() / 24000L)
                    + ", " + event.description();
        }

        // Use event UUID as seed for determinism
        Random rng = new Random(
                event.eventId().getMostSignificantBits());
        String template = templates.get(
                rng.nextInt(templates.size()));

        return fill(template, kingdomName, rulerName,
                event.involvedParty(),
                event.tick() / 24000L);
    }

    // -------------------------------------------------------------------------
// Event factory methods — call these from integration points
// -------------------------------------------------------------------------

    public static KingdomHistoryData.KingdomHistoryEvent
    kingdomFounded(String kingdomName,
                   String foundingVillage,
                   long tick) {
        return KingdomHistoryData.KingdomHistoryEvent.create(
                KingdomHistoryData.HistoryEventType.KINGDOM_FOUNDED,
                "The founding of " + kingdomName,
                "Born from the village of " + foundingVillage
                        + ", the kingdom was established when "
                        + "its people grew prosperous enough "
                        + "to stand as one.",
                tick, foundingVillage);
    }

    public static KingdomHistoryData.KingdomHistoryEvent
    playerBecameRuler(String playerName,
                      String kingdomName,
                      long tick) {
        return KingdomHistoryData.KingdomHistoryEvent.create(
                KingdomHistoryData.HistoryEventType
                        .PLAYER_BECAME_RULER,
                playerName + " takes the throne",
                playerName + " assumed rulership of "
                        + kingdomName
                        + ", and the kingdom entered "
                        + "a new chapter.",
                tick, playerName);
    }

    public static KingdomHistoryData.KingdomHistoryEvent
    villageJoined(String villageName,
                  String kingdomName,
                  long tick) {
        return KingdomHistoryData.KingdomHistoryEvent.create(
                KingdomHistoryData.HistoryEventType.VILLAGE_JOINED,
                villageName + " joins the kingdom",
                "The village of " + villageName
                        + " swore fealty to " + kingdomName
                        + " and was welcomed into the realm.",
                tick, villageName);
    }

    public static KingdomHistoryData.KingdomHistoryEvent
    villageLeft(String villageName,
                String kingdomName,
                long tick) {
        return KingdomHistoryData.KingdomHistoryEvent.create(
                KingdomHistoryData.HistoryEventType.VILLAGE_LEFT,
                villageName + " departs",
                "The village of " + villageName
                        + " broke away from " + kingdomName
                        + ", choosing to chart its own course.",
                tick, villageName);
    }

    public static KingdomHistoryData.KingdomHistoryEvent
    tradeRouteEstablished(String villageA,
                          String villageB,
                          long tick) {
        return KingdomHistoryData.KingdomHistoryEvent.create(
                KingdomHistoryData.HistoryEventType
                        .TRADE_ROUTE_ESTABLISHED,
                "New road to " + villageB,
                "A road was opened between " + villageA
                        + " and " + villageB
                        + ", carrying goods and prosperity "
                        + "between the two settlements.",
                tick, villageB);
    }

    public static KingdomHistoryData.KingdomHistoryEvent
    tradeRouteCollapsed(String villageA,
                        String villageB,
                        long tick) {
        return KingdomHistoryData.KingdomHistoryEvent.create(
                KingdomHistoryData.HistoryEventType
                        .TRADE_ROUTE_COLLAPSED,
                "Road to " + villageB + " collapses",
                "The road between " + villageA + " and "
                        + villageB + " fell into disrepair "
                        + "and trade was suspended.",
                tick, villageB);
    }

    public static KingdomHistoryData.KingdomHistoryEvent
    festivalHeld(String villageName,
                 String festivalType,
                 long tick) {
        String formatted = festivalType.replace("_", " ")
                .toLowerCase();
        return KingdomHistoryData.KingdomHistoryEvent.create(
                KingdomHistoryData.HistoryEventType.FESTIVAL_HELD,
                formatted + " in " + villageName,
                "The people of " + villageName
                        + " gathered to celebrate a "
                        + formatted + ".",
                tick, villageName);
    }

    public static KingdomHistoryData.KingdomHistoryEvent
    greatHarvest(String villageName, long tick) {
        return KingdomHistoryData.KingdomHistoryEvent.create(
                KingdomHistoryData.HistoryEventType.GREAT_HARVEST,
                "A great harvest in " + villageName,
                "The fields of " + villageName
                        + " yielded beyond all expectation, "
                        + "and the realm gave thanks "
                        + "for its good fortune.",
                tick, villageName);
    }

    public static KingdomHistoryData.KingdomHistoryEvent
    treasuryBankrupt(String kingdomName, long tick) {
        return KingdomHistoryData.KingdomHistoryEvent.create(
                KingdomHistoryData.HistoryEventType
                        .TREASURY_BANKRUPT,
                "The treasury runs dry",
                "The coffers of " + kingdomName
                        + " were emptied, and the crown "
                        + "faced financial ruin.",
                tick, kingdomName);
    }

    public static KingdomHistoryData.KingdomHistoryEvent
    greatProsperity(String kingdomName, long tick) {
        return KingdomHistoryData.KingdomHistoryEvent.create(
                KingdomHistoryData.HistoryEventType
                        .GREAT_PROSPERITY,
                "An age of prosperity",
                "The Kingdom of " + kingdomName
                        + " entered a period of remarkable "
                        + "wealth, its treasury full "
                        + "and its people content.",
                tick, kingdomName);
    }

    public static KingdomHistoryData.KingdomHistoryEvent
    decreed(String decree, String rulerName, long tick) {
        return KingdomHistoryData.KingdomHistoryEvent.create(
                KingdomHistoryData.HistoryEventType.DECREE_ISSUED,
                "Royal decree issued",
                rulerName + " proclaimed: \"" + decree + "\"",
                tick, rulerName);
    }

    public static KingdomHistoryData.KingdomHistoryEvent
    lawEnacted(String lawName, String rulerName,
               long tick) {
        return KingdomHistoryData.KingdomHistoryEvent.create(
                KingdomHistoryData.HistoryEventType.LAW_ENACTED,
                "Enacted: " + lawName,
                "By decree of " + rulerName + ".",
                tick, rulerName);
    }

    public static KingdomHistoryData.KingdomHistoryEvent
    lawRepealed(String lawName, String rulerName,
                long tick) {
        return KingdomHistoryData.KingdomHistoryEvent.create(
                KingdomHistoryData.HistoryEventType.LAW_REPEALED,
                "Repealed: " + lawName,
                "By decree of " + rulerName + ".",
                tick, rulerName);
    }

    public static KingdomHistoryData.KingdomHistoryEvent
    warDeclared(String kingdomName,
                String targetName,
                long tick) {
        return KingdomHistoryData.KingdomHistoryEvent.create(
                KingdomHistoryData.HistoryEventType.WAR_DECLARED,
                "War declared on " + targetName,
                "The Kingdom of " + kingdomName
                        + " declared war upon " + targetName
                        + ". The drums of conflict echoed "
                        + "across the realm.",
                tick, targetName);
    }

    public static KingdomHistoryData.KingdomHistoryEvent
    allianceFormed(String kingdomName,
                   String partnerName,
                   long tick) {
        return KingdomHistoryData.KingdomHistoryEvent.create(
                KingdomHistoryData.HistoryEventType
                        .ALLIANCE_FORMED,
                "Alliance with " + partnerName,
                kingdomName + " and " + partnerName
                        + " sealed an alliance, binding "
                        + "their fates together.",
                tick, partnerName);
    }

    public static KingdomHistoryData.KingdomHistoryEvent
    tradePactSigned(String kingdomName,
                    String partnerName,
                    long tick) {
        return KingdomHistoryData.KingdomHistoryEvent.create(
                KingdomHistoryData.HistoryEventType
                        .TRADE_PACT_SIGNED,
                "Trade pact with " + partnerName,
                kingdomName + " and " + partnerName
                        + " agreed to a trade pact, opening "
                        + "new roads of commerce.",
                tick, partnerName);
    }
    // Milestones
    public static KingdomHistoryData.KingdomHistoryEvent
    kingdomAnniversary(String kingdomName,
                       long days, long tick) {
        String milestone = days >= 365 * 5
                ? "five years"
                : days >= 365
                ? "one year"
                : days + " days";
        return KingdomHistoryData.KingdomHistoryEvent.create(
                KingdomHistoryData.HistoryEventType
                        .KINGDOM_ANNIVERSARY,
                kingdomName + " marks " + milestone,
                "The Kingdom of " + kingdomName
                        + " celebrated " + milestone
                        + " since its founding. "
                        + "Those who remembered its early "
                        + "days marvelled at how far it "
                        + "had come.",
                tick, kingdomName);
    }

    public static KingdomHistoryData.KingdomHistoryEvent
    populationMilestone(String villageName,
                        int population,
                        long tick) {
        return KingdomHistoryData.KingdomHistoryEvent.create(
                KingdomHistoryData.HistoryEventType
                        .POPULATION_MILESTONE,
                villageName + " reaches "
                        + population + " souls",
                "The village of " + villageName
                        + " grew to " + population
                        + " inhabitants, a sign of "
                        + "its growing prosperity "
                        + "and stability.",
                tick, villageName);
    }

    // Buildings
    public static KingdomHistoryData.KingdomHistoryEvent
    townHallUpgraded(String villageName,
                     int newLevel, long tick) {
        return KingdomHistoryData.KingdomHistoryEvent.create(
                KingdomHistoryData.HistoryEventType
                        .TOWN_HALL_UPGRADED,
                "Town hall expanded in " + villageName,
                "The town hall of " + villageName
                        + " was expanded to its "
                        + ordinal(newLevel) + " tier, "
                        + "reflecting the village's "
                        + "growing importance.",
                tick, villageName);
    }

    public static KingdomHistoryData.KingdomHistoryEvent
    castleBuilt(String villageName, long tick) {
        return KingdomHistoryData.KingdomHistoryEvent.create(
                KingdomHistoryData.HistoryEventType.CASTLE_BUILT,
                "A castle rises in " + villageName,
                "Construction was completed on a great "
                        + "castle in " + villageName
                        + ". Its towers could be seen "
                        + "from miles around, a symbol "
                        + "of the kingdom's power.",
                tick, villageName);
    }

    public static KingdomHistoryData.KingdomHistoryEvent
    guildHallFounded(String villageName, long tick) {
        return KingdomHistoryData.KingdomHistoryEvent.create(
                KingdomHistoryData.HistoryEventType
                        .GUILD_HALL_FOUNDED,
                "Guild hall founded in " + villageName,
                "A guild hall was established in "
                        + villageName
                        + ", drawing adventurers and "
                        + "skilled workers from across "
                        + "the realm.",
                tick, villageName);
    }

    public static KingdomHistoryData.KingdomHistoryEvent
    notableBuildingBuilt(String buildingType,
                         String villageName,
                         long tick) {
        String formatted = buildingType.replace("_", " ")
                .toLowerCase();
        return KingdomHistoryData.KingdomHistoryEvent.create(
                KingdomHistoryData.HistoryEventType
                        .NOTABLE_BUILDING_BUILT,
                "A " + formatted + " built in "
                        + villageName,
                "A new " + formatted
                        + " was completed in "
                        + villageName
                        + ", adding to the village's "
                        + "growing collection of "
                        + "institutions.",
                tick, villageName);
    }

    // NPC life
    public static KingdomHistoryData.KingdomHistoryEvent
    notableBirth(String npcName,
                 String villageName,
                 long tick) {
        return KingdomHistoryData.KingdomHistoryEvent.create(
                KingdomHistoryData.HistoryEventType.NOTABLE_BIRTH,
                "A child is born in " + villageName,
                npcName + " was born in " + villageName
                        + ". Few knew then what role "
                        + "they would come to play "
                        + "in the kingdom's story.",
                tick, npcName);
    }

    public static KingdomHistoryData.KingdomHistoryEvent
    notableMarriage(String name1, String name2,
                    String villageName, long tick) {
        return KingdomHistoryData.KingdomHistoryEvent.create(
                KingdomHistoryData.HistoryEventType
                        .NOTABLE_MARRIAGE,
                name1 + " and " + name2 + " wed",
                name1 + " and " + name2
                        + " were married in "
                        + villageName
                        + ", and the village celebrated "
                        + "with them.",
                tick, name1 + " and " + name2);
    }

    public static KingdomHistoryData.KingdomHistoryEvent
    notableDeath(String npcName,
                 String villageName,
                 String profession,
                 long tick) {
        return KingdomHistoryData.KingdomHistoryEvent.create(
                KingdomHistoryData.HistoryEventType.NOTABLE_DEATH,
                "Death of " + npcName,
                npcName + ", "
                        + profession.toLowerCase()
                        + " of " + villageName
                        + ", passed from this world. "
                        + "Those who knew them "
                        + "would not soon forget.",
                tick, npcName);
    }

    // Adventurers
    public static KingdomHistoryData.KingdomHistoryEvent
    legendaryAdventurer(String adventurerName,
                        long tick) {
        return KingdomHistoryData.KingdomHistoryEvent.create(
                KingdomHistoryData.HistoryEventType
                        .LEGENDARY_ADVENTURER,
                adventurerName + " becomes legend",
                "Word spread of the deeds of "
                        + adventurerName
                        + ", whose exploits had earned "
                        + "them a place among the "
                        + "legends of the realm.",
                tick, adventurerName);
    }

    public static KingdomHistoryData.KingdomHistoryEvent
    questCompleted(String groupLeader,
                   String questType,
                   long tick) {
        String formatted = questType.replace("_", " ")
                .toLowerCase();
        return KingdomHistoryData.KingdomHistoryEvent.create(
                KingdomHistoryData.HistoryEventType
                        .QUEST_COMPLETED,
                groupLeader + " completes a "
                        + formatted,
                "The adventurer " + groupLeader
                        + " and their companions "
                        + "returned victorious from "
                        + "a " + formatted
                        + ", their deeds adding to "
                        + "the kingdom's renown.",
                tick, groupLeader);
    }

    // Caravans
    public static KingdomHistoryData.KingdomHistoryEvent
    firstCaravan(String originVillage,
                 String destVillage,
                 long tick) {
        return KingdomHistoryData.KingdomHistoryEvent.create(
                KingdomHistoryData.HistoryEventType.FIRST_CARAVAN,
                "First caravan departs " + originVillage,
                "The first caravan set out from "
                        + originVillage
                        + " bound for " + destVillage
                        + ". Merchants and onlookers "
                        + "lined the road to watch "
                        + "it go.",
                tick, destVillage);
    }

    public static KingdomHistoryData.KingdomHistoryEvent
    nearAncientRoad(String kingdomName, String roadName, long tick) {
        return KingdomHistoryData.KingdomHistoryEvent.create(
                KingdomHistoryData.HistoryEventType.ANCIENT_ROAD_FOUNDED_NEAR,
                "Founded near " + roadName,
                "The kingdom arose beside " + roadName
                        + ", an ancient road of the Old Realm.",
                tick, roadName);
    }

    public static KingdomHistoryData.KingdomHistoryEvent
    caravanAttacked(String originVillage,
                    String destVillage,
                    long tick) {
        return KingdomHistoryData.KingdomHistoryEvent.create(
                KingdomHistoryData.HistoryEventType
                        .CARAVAN_ATTACKED,
                "Caravan attacked on the road",
                "A caravan travelling from "
                        + originVillage + " to "
                        + destVillage
                        + " was set upon by hostile "
                        + "forces. The guards fought "
                        + "bravely, but the loss was "
                        + "felt.",
                tick, originVillage);
    }

    // Helper
    private static String ordinal(int n) {
        return switch (n) {
            case 1 -> "first";
            case 2 -> "second";
            case 3 -> "third";
            case 4 -> "fourth";
            default -> n + "th";
        };
    }

    /**
     * Generates a random filler passage.
     */
    public static String generateFiller(long seed) {
        Random rng = new Random(seed);
        return FILLERS.get(rng.nextInt(FILLERS.size()));
    }

    /**
     * Generates the full chronicle by interleaving
     * event text with filler passages.
     * Every 2-3 events a filler is inserted.
     */
    public static String generateFullChronicle(
            List<KingdomHistoryData.KingdomHistoryEvent>
                    events,
            KingdomHistoryData.KingdomOriginData origin,
            String kingdomName,
            String rulerName) {

        StringBuilder sb = new StringBuilder();
        Random rng = new Random(kingdomName.hashCode());

        // Opening line
        if (origin != null) {
            sb.append("Here begins the chronicle of ")
                    .append(kingdomName)
                    .append(", as recorded by its royal scribes.")
                    .append("\n\n");
        }

        int eventCount = 0;
        for (var event : events) {
            String text = event.generatedText().isEmpty()
                    ? generateForEvent(event,
                    kingdomName, rulerName)
                    : event.generatedText();

            sb.append(text).append("\n\n");
            eventCount++;

            // Insert filler every 2-3 events
            if (eventCount % (2 + rng.nextInt(2)) == 0
                    && eventCount < events.size()) {
                sb.append(generateFiller(
                                kingdomName.hashCode() + eventCount))
                        .append("\n\n");
            }
        }

        return sb.toString().trim();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static void put(
            KingdomHistoryData.HistoryEventType type,
            String... templates) {
        TEMPLATES.put(type,
                List.of(templates));
    }

    private static String fill(String template,
                               String kingdom,
                               String ruler,
                               String party,
                               long day) {
        return template
                .replace("{kingdom}", kingdom)
                .replace("{ruler}",   ruler)
                .replace("{party}",   party.isEmpty()
                        ? "unknown parties" : party)
                .replace("{day}",     String.valueOf(day));
    }

    // Add to HistoryTextGenerator.java

    // Add constant
    private static final int LINES_PER_PAGE = 17;
    // Average chars per line at standard book width
    private static final int AVG_LINE_WIDTH  = 38;

    /**
     * Builds history pages by packing content greedily.
     * Multiple short events can share a page.
     * Long events spill onto the next page.
     * Fillers are inserted between event groups.
     */
    public static List<HistoryPage> buildHistoryPages(
            KingdomHistoryData history,
            String kingdomName,
            String rulerName,
            long currentTick,
            long foundingTick) {

        List<ContentBlock> blocks = new ArrayList<>();

        // Origin block always first
        blocks.add(new ContentBlock(
                HistoryPageType.ORIGIN,
                buildOriginText(history.getOrigin()
                        .orElse(null), kingdomName, rulerName),
                "The Founding",
                0L));

        // If no founding tick set, assign a large default
        // so pre-existing kingdoms feel old
        if (foundingTick <= 0) {
            // Deterministic large value based on kingdom name
            // — same kingdom always gets same apparent age
            Random ageRng = new Random(
                    kingdomName.hashCode());
            // Between 30 and 200 in-game days old
            long apparentAge = 30L + (long)(
                    ageRng.nextInt(171)) * 24000L;
            foundingTick = currentTick - apparentAge;
        }

        long totalDays = Math.max(0,
                (currentTick - foundingTick) / 24000L);
        // Safety cap — never more than 10 years of filler
        totalDays = Math.min(totalDays, 365 * 10);

        // Index events by day relative to founding
        Map<Long, List<KingdomHistoryData.KingdomHistoryEvent>>
                eventsByDay = new LinkedHashMap<>();
        for (var event : history.getEvents()) {
            long day = Math.max(1L,
                    (event.tick() - foundingTick) / 24000L);
            eventsByDay.computeIfAbsent(day,
                    k -> new ArrayList<>()).add(event);
        }

        Random rng = new Random(
                kingdomName.hashCode() ^ 0xDEADBEEFL);
        List<Long> eventDays = new ArrayList<>(
                eventsByDay.keySet());
        Collections.sort(eventDays);

        long lastEventDay = 0;

        for (long eventDay : eventDays) {
            long gap = eventDay - lastEventDay;

            // Only insert fillers if there was a meaningful
            // gap AND we already have at least one block
            // (no fillers before the first event)
            if (gap > 3 && !blocks.isEmpty()
                    && blocks.get(blocks.size() - 1).type()
                    != HistoryPageType.ORIGIN
                    || (gap > 3 && lastEventDay > 0)) {

                // 1 filler per gap, unique seed per filler
                int fillerCount = 1
                        + (gap > 10 ? rng.nextInt(2) : 0);
                for (int f = 0; f < fillerCount; f++) {
                    // Seed combines multiple factors
                    // to guarantee uniqueness
                    long seed = (long) kingdomName.hashCode()
                            * 31L
                            + eventDay * 17L
                            + lastEventDay * 7L
                            + f * 1000L;
                    blocks.add(new ContentBlock(
                            HistoryPageType.FILLER,
                            generateFiller(seed),
                            "...",
                            lastEventDay + gap / 2));
                }
            }

            // Add events for this day
            for (var event : eventsByDay.get(eventDay)) {
                String text = event.generatedText().isEmpty()
                        ? generateForEvent(event,
                        kingdomName, rulerName)
                        : event.generatedText();
                blocks.add(new ContentBlock(
                        HistoryPageType.EVENT,
                        text,
                        event.title(),
                        eventDay));
            }

            lastEventDay = eventDay;
        }

        // Trailing fillers only if there were events
        if (!eventsByDay.isEmpty()) {
            long trailingGap = totalDays - lastEventDay;
            if (trailingGap > 3) {
                int fillerCount = 1
                        + (trailingGap > 10
                        ? rng.nextInt(2) : 0);
                for (int f = 0; f < fillerCount; f++) {
                    long seed = (long) kingdomName.hashCode()
                            * 31L
                            + totalDays * 17L
                            + f * 1000L + 99999L;
                    blocks.add(new ContentBlock(
                            HistoryPageType.FILLER,
                            generateFiller(seed),
                            "...",
                            lastEventDay));
                }
            }
        }

        return packIntoPages(blocks);
    }

    /**
     * Packs content blocks into pages.
     * Multiple blocks share a page if they fit.
     * A block that doesn't fit starts a new page.
     * If a single block is larger than one page it
     * is split across pages.
     */
    private static List<HistoryPage> packIntoPages(
            List<ContentBlock> blocks) {

        List<HistoryPage> pages = new ArrayList<>();

        // Each page accumulates lines
        List<String> currentLines  = new ArrayList<>();
        String currentTitle        = "";
        HistoryPageType currentType =
                HistoryPageType.ORIGIN;

        for (ContentBlock block : blocks) {
            List<String> blockLines =
                    splitToLines(block.text());

            // Add a blank separator line between blocks
            // on the same page (except at page start)
            int separatorLines = currentLines.isEmpty()
                    ? 0 : 1;

            boolean fitsOnCurrentPage =
                    currentLines.size()
                            + separatorLines
                            + blockLines.size()
                            <= LINES_PER_PAGE;

            if (fitsOnCurrentPage || currentLines.isEmpty()) {
                // Add separator if needed
                if (!currentLines.isEmpty()) {
                    currentLines.add("");
                } else {
                    // First block on this page sets the type
                    // and title
                    currentType  = block.type();
                    currentTitle = block.title();
                }
                currentLines.addAll(blockLines);
            } else {
                // Current page is full — save it and start new
                if (!currentLines.isEmpty()) {
                    pages.add(new HistoryPage(
                            currentType,
                            String.join("\n", currentLines),
                            currentTitle));
                    currentLines = new ArrayList<>();
                }

                // This block might itself be larger than
                // one page — split it
                List<List<String>> chunks =
                        chunkLines(blockLines, LINES_PER_PAGE);
                for (int c = 0; c < chunks.size(); c++) {
                    List<String> chunk = chunks.get(c);
                    if (c < chunks.size() - 1) {
                        // Full chunk — emit as page immediately
                        pages.add(new HistoryPage(
                                block.type(),
                                String.join("\n", chunk),
                                block.title()
                                        + (chunks.size() > 1
                                        ? " (cont.)" : "")));
                    } else {
                        // Last chunk — keep in buffer
                        // so next block might join it
                        currentLines.addAll(chunk);
                        currentTitle = block.title();
                        currentType  = block.type();
                    }
                }
            }
        }

        // Flush remaining lines
        if (!currentLines.isEmpty()) {
            pages.add(new HistoryPage(
                    currentType,
                    String.join("\n", currentLines),
                    currentTitle));
        }

        return pages;
    }

// -------------------------------------------------------------------------
// Internal helpers
// -------------------------------------------------------------------------

    private record ContentBlock(
            HistoryPageType type,
            String text,
            String title,
            long day) {}

    /**
     * Wraps text to lines using average character width.
     * The screen does final pixel-accurate wrapping,
     * but this gives us a good line count estimate
     * for packing decisions.
     */
    private static List<String> splitToLines(String text) {
        List<String> lines = new ArrayList<>();
        if (text == null || text.isEmpty()) return lines;

        for (String paragraph : text.split("\n")) {
            String[] words = paragraph.split(" ");
            StringBuilder cur = new StringBuilder();
            for (String word : words) {
                if (cur.length() + word.length() + 1
                        > AVG_LINE_WIDTH) {
                    if (!cur.isEmpty()) {
                        lines.add(cur.toString());
                        cur = new StringBuilder(word);
                    } else {
                        lines.add(word);
                    }
                } else {
                    if (!cur.isEmpty()) cur.append(' ');
                    cur.append(word);
                }
            }
            if (!cur.isEmpty()) lines.add(cur.toString());
        }
        return lines;
    }

    private static List<List<String>> chunkLines(
            List<String> lines, int chunkSize) {
        List<List<String>> chunks = new ArrayList<>();
        for (int i = 0; i < lines.size();
             i += chunkSize) {
            chunks.add(new ArrayList<>(lines.subList(
                    i, Math.min(i + chunkSize,
                            lines.size()))));
        }
        return chunks.isEmpty()
                ? List.of(new ArrayList<>()) : chunks;
    }

    private static String buildOriginText(
            KingdomHistoryData.KingdomOriginData origin,
            String kingdomName,
            String rulerName) {
        if (origin == null) {
            return "The origins of " + kingdomName
                    + " are lost to time. "
                    + "No chronicle records its founding, "
                    + "and those who might have remembered "
                    + "are long gone.";
        }
        long day = origin.foundingTick() / 24000L;
        String template = pick(TEMPLATES.get(
                        KingdomHistoryData.HistoryEventType
                                .KINGDOM_FOUNDED),
                origin.foundingTick());
        String text = fill(template, kingdomName, rulerName,
                origin.foundingVillageName(), day);

        if (!origin.foundingStatement().isEmpty()) {
            text += " It was said of those early days: \""
                    + origin.foundingStatement() + "\"";
        }
        return text;
    }

    // Random pick seeded by tick for determinism
    private static String pick(List<String> list, long seed) {
        if (list == null || list.isEmpty()) return "";
        return list.get((int)(Math.abs(seed) % list.size()));
    }

    // Expose for event recording
    public static String generateForEventDeterministic(
            KingdomHistoryData.KingdomHistoryEvent event,
            String kingdomName,
            String rulerName) {
        return generateForEvent(event, kingdomName, rulerName);
    }

    public enum HistoryPageType {
        ORIGIN, EVENT, FILLER
    }

    public record HistoryPage(
            HistoryPageType type,
            String text,
            String title) {}
}