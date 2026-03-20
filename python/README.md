# CRForge Gymnasium Environment

Python RL training framework for the CRForge Clash Royale simulator.

```
Python (Gymnasium)  --ZMQ-->  Java (CRForge Simulation)
   env.step()       JSON       GameEngine.tick()
   env.reset()      PAIR       30 FPS deterministic
```

## Prerequisites

- Python 3.10+
- Java 17 (for the bridge server)
- The crforge project built: `./gradlew build`

## Installation

```bash
# Basic install (env + bridge client)
pip install -e python/

# With training dependencies (SB3 + TensorBoard)
pip install -e "python/[train]"
```

## Quick Start

### 1. Start the Java bridge server

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
./gradlew :gym-bridge:run
```

The server listens on `tcp://localhost:9876` by default.

### 2. Run random episodes (smoke test)

```bash
python python/examples/run_episodes.py
```

This runs 3 episodes with random actions and prints per-episode stats.

### 3. Train a PPO agent

```bash
python python/examples/train_ppo.py --timesteps 50000
```

Monitor training:

```bash
tensorboard --logdir logs/ppo_crforge
```

### 4. Evaluate a trained model

```bash
python python/examples/evaluate.py --model models/ppo_crforge --episodes 50
```

### 5. Watch a trained model play (AI Visualizer)

The desktop visualizer can run in AI mode, where Python controls the game via the same ZMQ protocol
as the headless bridge. This lets you watch the trained model deploy cards with full rendering.

```bash
# Terminal 1: Start the desktop visualizer in AI mode
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
./gradlew :desktop:run --args="--ai-port 9876"

# Terminal 2: Run the trained model (same command as headless evaluation)
python python/examples/evaluate.py --model models/ppo_crforge
```

The visualizer renders each step in real-time: when the model sends a `step` message, the engine
ticks are spread across render frames so you can see entities move, projectiles fly, and cards
deploy visually.

**Controls during AI playback:**

| Key   | Action                         |
|-------|--------------------------------|
| SPACE | Pause/resume (Python blocks)   |
| +/-   | Speed up/slow down (0.25x-8x)  |
| P     | Toggle path visualization      |
| O     | Toggle attack range circles    |
| D     | Toggle floating damage numbers |
| A     | Toggle AOE damage indicators   |
| H     | Toggle HP numbers              |

Any script that connects to the bridge server works -- `run_episodes.py`, `evaluate.py`, or your own
custom loop. The Python side requires no changes; it cannot tell whether the server is headless or
rendering.

## Environment Details

### Action Space

`MultiDiscrete([2, 4, 18, 32])`

| Index | Meaning     | Values                |
|-------|-------------|-----------------------|
| 0     | action_type | 0=no-op, 1=play card  |
| 1     | hand_index  | 0-3 (which card slot) |
| 2     | tile_x      | 0-17 (arena column)   |
| 3     | tile_y      | 0-31 (arena row)      |

### Observation Space

All float32 for SB3 compatibility. Spatial coordinates normalized to [0, 1].

| Key            | Shape   | Description                          |
|----------------|---------|--------------------------------------|
| frame          | (1,)    | Current simulation frame             |
| game_time      | (1,)    | Game time in seconds (0-600)         |
| is_overtime    | (1,)    | 1.0 if overtime                      |
| elixir         | (2,)    | [blue, red] elixir (0-10)            |
| crowns         | (2,)    | [blue, red] crown count              |
| hand_costs     | (4,)    | Card costs / 10 (normalized)         |
| hand_types     | (4,)    | 0=troop, 1=spell, 2=building         |
| next_card_cost | (1,)    | Next card cost / 10                  |
| next_card_type | (1,)    | Next card type                       |
| towers         | (6, 4)  | [hp_frac, x_norm, y_norm, alive]     |
| entities       | (64, 7) | [team, type, move, x, y, hp, shield] |
| num_entities   | (1,)    | Active entity count                  |

### Reward Structure

| Source              | Magnitude     | Purpose                           |
|---------------------|---------------|-----------------------------------|
| Tower damage dealt  | +0.005/HP     | Incentivize pushing               |
| Tower damage taken  | -0.008/HP     | Incentivize defense (1.6x weight) |
| Unit kill           | +0.05/kill    | Reward killing enemy units        |
| Unit lost           | -0.07/death   | Penalize losing own units (1.4x)  |
| Unit damage dealt   | +0.001/HP     | Reward damaging enemy units       |
| Unit damage taken   | -0.0015/HP    | Penalize taking unit damage (1.5x)|
| Crown earned        | +1.0          | Major milestone                   |
| Win                 | +5.0          | Terminal reward                   |
| Loss                | -5.0          | Terminal penalty                  |
| Elixir waste        | -0.005/step   | Penalize capping at 10            |
| Time penalty        | -0.0001/step  | Discourage passive play           |
| Invalid action      | -0.01/step    | Penalize unaffordable plays       |

### Configuration

```python
env = CRForgeEnv(
    endpoint="tcp://localhost:9876",  # Bridge server address
    blue_deck=["knight", "archer", ...],  # 8 card IDs
    red_deck=["knight", "archer", ...],   # 8 card IDs
    level=11,                        # Card/tower level (1-15)
    ticks_per_step=6,                # Sim ticks per step (default: 6 = ~5 decisions/sec)
    opponent="random",               # "random", "noop", or callable
    invalid_action_penalty=-0.01,    # Penalty for failed actions
)
```

### Deterministic Seeding

Pass `seed` to `env.reset()` for reproducible episodes:

```python
obs, info = env.reset(seed=42)  # Same seed -> same deck shuffle
```

### FlattenedObsWrapper

For SB3's MlpPolicy, wrap the env to flatten Dict obs into a single vector:

```python
from crforge_gym.wrappers import FlattenedObsWrapper
env = FlattenedObsWrapper(CRForgeEnv(...))
```

## Integration Tests

Require the Java server running:

```bash
# Start server in one terminal
./gradlew :gym-bridge:run

# Run tests in another
CRFORGE_INTEGRATION=1 pytest python/tests/ -v
```

## Architecture

- **Java bridge** (`gym-bridge/`): ZMQ PAIR server, wraps GameEngine in a step/reset API
- **Python bridge** (`crforge_gym/bridge.py`): ZMQ PAIR client, JSON protocol
- **Gymnasium env** (`crforge_gym/env.py`): Wraps bridge client in standard Gym interface
- **Wrappers** (`crforge_gym/wrappers.py`): FlattenedObsWrapper for SB3 compatibility
