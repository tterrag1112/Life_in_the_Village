---
name: litv-gui-screen
description: >
  Create new Life in the Village GUI screens or migrate existing screens onto
  the Gui.Framework package. Use whenever the user asks to build a new screen
  (profile, book, compact, custom panel), migrate a hand-rolled screen to
  framework primitives, or wire an NPC profile action button to open a screen.
  Always use this skill before free-styling screen code — the Gui.Framework
  contract, the NeoForge 1.21.x event signatures, and the NPC profile action
  wiring are easy to get subtly wrong and the failures are usually silent
  (buttons that don't appear, packets that are never handled, screens that
  open but never close properly).
---

# Life in the Village — GUI Screen Skill

## Step 0 — Identify the Workflow

Before anything else, determine which path applies:

- **Path A — Migration**: an existing `Screen` subclass exists at
  `src/main/java/tterrag1112/life_in_the_village/Gui/` and needs to be
  rewritten to use Gui.Framework primitives. Behavior must stay identical.
- **Path B — New screen**: no screen exists yet. The user wants a fresh
  screen, possibly launched from an NPC profile action, a block interaction,
  or a command.

If the user's intent is unclear, ask them directly. Don't assume.

---

## Step 1 — Framework Inventory (both paths)

Do NOT write code until Gui.Framework has the primitives you need. List the
framework directory first:

```
src/main/java/tterrag1112/life_in_the_village/Gui/Framework/
```

Expected primitives as of Slice 6b:
- `Chrome` (with `Dims`, `Palette`; palettes `PARCHMENT` and `DARK_TRADE`)
- `Sidebar<S>` (generic section list)
- `TabBar<S>` (horizontal variant)
- `ScrollList<T>` (generic scrolling list with scrollbar)
- `StatBox` (label/value tile)
- `CoinRow` (g/s/b coin icon row)
- `Pill` (coloured text chip)
- `NeedMeter` (dots + bar)
- `ProgressBar` (XP / fill bar)
- `Portrait` (live entity render)
- `PortraitCache` (bounded LRU)
- `ClientNpcPreview` (detached preview entity builder)
- `TooltipLayer` (deferred rich tooltips)
- `StyledButton`, `StyledEditBox`

If a primitive needed for this screen is **missing** or **insufficient**:

1. Stop. Tell the user which primitive is needed and what shape.
2. Ask whether to extend the framework first (cleanest) or skip this
   migration/new screen until the primitive lands.
3. Do NOT silently fall back to manual drawing. Manual `g.fill()` /
   `g.renderOutline()` in screen code is a regression.

---

## Step 2 — Caller Inventory (CRITICAL — do this before writing anything)

This is the step most often skipped and the one that causes silent breakage.
The GuildScreen migration missed it, and the NPC profile action button
wasn't wired.

Run these searches **before** modifying the screen:

1. **Who opens this screen?** Grep the repo for the screen class name
   (e.g. `GuildScreen`). Every hit is a call site that must keep working.
   Common opener locations:
   - `NpcInteractionHandler` (right-click dispatch — deprecated but still
     reachable via `NpcProfileHub.handleAction`)
   - `NpcProfileHub.handleAction` (NPC profile action routing)
   - Block entity interaction handlers
   - Slash commands
   - Other screens (e.g. VillageBook's "Manage Company" button opens
     `CompanyManagementScreen`)

2. **What packets reference this screen?** Grep for `sendOpenPacket`,
   `OpenXScreenPacket`, `mc.setScreen(new XScreen(...))`, and
   `handle(...ctx)` patterns that set the screen as the active one.

3. **Is this screen an NPC profile action target?** Check
   `NpcProfileActionPacket.ActionType` — if an enum value like
   `OPEN_GUILD_HALL` exists, the screen is wired into the profile system.
   In that case the post-work checklist in Step 6 applies.

4. **Write the caller list down.** Maintain it as a text list in the response
   so verification in Step 7 has a concrete target. Example:
   ```
   Callers of GuildScreen:
   - NpcProfileHub.handleAction (action OPEN_GUILD_HALL) → sendOpenPacket
   - NpcInteractionHandler.handleGuildWorker (deprecated, still present)
   - OpenGuildScreenPacket.handle (client-side setScreen)
   ```

---

## Step 3 — Gather Inputs

| # | Input | Notes |
|---|-------|-------|
| 1 | **Screen name** | e.g. `GuildScreen`, `CommissionBoardScreen` |
| 2 | **Chrome choice** | `Chrome.BOOK` + `PARCHMENT` (book-style), `Chrome.COMPACT` + `PARCHMENT` (small NPC-ish), or custom dims + `DARK_TRADE` (dark panel). Define new palettes only if no existing one fits. |
| 3 | **Sections** | Vertical sidebar sections (`Sidebar<S>`), horizontal tabs (`TabBar<S>`), or none (single page). |
| 4 | **Data model** | What data does the screen render? For migrations, this is the existing packet payload. For new screens, design the snapshot record. |
| 5 | **Actions** | What server-side actions does the screen trigger? These become fields in an action packet (or reuse an existing one). |
| 6 | **Launch points** | How is the screen opened? Right-click NPC? Block interact? NPC profile action button? Multiple of these? |
| 7 | **List contents** | For each scrollable list, define a private row record and click semantics. |

For **migrations**: extract items 1–7 from the existing code. Don't redesign.

For **new screens**: confirm items 1–7 with the user before writing any file.

---

## Step 4A — Migration Flow

Follow this sequence. Do not reorder.

### 4A.1 — Read the source screen end to end
Full file. Count the sections, the lists, the buttons, the edit boxes, the
embedded panels. The goal is a visual identity match — if the migrated
version looks different from before, that's a regression.

### 4A.2 — Identify duplicated drawing code
Flag for deletion (not comment-out):
- Manual panel background fills (replace with `Chrome.draw`)
- Manual scrollbar drawing (replace with `ScrollList`'s built-in)
- Manual sidebar rendering (replace with `Sidebar<S>`)
- Local copies of `BookScreenColors` constants (use `BookScreenColors.X` directly)
- `drawStatBox`, `drawBook`, `drawSidebar` helpers (replace with framework calls)
- `renderCoinRow` helpers (use `CoinRow.draw`)
- Custom pill / rank indicator drawing (use `Pill`)

### 4A.3 — Rewrite the screen
Required elements:
- `Chrome.draw(g, x, y, dims, palette)` — always pass palette explicitly.
- `Sidebar<Section>` for section selection if multi-section.
- `ScrollList<RowType>` for each scrolling list.
- Private row records (NOT public, NOT in Framework package).
- `StyledButton` for all buttons (never bare vanilla `Button`).
- `StyledEditBox` for any text input.
- `CoinRow.draw` for every bronze amount display.
- `TooltipLayer` for hover tooltips — queue during render, flush after super.

Event routing order (inside `mouseClicked`):
1. Sidebar/TabBar (if present)
2. Active section's ScrollList
3. Other widgets
4. `super.mouseClicked(event, isDoubleClick)`

Use NeoForge 1.21.x signatures — see Step 8.

### 4A.4 — Preserve exactly
- Packet contracts (both `Open<X>Packet` and `<X>ActionPacket`) must not change
- Panel dimensions
- Section order and labels
- `isPauseScreen()` return value
- All server-side action wiring

### 4A.5 — Delete, don't comment
Dead code flagged in 4A.2 must be physically removed. Commented-out drawing
code creates uncertainty for the next migrator.

---

## Step 4B — New Screen Flow

### 4B.1 — Design the packet(s)
For a new screen launched from the server, you need:

- **`Open<Name>Packet`** (S → C) — carries the initial snapshot.
- **`<Name>ActionPacket`** (C → S) — carries action dispatches.
- Optional: **`<Name>SyncPacket`** (S → C) — for live refresh (like the
  NPC profile uses every 5 seconds).

Follow the pattern in `OpenVillageBookPacket.java` and
`OpenNpcProfilePacket.java`. Use `RegistryFriendlyByteBuf` and
`StreamCodec`. Keep the payload under 2MB; if it might exceed that, follow
the raw `ByteBuf` codec pattern in `KingdomMapSyncPacket`.

### 4B.2 — Design the server-side gatherer
Single static method that takes the relevant server-side data and produces
the snapshot. Mirror `NpcProfileSnapshotBuilder`. Reads should go through
the `VillageDataAccess` view interfaces where available.

### 4B.3 — Write the Screen class
Same requirements as 4A.3. Extend `Screen`, NOT `AbstractContainerScreen`
(this mod's pattern is snapshot-based GUIs, not inventory containers).

Required fields:
```java
private final <Snapshot> data;
private int chromeX, chromeY;   // computed in init()
```

Required methods:
```java
public static void sendOpenPacket(ServerPlayer player, ...) { ... }
@Override protected void init() { ... }
@Override public void render(GuiGraphics g, int mx, int my, float pt) { ... }
@Override public void onClose() { ... }  // if the screen holds resources
@Override public boolean isPauseScreen() { return false; }
```

### 4B.4 — Register packets
Every new packet MUST be added to `Events/ModModEvents.registerPayloads`.
Without this, the packet silently never arrives. This is the single most
common cause of "screen doesn't open".

Example:
```java
registrar.playToClient(OpenFooPacket.TYPE, OpenFooPacket.CODEC, OpenFooPacket::handle);
registrar.playToServer(FooActionPacket.TYPE, FooActionPacket.CODEC, FooActionPacket::handle);
```

### 4B.5 — Wire the launch point
Use the checklist in Step 5 if the screen is launched from an NPC profile
action. Otherwise:
- **Block interaction**: add a case in the block's `useWithoutItem` /
  `use` method, call `sendOpenPacket`.
- **Command**: register the command in `ModModEvents.onRegisterCommands`.
- **Other screen**: add a `StyledButton` whose onPress sends the open packet.

---

## Step 5 — NPC Profile Action Integration (the most-missed step)

If the screen is launched from an NPC profile action button, ALL of these
must be done. Missing any one causes the action to silently not work.

### 5.1 — Add the action enum value
In `NpcProfileActionPacket.ActionType`:
```java
OPEN_<SCREEN_NAME>,
```

### 5.2 — Handle the action in NpcProfileHub
`NpcProfileHub.handleAction` dispatch switch:
```java
case OPEN_<SCREEN_NAME> -> {
    <Screen>.sendOpenPacket(player, ...relevant ids...);
    forceClose(npc);   // action-opens-another-screen pattern
}
```

The `forceClose(npc)` is critical. Without it, the profile screen stays
"open" conceptually on the server and the NPC stays locked in conversation.

### 5.3 — Set action availability in the snapshot
`NpcProfileSnapshotBuilder.build` — add a line to the `actionAvailable`
map:
```java
actions.put(NpcProfileActionPacket.ActionType.OPEN_<SCREEN_NAME>.name(),
    <boolean predicate>);
```

The predicate is typically based on the NPC's profession, reputation, or
state (e.g. `profession == GUILDWORKER && !repHostile`).

### 5.4 — Add the button entry in ActionBarPanel
`ActionBarPanel` has a static table mapping `ActionType` → label + priority.
Add:
```java
entry(ActionType.OPEN_<SCREEN_NAME>, "Display Label", <priority>),
```

Priorities cluster by theme (trade = 10s, book-views = 30s, guild = 40s,
company = 50s, adventurer = 60s, NEW = 80s, gift = 90s). Pick a priority
that groups logically with similar actions.

### 5.5 — Client handler set-screen pattern
Every `Open<Name>Packet.handle` on the client side should either open fresh
or apply-snapshot-if-already-open:
```java
public static void handle(Open<Name>Packet pkt, IPayloadContext ctx) {
    ctx.enqueueWork(() -> {
        var mc = Minecraft.getInstance();
        if (mc.screen instanceof <Name>Screen existing
                && existing.getDataId().equals(pkt.snapshot().dataId())) {
            existing.applySnapshot(pkt.snapshot());
        } else {
            mc.setScreen(new <Name>Screen(pkt.snapshot()));
        }
    });
}
```

### 5.6 — Verification

Open the profile of an NPC whose profession should show this action.
Confirm:
1. Button appears in the action bar.
2. Click closes the profile and opens the target screen.
3. Target screen has the expected content.
4. Closing the target screen returns the player to the world, not back
   into the profile.
5. NPC is unlocked (walks away normally).

---

## Step 6 — Event Signature Gotchas

NeoForge 1.21.x uses wrapper classes for mouse and keyboard events. Every
screen migration or new screen must use the current signatures. Wrong
signatures cause compile errors like "method cannot be applied to given
types".

### Imports
```java
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.KeyEvent;
```

### Mouse clicks
```java
@Override
public boolean mouseClicked(MouseButtonEvent event, boolean isDoubleClick) {
    double mx = event.x();
    double my = event.y();
    int button = event.button();
    // route to widgets, then super
    return super.mouseClicked(event, isDoubleClick);
}
```

### Mouse scrolling
```java
@Override
public boolean mouseScrolled(double mx, double my, double sx, double sy) {
    // sy is the vertical scroll amount; sx is horizontal (usually 0)
    return super.mouseScrolled(mx, my, sx, sy);
}
```

### Key presses
```java
@Override
public boolean keyPressed(KeyEvent event) {
    return super.keyPressed(event);
}
```

If a framework widget (Sidebar, ScrollList) has a different signature, adapt
the CALL SITE, not the framework. The framework matches NeoForge 1.21.x
conventions.

### StyledButton construction
```java
StyledButton btn = StyledButton.builder(
        Component.literal("Label"),
        b -> onClickHandler())
    .pos(x, y)
    .size(w, h)
    .build();
addRenderableWidget(btn);
```

Lowercase `builder`, matches vanilla `Button.builder(...)`.

---

## Step 7 — Post-Write Verification

Before considering the work done, run this checklist against the caller
inventory from Step 2.

For **every** caller in the inventory:
- [ ] Does it still compile?
- [ ] Does its invocation path still reach the screen?
- [ ] For screens invoked via NPC profile action: did you hit every point
      in Step 5 (action enum, hub handler, snapshot builder, action bar,
      client handler)?

For the **screen itself**:
- [ ] Build succeeds with no new warnings.
- [ ] Line count after migration is ≥30% smaller than before.
- [ ] No `BookScreenColors` values are copied as local `static final int`
      constants — the framework and screen refer to `BookScreenColors.X`
      directly.
- [ ] No manual `g.fill(...)` for panel backgrounds.
- [ ] No manual scrollbar drawing.
- [ ] No `drawBook` / `drawSidebar` / `drawStatBox` helpers remain in the
      screen file.
- [ ] Event signatures match Step 6.
- [ ] `StyledButton.builder(...)` not `StyledButton.Builder(...)`.

For **new screens**:
- [ ] Packet registered in `ModModEvents.registerPayloads`.
- [ ] Server-side `sendOpenPacket` method exists and is called from every
      launch point.
- [ ] Client-side `handle` method sets the screen.
- [ ] If NPC profile action: all five points of Step 5 complete.

---

## Step 8 — Chrome Palette Quick Reference

| Palette | When to use |
|---------|-------------|
| `Chrome.PARCHMENT` | Default for book-chrome (420×300 or 320×240 compact). Parchment background, border outline, double-outline interior. Matches VillageBook, KingdomBook, Guild, Company, NpcProfile. |
| `Chrome.DARK_TRADE` | Dark vendor-style panel. Matches TradeScreen. 0xFF222222 outer, 0xFF2E2E2E inner. |
| New palette | Only if the screen's visual identity genuinely differs from both. Add as a `Chrome.Palette` constant with a descriptive name. Update Chrome.java; no other files. |

---

## Step 9 — Dims Quick Reference

| Preset | Size | Typical use |
|--------|------|-------------|
| `Chrome.BOOK` | 420 × 300 | Multi-section book screens with sidebar |
| `Chrome.COMPACT` | 320 × 240 | NPC profile and similarly-sized single-page screens |
| Custom `Dims(w, h, sidebarW, pagePad)` | Any | Trade panel (320×240, no sidebar), commission board (TBD) |

---

## Output Requirements

Deliver one of the following, matching the path:

**Path A (migration):**
1. Complete rewrite of the screen file.
2. List of deleted helpers and dead code blocks.
3. Line count before/after.
4. Caller inventory from Step 2, each entry marked verified.
5. Notes on any framework friction encountered.

**Path B (new screen):**
1. New screen file.
2. `Open<Name>Packet.java` and `<Name>ActionPacket.java` (and `SyncPacket`
   if needed).
3. `<Name>SnapshotBuilder.java` (server-side gatherer).
4. Diff for `ModModEvents.registerPayloads` showing the new registrations.
5. If NPC profile action: diffs for all five files touched in Step 5.
6. Launch-point wiring diff(s).

In both cases the work is not done until the Step 7 checklist is satisfied.

---

## Related Skills

- `litv-building-profile` — wiring a new BuildingType's placement and
  inhabitants. Sometimes a new building needs a new GUI (e.g. a bank block
  → bank screen) — use both skills in sequence.
- `minecraft-mod-dev` — broader context for modding, NeoForge conventions,
  data components. Consult when this skill doesn't cover something (e.g.
  entity rendering internals for Portrait extensions).
