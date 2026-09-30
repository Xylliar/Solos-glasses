#!/usr/bin/env python3
"""
The LLM agent loop.

    export ANTHROPIC_API_KEY=...
    python agent.py --url ws://192.168.1.42:8765 --goal "help me adjust my bike brakes"

A manual tool loop rather than the SDK's tool_runner, because every tool here is async
(they all go over the WebSocket to the phone) and because the loop needs to own the
"speak then wait for a gesture" rhythm.

If you want to swap Claude for Gemini or anything else, TOOLS below is a plain JSON
schema list and _dispatch is a plain dict lookup - only _turn is Anthropic-specific.
"""

from __future__ import annotations

import argparse
import asyncio
import base64
import os
import sys
import time
from typing import Any

import anthropic
import httpx

from glasses import Glasses, GlassesError
from prompts import SYSTEM, kickoff

MODEL = "claude-opus-5"

# Voice interaction lives or dies on latency, so this starts low. Raise to "high" if the
# agent is making poor decisions about what to bind or when to look.
EFFORT = "low"


# ---------------------------------------------------------------------------------
# Tool surface
# ---------------------------------------------------------------------------------

TOOLS: list[dict[str, Any]] = [
    {
        "name": "speak",
        "description": (
            "Say something out loud through the glasses' speakers. This is your only way "
            "to communicate. Keep it to one or two short sentences."
        ),
        "input_schema": {
            "type": "object",
            "properties": {
                "text": {"type": "string", "description": "What to say. Spoken aloud, so no formatting."}
            },
            "required": ["text"],
            "additionalProperties": False,
        },
    },
    {
        "name": "look",
        "description": (
            "Capture a photo from the camera on the user's face and see it. "
            "detail='quick' is ~1 second but too low-resolution to read text. "
            "detail='detailed' can read labels and signs but takes several seconds. "
            "Tell the user you are looking before you call this."
        ),
        "input_schema": {
            "type": "object",
            "properties": {
                "detail": {"type": "string", "enum": ["quick", "medium", "detailed"]},
                "reason": {
                    "type": "string",
                    "description": "Why you need to look. Forces you to have one.",
                },
            },
            "required": ["detail", "reason"],
            "additionalProperties": False,
        },
    },
    {
        "name": "listen",
        "description": "Record the user speaking and get back a transcript.",
        "input_schema": {
            "type": "object",
            "properties": {
                "seconds": {"type": "integer", "minimum": 1, "maximum": 30},
            },
            "required": ["seconds"],
            "additionalProperties": False,
        },
    },
    {
        "name": "bind_gestures",
        "description": (
            "Reassign what the glasses' physical controls mean, and announce the change "
            "out loud in the same call. Use this whenever the step changes. "
            "Available controls: SINGLE_TAP, DOUBLE_TAP, FORWARD_SLIDE, REVERSE_SLIDE. "
            "Bind at most three."
        ),
        "input_schema": {
            "type": "object",
            "properties": {
                "label": {"type": "string", "description": "Short name for this step, e.g. 'bloom'."},
                "mapping": {
                    "type": "object",
                    "description": (
                        "Control name -> action name, e.g. "
                        "{\"SINGLE_TAP\": \"next_step\", \"DOUBLE_TAP\": \"repeat\"}. "
                        "Action names come back to you when the user triggers them."
                    ),
                    "additionalProperties": {"type": "string"},
                },
                "announcement": {
                    "type": "string",
                    "description": (
                        "Spoken immediately, describing the new controls in one sentence. "
                        "e.g. 'Slide forward for the next step, tap if you want me to look.'"
                    ),
                },
            },
            "required": ["label", "mapping", "announcement"],
            "additionalProperties": False,
        },
    },
    {
        "name": "push_gestures",
        "description": (
            "Layer a temporary control mapping over the current one - for a confirmation "
            "or a quick choice. Call pop_gestures afterwards to go back."
        ),
        "input_schema": {
            "type": "object",
            "properties": {
                "label": {"type": "string"},
                "mapping": {"type": "object", "additionalProperties": {"type": "string"}},
                "announcement": {"type": "string"},
            },
            "required": ["label", "mapping", "announcement"],
            "additionalProperties": False,
        },
    },
    {
        "name": "pop_gestures",
        "description": "Return to the control mapping that was active before the last push.",
        "input_schema": {"type": "object", "properties": {}, "additionalProperties": False},
    },
    {
        "name": "await_gesture",
        "description": (
            "Wait for the user to trigger one of the controls you bound. Returns the "
            "action name you assigned, or 'timeout'. Call this after you have spoken and "
            "bound controls - it is how you hand the turn back to the user."
        ),
        "input_schema": {
            "type": "object",
            "properties": {"timeout": {"type": "integer", "minimum": 1, "maximum": 600}},
            "required": ["timeout"],
            "additionalProperties": False,
        },
    },
    {
        "name": "unbind_gestures",
        "description": "Hand the controls back to the user. Call when the task is complete.",
        "input_schema": {"type": "object", "properties": {}, "additionalProperties": False},
    },
    {
        "name": "get_battery",
        "description": "Battery percentage of the glasses.",
        "input_schema": {"type": "object", "properties": {}, "additionalProperties": False},
    },
    {
        "name": "fetch_image",
        "description": (
            "Download an image from a URL and LOOK at it yourself. Use this for floor "
            "plans, venue maps, wiring diagrams, exploded parts diagrams, product photos "
            "- anything visual you found via web_search. "
            "Remember the user has no screen: you are looking at this so you can describe "
            "it to them out loud, not so you can show it to them."
        ),
        "input_schema": {
            "type": "object",
            "properties": {
                "url": {"type": "string", "description": "Direct URL to a JPEG/PNG/WEBP image."},
                "reason": {"type": "string", "description": "What you are hoping to read from it."},
            },
            "required": ["url", "reason"],
            "additionalProperties": False,
        },
    },
]

# Anthropic-hosted tools. These run on Anthropic's servers - there is no function to
# implement and no dispatch entry, the results just come back inside the same response.
# (Do NOT also declare code_execution: these variants run it under the hood.)
SERVER_TOOLS: list[dict[str, Any]] = [
    {"type": "web_search_20260209", "name": "web_search", "max_uses": 8},
    {"type": "web_fetch_20260209", "name": "web_fetch", "max_uses": 5},
]


# ---------------------------------------------------------------------------------
# Tool execution
# ---------------------------------------------------------------------------------


class Session:
    def __init__(self, g: Glasses, save_images: bool = True):
        self.g = g
        self.save_images = save_images
        self.done = False

    async def dispatch(self, name: str, args: dict) -> Any:
        """
        Run a tool. Returns either a plain string, or a list of content blocks when the
        result includes an image.
        """
        fn = getattr(self, f"_t_{name}", None)
        if fn is None:
            return f"unknown tool: {name}"
        try:
            return await fn(args)
        except GlassesError as e:
            return f"the glasses did not respond: {e}"
        except Exception as e:  # keep one bad tool call from ending the session
            return f"error: {e}"

    # -- tools ---------------------------------------------------------------------

    async def _t_speak(self, a: dict) -> str:
        await self.g.speak(a["text"], flush=False)
        # Rough pacing so the next tool call doesn't talk over this one.
        await asyncio.sleep(min(6.0, 0.9 + len(a["text"]) / 14))
        return "spoken"

    async def _t_look(self, a: dict):
        data = await self.g.look(detail=a.get("detail", "detailed"))
        if not data:
            return "the camera did not return an image - tell the user and try again or ask them"
        if self.save_images:
            path = f"look_{int(time.time())}.jpg"
            with open(path, "wb") as fh:
                fh.write(data)
        return [
            {
                "type": "image",
                "source": {
                    "type": "base64",
                    "media_type": "image/jpeg",
                    "data": base64.b64encode(data).decode(),
                },
            },
            {"type": "text", "text": f"Camera frame ({a.get('detail')}). Reason: {a.get('reason','')}"},
        ]

    async def _t_listen(self, a: dict) -> str:
        # Speech-to-text is the one piece deliberately left unimplemented - drop in
        # whisper, Deepgram, or Android's recognizer here. Until then the agent falls
        # back to gestures, which is the more interesting half anyway.
        secs = a.get("seconds", 5)
        print(f"  [listen {secs}s - STT not wired up; type what the user said, or blank]")
        loop = asyncio.get_running_loop()
        said = await loop.run_in_executor(None, sys.stdin.readline)
        said = said.strip()
        return said or "(the user said nothing)"

    async def _t_bind_gestures(self, a: dict) -> str:
        mapping = {k.upper(): v for k, v in (a.get("mapping") or {}).items()}
        await self.g.bind(a.get("label", "task"), mapping)
        await self.g.speak(a["announcement"])
        await asyncio.sleep(min(5.0, 0.9 + len(a["announcement"]) / 14))
        return f"bound and announced: {mapping}"

    async def _t_push_gestures(self, a: dict) -> str:
        mapping = {k.upper(): v for k, v in (a.get("mapping") or {}).items()}
        await self.g.push(a.get("label", "sub"), mapping)
        await self.g.speak(a["announcement"])
        await asyncio.sleep(min(5.0, 0.9 + len(a["announcement"]) / 14))
        return f"pushed: {mapping}"

    async def _t_pop_gestures(self, a: dict) -> str:
        await self.g.pop()
        return "popped back to the previous mapping"

    async def _t_await_gesture(self, a: dict) -> str:
        act = await self.g.wait_action(timeout=a.get("timeout", 120))
        if act is None:
            return "timeout - the user did nothing"
        return f"the user triggered: {act.action} (via {act.gesture})"

    async def _t_unbind_gestures(self, a: dict) -> str:
        await self.g.release()
        self.done = True
        return "controls handed back to the user"

    async def _t_get_battery(self, a: dict) -> str:
        return f"{await self.g.battery()}%"

    async def _t_fetch_image(self, a: dict):
        url = a.get("url", "")
        if not url.lower().startswith(("http://", "https://")):
            return "that is not an http(s) URL"
        try:
            async with httpx.AsyncClient(follow_redirects=True, timeout=25) as http:
                r = await http.get(url, headers={"User-Agent": "airgo-agent/0.1"})
            r.raise_for_status()
        except Exception as e:
            return f"could not fetch that image: {e} - try another source or ask the user"

        media = (r.headers.get("content-type") or "").split(";")[0].strip().lower()
        if media not in ("image/jpeg", "image/png", "image/webp", "image/gif"):
            return f"that URL returned {media or 'no content-type'}, not an image"
        # Roughly the API's per-image ceiling; better a clear message than a 400.
        if len(r.content) > 5_000_000:
            return f"that image is {len(r.content)/1e6:.1f} MB, too large - find a smaller one"

        if self.save_images:
            ext = media.split("/")[1].replace("jpeg", "jpg")
            with open(f"fetched_{int(time.time())}.{ext}", "wb") as fh:
                fh.write(r.content)
        return [
            {
                "type": "image",
                "source": {
                    "type": "base64",
                    "media_type": media,
                    "data": base64.b64encode(r.content).decode(),
                },
            },
            {"type": "text", "text": f"Fetched from {url}. Reason: {a.get('reason','')}"},
        ]


# ---------------------------------------------------------------------------------
# Loop
# ---------------------------------------------------------------------------------


async def run(g: Glasses, goal: str | None, max_turns: int = 60, save_images: bool = True) -> None:
    client = anthropic.AsyncAnthropic()
    session = Session(g, save_images=save_images)
    messages: list[dict[str, Any]] = [{"role": "user", "content": kickoff(goal)}]

    for turn in range(max_turns):
        resp = await client.messages.create(
            model=MODEL,
            max_tokens=16000,
            system=SYSTEM,
            tools=TOOLS + SERVER_TOOLS,
            output_config={"effort": EFFORT},
            messages=messages,
        )

        if resp.stop_reason == "refusal":
            print("model declined this request", file=sys.stderr)
            break

        # Anything the model said outside a tool call is thinking-out-loud, not speech.
        for block in resp.content:
            if block.type == "text" and block.text.strip():
                print(f"  [model] {block.text.strip()[:200]}")

        # Server tools (web_search / web_fetch) appear as `server_tool_use` blocks and
        # run on Anthropic's side, so they are deliberately NOT in this list.
        tool_uses = [b for b in resp.content if b.type == "tool_use"]
        messages.append({"role": "assistant", "content": resp.content})

        # A long server-tool turn can stop early; re-send to let it continue.
        if resp.stop_reason == "pause_turn":
            continue

        if resp.stop_reason == "end_turn" or not tool_uses:
            print("model ended the turn")
            break

        results = []
        for tu in tool_uses:
            print(f"  [tool] {tu.name} {tu.input}")
            out = await session.dispatch(tu.name, tu.input or {})
            results.append({"type": "tool_result", "tool_use_id": tu.id, "content": out})

        # All results for a parallel batch go back in ONE user message.
        messages.append({"role": "user", "content": results})

        if session.done:
            print("task complete")
            break
    else:
        print(f"hit the {max_turns}-turn cap")


async def main() -> int:
    ap = argparse.ArgumentParser(description="Run the LLM agent on the glasses.")
    ap.add_argument("--url", default="ws://192.168.1.42:8765")
    ap.add_argument("--goal", default=None, help="What the user wants. Omit and it asks.")
    ap.add_argument("--no-connect", action="store_true")
    ap.add_argument("--max-turns", type=int, default=60)
    ap.add_argument("--no-save", action="store_true")
    args = ap.parse_args()

    if not (os.getenv("ANTHROPIC_API_KEY") or os.getenv("ANTHROPIC_AUTH_TOKEN")):
        print("warning: no ANTHROPIC_API_KEY set - relying on an `ant auth login` profile",
              file=sys.stderr)

    async with Glasses(args.url) as g:
        if not args.no_connect:
            if not await g.connect_glasses():
                print("could not connect to glasses", file=sys.stderr)
                return 1
        await g.claim()
        try:
            await run(g, args.goal, max_turns=args.max_turns, save_images=not args.no_save)
        finally:
            # Never leave the user's controls hijacked, whatever went wrong.
            await g.release()
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(asyncio.run(main()))
    except KeyboardInterrupt:
        raise SystemExit(130)
