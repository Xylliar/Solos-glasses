#!/usr/bin/env python3
"""
A fake phone. Speaks the same protocol as the Android relay, with no glasses involved.

Use it to build and debug scenarios or agent prompts before the hardware works - or on
a train. Speech is printed instead of spoken, and gestures come from your keyboard.

    python mock_relay.py            # then, in another terminal:
    python run_scenario.py --url ws://127.0.0.1:8765 --demo pour_over --no-connect

Keys (type into the mock_relay terminal, then Enter):
    t  SINGLE_TAP        d  DOUBLE_TAP
    f  FORWARD_SLIDE     r  REVERSE_SLIDE
    q  quit
"""

from __future__ import annotations

import asyncio
import base64
import json
import sys

import websockets

KEYS = {
    "t": "SINGLE_TAP",
    "d": "DOUBLE_TAP",
    "f": "FORWARD_SLIDE",
    "r": "REVERSE_SLIDE",
}

# A 1x1 JPEG, so look() returns something decodable without a camera.
TINY_JPEG = base64.b64decode(
    "/9j/4AAQSkZJRgABAQEAYABgAAD/2wBDAAgGBgcGBQgHBwcJCQgKDBQNDAsLDBkSEw8UHRofHh0a"
    "HBwgJC4nICIsIxwcKDcpLDAxNDQ0Hyc5PTgyPC4zNDL/wAALCAABAAEBAREA/8QAFAABAAAAAAAA"
    "AAAAAAAAAAAACf/EABQQAQAAAAAAAAAAAAAAAAAAAAD/2gAIAQEAAD8AKp//2Q=="
)


class Mock:
    def __init__(self) -> None:
        self.clients: set = set()
        self.bindings: list[tuple[str, dict]] = []
        self.claimed = False

    async def handler(self, ws) -> None:
        self.clients.add(ws)
        print("[mock] agent connected")
        await ws.send(json.dumps({"type": "hello", "connected": True}))
        try:
            async for raw in ws:
                await self.handle(ws, json.loads(raw))
        except websockets.ConnectionClosed:
            pass
        finally:
            self.clients.discard(ws)
            print("[mock] agent gone")

    async def handle(self, ws, req: dict) -> None:
        cmd = req.get("cmd")
        out: dict = {"type": "result", "cmd": cmd, "ok": True}
        if "id" in req:
            out["id"] = req["id"]

        if cmd == "speak":
            print(f'\n  🔊  "{req.get("text")}"\n')
        elif cmd == "look":
            detail = req.get("detail")
            delay = {"quick": 0.8, "medium": 2.0}.get(detail, 4.0)
            print(f"  📷  capture ({detail}) - simulating {delay:.1f}s")
            await asyncio.sleep(delay)
            out["image"] = base64.b64encode(TINY_JPEG).decode()
        elif cmd == "claim":
            self.claimed = True
            print("  🔒  gestures claimed")
        elif cmd == "release":
            self.claimed = False
            self.bindings.clear()
            print("  🔓  gestures released")
        elif cmd == "bind":
            mapping = req.get("map", {})
            if self.bindings:
                self.bindings[-1] = (req.get("label", "?"), mapping)
            else:
                self.bindings.append((req.get("label", "?"), mapping))
            self._show()
        elif cmd == "push":
            self.bindings.append((req.get("label", "?"), req.get("map", {})))
            self._show()
        elif cmd == "pop":
            popped = self.bindings.pop() if self.bindings else None
            out["popped"] = popped[0] if popped else None
            self._show()
        elif cmd == "battery":
            out["level"] = 87
        elif cmd == "connect":
            print("  🔗  connected to (fake) glasses")
        # scan / stop_scan / ping / stop_speaking all just succeed

        await ws.send(json.dumps(out))

    def _show(self) -> None:
        if not self.bindings:
            print("  🎛   (no bindings)")
            return
        label, mapping = self.bindings[-1]
        depth = f" [depth {len(self.bindings)}]" if len(self.bindings) > 1 else ""
        print(f"  🎛   [{label}]{depth}")
        for gesture, action in mapping.items():
            key = next((k for k, v in KEYS.items() if v == gesture), "?")
            print(f"        {key} = {gesture:<14} -> {action}")

    async def fire(self, gesture: str) -> None:
        """Deliver a gesture, respecting the current bindings."""
        action = None
        if self.bindings:
            action = self.bindings[-1][1].get(gesture)
        if action:
            msg = {
                "type": "action",
                "action": action,
                "gesture": gesture,
                "context": self.bindings[-1][0],
            }
            print(f"  👆  {gesture} -> {action}")
        else:
            msg = {"type": "gesture", "gesture": gesture, "bound": False}
            print(f"  👆  {gesture} (unbound)")
        for ws in list(self.clients):
            await ws.send(json.dumps(msg))

    async def keyboard(self) -> None:
        loop = asyncio.get_running_loop()
        print("keys: t=tap  d=double  f=slide fwd  r=slide back  q=quit")
        while True:
            line = await loop.run_in_executor(None, sys.stdin.readline)
            if not line:
                await asyncio.sleep(0.2)
                continue
            key = line.strip().lower()
            if key == "q":
                raise SystemExit(0)
            if key in KEYS:
                await self.fire(KEYS[key])
            elif key:
                print(f"  ?  unknown key {key!r}")


async def main() -> None:
    mock = Mock()
    async with websockets.serve(mock.handler, "127.0.0.1", 8765, max_size=32 * 1024 * 1024):
        print("[mock] listening on ws://127.0.0.1:8765")
        await mock.keyboard()


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except (KeyboardInterrupt, SystemExit):
        print("\n[mock] bye")
