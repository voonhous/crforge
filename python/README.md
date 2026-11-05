# crforge-env

Reinforcement learning environments on the crforge battle core, in Python: a vector of Ladder battles stepped in the JVM by the `env` module and read from Python as arrays that view the same memory, a parallel multi-agent environment for both seats of a battle, and a single-agent environment for one seat against a given opponent.

The design, the observation schema, the wrappers, faults and the benchmark are in [docs/environment.md](../docs/environment.md) (section "Python"). In short, from the repository root:

```bash
# once: the env module's jars, and the game tables of a data version (docs/game-tables.md)
./gradlew :env:installDist :tables:dataVersionTables -Pcrforge.assetSource=file://$DD/cdn/
cd python && uv sync --extra dev

# the tests: --install is required; without --tables the Ladder tests are skipped, and the run says so
uv run pytest --install=../env/build/install/env --tables=$HOME/.crforge/tables/<data version>
uv run ruff check && uv run ruff format --check

# the Python-side benchmark, both engines
uv run crforge-env-bench --install ../env/build/install/env --tables ~/.crforge/tables/<data version>
```

```python
from crforge_env import (
    ClashGymEnv,
    ClashParallelEnv,
    ScriptedBot,
    SideSetup,
    VectorBattle,
    evaluate,
)

deck = ["Knight", "Archer", "Musketeer", "Giant", "MiniPekka", "Fireball", "Arrows", "Zap"]
install = "../env/build/install/env"  # ./gradlew :env:installDist
tables = "<tables folder of a data version>"

with VectorBattle(256, 8, install=install, tables=tables) as vector:
    vector.write_metadata("run/metadata.json")  # keep it with the run
    vector.reset(list(range(256)), deck, deck)
    while training:
        vector.actions[:] = policy(vector.observations, vector.masks)
        vector.step()  # rewards, dones, infos are in place

env = ClashGymEnv(deck, deck, install=install, tables=tables)  # one seat, a random opponent
env = ClashGymEnv(deck, deck, install=install, tables=tables, opponent=ScriptedBot())
result = evaluate(policy, ScriptedBot(), deck=deck, episodes=200, install=install, tables=tables)
both = ClashParallelEnv(deck, deck, install=install, tables=tables)  # both seats

# a side's tower troop and levels, as a player's account gives them; a field left None takes
# the configuration's (princess towers, LadderConfig.tower_level, LadderConfig.level)
cannoneer = SideSetup(
    towers="King_CannonTowers",
    king_level=15,
    tower_level=15,
    card_levels=[15, 15, 15, 15, 15, 14, 15, 15],
)
vector.reset(seeds, deck, deck, setup1=cannoneer)  # or one SideSetup per battle
env = ClashGymEnv(deck, deck, install=install, tables=tables, opponent_setup=cannoneer)
```

Neither folder has a default and no environment variable is read: every entry point takes `install` (the env module's installed distribution) and, for Ladder battles, `tables`. A faulted episode ends as truncated with no reward, the fault named in the info; it is never a result.
