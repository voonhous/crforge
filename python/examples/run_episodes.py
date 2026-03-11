#!/usr/bin/env python3
"""
Run random episodes against the CRForge simulation.

This is the simplest smoke test -- it proves the full loop works
(Python -> ZMQ -> Java -> simulation -> observation -> reward).

Prerequisites:
  1. Start the Java bridge server: ./gradlew :gym-bridge:run
  2. Install the Python package: pip install -e python/

Usage:
  python python/examples/run_episodes.py
  python python/examples/run_episodes.py --episodes 5 --ticks-per-step 1
"""

import argparse
import sys
import time

import numpy as np


def main():
    parser = argparse.ArgumentParser(description="Run random CRForge episodes")
    parser.add_argument("--episodes", type=int, default=3, help="Number of episodes to run")
    parser.add_argument("--endpoint", default="tcp://localhost:9876", help="Bridge server endpoint")
    parser.add_argument("--ticks-per-step", type=int, default=6, help="Simulation ticks per step")
    parser.add_argument("--seed", type=int, default=None, help="Random seed for reproducibility")
    args = parser.parse_args()

    # Import here to give a clear error if not installed
    try:
        from crforge_gym import CRForgeEnv
    except ImportError:
        print("Error: crforge_gym not installed. Run: pip install -e python/")
        sys.exit(1)

    env = CRForgeEnv(
        endpoint=args.endpoint,
        ticks_per_step=args.ticks_per_step,
        opponent="random",
    )

    try:
        for ep in range(args.episodes):
            seed = args.seed + ep if args.seed is not None else None
            obs, info = env.reset(seed=seed)

            total_reward = 0.0
            steps = 0
            action_failures = 0
            start_time = time.time()

            terminated = False
            truncated = False

            while not (terminated or truncated):
                action = env.action_space.sample()
                obs, reward, terminated, truncated, info = env.step(action)
                total_reward += reward
                steps += 1
                if info.get("action_failed"):
                    action_failures += 1

            elapsed = time.time() - start_time
            game_time = float(obs["game_time"][0])
            blue_crowns = int(obs["crowns"][0])
            red_crowns = int(obs["crowns"][1])

            print(
                f"Episode {ep + 1}/{args.episodes}: "
                f"steps={steps}, "
                f"reward={total_reward:.3f}, "
                f"game_time={game_time:.1f}s, "
                f"crowns={blue_crowns}-{red_crowns}, "
                f"action_failures={action_failures}, "
                f"wall_time={elapsed:.1f}s"
            )

    finally:
        env.close()

    print("\nDone! The bridge is working correctly.")


if __name__ == "__main__":
    main()
