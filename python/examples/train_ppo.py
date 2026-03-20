#!/usr/bin/env python3
"""
Train a PPO agent on CRForge using Stable Baselines 3.

Prerequisites:
  1. Start the Java bridge server: ./gradlew :gym-bridge:run
  2. Install dependencies: pip install -e "python/[train]"

Usage:
  python python/examples/train_ppo.py
  python python/examples/train_ppo.py --timesteps 100000 --save-path models/ppo_crforge
  python python/examples/train_ppo.py --resume models/ppo_crforge --timesteps 50000
  python python/examples/train_ppo.py --opponent self_play --timesteps 100000
"""

import argparse
import os
import sys
import time


def check_server(endpoint: str) -> bool:
    """Check if the Java bridge server is reachable."""
    import zmq

    ctx = zmq.Context()
    sock = ctx.socket(zmq.PAIR)
    sock.setsockopt(zmq.RCVTIMEO, 3000)
    sock.setsockopt(zmq.SNDTIMEO, 3000)

    try:
        sock.connect(endpoint)
        # Send a ping-like init to see if server responds
        import json
        sock.send_string(json.dumps({
            "type": "init",
            "data": {
                "blueDeck": ["knight", "archer", "fireball", "arrows",
                             "giant", "musketeer", "minions", "valkyrie"],
                "redDeck": ["knight", "archer", "fireball", "arrows",
                            "giant", "musketeer", "minions", "valkyrie"],
                "level": 11,
                "ticksPerStep": 6,
            }
        }))
        response = sock.recv_string()
        return "init_ok" in response
    except zmq.error.Again:
        return False
    finally:
        sock.close()
        ctx.term()


def main():
    parser = argparse.ArgumentParser(description="Train PPO on CRForge")
    parser.add_argument("--timesteps", type=int, default=50000,
                        help="Total training timesteps (default: 50000)")
    parser.add_argument("--save-path", type=str, default="models/ppo_crforge",
                        help="Path to save the trained model")
    parser.add_argument("--endpoint", default="tcp://localhost:9876",
                        help="Bridge server endpoint")
    parser.add_argument("--ticks-per-step", type=int, default=6,
                        help="Simulation ticks per step")
    parser.add_argument("--seed", type=int, default=42,
                        help="Random seed")
    parser.add_argument("--resume", type=str, default=None,
                        help="Path to a saved model .zip to resume training from")
    parser.add_argument("--eval-episodes", type=int, default=10,
                        help="Number of evaluation episodes after training")
    parser.add_argument("--log-dir", type=str, default="logs/ppo_crforge",
                        help="TensorBoard log directory")
    parser.add_argument("--opponent", type=str, default="rule_based",
                        choices=["random", "rule_based", "self_play"],
                        help="Opponent type (default: rule_based)")
    parser.add_argument("--self-play-interval", type=int, default=10000,
                        help="Timesteps between opponent model updates in self-play mode (default: 10000)")
    args = parser.parse_args()

    # Check dependencies
    try:
        from sb3_contrib import MaskablePPO
        from stable_baselines3.common.callbacks import BaseCallback
        from stable_baselines3.common.evaluation import evaluate_policy
    except ImportError:
        print("Error: sb3-contrib or stable-baselines3 not installed.")
        print("Install with: pip install -e \"python/[train]\"")
        sys.exit(1)

    from crforge_gym import CRForgeEnv
    from crforge_gym.opponents import SelfPlayOpponent
    from crforge_gym.wrappers import ActionMaskedWrapper, FlattenedObsWrapper

    # Check server
    print(f"Checking Java bridge server at {args.endpoint}...")
    if not check_server(args.endpoint):
        print("Error: Cannot connect to the Java bridge server.")
        print("Start it with: ./gradlew :gym-bridge:run")
        sys.exit(1)
    print("Server is running.")

    # -- Self-play callback --

    class SelfPlayCallback(BaseCallback):
        """Periodically snapshot the training model into the SelfPlayOpponent.

        Every `update_interval` timesteps, save the current model to a
        checkpoint file and reload it into the opponent. The first iteration
        starts with the initial (random) policy.
        """

        def __init__(self, opponent: SelfPlayOpponent, update_interval: int,
                     checkpoint_dir: str = "models", verbose: int = 0):
            super().__init__(verbose)
            self.opponent = opponent
            self.update_interval = update_interval
            self.checkpoint_dir = checkpoint_dir
            self._last_update_step = 0

        def _on_training_start(self) -> None:
            os.makedirs(self.checkpoint_dir, exist_ok=True)
            # Snapshot the initial (random) policy as the first opponent
            self._snapshot_model(tag="init")

        def _on_step(self) -> bool:
            elapsed = self.num_timesteps - self._last_update_step
            if elapsed >= self.update_interval:
                self._snapshot_model(tag=f"step_{self.num_timesteps}")
                self._last_update_step = self.num_timesteps
            return True

        def _snapshot_model(self, tag: str) -> None:
            path = os.path.join(self.checkpoint_dir, f"self_play_{tag}")
            self.model.save(path)
            loaded = MaskablePPO.load(path)
            self.opponent.model = loaded
            if self.verbose > 0:
                print(f"[SelfPlayCallback] Updated opponent model at step {self.num_timesteps} ({tag})")

    # Determine opponent for env creation
    if args.opponent == "self_play":
        # Create a placeholder SelfPlayOpponent; model will be set after model creation
        self_play_opponent = SelfPlayOpponent(model=None)
        env_opponent = self_play_opponent
    else:
        self_play_opponent = None
        env_opponent = args.opponent

    # Create environment with flattened observations and action masking
    env = ActionMaskedWrapper(
        FlattenedObsWrapper(
            CRForgeEnv(
                endpoint=args.endpoint,
                ticks_per_step=args.ticks_per_step,
                opponent=env_opponent,
            )
        )
    )

    if args.resume:
        # Resume training from a saved model
        print(f"Resuming training from {args.resume}...")
        model = MaskablePPO.load(args.resume, env=env, tensorboard_log=args.log_dir)
    else:
        # MaskablePPO with action masking and larger network for 1071-dim observation
        model = MaskablePPO(
            "MlpPolicy",
            env,
            # Larger network: default [64,64] is too small for 1071-dim obs
            policy_kwargs={"net_arch": [256, 256]},
            # Learning rate: standard for PPO
            learning_rate=3e-4,
            # Steps per rollout: 2048 is standard, gives ~2 full games per rollout at ticks_per_step=6
            n_steps=2048,
            # Mini-batch size: 64 is a good default
            batch_size=64,
            # Epochs per rollout: 10 is standard for PPO
            n_epochs=10,
            # Discount factor: high because games are long (~900 steps)
            gamma=0.999,
            # GAE lambda: standard value
            gae_lambda=0.95,
            # Clip range: standard PPO clip
            clip_range=0.2,
            # Entropy bonus: encourages exploration of different card plays
            ent_coef=0.01,
            # Value function coefficient: standard
            vf_coef=0.5,
            # Max gradient norm: standard
            max_grad_norm=0.5,
            seed=args.seed,
            verbose=1,
            tensorboard_log=args.log_dir,
        )

    # Build callback list
    callbacks = []
    if args.opponent == "self_play":
        # Wire the model into the self-play opponent now that it exists
        self_play_opponent.model = model
        callbacks.append(SelfPlayCallback(
            opponent=self_play_opponent,
            update_interval=args.self_play_interval,
            checkpoint_dir=os.path.dirname(args.save_path) or "models",
            verbose=1,
        ))

    print(f"\nStarting training for {args.timesteps} timesteps...")
    print(f"Opponent: {args.opponent}")
    if args.opponent == "self_play":
        print(f"Self-play opponent update interval: {args.self_play_interval} steps")
    print(f"TensorBoard logs: {args.log_dir}")
    print(f"Monitor with: tensorboard --logdir {args.log_dir}\n")

    start_time = time.time()
    model.learn(total_timesteps=args.timesteps, callback=callbacks if callbacks else None)
    training_time = time.time() - start_time

    print(f"\nTraining completed in {training_time:.1f}s")

    # Save model
    model.save(args.save_path)
    print(f"Model saved to {args.save_path}")

    # Evaluate
    print(f"\nEvaluating over {args.eval_episodes} episodes...")
    mean_reward, std_reward = evaluate_policy(model, env, n_eval_episodes=args.eval_episodes)
    print(f"Mean reward: {mean_reward:.3f} +/- {std_reward:.3f}")

    env.close()
    print("\nDone!")


if __name__ == "__main__":
    main()
