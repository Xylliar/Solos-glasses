#!/usr/bin/env python3
"""
The agent loop, on Gemini.

    export GEMINI_API_KEY=...
    python agent_gemini.py --list-models                      # find your exact model id
    python agent_gemini.py --url ws://PHONE_IP:8765 --goal "help me fix my brakes"

Gemini's web research is a NATIVE capability, not something we implement. We declare
`google_search`, `url_context` and optionally `google_maps`, and the model uses them on
its own - no handler, no schema, no code. Our own tools (look, speak, bind_gestures...)
are declared alongside them.

The glasses tools and the Session that runs them are imported from agent.py - those are
provider-neutral JSON Schema. Only this loop is Gemini-specific.
"""

from __future__ import annotations

import argparse
import asyncio
import base64
import os
import sys
from typing import Any

from google import genai
from google.genai import types

from agent import TOOLS, Session
from glasses import Glasses
from prompts import SYSTEM, kickoff

# Set GEMINI_MODEL to the exact id from AI Studio. Run --list-models to see what your
# key can actually reach; model ids change faster than any hardcoded default.
MODEL = os.getenv("GEMINI_MODEL", "gemini-flash-latest")


def build_tools(use_search: bool, use_maps: bool) -> list[types.Tool]:
    """
    Our glasses tools, plus Gemini's own.

    The built-ins carry no implementation - the model calls them server-side and the
    grounded result comes back inside its answer.
    """
    declarations = [
        types.FunctionDeclaration(
            name=t["name"],
            description=t["description"],
            # Gemini accepts plain JSON Schema here, so the schemas in agent.py are
            # reused verbatim rather than translated.
            parameters_json_schema=t["input_schema"],
        )
        # fetch_image is redundant on Gemini when url_context is on, but harmless and
        # more explicit about "look at this picture yourself".
        for t in TOOLS
    ]

    tools = [types.Tool(function_declarations=declarations)]
    if use_search:
        tools.append(types.Tool(google_search=types.GoogleSearch()))
        tools.append(types.Tool(url_context=types.UrlContext()))
    if use_maps:
        tools.append(types.Tool(google_maps=types.GoogleMaps()))
    return tools


def to_gemini_response(name: str, out: Any) -> types.Part:
    """
    Convert a Session tool result into a Gemini function response.

    Session returns either a plain string, or a list of Anthropic-style content blocks
    when there's an image. Gemini carries images in FunctionResponsePart.inline_data.
    """
    if isinstance(out, str):
        return types.Part.from_function_response(name=name, response={"result": out})

    text_bits: list[str] = []
    parts: list[types.FunctionResponsePart] = []
    for block in out:
        if block.get("type") == "text":
            text_bits.append(block.get("text", ""))
        elif block.get("type") == "image":
            src = block.get("source", {})
            parts.append(
                types.FunctionResponsePart(
                    inline_data=types.FunctionResponseBlob(
                        mime_type=src.get("media_type", "image/jpeg"),
                        data=base64.b64decode(src["data"]),
                    )
                )
            )
    return types.Part.from_function_response(
        name=name,
        response={"result": " ".join(text_bits) or "see attached image"},
        parts=parts or None,
    )


async def run(
    g: Glasses,
    goal: str | None,
    *,
    use_search: bool = True,
    use_maps: bool = False,
    max_turns: int = 60,
    save_images: bool = True,
) -> None:
    client = genai.Client()  # reads GEMINI_API_KEY (or GOOGLE_API_KEY)
    session = Session(g, save_images=save_images)

    config = types.GenerateContentConfig(
        system_instruction=SYSTEM,
        tools=build_tools(use_search, use_maps),
        # Built-in tools (google_search, url_context) can only be combined with our own
        # function declarations when this is set - otherwise the API returns 400.
        tool_config=types.ToolConfig(include_server_side_tool_invocations=True)
        if (use_search or use_maps) else None,
    )

    contents: list[types.Content] = [
        types.Content(role="user", parts=[types.Part.from_text(text=kickoff(goal))])
    ]

    for _turn in range(max_turns):
        resp = await client.aio.models.generate_content(
            model=MODEL, contents=contents, config=config
        )

        cand = (resp.candidates or [None])[0]
        if cand is None or not cand.content or not cand.content.parts:
            print("empty response from the model")
            break

        contents.append(cand.content)

        calls = []
        for part in cand.content.parts:
            if part.function_call:
                calls.append(part.function_call)
            elif part.text and part.text.strip() and not part.thought:
                # Text outside a speak() call is the model thinking aloud, not speech.
                print(f"  [model] {part.text.strip()[:200]}")

        # Show what its own research turned up - useful for judging prompt quality.
        meta = getattr(cand, "grounding_metadata", None)
        if meta and getattr(meta, "web_search_queries", None):
            print(f"  [gemini searched] {list(meta.web_search_queries)}")

        if not calls:
            print("model ended the turn")
            break

        responses: list[types.Part] = []
        for call in calls:
            args = dict(call.args or {})
            print(f"  [tool] {call.name} {args}")
            out = await session.dispatch(call.name, args)
            responses.append(to_gemini_response(call.name, out))

        contents.append(types.Content(role="user", parts=responses))

        if session.done:
            print("task complete")
            break
    else:
        print(f"hit the {max_turns}-turn cap")


async def main() -> int:
    ap = argparse.ArgumentParser(description="Run the glasses agent on Gemini.")
    ap.add_argument("--url", default="ws://192.168.1.42:8765")
    ap.add_argument("--goal", default=None)
    ap.add_argument("--list-models", action="store_true",
                    help="Print model ids this API key can reach, then exit.")
    ap.add_argument("--no-search", action="store_true",
                    help="Disable google_search/url_context. Use this to isolate whether "
                         "mixing built-in tools with function calling is the problem.")
    ap.add_argument("--maps", action="store_true", help="Also enable the google_maps tool.")
    ap.add_argument("--no-connect", action="store_true")
    ap.add_argument("--max-turns", type=int, default=60)
    ap.add_argument("--no-save", action="store_true")
    args = ap.parse_args()

    if not (os.getenv("GEMINI_API_KEY") or os.getenv("GOOGLE_API_KEY")):
        print("set GEMINI_API_KEY (get one at https://aistudio.google.com/apikey)",
              file=sys.stderr)
        return 2

    if args.list_models:
        client = genai.Client()
        for m in client.models.list():
            actions = getattr(m, "supported_actions", None) or []
            if not actions or "generateContent" in actions:
                print(f"  {m.name}")
        return 0

    print(f"model: {MODEL}  (override with GEMINI_MODEL)")

    async with Glasses(args.url) as g:
        if not args.no_connect:
            if not await g.connect_glasses():
                print("could not connect to glasses", file=sys.stderr)
                return 1
        await g.claim()
        try:
            await run(
                g, args.goal,
                use_search=not args.no_search,
                use_maps=args.maps,
                max_turns=args.max_turns,
                save_images=not args.no_save,
            )
        finally:
            await g.release()
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(asyncio.run(main()))
    except KeyboardInterrupt:
        raise SystemExit(130)
