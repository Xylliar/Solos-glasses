#!/usr/bin/env python3
"""
Integration test with no hardware and no API key.

Runs the mock relay and a scenario in one process, firing gestures on a timer, and
asserts that bindings actually changed between steps - which is the whole point of the
project, so it is worth a test.

    python selftest.py
"""

from __future__ import annotations

import asyncio
import sys

import websockets

from glasses import Glasses
from mock_relay import Mock
from scenarios import ALL, run

PORT = 8799
URL = f"ws://127.0.0.1:{PORT}"


async def drive(mock: Mock, script: list[tuple[float, str]]) -> None:
    """Fire a scripted sequence of gestures."""
    for delay, gesture in script:
        await asyncio.sleep(delay)
        await mock.fire(gesture)


async def main() -> int:
    mock = Mock()
    seen_bindings: list[tuple[str, dict]] = []

    # Record every binding the scenario applies so we can assert on them afterwards.
    original_show = mock._show

    def record() -> None:
        if mock.bindings:
            seen_bindings.append((mock.bindings[-1][0], dict(mock.bindings[-1][1])))
        original_show()

    mock._show = record  # type: ignore[method-assign]

    async with websockets.serve(mock.handler, "127.0.0.1", PORT, max_size=32 * 1024 * 1024):
        script = [
            (2.0, "SINGLE_TAP"),      # colours: red
            (1.0, "DOUBLE_TAP"),      # colours: blue
            (1.0, "REVERSE_SLIDE"),   # -> animals
            (2.0, "SINGLE_TAP"),      # animals: cat
            (1.0, "REVERSE_SLIDE"),   # -> camera
            (2.0, "REVERSE_SLIDE"),   # -> done
        ]

        async with Glasses(URL, verbose=True) as g:
            await g.claim()
            driver = asyncio.create_task(drive(mock, script))
            try:
                await asyncio.wait_for(run(g, ALL["gesture_lab"], save_images=False), timeout=60)
            finally:
                driver.cancel()
                await g.release()

    # ---- assertions --------------------------------------------------------------
    print("\n" + "=" * 60)
    failures = []

    labels = [b[0] for b in seen_bindings]
    if "colors" not in labels:
        failures.append("never bound the 'colors' step")
    if "animals" not in labels:
        failures.append("never bound the 'animals' step - scenario did not advance")

    # The real assertion: the SAME gesture meant DIFFERENT things at different times.
    tap_meanings = {
        mapping.get("SINGLE_TAP")
        for _, mapping in seen_bindings
        if mapping.get("SINGLE_TAP")
    }
    if len(tap_meanings) < 2:
        failures.append(f"SINGLE_TAP never got remapped (always {tap_meanings})")

    if mock.claimed:
        failures.append("gestures were never released")

    print(f"bindings applied: {len(seen_bindings)}")
    for label, mapping in seen_bindings:
        print(f"  [{label}] {mapping}")
    print(f"SINGLE_TAP meant: {sorted(tap_meanings)}")

    if failures:
        print("\nFAILED:")
        for f in failures:
            print(f"  - {f}")
        return 1

    print("\nPASS - remapping works, and the controls were handed back.")
    return 0


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
