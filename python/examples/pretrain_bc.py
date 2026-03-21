#!/usr/bin/env python3
"""
Behavioral cloning pre-training for PPO on CRForge.

Records demonstrations from the rule-based opponent playing as blue,
then trains a MaskablePPO policy to imitate it via cross-entropy loss.
The resulting model can be fine-tuned with train_ppo.py --resume.

Usage:
  python python/examples/pretrain_bc.py --episodes 200 --epochs 10
  python python/examples/pretrain_bc.py --save-path models/ppo_crforge_bc
"""

import argparse
import math
import os
import sys
import time

import numpy as np
import torch


def find_nearest_zone(x: float, y: float, card_type: str) -> int:
    """Map expert (x, y) coordinates to the nearest placement zone index.

    Troops/buildings are restricted to own-half zones (0-6).
    Spells can use any zone (0-9).
    """
    from crforge_gym.env import PLACEMENT_ZONES, NUM_OWN_HALF_ZONES, NUM_ZONES

    max_zone = NUM_ZONES if card_type == "SPELL" else NUM_OWN_HALF_ZONES
    best_zone = 0
    best_dist = float("inf")
    for z in range(max_zone):
        zx, zy = PLACEMENT_ZONES[z]
        dist = (x - zx) ** 2 + (y - zy) ** 2
        if dist < best_dist:
            best_dist = dist
            best_zone = z
    return best_zone


def record_demonstrations(env, num_episodes: int, seed: int) -> tuple[np.ndarray, np.ndarray]:
    """Record expert demonstrations using the rule-based opponent as blue.

    Returns (observations, actions) arrays.
    """
    from crforge_gym.opponents import RuleBasedOpponent

    expert = RuleBasedOpponent(rng=np.random.default_rng(seed))

    all_obs = []
    all_actions = []
    wins = 0
    losses = 0
    draws = 0

    for ep in range(num_episodes):
        obs, _ = env.reset()
        done = False

        while not done:
            raw_obs = env.unwrapped._last_obs_raw
            expert_action = expert.act(raw_obs, player="blue")

            if expert_action is not None:
                hand_index = expert_action["handIndex"]
                x = expert_action["x"]
                y = expert_action["y"]

                # Determine card type from raw obs
                blue = raw_obs.get("bluePlayer", {})
                hand = blue.get("hand", [])
                card_type = "TROOP"
                if hand_index < len(hand):
                    card_type = hand[hand_index].get("type", "TROOP")

                zone = find_nearest_zone(x, y, card_type)
                action = np.array([1, hand_index, zone])
            else:
                action = np.array([0, 0, 0])

            all_obs.append(obs.copy())
            all_actions.append(action.copy())

            obs, reward, terminated, truncated, info = env.step(action)
            done = terminated or truncated

        outcome = info.get("game_outcome", "draw")
        if outcome == "win":
            wins += 1
        elif outcome == "loss":
            losses += 1
        else:
            draws += 1

        if (ep + 1) % 50 == 0 or ep == num_episodes - 1:
            total = ep + 1
            print(
                f"  Recorded {total}/{num_episodes} episodes | "
                f"win={wins/total:.0%} loss={losses/total:.0%} draw={draws/total:.0%} | "
                f"samples={len(all_obs)}"
            )

    print(
        f"\nRecording complete: {len(all_obs)} samples from {num_episodes} episodes "
        f"(win={wins/num_episodes:.0%} loss={losses/num_episodes:.0%} draw={draws/num_episodes:.0%})"
    )

    return np.array(all_obs, dtype=np.float32), np.array(all_actions, dtype=np.int64)


def train_bc(model, observations: np.ndarray, actions: np.ndarray,
             epochs: int, batch_size: int, lr: float):
    """Train the policy via behavioral cloning (cross-entropy on expert actions)."""
    device = model.policy.device
    n_samples = len(observations)

    optimizer = torch.optim.Adam(model.policy.parameters(), lr=lr)

    obs_tensor = torch.tensor(observations, dtype=torch.float32, device=device)
    act_tensor = torch.tensor(actions, dtype=torch.long, device=device)

    print(f"\nBC training: {n_samples} samples, {epochs} epochs, batch_size={batch_size}, lr={lr}")

    for epoch in range(epochs):
        # Shuffle
        indices = np.random.permutation(n_samples)
        epoch_loss = 0.0
        n_batches = 0

        for start in range(0, n_samples, batch_size):
            batch_idx = indices[start : start + batch_size]
            batch_obs = obs_tensor[batch_idx]
            batch_act = act_tensor[batch_idx]

            log_prob, _, _ = model.policy.evaluate_actions(batch_obs, batch_act)
            loss = -log_prob.mean()

            optimizer.zero_grad()
            loss.backward()
            optimizer.step()

            epoch_loss += loss.item()
            n_batches += 1

        avg_loss = epoch_loss / max(n_batches, 1)
        print(f"  Epoch {epoch + 1}/{epochs} | loss={avg_loss:.4f} | batches={n_batches}")


def main():
    parser = argparse.ArgumentParser(description="Behavioral cloning pre-training for CRForge PPO")
    parser.add_argument("--episodes", type=int, default=200,
                        help="Number of demo episodes to record (default: 200)")
    parser.add_argument("--epochs", type=int, default=10,
                        help="BC training epochs (default: 10)")
    parser.add_argument("--batch-size", type=int, default=256,
                        help="BC batch size (default: 256)")
    parser.add_argument("--lr", type=float, default=1e-3,
                        help="BC learning rate (default: 1e-3)")
    parser.add_argument("--save-path", type=str, default="models/ppo_crforge_bc",
                        help="Path to save the BC-pretrained model")
    parser.add_argument("--opponent", type=str, default="random",
                        choices=["noop", "random"],
                        help="Red opponent during recording (default: random)")
    parser.add_argument("--ticks-per-step", type=int, default=15,
                        help="Simulation ticks per step (default: 15)")
    parser.add_argument("--seed", type=int, default=42,
                        help="Random seed (default: 42)")
    parser.add_argument("--base-port", type=int, default=9876,
                        help="Port for the Java bridge server (default: 9876)")
    args = parser.parse_args()

    # Check dependencies
    try:
        from sb3_contrib import MaskablePPO
    except ImportError:
        print("Error: sb3-contrib not installed.")
        print("Install with: pip install -e \"python/[train]\"")
        sys.exit(1)

    from crforge_gym import CRForgeEnv
    from crforge_gym.wrappers import ActionMaskedWrapper, EpisodeStatsWrapper, FlattenedObsWrapper

    # Import launch_servers from train_ppo (same directory)
    script_dir = os.path.dirname(os.path.abspath(__file__))
    sys.path.insert(0, script_dir)
    from train_ppo import launch_servers

    # Launch 1 Java bridge server
    print("Launching Java bridge server...")
    launch_servers(args.base_port, 1)

    endpoint = f"tcp://localhost:{args.base_port}"

    # Create wrapped environment
    # BC recording needs JSON mode for expert access to raw observation dicts
    env = CRForgeEnv(
        endpoint=endpoint,
        ticks_per_step=args.ticks_per_step,
        opponent=args.opponent,
        binary_obs=False,
    )
    env = EpisodeStatsWrapper(env)
    env = FlattenedObsWrapper(env)
    env = ActionMaskedWrapper(env)

    # -- Phase 1: Record demonstrations --

    print(f"\nRecording {args.episodes} expert demonstrations (opponent={args.opponent})...")
    start_time = time.time()
    observations, actions = record_demonstrations(env, args.episodes, args.seed)
    record_time = time.time() - start_time
    print(f"Recording took {record_time:.1f}s")

    # -- Phase 2: Create model and train BC --

    model = MaskablePPO(
        "MlpPolicy",
        env,
        policy_kwargs={"net_arch": [512, 256]},
        learning_rate=3e-4,
        seed=args.seed,
        verbose=1,
    )

    env.close()

    start_time = time.time()
    train_bc(model, observations, actions, args.epochs, args.batch_size, args.lr)
    bc_time = time.time() - start_time
    print(f"BC training took {bc_time:.1f}s")

    # -- Save --

    os.makedirs(os.path.dirname(args.save_path) or ".", exist_ok=True)
    model.save(args.save_path)
    print(f"\nModel saved to {args.save_path}")
    print("Fine-tune with PPO:")
    print(f"  python python/examples/train_ppo.py --resume {args.save_path} --opponent noop --timesteps 2000000")


if __name__ == "__main__":
    main()
