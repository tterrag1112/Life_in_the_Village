package tterrag1112.life_in_the_village.Village.Decoration.StreetFurniture;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SignBlock;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tterrag1112.life_in_the_village.Guilds.Common.Requests.Request;
import tterrag1112.life_in_the_village.Guilds.Common.Requests.RequestBoard;
import tterrag1112.life_in_the_village.Kingdom.Kingdom;
import tterrag1112.life_in_the_village.Kingdom.KingdomLaw;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Npc.Laws.VillageLaw;
import tterrag1112.life_in_the_village.Npc.Laws.VillagePolicy;
import tterrag1112.life_in_the_village.Village.Decoration.Framework.DecorationPlacement;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.ArrayList;
import java.util.List;

/**
 * B2.2 — places a vanilla sign at every {@code PLAZA_NOTICE_BOARD}
 * placement in a village and populates its four lines from
 * {@link VillagePolicy} (laws), {@link RequestBoard} (open requests
 * scoped to the village), and the village's owning {@link Kingdom}'s
 * {@link KingdomLaw} list.
 *
 * <p>Doc 06 §"Noticeboard content integration" originally specified
 * a richer multi-tab UI on a lectern; B2.2 simplifies to a 4-line
 * sign per the user's "use a sign for now until the BlockEntity is
 * created" direction. When that custom BlockEntity lands, this
 * writer's {@link #generateContent} is the only call site that needs
 * to produce different output — placement and refresh stay
 * unchanged.</p>
 *
 * <h3>Update path</h3>
 * <ol>
 *   <li>Initial write: {@link #writeForVillage} after the decoration
 *       pass commits placements.</li>
 *   <li>Refresh: {@link NoticeboardTickHandler} polls every
 *       in-game day and calls {@link #writeForVillage} again.</li>
 * </ol>
 *
 * <p>Pre-conditions: {@link DecorationPlacement} records produced by
 * the matcher have already been persisted on
 * {@link VillageSavedData}; the writer locates noticeboards by
 * filtering {@code pieceId} suffix {@code "/sign/notice_board"}.</p>
 */
public final class NoticeboardWriter {

    private static final Logger LOGGER = LoggerFactory.getLogger(NoticeboardWriter.class);

    /** Match by suffix so the culture prefix doesn't matter — every
     *  culture's notice_board piece sits under {@code .../sign/notice_board}. */
    private static final String NOTICEBOARD_PIECE_SUFFIX = "/sign/notice_board";

    /** Vanilla sign supports exactly four lines on the front face. */
    public static final int SIGN_LINES = 4;

    private NoticeboardWriter() {}

    /**
     * Walks the village's persisted decoration placements, locates
     * every noticeboard, places a sign block (if not already
     * present), and writes current content lines. Idempotent — safe
     * to call after every law / request / decree change.
     */
    public static int writeForVillage(ServerLevel level, Village village,
                                      VillageSavedData data) {
        if (level == null || village == null || data == null) return 0;
        List<Component> content = generateContent(level, village);
        int written = 0;
        for (DecorationPlacement p : data.getDecorationsForVillage(village.getId())) {
            if (!isNoticeBoard(p)) continue;
            if (placeAndPopulate(level, p, content)) written++;
        }
        return written;
    }

    /** Builds a 4-line sign payload from laws / requests / decrees.
     *  Always non-null and exactly {@link #SIGN_LINES} long; missing
     *  data appears as blank lines. Public for tests + the future
     *  custom-BE path that wants the same content. */
    public static List<Component> generateContent(ServerLevel level, Village village) {
        List<String> lines = new ArrayList<>();

        // Header — village name kept short.
        String name = village.getName();
        lines.add(name == null || name.isEmpty() ? "Village Notices" : name);

        // Active laws — VillagePolicy.
        VillagePolicy policy = village.getPolicy();
        int lawCount = policy != null ? policy.activeLaws().size() : 0;
        if (lawCount > 0) {
            // Single line summary; detailed list is for the future
            // multi-page UI. Show the first law's name + count.
            VillageLaw first = policy.activeLaws().iterator().next();
            String suffix = lawCount > 1 ? " +" + (lawCount - 1) + " more" : "";
            lines.add("Law: " + shortName(first.name()) + suffix);
        }

        // Open requests scoped to this village.
        int reqCount = countOpenRequestsForVillage(level, village);
        if (reqCount > 0) {
            lines.add(reqCount == 1 ? "1 open request" : reqCount + " open requests");
        }

        // Kingdom decrees — first kingdom that owns this village.
        Kingdom owner = findOwningKingdom(level, village);
        if (owner != null && !owner.getActiveLaws().isEmpty()) {
            KingdomLaw first = owner.getActiveLaws().iterator().next();
            int decreeCount = owner.getActiveLaws().size();
            String suffix = decreeCount > 1 ? " +" + (decreeCount - 1) : "";
            lines.add("Decree: " + shortName(first.name()) + suffix);
        }

        // Pad / truncate to 4 lines.
        while (lines.size() < SIGN_LINES) lines.add("");
        if (lines.size() > SIGN_LINES) lines = lines.subList(0, SIGN_LINES);

        List<Component> out = new ArrayList<>(SIGN_LINES);
        for (String s : lines) out.add(Component.literal(truncateForSign(s)));
        return out;
    }

    // ── Internals ───────────────────────────────────────────────────────

    private static boolean isNoticeBoard(DecorationPlacement p) {
        return p != null && p.pieceId() != null
                && p.pieceId().endsWith(NOTICEBOARD_PIECE_SUFFIX);
    }

    /**
     * Places a standing sign at {@code placement.pos()} (if not
     * already a sign) and writes the four content lines. Returns
     * true if a sign block was placed or an existing sign updated.
     */
    private static boolean placeAndPopulate(ServerLevel level,
                                            DecorationPlacement placement,
                                            List<Component> content) {
        BlockPos pos = placement.pos();
        BlockState current = level.getBlockState(pos);
        boolean placed = false;
        boolean isSign = current.getBlock() instanceof SignBlock;
        if (!isSign) {
            // Only auto-place a sign over empty space. If a real NBT
            // landed here (from a user-authored notice_board piece)
            // and put a non-sign block in this position, leave it
            // alone — assume the NBT knows what it wants. Without
            // this guard the writer would overwrite future custom
            // BlockEntities the moment a player authored real
            // content.
            if (!current.isAir() && !current.canBeReplaced()) {
                return false;
            }
            BlockState sign = Blocks.OAK_SIGN.defaultBlockState();
            // Rotation: 0..15 around Y. Map facing direction to the
            // closest 16-step rotation. Direction.SOUTH = rotation 0
            // in vanilla sign-rotation convention.
            int rotation = signRotationFromFacing(placement.facing());
            if (sign.hasProperty(BlockStateProperties.ROTATION_16)) {
                sign = sign.setValue(BlockStateProperties.ROTATION_16, rotation);
            }
            if (!level.setBlock(pos, sign, 3)) return false;
            placed = true;
        }

        if (!(level.getBlockEntity(pos) instanceof SignBlockEntity be)) {
            LOGGER.warn("NoticeboardWriter: sign block at {} has no SignBlockEntity",
                    pos);
            return placed;
        }
        SignText text = be.getFrontText();
        for (int i = 0; i < SIGN_LINES; i++) {
            text = text.setMessage(i, content.get(i));
        }
        be.setText(text, /* front */ true);
        be.setChanged();
        level.sendBlockUpdated(pos, level.getBlockState(pos),
                level.getBlockState(pos), 3);
        return true;
    }

    private static int signRotationFromFacing(Direction facing) {
        if (facing == null) return 0;
        // Standing sign rotation (0..15): 0 = south, 4 = west,
        // 8 = north, 12 = east. Approximate from cardinal.
        return switch (facing) {
            case SOUTH -> 0;
            case WEST  -> 4;
            case NORTH -> 8;
            case EAST  -> 12;
            default    -> 0;
        };
    }

    private static int countOpenRequestsForVillage(ServerLevel level, Village village) {
        try {
            RequestBoard board = RequestBoard.get(level);
            int count = 0;
            for (Request r : board.open()) {
                if (village.getId().equals(r.originVillageId())) count++;
            }
            return count;
        } catch (Exception e) {
            // RequestBoard is per-world SavedData; if the lookup
            // fails (e.g. on integrated server before the world is
            // attached) treat as zero requests.
            return 0;
        }
    }

    private static Kingdom findOwningKingdom(ServerLevel level, Village village) {
        try {
            VillageSavedData data = VillageSavedData.get(level);
            for (Kingdom k : data.getAllKingdoms()) {
                if (k.containsVillage(village.getId())) return k;
            }
        } catch (Exception e) {
            // Best-effort — kingdom data missing means no decree line.
        }
        return null;
    }

    /** Vanilla signs render about 15 wide-glyph chars per line.
     *  Truncate harder than necessary so heavy glyphs don't wrap
     *  awkwardly — under-fills are fine, over-fills clip. */
    private static String truncateForSign(String s) {
        if (s == null) return "";
        return s.length() <= 15 ? s : s.substring(0, 15);
    }

    /** Compresses {@code SCREAMING_SNAKE} enum names into a more
     *  readable Title Case form, then truncates. */
    private static String shortName(String enumName) {
        if (enumName == null) return "";
        String[] parts = enumName.split("_");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p.isEmpty()) continue;
            sb.append(Character.toUpperCase(p.charAt(0)));
            sb.append(p.substring(1).toLowerCase());
            sb.append(' ');
        }
        return sb.toString().trim();
    }
}
