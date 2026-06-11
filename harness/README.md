# harness/baseline.json

This directory holds the committed baseline that the headless layout
harness diffs against in `check` mode.

> **⚠️ The committed `baseline.json` (recorded 2026-05-18, schema v1)
> is INVALID until re-recorded.** It measures the pre-district planner
> (roads-first, no civic/market/residential/workshop districts, with
> `FARMHOUSE`/`WELL`/`SHRINE`/`TREASURY` placing freely). The shipped
> planner runs with `DISTRICT_ONLY_MODE` on, so a `check` against this
> file floods false per-type regressions and carries no district
> metrics (schema v1 has no `district` block). Re-record before
> trusting any diff.

## Re-recording (the one command)

```
./gradlew test --tests HeadlessHarnessTest -Dharness.mode=record
```

That overwrites this file with the current planner's output (schema
**v2** — adds the per-run `district` block: civic/market plaza areas,
market reserve outcome, residential precinct reserve rate, workshop
craft-row seating) and never fails. Commit the regenerated file; then
`./gradlew test --tests HeadlessHarnessTest` (check mode) gates
against it.

The committed file must capture the system as it is — bugs included —
so that regressions show up as deltas rather than disappearing into
noise. A v1 baseline still loads (the missing `district` block reads
back empty), but the district gates can't fire until a v2 baseline
exists.

See [`docs/HEADLESS_HARNESS.md`](../docs/HEADLESS_HARNESS.md) for the
full contract, including the `DISTRICT_ONLY_MODE` decision and the
district metric definitions.
