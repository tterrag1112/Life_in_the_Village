---
name: litv-shape-rule
description: >
  Writes a new ShapeRule implementation for the Life in the Village Minecraft
  mod. Use this skill whenever the user needs a new rule that can be applied
  to village types via their shape_rules JSON array — for example, a rule that
  forces buildings near a specific feature, overrides density for a specific
  type, or sets a custom anchor position. Output is a complete inner class
  added to BuiltinRules.java plus the registration line in
  ShapeRuleRegistration.registerBuiltins(). Always use this skill before
  free-styling rule code — it documents the RuleContext API and the JSON
  parsing contract that rules must satisfy.
---

# Life in the Village — Shape Rule Skill

## Step 0 — Gather Required Inputs

| # | Input | Notes |
|---|-------|-------|
| 1 | **Rule type name** | The JSON `"type"` string that identifies this rule (e.g. `"landmark"`) |
| 2 | **Class name** | Inner class name in `BuiltinRules`, e.g. `LandmarkRule` |
| 3 | **What it does to RuleContext** | Which `ctx.setX()` / `ctx.addX()` methods does it call? |
| 4 | **Configuration fields** | What JSON fields does it read? Field names, types, defaults |
| 5 | **Ordering dependency** | Does it depend on anchor/axis/zone rules having run first? |

---

## Step 1 — Write the Rule Class

**File:** `src/main/java/tterrag1112/life_in_the_village/Village/Planning/Rules/BuiltinRules.java`

Add a new `public static final class <n>Rule implements ShapeRule` at the end of the file.

**Skeleton:**
```java
// =========================================================================
// <n>Rule — <one-line description>
// =========================================================================

public static final class <n>Rule implements ShapeRule {

    // configuration fields parsed from JSON
    private final <type> <field>;

    public <n>Rule(<type> <field>) {
        this.<field> = <field>;
    }

    @Override public String typeName() { return "<type_name>"; }

    @Override
    public void apply(RuleContext ctx) {
        // mutate ctx using the RuleContext write API
        // see references/api.md → RuleContext Write API
    }

    public static void register() {
        ShapeRule.Registry.register("<type_name>", json -> {
            // parse fields from json with has() guards and defaults
            // return new <n>Rule(...)
        });
    }
}
```

See `references/api.md` for the complete `RuleContext` read/write API,
JSON parsing patterns, and all six existing rules as examples.

---

## Step 2 — Register in ShapeRuleRegistration

**File:** `src/main/java/tterrag1112/life_in_the_village/Village/Planning/Rules/ShapeRuleRegistration.java`

Add one line inside `registerBuiltins()`:

```java
BuiltinRules.<n>Rule.register();
```

---

## Step 3 — Self-check before presenting

- [ ] `typeName()` returns the same string registered in `ShapeRule.Registry.register()`
- [ ] JSON parser uses `json.has("field")` before every `json.get("field")` call
- [ ] All JSON fields have sensible defaults when the field is absent
- [ ] `apply()` only calls `ctx.setX()` / `ctx.addX()` — never reads world blocks
- [ ] Rule is stateless beyond its constructor fields (no mutable instance state)
- [ ] Registration line added to `ShapeRuleRegistration.registerBuiltins()`

Present the complete inner class inline, followed by the registration line.
