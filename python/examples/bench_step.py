#!/usr/bin/env python3
"""
Benchmark per-step throughput for binary, JSON, and JPype observation modes.

Requires a Java bridge server running on the default port for binary/JSON modes.
Start one with: ./gradlew :gym-bridge:run
Or auto-launch with --launch-server.

JPype mode runs in-process and does not need a server.

Usage:
  python python/examples/bench_step.py
  python python/examples/bench_step.py --steps 5000 --launch-server
  python python/examples/bench_step.py --jpype --steps 5000
"""

import argparse
import time

import numpy as np


def bench(endpoint: str, binary_obs: bool, num_steps: int, backend: str = "zmq") -> float:
    """Run num_steps no-op steps and return steps/sec."""
    from crforge_gym import CRForgeEnv

    env = CRForgeEnv(endpoint=endpoint, binary_obs=binary_obs, backend=backend)
    obs, _ = env.reset()

    noop = np.array([0, 0, 0])
    start = time.perf_counter()
    for _ in range(num_steps):
        obs, r, term, trunc, info = env.step(noop)
        if term or trunc:
            obs, _ = env.reset()
    elapsed = time.perf_counter() - start

    env.close()
    return num_steps / elapsed


def main():
    parser = argparse.ArgumentParser(description="Benchmark binary vs JSON vs JPype step throughput")
    parser.add_argument("--steps", type=int, default=2000,
                        help="Number of steps per benchmark run (default: 2000)")
    parser.add_argument("--port", type=int, default=9876,
                        help="Bridge server port (default: 9876)")
    parser.add_argument("--launch-server", action="store_true",
                        help="Auto-launch a Java bridge server")
    parser.add_argument("--jpype", action="store_true",
                        help="Include JPype in-process benchmark")
    parser.add_argument("--jpype-only", action="store_true",
                        help="Only run the JPype benchmark (no server needed)")
    parser.add_argument("--threaded-envs", type=int, default=0,
                        help="Benchmark ThreadedJPypeVecEnv with N envs (requires JPype)")
    args = parser.parse_args()

    endpoint = f"tcp://localhost:{args.port}"
    results = {}

    if not args.jpype_only:
        if args.launch_server:
            import os
            import sys
            script_dir = os.path.dirname(os.path.abspath(__file__))
            sys.path.insert(0, script_dir)
            from train_ppo import launch_servers
            launch_servers(args.port, 1)

        print(f"Benchmarking {args.steps} steps per mode on {endpoint}\n")

        # Warmup
        print("Warming up (binary)...")
        bench(endpoint, binary_obs=True, num_steps=200)

        # Binary mode
        binary_fps = bench(endpoint, binary_obs=True, num_steps=args.steps)
        print(f"Binary: {binary_fps:,.0f} steps/sec ({args.steps} steps)")
        results["binary"] = binary_fps

        # JSON mode
        json_fps = bench(endpoint, binary_obs=False, num_steps=args.steps)
        print(f"JSON:   {json_fps:,.0f} steps/sec ({args.steps} steps)")
        results["json"] = json_fps

        speedup = binary_fps / json_fps if json_fps > 0 else float("inf")
        print(f"\nBinary/JSON speedup: {speedup:.1f}x")

    if args.jpype or args.jpype_only:
        print(f"\nBenchmarking JPype in-process mode ({args.steps} steps)...")

        # Warmup
        print("Warming up (jpype)...")
        bench(endpoint, binary_obs=True, num_steps=200, backend="jpype")

        jpype_fps = bench(endpoint, binary_obs=True, num_steps=args.steps, backend="jpype")
        print(f"JPype:  {jpype_fps:,.0f} steps/sec ({args.steps} steps)")
        results["jpype"] = jpype_fps

        if "binary" in results:
            speedup = jpype_fps / results["binary"] if results["binary"] > 0 else float("inf")
            print(f"\nJPype/Binary speedup: {speedup:.1f}x")

    if args.threaded_envs > 0:
        n = args.threaded_envs
        print(f"\nBenchmarking ThreadedJPypeVecEnv with {n} envs ({args.steps} steps per env)...")

        from crforge_gym import CRForgeEnv, ThreadedJPypeVecEnv
        from crforge_gym.wrappers import ActionMaskedWrapper, EpisodeStatsWrapper

        def make_env():
            def _init():
                env = CRForgeEnv(binary_obs=True, backend="jpype")
                env = EpisodeStatsWrapper(env)
                env = ActionMaskedWrapper(env)
                return env
            return _init

        env_fns = [make_env() for _ in range(n)]
        vec_env = ThreadedJPypeVecEnv(env_fns)
        vec_env.reset()

        noop_actions = np.zeros((n, 3), dtype=int)
        total_steps = args.steps * n

        # Warmup
        print("Warming up (threaded)...")
        for _ in range(50):
            vec_env.step(noop_actions)

        start = time.perf_counter()
        steps_done = 0
        while steps_done < total_steps:
            vec_env.step(noop_actions)
            steps_done += n
        elapsed = time.perf_counter() - start

        total_fps = steps_done / elapsed
        per_env_fps = total_fps / n
        print(f"Threaded ({n} envs): {total_fps:,.0f} total steps/sec, {per_env_fps:,.0f} per-env steps/sec")
        results["threaded"] = total_fps

        if "jpype" in results:
            speedup = total_fps / results["jpype"] if results["jpype"] > 0 else float("inf")
            print(f"Threaded/SingleJPype speedup: {speedup:.1f}x")

        vec_env.close()


if __name__ == "__main__":
    main()
