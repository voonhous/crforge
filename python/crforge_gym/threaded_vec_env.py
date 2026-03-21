"""
ThreadedJPypeVecEnv: a VecEnv that steps N JPype environments in parallel
using a thread pool instead of subprocesses.

JPype releases the Python GIL during Java calls, so N threads can step
N GameSession instances in true parallelism within a single JVM -- zero
pipes, zero pickle, zero IPC overhead.

Mirrors DummyVecEnv's buffer layout and auto-reset behavior exactly,
but dispatches step() and reset() to a ThreadPoolExecutor.

Requires: jpype1>=1.5.0, stable-baselines3>=2.0
"""

from collections import OrderedDict
from collections.abc import Callable, Sequence
from concurrent.futures import ThreadPoolExecutor
from copy import deepcopy
from typing import Any, Optional

import gymnasium as gym
import numpy as np

from stable_baselines3.common.vec_env.base_vec_env import (
    VecEnv,
    VecEnvIndices,
    VecEnvObs,
    VecEnvStepReturn,
)
from stable_baselines3.common.vec_env.patch_gym import _patch_env
from stable_baselines3.common.vec_env.util import dict_to_obs, obs_space_info


class ThreadedJPypeVecEnv(VecEnv):
    """Vectorized env that steps JPype-backed environments in parallel threads.

    Each environment lives in the main process (single JVM). Java calls
    release the GIL, so the thread pool achieves true parallelism for the
    expensive simulation work. Python-side overhead (buffer writes, auto-reset)
    serializes but is negligible.

    Drop-in replacement for SubprocVecEnv when using the JPype backend.

    :param env_fns: list of callables that return gymnasium environments.
    """

    actions: np.ndarray

    def __init__(self, env_fns: list[Callable[[], gym.Env]]):
        self.envs = [_patch_env(fn()) for fn in env_fns]
        if len(set(id(env.unwrapped) for env in self.envs)) != len(self.envs):
            raise ValueError(
                "All env_fns must return distinct environment instances. "
                "Use a factory function that creates a new env each time."
            )
        env = self.envs[0]
        super().__init__(len(env_fns), env.observation_space, env.action_space)
        obs_space = env.observation_space
        self.keys, shapes, dtypes = obs_space_info(obs_space)

        self.buf_obs = OrderedDict(
            [
                (k, np.zeros((self.num_envs, *tuple(shapes[k])), dtype=dtypes[k]))
                for k in self.keys
            ]
        )
        self.buf_dones = np.zeros((self.num_envs,), dtype=bool)
        self.buf_rews = np.zeros((self.num_envs,), dtype=np.float32)
        self.buf_infos: list[dict[str, Any]] = [{} for _ in range(self.num_envs)]
        self.metadata = env.metadata

        self._executor = ThreadPoolExecutor(max_workers=self.num_envs)

    def step_async(self, actions: np.ndarray) -> None:
        self.actions = actions

    def step_wait(self) -> VecEnvStepReturn:
        def _step_one(idx: int) -> None:
            obs, self.buf_rews[idx], terminated, truncated, self.buf_infos[idx] = (
                self.envs[idx].step(self.actions[idx])
            )
            self.buf_dones[idx] = terminated or truncated
            self.buf_infos[idx]["TimeLimit.truncated"] = truncated and not terminated

            if self.buf_dones[idx]:
                self.buf_infos[idx]["terminal_observation"] = obs
                obs, self.reset_infos[idx] = self.envs[idx].reset()
            self._save_obs(idx, obs)

        futures = [self._executor.submit(_step_one, i) for i in range(self.num_envs)]
        for f in futures:
            f.result()  # propagates exceptions

        return (
            self._obs_from_buf(),
            np.copy(self.buf_rews),
            np.copy(self.buf_dones),
            deepcopy(self.buf_infos),
        )

    def reset(self) -> VecEnvObs:
        def _reset_one(idx: int) -> None:
            maybe_options = (
                {"options": self._options[idx]} if self._options[idx] else {}
            )
            obs, self.reset_infos[idx] = self.envs[idx].reset(
                seed=self._seeds[idx], **maybe_options
            )
            self._save_obs(idx, obs)

        futures = [self._executor.submit(_reset_one, i) for i in range(self.num_envs)]
        for f in futures:
            f.result()

        self._reset_seeds()
        self._reset_options()
        return self._obs_from_buf()

    def close(self) -> None:
        for env in self.envs:
            env.close()
        self._executor.shutdown(wait=False)

    def get_images(self) -> Sequence[Optional[np.ndarray]]:
        return [None for _ in self.envs]

    def render(self, mode: Optional[str] = None) -> Optional[np.ndarray]:
        return super().render(mode=mode)

    def _save_obs(self, env_idx: int, obs: VecEnvObs) -> None:
        for key in self.keys:
            if key is None:
                self.buf_obs[key][env_idx] = obs
            else:
                self.buf_obs[key][env_idx] = obs[key]

    def _obs_from_buf(self) -> VecEnvObs:
        return dict_to_obs(self.observation_space, deepcopy(self.buf_obs))

    def get_attr(self, attr_name: str, indices: VecEnvIndices = None) -> list[Any]:
        """Return attribute from vectorized environment (see base class)."""
        target_envs = self._get_target_envs(indices)
        return [env_i.get_wrapper_attr(attr_name) for env_i in target_envs]

    def set_attr(
        self, attr_name: str, value: Any, indices: VecEnvIndices = None
    ) -> None:
        """Set attribute inside vectorized environments (see base class)."""
        target_envs = self._get_target_envs(indices)
        for env_i in target_envs:
            setattr(env_i, attr_name, value)

    def env_method(
        self,
        method_name: str,
        *method_args,
        indices: VecEnvIndices = None,
        **method_kwargs,
    ) -> list[Any]:
        """Call instance methods of vectorized environments."""
        target_envs = self._get_target_envs(indices)
        return [
            env_i.get_wrapper_attr(method_name)(*method_args, **method_kwargs)
            for env_i in target_envs
        ]

    def env_is_wrapped(
        self, wrapper_class: type[gym.Wrapper], indices: VecEnvIndices = None
    ) -> list[bool]:
        """Check if worker environments are wrapped with a given wrapper."""
        target_envs = self._get_target_envs(indices)
        from stable_baselines3.common import env_util

        return [env_util.is_wrapped(env_i, wrapper_class) for env_i in target_envs]

    def _get_target_envs(self, indices: VecEnvIndices) -> list[gym.Env]:
        indices = self._get_indices(indices)
        return [self.envs[i] for i in indices]
