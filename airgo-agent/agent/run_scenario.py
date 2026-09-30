#!/usr/bin/env python3
"""
Run a scripted demo on the glasses. No LLM, no API key.

    python run_scenario.py --url ws://192.168.1.42:8765 --list
    python run_scenario.py --url ws://192.168.1.42:8765 --demo gesture_lab

Start with gesture_lab. If that works, the hard parts (BLE, gesture claim, TTS routing)
are all proven and everything else is just content.
"""

from __future__ import annotations

import argparse
import asyncio
import sys

from glasses import Glasses
from scenarios import ALL, run


async def main() -> int:
    ap = argparse.ArgumentParser(description="Run a scripted AirGo gesture demo.")
    ap.add_argument("--url", default="ws://192.168.1.42:8765",
                    help="Relay WebSocket URL, shown on the phone screen.")
    ap.add_argument("--demo", default="gesture_lab", help="Scenario name.")
    ap.add_argument("--list", action="store_true", help="List scenarios and exit.")
    ap.add_argument("--no-connect", action="store_true",
                    help="Skip scan/connect (glasses already connected from the phone UI).")
    ap.add_argument("--scan-seconds", type=float, default=6.0)
    ap.add_argument("--no-save", action="store_true", help="Don't write captured frames to disk.")
    args = ap.parse_args()

    if args.list:
        width = max(len(n) for n in ALL)
        for name, s in ALL.items():
            print(f"  {name.ljust(width)}  {s.blurb}")
        return 0

    if args.demo not in ALL:
        print(f"unknown demo: {args.demo}", file=sys.stderr)
        print(f"available: {', '.join(ALL)}", file=sys.stderr)
        return 2

    scenario = ALL[args.demo]

    async with Glasses(args.url) as g:
        if not args.no_connect:
            if not await g.connect_glasses(scan_seconds=args.scan_seconds):
                print("could not connect to glasses", file=sys.stderr)
                return 1
        else:
            await g.request("ping")

        level = await g.battery()
        if level is not None:
            print(f"battery: {level}%")

        await g.claim()
        try:
            await run(g, scenario, save_images=not args.no_save)
        except KeyboardInterrupt:
            print("\ninterrupted")
        finally:
            # The single most important line in this file. Without it you leave the
            # user's volume slider dead until they reboot the glasses.
            await g.release()

    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(asyncio.run(main()))
    except KeyboardInterrupt:
        raise SystemExit(130)
