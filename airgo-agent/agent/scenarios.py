"""
Scripted demos - no LLM, no API key, no network.

These exist so you can put the glasses on and feel the remapping within a minute of the
relay working. Every scenario here is a fixed script; the LLM agent in agent.py does the
same thing but decides the bindings itself.

Run one with:   python run_scenario.py --url ws://PHONE_IP:8765 --demo pour_over

Design rule every scenario follows: the controls CHANGE between steps, and the change is
always spoken. A demo where the mapping is constant looks like a config file. A demo
where tap means three different things in three minutes looks like magic.
"""

from __future__ import annotations

import asyncio
import time
from dataclasses import dataclass, field

from glasses import Glasses


@dataclass
class Step:
    """One beat of a scripted task."""

    key: str
    say: str
    bind: dict[str, str]
    # Spoken right after binding. Say what the controls do, in one breath.
    hint: str = ""
    # action name -> where to go. "next"/"prev"/"done" or another step key.
    on: dict[str, str] = field(default_factory=dict)
    # Capture a frame on entry and report its size (stands in for a vision call).
    look: str | None = None
    # Seconds to count down before auto-advancing. Gestures still work during it.
    timer: int | None = None


@dataclass
class Scenario:
    name: str
    blurb: str
    steps: list[Step]


# ---------------------------------------------------------------------------------
# 0. Gesture lab - run this FIRST
# ---------------------------------------------------------------------------------
# Not a task, a hardware test. Proves three things in ninety seconds: gestures arrive,
# bindings apply, and the same physical gesture can mean something different a moment
# later. If this does not work nothing else will.

GESTURE_LAB = Scenario(
    name="gesture_lab",
    blurb="Hardware check. Same gestures, three different meanings.",
    steps=[
        Step(
            key="colors",
            say="Gesture lab. Round one: colours.",
            bind={
                "SINGLE_TAP": "red",
                "DOUBLE_TAP": "blue",
                "FORWARD_SLIDE": "green",
                "REVERSE_SLIDE": "next",
            },
            hint="Tap for red, double tap for blue, slide forward for green. "
                 "Slide back when you're done.",
            on={"red": "colors", "blue": "colors", "green": "colors", "next": "next"},
        ),
        Step(
            key="animals",
            say="Round two. Same gestures, new meanings.",
            bind={
                "SINGLE_TAP": "cat",
                "DOUBLE_TAP": "dog",
                "FORWARD_SLIDE": "horse",
                "REVERSE_SLIDE": "next",
            },
            hint="Now tap is cat, double tap is dog, slide forward is horse. "
                 "Slide back to continue.",
            on={"cat": "animals", "dog": "animals", "horse": "animals", "next": "next"},
        ),
        Step(
            key="camera",
            say="Round three. Tap now takes a picture.",
            bind={"SINGLE_TAP": "shoot", "REVERSE_SLIDE": "next"},
            hint="Tap to capture. Slide back to finish.",
            on={"shoot": "camera", "next": "done"},
        ),
    ],
)


# ---------------------------------------------------------------------------------
# 1. Pour-over coffee - the best all-round demo
# ---------------------------------------------------------------------------------
# Needs almost no props, everyone understands it, hands are genuinely busy, and the
# control surface has an honest reason to change: during the bloom you want a timer,
# during the pour you want nothing, at the end you want a judgement call.

POUR_OVER = Scenario(
    name="pour_over",
    blurb="Brew coffee. Controls change per phase - timer, then pour, then taste.",
    steps=[
        Step(
            key="setup",
            say="Pour over coffee. Weigh out thirty grams of coffee, medium grind.",
            bind={"SINGLE_TAP": "next", "DOUBLE_TAP": "check"},
            hint="Tap when the grounds are ready. Double tap if you want me to look at the grind.",
            on={"next": "next", "check": "setup"},
        ),
        Step(
            key="rinse",
            say="Rinse the filter with hot water, then tip the water out.",
            bind={"SINGLE_TAP": "next", "REVERSE_SLIDE": "prev"},
            hint="Tap when that's done. Slide back to hear the last step again.",
            on={"next": "next", "prev": "prev"},
        ),
        Step(
            key="bloom",
            say="Pour sixty grams of water, just enough to wet the grounds. Blooming for forty seconds.",
            bind={"SINGLE_TAP": "skip", "DOUBLE_TAP": "add"},
            hint="I'm timing it. Tap to skip ahead, double tap to add ten seconds.",
            on={"skip": "next", "add": "bloom", "timeout": "next"},
            timer=40,
        ),
        Step(
            key="pour",
            say="Now pour slowly in circles up to four hundred and fifty grams. Take about two minutes.",
            bind={"SINGLE_TAP": "next", "DOUBLE_TAP": "check"},
            hint="Tap when you've finished pouring. Double tap and I'll look at the water level.",
            on={"next": "next", "check": "pour"},
        ),
        Step(
            key="taste",
            say="Let it drain, then taste it. How is it?",
            bind={"SINGLE_TAP": "good", "DOUBLE_TAP": "bitter", "FORWARD_SLIDE": "sour"},
            hint="Tap if it's good. Double tap if it's bitter. Slide forward if it's sour.",
            on={"good": "done", "bitter": "bitter", "sour": "sour"},
        ),
        Step(
            key="bitter",
            say="Bitter means over-extracted. Go coarser on the grind, or pour a little faster next time.",
            bind={"SINGLE_TAP": "done"},
            hint="Tap to finish.",
            on={"done": "done"},
        ),
        Step(
            key="sour",
            say="Sour means under-extracted. Go finer, or pour more slowly next time.",
            bind={"SINGLE_TAP": "done"},
            hint="Tap to finish.",
            on={"done": "done"},
        ),
    ],
)


# ---------------------------------------------------------------------------------
# 2. Bike brake adjustment - the strongest "hands are busy" story
# ---------------------------------------------------------------------------------

BIKE_BRAKE = Scenario(
    name="bike_brake",
    blurb="Adjust bike brakes. Camera checks mid-task, controls change per step.",
    steps=[
        Step(
            key="find",
            say="Brake adjustment. Find the barrel adjuster where the cable enters the brake lever.",
            bind={"SINGLE_TAP": "look", "FORWARD_SLIDE": "next"},
            hint="Tap and I'll look at what's in front of you. Slide forward when you've found it.",
            on={"look": "find", "next": "next"},
        ),
        Step(
            key="squeeze",
            say="Squeeze the brake lever. Tell me how far it travels before it bites.",
            bind={
                "SINGLE_TAP": "too_far",
                "DOUBLE_TAP": "too_tight",
                "FORWARD_SLIDE": "just_right",
            },
            hint="Tap if it goes almost to the bar. Double tap if it grabs immediately. "
                 "Slide forward if it feels right.",
            on={"too_far": "tighten", "too_tight": "loosen", "just_right": "pads"},
        ),
        Step(
            key="tighten",
            say="Turn the barrel adjuster counter-clockwise, one full turn. That takes up cable slack.",
            bind={"SINGLE_TAP": "again", "FORWARD_SLIDE": "recheck"},
            hint="Tap to hear that again. Slide forward and we'll re-test the lever.",
            on={"again": "tighten", "recheck": "squeeze"},
        ),
        Step(
            key="loosen",
            say="Turn the barrel adjuster clockwise, half a turn, to give the cable some slack.",
            bind={"SINGLE_TAP": "again", "FORWARD_SLIDE": "recheck"},
            hint="Tap to repeat. Slide forward to re-test.",
            on={"again": "loosen", "recheck": "squeeze"},
        ),
        Step(
            key="pads",
            say="Good. Now look at the pads and check they hit the rim squarely, not the tyre.",
            bind={"SINGLE_TAP": "look", "FORWARD_SLIDE": "done", "REVERSE_SLIDE": "prev"},
            hint="Tap and I'll take a look. Slide forward when they're aligned.",
            on={"look": "pads", "done": "done", "prev": "squeeze"},
        ),
    ],
)


# ---------------------------------------------------------------------------------
# 3. Photo triage - a different binding SHAPE
# ---------------------------------------------------------------------------------
# Worth including because the mapping is not next/previous. It shows the agent choosing
# a verb set that fits the task rather than a generic remote control.

PHOTO_TRIAGE = Scenario(
    name="photo_triage",
    blurb="Sort through shots. Keep, bin, flag - a non-linear binding shape.",
    steps=[
        Step(
            key="intro",
            say="Photo triage. I'll take a shot, then you decide what to do with it.",
            bind={"SINGLE_TAP": "next"},
            hint="Tap to take the first one.",
            on={"next": "next"},
        ),
        Step(
            key="judge",
            say="Here we go.",
            bind={
                "SINGLE_TAP": "keep",
                "DOUBLE_TAP": "bin",
                "FORWARD_SLIDE": "flag",
                "REVERSE_SLIDE": "stop",
            },
            hint="Tap to keep, double tap to bin it, slide forward to flag it for later. "
                 "Slide back to stop.",
            on={"keep": "judge", "bin": "judge", "flag": "judge", "stop": "done"},
            look="quick",
        ),
    ],
)


# ---------------------------------------------------------------------------------
# 4. Booth guide - checkpoint navigation, no GPS
# ---------------------------------------------------------------------------------
# The venue demo, scoped honestly: the glasses have no position, so the user stops,
# points at a sign, and gets a bearing. Works in the hackathon room using other teams'
# tables as booths.

BOOTH_GUIDE = Scenario(
    name="booth_guide",
    blurb="Find a stand. Stop, point at a sign, get a bearing. No GPS needed.",
    steps=[
        Step(
            key="start",
            say="Tell me where you want to go by pointing me at signs as we walk. "
                "Point at the nearest one and tap.",
            bind={"SINGLE_TAP": "fix", "DOUBLE_TAP": "lost"},
            hint="Tap when a sign is in view. Double tap if you can't see one.",
            on={"fix": "next", "lost": "lost"},
            look="detailed",
        ),
        Step(
            key="bearing",
            say="Got it. Your target is two rows to the right. Walk to the end of this aisle.",
            bind={"SINGLE_TAP": "arrived", "DOUBLE_TAP": "again", "REVERSE_SLIDE": "recheck"},
            hint="Tap when you get there. Double tap to hear that again. "
                 "Slide back and I'll look around.",
            on={"arrived": "done", "again": "bearing", "recheck": "start"},
        ),
        Step(
            key="lost",
            say="No problem. Turn slowly until you can see any sign or stand number, then tap.",
            bind={"SINGLE_TAP": "fix"},
            hint="Tap when something readable is in front of you.",
            on={"fix": "bearing"},
            look="detailed",
        ),
    ],
)


ALL: dict[str, Scenario] = {
    s.name: s
    for s in [GESTURE_LAB, POUR_OVER, BIKE_BRAKE, PHOTO_TRIAGE, BOOTH_GUIDE]
}


# ---------------------------------------------------------------------------------
# Runner
# ---------------------------------------------------------------------------------


async def run(g: Glasses, scenario: Scenario, save_images: bool = True) -> None:
    """Walk a scenario. Handles binding, announcing, timers and camera on the user's behalf."""
    by_key = {s.key: s for s in scenario.steps}
    order = [s.key for s in scenario.steps]
    idx = 0

    await g.speak(f"Starting {scenario.name.replace('_', ' ')}.", flush=True)
    await asyncio.sleep(1.5)

    while 0 <= idx < len(order):
        step = by_key[order[idx]]
        g.drain_actions()  # a stale tap must not drive the new step

        await g.speak(step.say, flush=True)

        if step.look:
            await _capture(g, step.look, save_images)

        # Bind, then immediately say what the controls now do. Never one without the other.
        await g.bind(step.key, step.bind)
        if step.hint:
            await asyncio.sleep(0.3)
            await g.speak(step.hint)

        # Stay on this step until something that actually moves us happens. A camera
        # action is not one of those - re-announcing the whole instruction after every
        # photo gets tiresome fast.
        while True:
            act = await _await_step(g, step)
            if act is None:
                target = step.on.get("timeout", "next")
                break
            if act.action in ("look", "check", "shoot"):
                await _capture(g, "quick" if act.action == "shoot" else "detailed", save_images)
                await g.speak("Got it.")
                continue
            target = step.on.get(act.action, "next")
            break

        if target == "done":
            break
        if target == "next":
            idx += 1
        elif target == "prev":
            idx = max(0, idx - 1)
        elif target in by_key:
            idx = order.index(target)
        else:
            idx += 1

    await g.speak("All done. Your controls are back to normal.", flush=True)


async def _await_step(g: Glasses, step: Step):
    """Wait for an action, running a spoken countdown if the step has a timer."""
    if step.timer is None:
        return await g.wait_action(timeout=300)

    deadline = time.time() + step.timer
    spoken: set[int] = set()
    while True:
        left = deadline - time.time()
        if left <= 0:
            return None
        # Call out the last few seconds so the timer is audible, not silent.
        whole = int(left)
        if whole in (10, 5, 3, 2, 1) and whole not in spoken:
            spoken.add(whole)
            await g.speak(str(whole) if whole <= 3 else f"{whole} seconds")
        act = await g.wait_action(timeout=min(0.5, max(0.05, left)))
        if act is not None:
            return act


async def _capture(g: Glasses, detail: str, save: bool) -> None:
    data = await g.look(detail=detail)
    if data and save:
        name = f"capture_{int(time.time())}.jpg"
        with open(name, "wb") as fh:
            fh.write(data)
        print(f"  saved {name} ({len(data)/1024:.0f} kB)")
