# Life in the Village — Castle Addon Guide

This guide explains how to create new castle styles or extend existing ones
using only JSON and NBT files, without writing any Java code.

---

## Adding a New Castle Style

Create a file at:
```
data/<your_modid>/castle_styles/<style_name>.json
```

The file must match the CastleStyle codec structure. Copy and modify an
existing style as a starting point. The minimum required fields are:
```json
{
  "style_id": "my_style",
  "layout": {
    "plan_type": "square",
    "min_radius": 20,
    "max_radius": 30,
    "polygon_sides": 0
  },
  "walls": {
    "min_wall_height": 8,
    "max_wall_height": 12,
    "wall_thickness": 3
  },
  "towers": {
    "tower_shape": "square",
    "min_tower_radius": 3,
    "max_tower_radius": 5,
    "tower_height_bonus": 4,
    "tower_frequency": 0.6
  },
  "donjon": {
    "has_donjon": true,
    "donjon_min_size": 5,
    "donjon_max_size": 7,
    "donjon_height_bonus": 6,
    "has_inner_ward": false,
    "inner_ward_scale": 0.5
  },
  "features": {
    "has_moat": true,
    "moat_width": 4,
    "moat_depth": 3,
    "moat_fill": "water",
    "has_portcullis": true,
    "has_drawbridge": true,
    "has_barbian": false,
    "has_wall_turrets": false,
    "turret_frequency": 0.0,
    "add_torches": true,
    "add_flags": true,
    "add_vines": false,
    "add_rubble": false
  },
  "pools": {
    "battlement_pool":  "<modid>:castle/battlements/<style>",
    "gatehouse_pool":   "<modid>:castle/gatehouses/<style>",
    "window_pool":      "<modid>:castle/windows/<style>",
    "tower_roof_pool":  "<modid>:castle/tower_roofs/<style>",
    "donjon_roof_pool": "<modid>:castle/donjon_roofs/<style>",
    "interior_pool":    "life_in_the_village:castle/interiors/empty"
  },
  "primary_material":  { ... },
  "accent_material":   { ... },
  "floor_material":    { ... },
  "ruination_level": 0.0,
  "style_seed": 9999,
  "function": { ... }
}
```

---

## Plan Types

| Value         | Description                                          |
|---------------|------------------------------------------------------|
| `square`      | Four-corner square curtain wall                      |
| `rectangle`   | Longer on the X axis                                 |
| `polygon`     | Regular N-sided polygon. Set `polygon_sides` (5–12)  |
| `concentric`  | Two concentric curtain walls                         |
| `irregular`   | Perturbed polygon — organic, asymmetric              |

---

## Tower Shapes

| Value       | Description                                |
|-------------|--------------------------------------------|
| `round`     | Circular cross-section                     |
| `square`    | Square cross-section                       |
| `d_shaped`  | Semicircle projecting from wall face       |
| `polygonal` | Octagonal cross-section                    |

---

## Moat Fill Types

| Value    | Description                    |
|----------|--------------------------------|
| `water`  | Standard water-filled moat     |
| `lava`   | Lava moat with netherrack floor|
| `empty`  | Dry ditch                      |
| `poison` | Water (visual tint via biome)  |

---

## Material Palette Format
```json
{
  "main_blocks": [
    { "block": "minecraft:stone_bricks",         "weight": 70 },
    { "block": "minecraft:mossy_stone_bricks",   "weight": 20 },
    { "block": "minecraft:cracked_stone_bricks", "weight": 10 }
  ],
  "cracked_variants": [
    { "block": "minecraft:cracked_stone_bricks", "weight": 60 },
    { "block": "minecraft:cobblestone",          "weight": 40 }
  ],
  "stair_block": "minecraft:stone_brick_stairs",
  "slab_block":  "minecraft:stone_brick_slab",
  "wall_block":  "minecraft:stone_brick_wall"
}
```

---

## Adding NBT Structure Templates

Place `.nbt` files at:
```
data/<modid>/structures/castle/<pool_type>/<style_name>.nbt
data/<modid>/structures/castle/<pool_type>/<style_name>_0.nbt
data/<modid>/structures/castle/<pool_type>/<style_name>_1.nbt
```

The system scans for `_0` through `_7` variants and picks randomly.
If no file is found, the procedural fallback is used.

### Pool types and their contracts

| Pool type      | Template purpose            | Size convention                     |
|----------------|-----------------------------|-------------------------------------|
| `battlements`  | Merlon strip for wall tops  | N × 2 × 2, origin at outer top face |
| `gatehouses`   | Gate cap / arch             | Variable, must include 3-wide air opening |
| `windows`      | Arrow slit surround         | 3 × 3 × 1, origin at outer face     |
| `tower_roofs`  | Tower cap                   | (2R+1) × H × (2R+1), system centers |
| `donjon_roofs` | Keep roof                   | Same as tower_roofs                 |
| `interiors`    | Interior detail             | Fits inside inner ward, may include entities |

### Subbuilding templates
```
data/<modid>/structures/castle/subbuildings/<type>/<style>.nbt
```

Example: `castle/subbuildings/library/moorish.nbt`

Subbuilding templates may include:
- Chest block entities (loot tables override via SubbuildingSpec)
- Armor stands (decoration)
- Entity spawn data (TownspersonMob variants — see Phase 3 docs)

---

## Adding a Custom Subbuilding Spec

In your style JSON, under `"function"` → `"subbuilding_specs"`:
```json
{
  "type": "library",
  "zone": "inner_ward",
  "min_count": 1,
  "max_count": 1,
  "loot_table": "life_in_the_village:castle/library",
  "pool": "my_modid:castle/subbuildings/library/my_style"
}
```

If `pool` is omitted or its file is missing, the procedural fallback runs.
If `loot_table` is omitted, no chest is placed.

---

## Ruination

Set `ruination_level` between `0.0` (pristine) and `1.0` (collapsed ruin).

Enable `"add_vines": true` and `"add_rubble": true` for overgrown appearance.
The ruination post-pass runs automatically when `ruination_level >= 0.5`.

Values below `0.5` apply partial ruination (cracked blocks, missing battlements)
but do not trigger the full post-pass.