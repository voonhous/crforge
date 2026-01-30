# A DEEP DIVE INTO CLASH ROYALE ATTACK MECHANICS

"First Hit" isn't just a random number. It is the result of a hidden formula involving Load Time
and Hit Time.

Nomenclature:
1. Hit Speed (Clash Royale) = Hit Time (CR Forge) 
2. Load Time

---

## THE FORMULA

The speed of a troop's first attack is calculated using a hidden stat called **Load Time**.

```math
+-------------------------------------------------+
|   FIRST HIT  =  HIT TIME  -  LOAD TIME          |
+-------------------------------------------------+
```

- **Hit Time**: The standard "Attack Speed" (e.g., 1.2s). In Clash Royale, this is defined as
  `Hit Speed`.
- **Load Time**: The time a troop "charges" while moving or deploying.

## VISUALIZING THE MECHANICS

### 1. The Deploy Phase (Pre-loading)

When a troop is deployed, it "loads" its attack during the spawn timer. This is why troops dropped
on enemies attack instantly.

```
        Start                                  Active
          |                                      |
          v                                      v
TIMELINE: [==================================] | [------------------]
          :                                  : | :                  :
          :        [  DEPLOY TIME  ]         : | :                  :
          :        (Spawn Animation)         : | :                  :
          :                                  : | :                  :
 HIDDEN   :        [   LOAD TIME   ]         : | :  FIRST HIT TIME  :
 STATS:   :        (Charging Attack)         : | :  (Actual Delay)  :
          :                                  : | :                  :
          :                                  : | :                  :
TOTAL:    :                                  : | [==== HIT TIME ====]
```

#### EXAMPLE: THE KNIGHT (1.2s Hit Speed)

```    
Step 1: The "Deploy" (Hidden Loading)
While the troop is spawning, the game fills the bar for you.

[//////////////////////......] 
^                      ^
0%                     58% Charged (0.7s Load Time)

         |
         V

Step 2: The "Spawn" (Unit Appears)
The Knight enters the arena with the bar already green!

[//////////////////////......] 
                      ^
                      You only have this much left to wait!

         |
         V

Step 3: The "First Hit" (The Attack)
The Knight only waits for the empty space to fill.

[//////////////////////::::::] * SWING! *
                      ^    ^
                      |    |
           Wait 0.5s -+    +- Total 1.2s
```

#### Comparison: Standard vs. Secret

```
                  ( 0.0s )             ( 1.2s )
                  Start                Attack
                    |                    |
EXPECTATION:        [--------------------]
(Standard Hit Time) :      WAITING...    :
                    [--------------------]

                    VS

                  ( 0.0s )      ( 0.5s )
                  Spawn           Attack
                    |               |
REALITY:            [//////]        [-------]
(Secret Stats)      : LOAD :        : WAIT  :
                    [//////]        [-------]
                    ^               ^
             Done *during* spawn    Actual delay you see
             (0.7s deducted)        (0.5s remaining)
```

### The Movement Phase (Walking & Loading)

Troops charge their attack while walking toward a target. By the time they arrive, the "Load Time"
is already done.

```
     Walking...                    Target Reached!      Attack!
        |                                 |               |  
        v                                 v               v 
        [ . . . . . . . . . . . . . . . . ] [-------------] 
        :                                 : :             :
        :          LOAD TIME              : :  FIRST HIT  :
        :      (Deducted while moving)    : :   (Short)   :
        :                                 : :             :
        [=================================================]
                          TOTAL HIT TIME
```

### 4. Subsequent Hits (No Loading)

Load Time only helps the first hit. Once the troop is standing still, every attack takes the full
duration.

```
       [   FIRST ATTACK   ]       [   SECOND ATTACK  ]       [   THIRD ATTACK   ]
       :                  :       :                  :       :                  :
[ LOAD ] + [   FIRST HIT  ]       [   FULL HIT TIME  ]       [   FULL HIT TIME  ]
(Done)   :    (Fast)      :       :     (Normal)     :       :     (Normal)     :
```

## REAL EXAMPLES

### The Knight

- **Hit Time**: 1.2s
- **Load Time**: 0.7s
- **First Hit**: 1.2s - 0.7s = 0.5s

If you drop a Knight on top of a Goblin, he waits only `0.5s` to swing, not `1.2s`, because he
loaded during his deploy time.

### The Balloon

- **Hit Time**: 3.0s
- **Load Time**: 2.8s
- **First Hit**: 3.0s - 2.8s = 0.2s

When a Balloon reaches the tower, it drops the bomb almost instantly (`0.2s` delay) because it spent
`2.8s` loading while floating there.

## EXCEPTIONS & EDGE CASES

### The Reset Mechanic (Knockback)

If a troop is pushed or stunned (e.g., Fireball vs Balloon), the **Load Time is lost**.

- **Scenario**: Balloon is about to hit (`0.2s` left).
- **Action**: Fireball pushes it back.
- **Result**: Balloon must wait the full `3.0s` again.

### The Sparky Exception

Sparky is the only troop that **does NOT** pre-load during deploy.

- **Hit Time**: 4.0s
- **Load Time**: 3.0s
- **Deploy Logic**: Unlike the Knight, Sparky spawns with 0 load. She must sit there and charge for
  the full duration before firing.