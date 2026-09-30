# AirGo Agent: an AI that rewrites your glasses' controls

A hands-free assistant for **Solos AirGo V2** smart glasses (camera, mic, speakers, touchpad,
e-compass, no screen). Built at the SILMO Paris 2026 hackathon.

**The idea:** for each step of a task, the AI decides what the glasses' physical controls
mean, **says the new mapping out loud**, and gives the controls back when the task is done.

> "Slide forward for the next step. Tap if you want me to look."
> …two minutes later, the same tap: "Now tap adds thirty seconds."

It's not a voice assistant with buttons. It's a control surface that fits the task and gets
thrown away afterwards. The agent also sees through the glasses' camera (live video),
knows which way your head points (compass), and looks up real-world specs on the web.

---

## What it does

| Capability | How |
|---|---|
| **Wake it** | Say **"Hey Solos"** (the glasses' own wake word) or **double-tap** the temple. A beep means "speak now". |
| **Talk to it** | Your voice is recorded from the **glasses' mic** (Bluetooth HFP, noise-suppressed) and sent to Gemini **as raw audio**, with no speech-to-text step. English and French, switchable mid-task. |
| **It talks back** | Android text-to-speech, played through the glasses as normal Bluetooth audio. |
| **Rebindable controls** | Tap / double-tap / slide forward / slide back get task-specific meanings, announced out loud, at most 3 at a time. A short **tick** confirms a tap that did something; a **low tone** means that tap does nothing right now. |
| **It sees** | **Live view:** the glasses stream H.264 over Wi-Fi (RTSP); FFmpeg on the phone turns it into ~2 JPEG frames/s. `look()` returns the newest frame instantly. |
| **It looks around** | `look_around`: you turn your head slowly; the agent gets several frames, **each tagged with the compass heading it was filmed at**, and can answer "booth 42 is behind you on the right", then **steer your head** there. |
| **It knows direction** | E-compass heading + a phone-side "left… a little left… stop" steering loop (too fast for the LLM, so it runs locally). |
| **It researches** | Gemini's built-in Google Search and URL reading (read a model number with the camera, then search the exact spec). |
| **Survives the real world** | Auto-reconnects after Bluetooth drops, survives screen rotation, runs as a foreground service on battery, stretches the glasses' 3-minute auto power-off while connected. |

---

## Architecture

Everything runs **on the phone** (Android, Kotlin). There's no laptop and no server of our own.

```
 GLASSES (Solos AirGo V2)               PHONE (this app)                         CLOUD
 ┌───────────────────────┐   BLE        ┌──────────────────────────────┐  HTTPS ┌─────────┐
 │ touchpad, "Hey Solos" ├────────────► │ GestureBinder (rebinding)    │        │ Gemini  │
 │ e-compass             ├────────────► │ HeadTracker (heading + steer)│◄──────►│ (Flash, │
 │ camera ── RTSP video  ├──Wi-Fi─────► │ LiveVision (FFmpeg → frames) │        │ thinking│
 │        └─ photos/clips├──Wi-Fi/BLE─► │ GlassesRelay (SDK wrapper)   │        │ = low)  │
 │ mic (HFP)             ├──Bluetooth─► │ VoiceCapture (raw WAV)       │        │ + Google│
 │ speakers ◄────────────┼──A2DP─────── │ TTS                          │        │ Search  │
 └───────────────────────┘              │ GeminiAgent (tool loop)      │        └─────────┘
                                        │ Session (lives past the UI)  │
                                        └──────────────────────────────┘
```

### Key files (`android/app/src/main/java/com/solos/relay/`)

| File | Role |
|---|---|
| `GeminiAgent.kt` | The agent loop: Gemini REST `generateContent` with function calling. Tools: `speak`, `listen`, `look`, `look_around`, `bind_gestures`, `push/pop_gestures`, `await_gesture`, `unbind_gestures`, `heading`, `steer_to`, `set_language`, `get_battery`, plus Gemini's own `googleSearch` / `urlContext`. |
| `Prompt.kt` | System prompt: "don't assume, look or ask", always announce rebinds, at most 3 controls, wayfinding with map and compass, language rules. |
| `GestureBinder.kt` | The remapping core. One *volatile* firmware call disables the glasses' built-in gesture actions, and an in-memory **stack** of bindings says what each gesture means now. Rebinding is instant and free. Volatile means that if the app dies, a reboot restores the user's glasses. |
| `GlassesRelay.kt` | Solos SDK wrapper: connect / auto-reconnect, camera (photo, video clip, V1/V2 config), Wi-Fi (glasses hotspot or joining a network), compass, "Hey Solos" (voice commands in CUSTOM mode), auto power-off, sounds. |
| `LiveVision.kt` | Starts the glasses' RTSP stream (`rtsp://<glasses>:554/live1`) and runs FFmpeg to produce a rolling 20 s buffer of compass-tagged frames. |
| `VoiceCapture.kt` | Records one utterance from the glasses' mic over HFP (falls back to the phone mic), energy-based end-of-speech, returns WAV. |
| `Heading.kt` | E-compass reader with calibration or interference flags, a 10 s history (to tag video frames with the heading **when filmed**), and the local steering loop. |
| `Session.kt` | Process-wide state: glasses, agent, live view, log. The Activity is only a view, so rotating the phone doesn't kill the session. |
| `KeepAliveService.kt` | Foreground service with wake and Wi-Fi locks, so Android doesn't throttle Bluetooth or Wi-Fi on battery. |
| `MainActivity.kt` | Minimal Compose UI: Controls tab (connect, Wi-Fi, compass, live view, language, wake toggle) and a Log tab. |
| `SolosKey.kt` | Checks the Solos SDK licence at startup and explains its errors in plain words. |
| `RelayServer.kt` | Optional WebSocket relay (port 8765) so the Python agent in `agent/` can drive the glasses from a laptop during development. |

---

## Measured on real hardware

| What | Result |
|---|---|
| Gemini turn, default thinking | ~9 s |
| Gemini turn, `thinkingLevel: low` (used) | **~1.5 s**, same tool-calling behaviour |
| Photo over Bluetooth | ~6 s (≈ 8 KB/s) |
| Photo over Wi-Fi | ~2.5 s (mostly fixed overhead; raw Wi-Fi is ~400 KB/s) |
| 6 s video clip (record, fetch, download) | ~14 s before Gemini |
| **Live view** frame rate / start-up | **~1.8 fps**, stream up in ~1.5 s |
| `look()` with live view | **instant** (newest frame) |
| Gemini on a 5 s MP4 | ~5 s, ~520 prompt tokens |
| Gemini on spoken audio (French) | ~1.8 s |

---

## Hardware lessons (the non-obvious ones)

- **Solos SDK keys are bound to an app ID.** The SDK sends `X-API-KEY: <key>.<applicationId>`; the key must be activated for that package name.
- **The camera is a separate Bluetooth device.** Use `reconnectCamera()` first, and pick V1 vs V2 photo configs with `supports(...)`, not by trial.
- **Wi-Fi photos need the file-server password** (`fileServerPassword`, factory default `solos`). Otherwise it's `JSch Auth fail`.
- **The SDK photo stream isn't supported on AirGo V2.** It silently sends nothing, so we use the RTSP video stream instead.
- **SDK-controllable LEDs face the wearer.** The outward LED only lights through the camera's own `isLEDIndicationEnabled`.
- **If the app dies mid-stream, the camera keeps streaming** and every photo times out, so we stop stale streams on connect.
- **Two apps can't share the glasses.** Force-stop the official Solos app while testing.
- **Glasses auto power-off defaults to 3 minutes.** We stretch it to 60 while connected.
- **Compass indoors is unreliable.** Every reading carries calibration or interference state, and the agent is told to fall back to the camera.
- **Phone hotspot beats the glasses' hotspot:** the glasses join the phone's hotspot, and the phone keeps its mobile data for Gemini.

---

## Build and run

1. Copy `android/local.properties.example` to `android/local.properties` and fill in:
   `SOLOS_API_KEY`, `SOLOS_APP_ID` (the package your key is activated for, default `com.solos.relay`),
   `GEMINI_API_KEY`, `GEMINI_MODEL` (e.g. `gemini-flash-latest`), `GEMINI_THINKING=low`.
2. Put the SDK binaries in `android/app/libs/`: `SolosAirGoSDK-v5.9.0.aar`, `opus.aar`, `ffmpeg-kit.aar`
   (from the Solos release package; not in this repo).
3. Build with Android Studio (JDK 17+; the build targets arm64 only) and run on a **physical phone**.
4. In the app: **Scan**, connect, then optionally join Wi-Fi (for live view). Say "Hey Solos".

## Status and known gaps

- Built and iterated live at the hackathon. Many pieces were verified on the glasses (numbers above); the newest tweaks (tap sounds, compass-to-frame sync, `LATENCY_MS = 700` estimate) are not yet measured.
- No position tracking exists on the hardware. Wayfinding is checkpoint-style: read a sign or map, get a heading, walk to a landmark.
- Live view and video need Wi-Fi; without it the agent falls back to Bluetooth photos.
- `agent/` holds the older Python version (laptop relay mode). Its prompt is not in sync with `Prompt.kt`.
