# The Trainer

A small PPO trainer on the environment's vector of battles: the `crforge-rl` package under `python/crforge_rl`, a uv project of its own beside `crforge-env` ([The Environment](environment.md#python)), which it uses by path. It is the first milestone of the reinforcement learning work: small, easy to read and to show, run on one Mac.

**TL;DR:** `crforge-rl-train` plays a mirror deck at level 11 on many Ladder battles at once, the policy on one seat of each against a random opponent (or on both seats against itself), updates it by PPO, evaluates it every few updates against the random opponent and the scripted bot, and writes a win-rate curve, checkpoints that carry their schema and vocabulary, and a count of the faults it met by kind and signature.

---

## Running it

```bash
# once: the env module's jars and the tables of a data version (docs/environment.md, Install)
./gradlew :env:installDist :tables:dataVersionTables -Pcrforge.assetSource=file://$DD/cdn/
cd python/crforge_rl && uv sync --extra torch --extra plot --extra dev

# train for 30 minutes (runs/poc: config, metadata, log, curve.csv and curve.png, faults, checkpoints)
uv run crforge-rl-train --install ../../env/build/install/env --tables ~/.crforge/tables/<data version> --run-dir runs/poc --minutes 30 --device cpu

# evaluate a checkpoint against opponents
uv run crforge-rl-eval --install ../../env/build/install/env --tables ~/.crforge/tables/<data version> --checkpoint runs/poc/latest.pt --opponents random,scripted --games 128

# draw a curve again
uv run crforge-rl-plot runs/poc/curve.csv
```

`--install` and `--tables` are the two folders every entry point of `crforge-env` takes, with no default. `crforge-rl-train --help` lists the rest: the battles and their threads, the rollout length, the updates or the time box (`--updates`, `--minutes`), the PPO coefficients, the shaping, the evaluation's interval, games and opponents, the deck (eight card row names, `Knight,Archer,Musketeer,Giant,MiniPekka,Fireball,Arrows,Zap` by default), the decision interval and the level (the environment's defaults: 5 ticks, level 11).

torch is the `torch` extra and matplotlib the `plot` extra, so `crforge-env` and its CI job stay without either. The device is `--device auto` by default: CUDA when present, else MPS on Apple silicon, else the CPU; `cpu`, `mps` and `cuda` pick one. For this network the CPU was the faster on the Mac (below).

Runs stay local: `runs/` is ignored by git, and a checkpoint is never committed.

## How it trains

One file, `train.py`, in the shape of CleanRL's PPO: a rollout of `--steps` decisions of every battle, the advantages by GAE, then `--epochs` passes of clipped policy and value updates over `--minibatches` minibatches. The policy acts for all its seats of the vector in one forward pass a decision, and the vector steps every battle in one call.

- **Seats** (`--mode`): `random` (the default) puts the learner on one seat of each battle, seat 0 in even battles and seat 1 in odd ones, and the training opponent (`--opponent`, `random` by default) on the other; `self` puts the learner on both seats of every battle. The seat frames make the two sides the same game for a policy, so one policy plays either.
- **Rewards**: the match's result from the environment (+1, -1, 0 for a draw), plus `--shaping` times the change of the tower balance (the share of the enemy's tower hit points lost less the share of its own, a potential, so the two seats' shaping sums to 0), less `--leak` (0 by default, off) for each decision the learner waits at full elixir with a play open. The curve reads the result only.
- **Episode ends**: the vector resets a finished battle on the step after its end, ignoring that step's actions; the sample of that step is left out of the loss. A fault or the tick limit ends an episode as truncated with no reward: the advantage bootstraps there from the value of the step's own observation (the observation after a fault is cleared), never as a loss or a win.
- **Masking**: every sampled action is checked against its seat's mask, and a closed one halts the run: the head's masking would be broken.

### The policy

`policy.py`. The encoder reads the header's amounts scaled to about 0 to 1, the tower hit points as shares, and the card ids (the hand, the next card, the deck, the enemy's cards seen, the waiting commands' cards) through one embedding of the vocabulary; the entity rows go through a shared network and are pooled by mean and max over the rows written (a deep set), so their order does not matter. The rows are cut after the most any observation of the batch wrote before they reach the network.

The action head is factored the way the action is built: a first choice of the wait, a hand slot (open when the mask opens any of its tiles), or an action past the plays (none in schema version 1; the champion abilities of a later version would land there), then for a slot one of its tiles, conditioned on the slot's card. The probability of an action is the product, and every distribution over the open actions is such a product, so nothing is lost against one categorical over all the actions. What it gains: the wait starts with about a fifth of the probability rather than one in the action count, and one tile head serves the four slots. Every size comes from the vector's metadata.

### Evaluation and opponents

`evaluate.py`. An evaluation plays each battle of a vector of its own once, the policy on seat 0 in even battles and seat 1 in odd ones, the same seeds every time, and counts wins, losses, draws, cut episodes and faults from the policy's side, with the win rate as wins over all games (as `crforge_env.evaluate` counts it) and the policy's plays a game (a policy that never plays shows there). Unlike `crforge_env.evaluate`, which asks a policy once a battle, it asks each player once a decision for all its seats.

Players and opponents are one interface, `act(observations, masks, rng)` on a batch of seats, and are named by a spec (`--opponent`, `--eval-opponents`, `--opponents`):

| Spec | Player |
|---|---|
| `random` | a random open action three decisions in ten, else the wait (`crforge_env.random_opponent` on a batch) |
| `wait` | never plays |
| `scripted` | `crforge_env.ScriptedBot` with its default rules, bound to the vector |
| `checkpoint:<path>` | a frozen policy from a checkpoint, checked against the vector |
| `<module>:<name>` | a function of one seat with `crforge_env`'s opponent shape `(observation, mask, rng) -> action`, a batch player, or a class of either; one with `bind(vector)` is bound to the vector it plays in |

### Checkpoints

`latest.pt` and `update-<n>.pt` hold the weights, the network's sizes, the vector's metadata export (the schema's version, sizes and field names, the configuration, and for Ladder battles the vocabulary by name with the data version, content sha and hash), the engine, the deck and the step counts. Loading one against a vector refuses, naming the difference: another checkpoint format, a schema the vector reads differently (`VectorBattle.check_metadata`), a vocabulary whose ids moved, another vocabulary size, engine or configuration (decision interval, levels, tick limit).

### Faults

A fault is the battle core's refusal of a behaviour it does not model, its bug, or the environment's own; the environment ends that battle's episode alone and the trainer carries on. The run counts them by kind and by signature (the kind, the exception's class, the message with its numbers as `#`, the throwing frame), prints the first of each signature with its tick, writes `faults.json` after every update, and leaves the faulted episode out of the curve and the training games. Self-play that meets a fault has found something to file.

## The run folder

| File | What it holds |
|---|---|
| `config.json` | the run's settings and the device |
| `metadata.json` | the vector's metadata export |
| `log.jsonl` | one line an update: decisions, steps a second, the training games' results, the play rate, the losses, the entropy, the approximate KL, the faults |
| `curve.csv` | one row an evaluation and opponent: update, decisions, seconds, steps a second, games, wins, losses, draws, cut, faults, win rate, plays a game |
| `curve.png` | the curve, one line an opponent (with the plot extra) |
| `faults.json` | the faults by kind, then by signature with the first message, update and tick |
| `latest.pt`, `update-<n>.pt` | the checkpoints, written at every evaluation |

## First results

Measured on 2026-10-16 on one Mac (Apple M4 Pro, 14 cores, JDK 17.0.14, Python 3.12.7, torch 2.14.1), data version 16.402.19, with other work running: the 1-minute load average was 4.9 at the start and 7.5 to 12 during the run. One run of 30 minutes with the defaults (the mirror deck above at level 11, `random` mode against the random opponent, 128 battles on 4 threads, rollouts of 128 decisions, shaping 0.5, no leak term), `--device cpu`:

```bash
uv run crforge-rl-train --install ../../env/build/install/env --tables <16.402.19 tables> --run-dir runs/poc-1 --minutes 30 --device cpu
```

| updates | learner decisions | minutes | vs random | vs scripted |
|---:|---:|---:|---:|---:|
| 0 | 0 | 0 | 36 of 64 (0.56) | 0 of 64 |
| 10 | 163,684 | 0.8 | 47 of 64 (0.73) | 0 of 64 |
| 60 | 981,579 | 5.5 | 59 of 64 (0.92) | 0 of 64 |
| 120 | 1,962,770 | 11.0 | 61 of 64 (0.95) | 1 of 64 |
| 200 | 3,270,862 | 17.9 | 62 of 64 (0.97) | 1 of 64 |
| 260 | 4,251,918 | 23.1 | 61 of 64 (0.95) | 0 of 64 |
| 341 | 5,576,328 | 30.1 | 62 of 64 (0.97) | 0 of 64 |

From update 60 on the win rate against the random opponent stayed between 0.86 and 0.98 over the 64 evaluation battles (mean 0.94 over the last ten evaluations). `crforge-rl-eval` on the last checkpoint over 128 battles: 115 wins and 13 losses against `random` (0.90), 128 of 128 against `wait`, 0 of 128 against `scripted`. The policy plays about 17 cards a game. No battle faulted.

Throughput: the rollouts ran at about 10,000 learner decisions a second (one seat of 128 battles), the whole run at about 3,100 including the updates and evaluations, 5.6 million decisions in 30 minutes. On the same machine a forward pass of 128 seats took 3.0 ms on the CPU and 4.8 ms on MPS, and a short run stepped 2,700 decisions a second on the CPU against 1,000 on MPS: at this size the transfers cost MPS more than the network gains, so `--device cpu` is the faster choice on the Mac until the network grows.

What it shows: the policy beats a random opponent nine times in ten within ten minutes, and does not beat the scripted bot at all. Against a random opponent waiting and defending near the own towers is enough; the scripted bot attacks with 9 elixir at a bridge, and a policy trained only against random play never had to answer that. The next steps, by expected effect: train against the scripted bot or a mix of opponents (`--opponent scripted`), self-play with frozen past checkpoints as opponents, an entity encoder that keeps positions (a raster for the tile head), and the leak term to stop elixir going to waste.

## Tests

```bash
cd python/crforge_rl
uv run pytest --install=../../env/build/install/env --tables=<tables folder>   # all tests
uv run ruff check && uv run ruff format --check
```

The tests without torch hold the seats, the shaping, the advantages at episode ends, the leak rule, the opponents and their specs, the evaluation, the fault log and the curve on synthetic environments. With torch they add: every sampled action open in its mask, the sampled log-probability equal to the evaluated one and the open actions' probabilities summing to 1, the cut of the entity rows changing nothing, a checkpoint round trip and its refusals, and tiny CPU trainings on synthetic environments (the run folder, finite losses, self-play, faults counted as truncations, the curve's picture). With the tables, a checkpoint whose vocabulary ids moved is refused and a short Ladder training evaluates against both default opponents. CI runs them in the `python` job, with the CPU build of torch.
