"""A small PPO trainer on crforge-env's vector of battles.

- :mod:`crforge_rl.train`: PPO on ``VectorBattle`` (``crforge-rl-train``), needs torch
- :mod:`crforge_rl.policy`: the masked policy, the device choice, checkpoints with their schema
  and vocabulary, needs torch
- :mod:`crforge_rl.evaluate`: a player against an opponent over fixed battles
  (``crforge-rl-eval``)
- :mod:`crforge_rl.opponents`: the players an evaluation or a training run plays against,
  pluggable by a spec (``random``, ``wait``, ``checkpoint:<path>``, ``<module>:<name>``)
- :mod:`crforge_rl.rollout`: the learner's seats, the shaping, the advantages
- :mod:`crforge_rl.records`: the win-rate curve and the fault log
- :mod:`crforge_rl.curve`: the curve as a picture (``crforge-rl-plot``), needs matplotlib

This module imports no torch: torch is the ``torch`` extra, so crforge-env and the parts here
that need no network install and test without it.
"""
