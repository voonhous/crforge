#!/usr/bin/env python3
"""
Evaluate a trained PPO model on CRForge.

Prerequisites:
  1. Start the Java bridge server: ./gradlew :gym-bridge:run
  2. Train a model first: python python/examples/train_ppo.py

Usage:
  python python/examples/evaluate.py --model models/ppo_crforge
  python python/examples/evaluate.py --model models/ppo_crforge --episodes 50
"""

import argparse
import sys


def main():
    parser = argparse.ArgumentParser(description="Evaluate a trained CRForge agent")
    parser.add_argument("--model", type=str, required=True,
                        help="Path to saved SB3 model (e.g. models/ppo_crforge)")
    parser.add_argument("--episodes", type=int, default=10,
                        help="Number of evaluation episodes")
    parser.add_argument("--endpoint", default="tcp://localhost:9876",
                        help="Bridge server endpoint")
    parser.add_argument("--ticks-per-step", type=int, default=15,
                        help="Simulation ticks per step (default: 15, matches training)")
    parser.add_argument("--seed", type=int, default=None,
                        help="Random seed for reproducibility")
    args = parser.parse_args()

    try:
        from sb3_contrib import MaskablePPO
    except ImportError:
        print("Error: sb3-contrib not installed.")
        print("Install with: pip install -e \"python/[train]\"")
        sys.exit(1)

    from crforge_gym import CRForgeEnv
    from crforge_gym.wrappers import ActionMaskedWrapper

    # Load model
    print(f"Loading model from {args.model}...")
    model = MaskablePPO.load(args.model)

    # Create environment with action masking
    # binary_obs=True produces flat observations directly (matches training)
    env = ActionMaskedWrapper(
        CRForgeEnv(
            endpoint=args.endpoint,
            ticks_per_step=args.ticks_per_step,
            opponent="random",
            binary_obs=True,
        )
    )

    print(f"\nRunning {args.episodes} evaluation episodes...\n")

    total_rewards = []
    total_steps = []
    total_crowns_won = []
    total_crowns_lost = []
    wins = 0
    losses = 0
    draws = 0

    for ep in range(args.episodes):
        seed = args.seed + ep if args.seed is not None else None
        obs, info = env.reset(seed=seed)

        episode_reward = 0.0
        steps = 0
        terminated = False
        truncated = False

        while not (terminated or truncated):
            action_masks = env.action_masks()
            action, _ = model.predict(obs, deterministic=True, action_masks=action_masks)
            obs, reward, terminated, truncated, info = env.step(action)
            episode_reward += reward
            steps += 1

        # Extract final state from the last observation
        inner = env._inner_env
        if inner._last_obs_flat is not None:
            # Binary mode: crowns at indices 5,6; game time at index 1
            flat = inner._last_obs_flat
            blue_crowns = int(flat[5])
            red_crowns = int(flat[6])
            game_time = float(flat[1])
        else:
            last_raw = inner._last_obs_raw or {}
            blue_crowns = last_raw.get("bluePlayer", {}).get("crowns", 0)
            red_crowns = last_raw.get("redPlayer", {}).get("crowns", 0)
            game_time = last_raw.get("gameTimeSeconds", 0)

        if blue_crowns > red_crowns:
            outcome = "WIN"
            wins += 1
        elif red_crowns > blue_crowns:
            outcome = "LOSS"
            losses += 1
        else:
            outcome = "DRAW"
            draws += 1

        total_rewards.append(episode_reward)
        total_steps.append(steps)
        total_crowns_won.append(blue_crowns)
        total_crowns_lost.append(red_crowns)
        print(
            f"  Episode {ep + 1:3d}: {outcome:4s} | "
            f"reward={episode_reward:7.3f} | "
            f"steps={steps:5d} | "
            f"crowns={blue_crowns}-{red_crowns} | "
            f"time={game_time:.0f}s"
        )

    env.close()

    # Summary
    import numpy as np
    rewards = np.array(total_rewards)
    steps_arr = np.array(total_steps)

    print(f"\n{'=' * 50}")
    print(f"Results over {args.episodes} episodes:")
    print(f"  Win rate:     {wins}/{args.episodes} ({100 * wins / args.episodes:.1f}%)")
    print(f"  Loss rate:    {losses}/{args.episodes} ({100 * losses / args.episodes:.1f}%)")
    print(f"  Draw rate:    {draws}/{args.episodes} ({100 * draws / args.episodes:.1f}%)")
    print(f"  Avg reward:   {rewards.mean():.3f} +/- {rewards.std():.3f}")
    print(f"  Avg steps:    {steps_arr.mean():.0f}")
    print(f"  Avg crowns:   {np.mean(total_crowns_won):.2f} won, {np.mean(total_crowns_lost):.2f} lost")
    print(f"{'=' * 50}")


if __name__ == "__main__":
    main()
