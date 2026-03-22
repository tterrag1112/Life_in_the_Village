// src/main/java/tterrag1112/life_in_the_village/Entities/Party/PlayerPartySavedData.java
package tterrag1112.life_in_the_village.Guilds;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import tterrag1112.life_in_the_village.Guilds.Adventurer.PlayerParty;

import java.util.*;
import java.util.stream.Collectors;


public class PlayerPartySavedData extends SavedData {

    public static final SavedDataType<PlayerPartySavedData> TYPE =
            new SavedDataType<>(
                    "player_parties",
                    PlayerPartySavedData::new,
                    RecordCodecBuilder.create(instance ->
                            instance.group(
                                    PlayerParty.CODEC.listOf()
                                            .optionalFieldOf("parties",
                                                    new ArrayList<>())
                                            .forGetter(d ->
                                                    new ArrayList<>(
                                                            d.parties.values()))
                            ).apply(instance,
                                    PlayerPartySavedData::fromCodec)));

    private static PlayerPartySavedData fromCodec(
            List<PlayerParty> partyList) {
        PlayerPartySavedData data = new PlayerPartySavedData();
        partyList.forEach(p -> data.parties.put(p.getPartyId(), p));
        return data;
    }

    private final Map<UUID, PlayerParty> parties = new HashMap<>();

    public PlayerPartySavedData() {}

    public static PlayerPartySavedData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(TYPE);
    }

    // =========================================================================
    // Party CRUD
    // =========================================================================

    public void addParty(PlayerParty party) {
        parties.put(party.getPartyId(), party);
        setDirty();
    }

    public void removeParty(UUID partyId) {
        parties.remove(partyId);
        setDirty();
    }

    public Optional<PlayerParty> getParty(UUID partyId) {
        return Optional.ofNullable(parties.get(partyId));
    }

    /**
     * Returns the party led by the given player, if any.
     * A player can only lead one party at a time.
     */
    public Optional<PlayerParty> getPartyForPlayer(UUID playerId) {
        return parties.values().stream()
                .filter(p -> p.getLeaderPlayerId().equals(playerId))
                .findFirst();
    }

    /**
     * Returns the party that the given NPC belongs to, if any.
     */
    public Optional<PlayerParty> getPartyContaining(UUID npcId) {
        return parties.values().stream()
                .filter(p -> p.hasMember(npcId))
                .findFirst();
    }

    public List<PlayerParty> getAllParties() {
        return new ArrayList<>(parties.values());
    }

    public boolean playerHasParty(UUID playerId) {
        return getPartyForPlayer(playerId).isPresent();
    }

    // =========================================================================
    // Member death handling
    // =========================================================================

    /**
     * Marks a member as dead. If all members are dead the party is
     * automatically disbanded.
     *
     * @return true if the party was disbanded as a result
     */
    public boolean handleMemberDeath(UUID npcId, ServerLevel level) {
        Optional<PlayerParty> partyOpt = getPartyContaining(npcId);
        if (partyOpt.isEmpty()) return false;

        PlayerParty party = partyOpt.get();
        party.getMember(npcId).ifPresent(m ->
                party.updateMember(m.withDead()));
        setDirty();

        if (party.getAliveCount() == 0) {
            removeParty(party.getPartyId());
            // Notify player
            var player = level.getServer().getPlayerList()
                    .getPlayer(party.getLeaderPlayerId());
            if (player != null) {
                player.displayClientMessage(
                        net.minecraft.network.chat.Component.literal(
                                "Your party has been wiped out."),
                        false);
            }
            return true;
        }

        return false;
    }

    // =========================================================================
    // Kill tracking
    // =========================================================================

    /**
     * Records a kill by an NPC member and updates their level.
     */
    public void recordMemberKill(UUID npcId) {
        getPartyContaining(npcId).ifPresent(party -> {
            party.getMember(npcId).ifPresent(m ->
                    party.updateMember(m.withKill()));
            setDirty();
        });
    }
}