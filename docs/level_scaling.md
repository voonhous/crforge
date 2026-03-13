# Level Scaling

## Card Stats

The base stats in our JSON data files are **level 1 values**.

All card rarities share the same multiplier sequence (`floor(prev × 1.10)`), but each rarity's level 1 starts at a
different point.

| Level | Common | Rare | Epic | Legendary |
|-------|--------|------|------|-----------|
| 1     | 1.00   | -    | -    | -         |
| 2     | 1.10   | -    | -    | -         |
| 3     | 1.21   | 1.00 | -    | -         |
| 4     | 1.33   | 1.10 | -    | -         |
| 5     | 1.46   | 1.21 | -    | -         |
| 6     | 1.60   | 1.33 | 1.00 | -         |
| 7     | 1.76   | 1.46 | 1.10 | -         |
| 8     | 1.93   | 1.60 | 1.21 | -         |
| 9     | 2.12   | 1.76 | 1.33 | 1.00      |
| 10    | 2.33   | 1.93 | 1.46 | 1.10      |
| 11    | 2.56   | 2.12 | 1.60 | 1.21      |
| 12    | 2.81   | 2.33 | 1.76 | 1.33      |
| 13    | 3.09   | 2.56 | 1.93 | 1.46      |
| 14    | 3.39   | 2.81 | 2.12 | 1.60      |
| 15    | 3.72   | 3.09 | 2.33 | 1.76      |
| 16    | 4.09   | 3.39 | 2.56 | 1.93      |

Formula: `stat_at_level = floor(base_stat × multiplier)`

Source: community-decoded game data

## Princess Tower

Tower stats use level 1 as the base, but follow a **different scaling formula** from cards:

- **Levels 1–9:** `floor(prev × 1.08)` (8% per level)
- **Levels 10–16:** `floor(prev × 1.10)` (10% per level)

| Level | Multiplier | HP   | Damage | DPS |
|-------|------------|------|--------|-----|
| 1     | 1.00       | 1400 | 50     | 62  |
| 2     | 1.08       | 1512 | 54     | 67  |
| 3     | 1.16       | 1624 | 58     | 72  |
| 4     | 1.25       | 1750 | 62     | 77  |
| 5     | 1.35       | 1890 | 67     | 83  |
| 6     | 1.45       | 2030 | 72     | 90  |
| 7     | 1.56       | 2184 | 78     | 97  |
| 8     | 1.68       | 2352 | 84     | 105 |
| 9     | 1.81       | 2534 | 90     | 112 |
| 10    | 1.99       | 2786 | 99     | 123 |
| 11    | 2.18       | 3052 | 109    | 136 |
| 12    | 2.39       | 3346 | 119    | 148 |
| 13    | 2.62       | 3668 | 131    | 163 |
| 14    | 2.88       | 4032 | 144    | 180 |
| 15    | 3.16       | 4424 | 158    | 197 |
| 16    | 3.47       | 4858 | 173    | 216 |

Formula: `stat_at_level = floor(base_stat × multiplier)`

## King Tower

King Tower HP and damage follow **different** growth rates early on. Damage uses the same sequence as Princess Tower,
but HP uses 7% growth (vs 8% for Princess Tower) in levels 1–9.

- HP scaling:
    - **Levels 1–9:** `floor(prev × 1.07)`
    - **Levels 10–16:** `floor(prev × 1.10)`
- Damage scaling:
    - **Levels 1–9:** `floor(prev × 1.08)`
    - **Levels 10–16:** `floor(prev × 1.10)`

| Level | HP multiplier | HP   | Damage multiplier | Damage | DPS |
|-------|---------------|------|-------------------|--------|-----|
| 1     | 1.00          | 2400 | 1.00              | 50     | 50  |
| 2     | 1.07          | 2568 | 1.08              | 54     | 54  |
| 3     | 1.14          | 2736 | 1.16              | 58     | 58  |
| 4     | 1.21          | 2904 | 1.25              | 62     | 62  |
| 5     | 1.29          | 3096 | 1.35              | 67     | 67  |
| 6     | 1.38          | 3312 | 1.45              | 72     | 72  |
| 7     | 1.47          | 3528 | 1.56              | 78     | 78  |
| 8     | 1.57          | 3768 | 1.68              | 84     | 84  |
| 9     | 1.67          | 4008 | 1.81              | 90     | 90  |
| 10    | 1.83          | 4392 | 1.99              | 99     | 99  |
| 11    | 2.01          | 4824 | 2.18              | 109    | 109 |
| 12    | 2.21          | 5304 | 2.39              | 119    | 119 |
| 13    | 2.43          | 5832 | 2.62              | 131    | 131 |
| 14    | 2.67          | 6408 | 2.88              | 144    | 144 |
| 15    | 2.93          | 7032 | 3.16              | 158    | 158 |
| 16    | 3.22          | 7728 | 3.47              | 173    | 173 |

Formula: `stat_at_level = floor(base_stat × multiplier)`