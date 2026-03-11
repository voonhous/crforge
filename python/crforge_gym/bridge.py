"""
ZeroMQ bridge client for communicating with the CRForge Java server.
"""

import json
from typing import Any

import zmq


class BridgeClient:
    """ZMQ PAIR socket client that connects to the CRForge bridge server."""

    def __init__(self, endpoint: str = "tcp://localhost:9876"):
        self.endpoint = endpoint
        self.context = zmq.Context()
        self.socket = self.context.socket(zmq.PAIR)
        self._connected = False

    def connect(self) -> None:
        """Connect to the bridge server."""
        self.socket.connect(self.endpoint)
        self._connected = True

    def send(self, msg_type: str, data: dict | None = None) -> None:
        """Send a JSON message to the server."""
        message = {"type": msg_type}
        if data is not None:
            message["data"] = data
        self.socket.send_json(message)

    def receive(self) -> dict[str, Any]:
        """Receive a JSON message from the server."""
        return self.socket.recv_json()

    def init(
        self,
        blue_deck: list[str],
        red_deck: list[str],
        level: int = 11,
        ticks_per_step: int = 1,
        seed: int | None = None,
    ) -> dict[str, Any]:
        """Initialize a match on the server."""
        data = {
            "blueDeck": blue_deck,
            "redDeck": red_deck,
            "level": level,
            "ticksPerStep": ticks_per_step,
        }
        if seed is not None:
            data["seed"] = seed
        self.send("init", data)
        response = self.receive()
        if response.get("type") == "error":
            raise RuntimeError(f"Init failed: {response.get('message')}")
        return response

    def reset(self, seed: int | None = None) -> dict[str, Any]:
        """Reset the match and get initial observation.

        Args:
            seed: optional seed override for deterministic deck shuffling
        """
        data = {}
        if seed is not None:
            data["seed"] = seed
        self.send("reset", data if data else None)
        response = self.receive()
        if response.get("type") == "error":
            raise RuntimeError(f"Reset failed: {response.get('message')}")
        return response.get("data", {})

    def step(
        self,
        blue_action: dict | None = None,
        red_action: dict | None = None,
    ) -> dict[str, Any]:
        """Submit actions for both players and advance the simulation."""
        self.send(
            "step",
            {
                "blueAction": blue_action,
                "redAction": red_action,
            },
        )
        response = self.receive()
        if response.get("type") == "error":
            raise RuntimeError(f"Step failed: {response.get('message')}")
        return response.get("data", {})

    def close(self) -> None:
        """Send close message and disconnect."""
        if self._connected:
            try:
                self.send("close")
                self.receive()  # wait for close_ok
            except Exception:
                pass
            self.socket.close()
            self.context.term()
            self._connected = False

    def __enter__(self):
        self.connect()
        return self

    def __exit__(self, exc_type, exc_val, exc_tb):
        self.close()
        return False
