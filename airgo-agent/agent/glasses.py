"""
Client for the phone-side AirGo relay.

Everything the glasses can do shows up here as a plain async method. Nothing in this
file knows about LLMs - it is the hardware layer, and both the scripted scenarios and
the LLM agent sit on top of it.
"""

from __future__ import annotations

import asyncio
import base64
import contextlib
import itertools
import json
import time
from dataclasses import dataclass, field
from typing import Any, AsyncIterator, Optional

import websockets

# Gesture names the glasses can report. Anything else is rejected by the binder.
GESTURES = [
    "SINGLE_TAP",
    "DOUBLE_TAP",
    "FORWARD_SLIDE",
    "REVERSE_SLIDE",
    "SLIDE_PRESSED",
    "SLIDE_RELEASED",
    "VB_PRESSED",
    "VB_RELEASED",
]

# Gestures worth binding in practice. SLIDE_PRESSED/RELEASED fire as a pair with the
# slide gestures and VB is the power button, so binding those tends to cause confusion.
USEFUL_GESTURES = ["SINGLE_TAP", "DOUBLE_TAP", "FORWARD_SLIDE", "REVERSE_SLIDE"]


@dataclass
class Action:
    """A bound gesture firing - the user did something you asked them to do."""

    action: str
    gesture: str
    context: str
    at: float = field(default_factory=time.time)


class GlassesError(RuntimeError):
    pass


class Glasses:
    """
    Async client for the relay.

    Usage:
        async with Glasses("ws://192.168.1.42:8765") as g:
            await g.connect_glasses()
            await g.claim()
            await g.speak("Ready.")
            await g.bind("demo", {"SINGLE_TAP": "go"})
            act = await g.wait_action(timeout=30)
    """

    def __init__(self, url: str, *, verbose: bool = True):
        self.url = url
        self.verbose = verbose
        self._ws: Optional[websockets.WebSocketClientProtocol] = None
        self._ids = itertools.count(1)
        self._pending: dict[str, asyncio.Future] = {}
        self._actions: asyncio.Queue[Action] = asyncio.Queue()
        self._events: asyncio.Queue[dict] = asyncio.Queue()
        self._reader: Optional[asyncio.Task] = None
        self.connected_to_glasses = False

    # ------------------------------------------------------------------ lifecycle

    async def __aenter__(self) -> "Glasses":
        await self.open()
        return self

    async def __aexit__(self, *exc) -> None:
        await self.close()

    async def open(self) -> None:
        self._ws = await websockets.connect(self.url, max_size=32 * 1024 * 1024)
        self._reader = asyncio.create_task(self._read_loop())
        self._log(f"relay connected: {self.url}")

    async def close(self) -> None:
        if self._reader:
            self._reader.cancel()
            with contextlib.suppress(asyncio.CancelledError):
                await self._reader
        if self._ws:
            await self._ws.close()

    async def _read_loop(self) -> None:
        assert self._ws
        async for raw in self._ws:
            try:
                msg = json.loads(raw)
            except json.JSONDecodeError:
                self._log(f"bad message: {raw[:120]}")
                continue

            kind = msg.get("type")

            if kind == "result":
                fut = self._pending.pop(str(msg.get("id")), None)
                if fut and not fut.done():
                    fut.set_result(msg)
                continue

            if kind == "action":
                act = Action(msg["action"], msg.get("gesture", ""), msg.get("context", ""))
                self._log(f"  <- {act.gesture} => {act.action}")
                await self._actions.put(act)

            elif kind == "gesture":
                self._log(f"  <- {msg.get('gesture')} (unbound)")

            elif kind in ("connected", "status"):
                if kind == "connected":
                    self.connected_to_glasses = True
                if msg.get("status") == "DISCONNECTED":
                    self.connected_to_glasses = False
                self._log(f"  <- {msg}")

            await self._events.put(msg)

    # ------------------------------------------------------------------ transport

    async def request(self, cmd: str, **kwargs: Any) -> dict:
        if not self._ws:
            raise GlassesError("relay not open")
        rid = str(next(self._ids))
        fut: asyncio.Future = asyncio.get_running_loop().create_future()
        self._pending[rid] = fut
        await self._ws.send(json.dumps({"id": rid, "cmd": cmd, **kwargs}))
        try:
            # Photo capture over BLE can genuinely take tens of seconds.
            return await asyncio.wait_for(fut, timeout=90)
        except asyncio.TimeoutError:
            self._pending.pop(rid, None)
            raise GlassesError(f"{cmd} timed out")

    def _log(self, line: str) -> None:
        if self.verbose:
            print(line, flush=True)

    # ------------------------------------------------------------------ connection

    async def connect_glasses(self, device: str | None = None, scan_seconds: float = 6.0) -> bool:
        """Scan, wait, then connect. Returns False if nothing was found."""
        await self.request("scan")
        self._log(f"scanning {scan_seconds:.0f}s ...")
        await asyncio.sleep(scan_seconds)
        res = await self.request("connect", device=device)
        if not res.get("ok"):
            self._log(f"connect failed: {res.get('error')}")
            return False
        self.connected_to_glasses = True
        self._log("glasses connected")
        return True

    async def battery(self) -> int | None:
        return (await self.request("battery")).get("level")

    # ------------------------------------------------------------------ output

    async def speak(self, text: str, flush: bool = False) -> None:
        """Say something through the glasses. Keep it short - it is spoken aloud."""
        self._log(f"  -> say: {text}")
        await self.request("speak", text=text, flush=flush)

    async def stop_speaking(self) -> None:
        await self.request("stop_speaking")

    # ------------------------------------------------------------------ camera

    async def look(self, detail: str = "detailed", transfer: str | None = None) -> bytes | None:
        """
        Capture a frame.

        detail="quick"    320x240, ~1s, cannot read text
        detail="medium"   640x480
        detail="detailed" 1280x960, readable, slow over BLE

        transfer=None uses BLE. Pass "WIFI" if the glasses are on Wi-Fi - much faster
        for the larger sizes.
        """
        t0 = time.time()
        res = await self.request("look", detail=detail, transfer=transfer)
        if not res.get("ok"):
            self._log(f"  look failed: {res.get('error')}")
            return None
        data = base64.b64decode(res["image"])
        self._log(f"  <- image {len(data)/1024:.0f}kB in {time.time()-t0:.1f}s ({detail})")
        return data

    # ------------------------------------------------------------------ gestures

    async def claim(self, steal_power_button: bool = False) -> None:
        """
        Take the gestures away from the glasses' built-in behaviours.

        Until this runs a slide changes the volume and a double-tap opens the phone's
        voice assistant. One BLE round trip; do it once per session.
        """
        await self.request("claim", steal_power_button=steal_power_button)
        self._log("gestures claimed")

    async def release(self) -> None:
        """Give the controls back. Always call this, even on the error path."""
        await self.request("release")
        self._log("gestures released")

    async def bind(self, label: str, mapping: dict[str, str]) -> None:
        """Replace the current binding. Free and instant - rebind as often as you like."""
        await self.request("bind", label=label, map=mapping)

    async def push(self, label: str, mapping: dict[str, str]) -> None:
        """Layer a temporary binding on top (a confirmation, a numeric pick)."""
        await self.request("push", label=label, map=mapping)

    async def pop(self) -> None:
        """Return to the binding underneath."""
        await self.request("pop")

    async def bindings(self) -> dict:
        return await self.request("bindings")

    # ------------------------------------------------------------------ waiting

    async def wait_action(self, timeout: float | None = None) -> Action | None:
        """Block until the user triggers a bound gesture. None on timeout."""
        try:
            if timeout is None:
                return await self._actions.get()
            return await asyncio.wait_for(self._actions.get(), timeout=timeout)
        except asyncio.TimeoutError:
            return None

    def drain_actions(self) -> None:
        """Throw away queued actions so a stale tap doesn't drive the next step."""
        while not self._actions.empty():
            self._actions.get_nowait()

    async def actions(self) -> AsyncIterator[Action]:
        while True:
            yield await self._actions.get()

    # ------------------------------------------------------------------ convenience

    async def announce_bindings(self, label: str, mapping: dict[str, str], phrase: str) -> None:
        """
        Bind and say what you did, in that order.

        The user cannot see the mapping. If you rebind silently they are lost, so this
        pairing should be the only way you ever bind during a task.
        """
        await self.bind(label, mapping)
        await self.speak(phrase)
