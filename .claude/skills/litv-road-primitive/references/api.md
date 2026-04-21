# Life in the Village — Road Primitive API Reference

## Contract

`computeCenterline` must be:
- **Pure** — no blocks placed, no world mutations
- **Deterministic** — same inputs → same output; seed from `DriftNoise.localSeed` + geometry
- **Surface-snapped** — every point calls `surfaceAt(level, x, z)`
- **Deduplicated** — consecutive XZ duplicates removed via `dedupe(line)`

---

## Shared Helpers (already in RoadPrimitive.java)

```java
// Surface-snap a point to MOTION_BLOCKING_NO_LEAVES
BlockPos p = surfaceAt(level, x, z);

// Remove consecutive XZ duplicates from a line
List<BlockPos> clean = dedupe(line);

// Walk a drifted straight line from `from` to `to`
// localSeed: from DriftNoise.localSeed; driftAmplitude: 3=subtle, 6=visible
List<BlockPos> line = driftedLine(level, from, to, driftAmplitude, localSeed);
```

---

## DriftNoise

```java
// Derive a deterministic local seed. XOR with geometry constants to
// make distinct primitives with the same endpoints produce different drift.
long localSeed = DriftNoise.localSeed(worldSeed, anchorA, anchorB)
    ^ ((long) someRadius * 2654435761L)          // radius constant
    ^ Double.doubleToLongBits(someAngle);         // angle constant

// Sample noise at parameter t ∈ [0,1] → value ∈ [-1, 1]
// Smooth: nearby t values return similar values (not jittery)
double noise = DriftNoise.sample(t, localSeed);
```

Always XOR at least one geometry constant into `localSeed`. Two different primitives with the same endpoint pair will otherwise produce identical drift — which looks obviously wrong when they share a common anchor.

---

## Building a Centerline

### Parametric loop (most primitives)
```java
int steps = Math.max(N, (int) Math.ceil(estimatedArcLength));
List<BlockPos> line = new ArrayList<>(steps + 1);
double ampScale = Math.min(1.0, estimatedArcLength / 64.0); // drift scales with length

for (int i = 0; i <= steps; i++) {
    double t = i / (double) steps;

    // 1. Compute base position from geometry
    double baseX = /* ... */;
    double baseZ = /* ... */;

    // 2. Compute perpendicular direction for drift
    double perpX = /* ... */;
    double perpZ = /* ... */;

    // 3. Apply drift
    double drift = DriftNoise.sample(t, localSeed) * driftAmplitude * ampScale;
    int x = (int) Math.round(baseX + perpX * drift);
    int z = (int) Math.round(baseZ + perpZ * drift);

    line.add(surfaceAt(level, x, z));
}
return dedupe(line);
```

### Segment-based (e.g. switchback — multiple straight legs)
```java
List<BlockPos> line = new ArrayList<>();
// For each leg, use driftedLine with a leg-specific seed:
long legSeed = DriftNoise.localSeed(worldSeed, legStart, legEnd)
    ^ ((long) legIndex * 0xDEADBEEFL);
line.addAll(driftedLine(level, legStart, legEnd, driftAmplitude, legSeed));
// driftedLine includes both endpoints, so remove the shared junction before
// appending subsequent legs to avoid duplicates — or call dedupe at the end.
return dedupe(line);
```

---

## Geometry Reference

### Circle / arc points
```java
// Point at angle `a` on a circle of `r` centred at (cx, cz):
int x = cx + (int) Math.round(Math.cos(a) * r);
int z = cz + (int) Math.round(Math.sin(a) * r);
```

### Arc length
```java
double arcLength = Math.abs(arcSpan) * radius;           // partial arc
double circumference = 2 * Math.PI * radius;             // full ring
```

### Step count — rules of thumb
```
Short connector (< 16 blocks):  steps = Math.max(4, (int) length)
Standard road (16–64 blocks):   steps = Math.max(8, (int) length)
Ring/arc:                        steps = Math.max(16, (int) arcLength)
```

### Perpendicular vector (for drift direction)
```java
// Given heading (hx, hz) normalised:
double perpX = -hz;
double perpZ =  hx;
// Drift offset: point += perp * noise * amplitude
```

---

## Tier

Every record must expose `tier()`. Always store `RoadShape.RoadTier tier` as the last record field and return it:

```java
@Override
public RoadShape.RoadTier tier() { return tier; }
```

Available tiers:
```
RoadShape.RoadTier.MAIN_ROAD      — trunk, widest
RoadShape.RoadTier.VILLAGE_PATH   — standard village road
RoadShape.RoadTier.FOOTPATH       — narrow connector / stub spur
```

---

## Documenting Connection Points

The Javadoc for every primitive must state its start and end positions so recipes can connect subsequent road pieces without gaps. Example:

```java
/**
 * Switchback road ascending a slope.
 *
 * <p>Centerline starts at {@code bottom} and ends at the top of the
 * final leg, approximately {@code legCount * legLength} blocks uphill
 * in the slope direction.
 */
```

---

## Full Example — Arc (for reference)

```java
record Arc(
        BlockPos centre,
        int radius,
        double startAngle,
        double arcSpan,
        double driftAmplitude,
        RoadShape.RoadTier tier
) implements RoadPrimitive {

    @Override
    public List<BlockPos> computeCenterline(ServerLevel level, long worldSeed) {
        long localSeed = DriftNoise.localSeed(worldSeed, centre, centre)
                ^ ((long) radius * 2654435761L)
                ^ Double.doubleToLongBits(startAngle)
                ^ Double.doubleToLongBits(arcSpan);

        double arcLength = Math.abs(arcSpan) * radius;
        int steps = Math.max(8, (int) Math.ceil(arcLength));
        List<BlockPos> line = new ArrayList<>(steps + 1);
        double ampScale = Math.min(1.0, arcLength / 64.0);

        for (int i = 0; i <= steps; i++) {
            double t = i / (double) steps;
            double angle = startAngle + arcSpan * t;
            // Perpendicular to a circle = radial direction
            double drift = DriftNoise.sample(t, localSeed) * driftAmplitude * ampScale;
            double r = radius + drift;
            int x = centre.getX() + (int) Math.round(Math.cos(angle) * r);
            int z = centre.getZ() + (int) Math.round(Math.sin(angle) * r);
            line.add(surfaceAt(level, x, z));
        }
        return dedupe(line);
    }

    @Override
    public RoadShape.RoadTier tier() { return tier; }
}
```
