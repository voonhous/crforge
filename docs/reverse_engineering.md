# Reverse Engineering Missing Unit Fields

Some units (e.g. SuspiciousBush, BushGoblin) are absent from `characters.decoded.csv`. Their stats in `missing_units.json` are partially estimated. The fields below can be verified by observing the live game.

## Check raw decoded data first

Before resorting to in-game observation, check whether the character exists in the full decoded CSV before the summarisation step. The `decoder/decoder_csv.py` pipeline may be filtering out newer characters. If the entry exists in the raw output, extract the fields directly -- no guesswork needed.

## Field-specific methods

### collisionRadius

Two units touching means their centers are separated by the sum of their collision radii.

1. Deploy the unknown unit next to a unit with a known collisionRadius (e.g. Knight = 0.5 tiles).
2. Screen-record at the moment they are pushing against each other (not mid-knockback).
3. Measure the pixel distance between their center points.
4. Repeat with the known unit paired with another known unit to establish a pixels-per-tile ratio.
5. Calculate: `unknownRadius = (measuredGap / pixelsPerTile) - knownRadius`

### mass

Mass determines how far a unit is knocked back by effects with known pushback values.

1. Use a consistent knockback source (e.g. The Log, pushback = 2400).
2. Hit the unknown unit and a reference unit with the same mass estimate (e.g. Goblin, mass = 2.0) in separate identical scenarios.
3. Screen-record both and compare knockback distance frame-by-frame.
4. If the distances match, the masses match. If not, test against other reference units (Skeleton = 1.0, Knight = 4.0, BattleRam = 6.0) to bracket the value.
5. Heavier units slide less. The relationship is approximately: `knockbackDistance ~ pushback / mass`.

### sightRange

Sight range is the maximum distance at which a unit acquires a target and begins moving toward it.

1. Place a defensive building (e.g. Cannon) at varying distances from the unknown unit's deploy point.
2. The furthest distance at which the unit pivots toward the building is its sightRange.
3. Compare against known units in the same scenario: Giant (7.5), Knight (5.5), Wallbreaker (7.0).
4. For building-targeting units, test with Crown Towers at different lane positions.

### loadTime

The point within the attack animation when damage is actually dealt.

1. Screen-record the unknown unit attacking at high frame rate (60fps minimum).
2. Identify frame 0: the start of the attack animation windup.
3. Identify the damage frame: when the damage number appears on the target.
4. Calculate: `loadTime = damageFrame / fps`
5. Verify: loadTime should be less than attackCooldown (e.g. Goblin: loadTime = 0.7s, attackCooldown = 1.1s).

## Reference table of known values

| Unit         | mass | collisionRadius | sightRange | loadTime |
|--------------|------|-----------------|------------|----------|
| Skeleton     | 1.0  | 0.5             | 5.5        | 0.6      |
| Goblin       | 2.0  | 0.5             | 5.5        | 0.7      |
| Knight       | 4.0  | 0.5             | 5.5        | 0.6      |
| BattleRam    | 6.0  | 0.75            | 5.5        | 0.35     |
| Wallbreaker  | 4.0  | 0.4             | 7.0        | 1.0      |
| Giant        | 18.0 | 0.75            | 7.5        | 1.0      |

## Tips

- Use friendly battles or training camp for controlled testing -- troop levels are fixed at tournament standard.
- iOS screen recording at 60fps is sufficient for most timing measurements. For sub-frame precision, use an external capture device at 120fps+.
- Knockback tests work best on flat ground away from the river to avoid terrain interference.
- Multiple trials per measurement reduce error. Aim for 3+ observations per field.