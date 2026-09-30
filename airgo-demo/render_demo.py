#!/usr/bin/env python3
"""
Turn a demo recording from the AirGo Demo app into an edited video.

    python3 render_demo.py demo_20260927_143000/            # -> demo_20260927_143000/demo.mp4
    python3 render_demo.py demo_... --no-transcribe         # skip Gemini transcription of the user
    python3 render_demo.py demo_... --video-offset 0.3      # nudge video vs. events (seconds)

Layout (1280x720):
  left 960x720   the glasses' camera, continuous
  right 320x720  what the AI is doing: current controls, the exact images it was sent
                 (with compass heading), and a status line (listening / thinking / speaking)
  captions       "AI: ..." and "YOU: ..." under the camera; badges for gestures and searches
Audio: AI voice + user voice + sound cues, each placed at its recorded time.

Needs the system ffmpeg (with libass and libx264). Transcription uses GEMINI_API_KEY from
the environment or ../android/local.properties.
"""
import argparse, base64, json, math, os, shutil, struct, subprocess, sys, urllib.request, wave
from pathlib import Path

W, H, VW = 1280, 720, 960          # canvas, camera width
PANEL_X = VW
THUMB_W, THUMB_H = 320, 240
THUMB_Y = 300

GESTURE_NAMES = {"SINGLE_TAP": "Tap", "DOUBLE_TAP": "Double tap", "FORWARD_SLIDE": "Slide forward",
                 "REVERSE_SLIDE": "Slide back", "WAKE_UP_WORD": '"Hey Solos"'}


def sh(cmd):
    r = subprocess.run(cmd, capture_output=True, text=True)
    if r.returncode != 0:
        sys.exit(f"command failed: {' '.join(map(str, cmd))[:300]}\n{r.stderr[-2000:]}")
    return r.stdout


def ts(sec):  # ASS time
    sec = max(0.0, sec)
    return f"{int(sec // 3600)}:{int(sec % 3600 // 60):02d}:{sec % 60:05.2f}"


def esc(text):
    return str(text).replace("\\", "/").replace("{", "(").replace("}", ")").replace("\n", " ")


def gemini_key():
    if os.environ.get("GEMINI_API_KEY"):
        return os.environ["GEMINI_API_KEY"]
    lp = Path(__file__).parent / "android" / "local.properties"
    if lp.exists():
        for line in lp.read_text().splitlines():
            if line.startswith("GEMINI_API_KEY="):
                return line.split("=", 1)[1].strip()
    return None


def transcribe(wav_path, key, model="gemini-flash-latest"):
    body = {"contents": [{"role": "user", "parts": [
        {"text": "Transcribe this speech verbatim, in its original language (usually French or English; the speaker is talking to a voice assistant in smart glasses). Output only the words, nothing else."},
        {"inlineData": {"mimeType": "audio/wav", "data": base64.b64encode(wav_path.read_bytes()).decode()}}]}],
        "generationConfig": {"thinkingConfig": {"thinkingLevel": "low"}}}
    req = urllib.request.Request(
        f"https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent",
        data=json.dumps(body).encode(), headers={"x-goog-api-key": key, "Content-Type": "application/json"})
    try:
        r = json.load(urllib.request.urlopen(req, timeout=60))
        return r["candidates"][0]["content"]["parts"][-1].get("text", "").strip()
    except Exception as e:
        print(f"  transcription failed for {wav_path.name}: {e}")
        return None


def wav_duration(p):
    try:
        with wave.open(str(p)) as w:
            return w.getnframes() / w.getframerate()
    except Exception:
        return 1.5


def beeps_track(sounds, total, path, rate=22050):
    """Recreate the phone's sound cues as a WAV track."""
    tones = {"wake": (1200, 0.12), "ack": (880, 0.09), "nothing": (220, 0.15), "shutter": (1500, 0.08)}
    n = int(total * rate) + rate
    buf = [0.0] * n
    for t, kind in sounds:
        f, d = tones.get(kind, (660, 0.1))
        start = int(t * rate)
        for i in range(int(d * rate)):
            if start + i < n:
                env = min(1.0, i / 200, (d * rate - i) / 200)
                buf[start + i] += 0.35 * env * math.sin(2 * math.pi * f * i / rate)
    with wave.open(str(path), "w") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(rate)
        w.writeframes(b"".join(struct.pack("<h", int(max(-1, min(1, x)) * 32767)) for x in buf))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("folder")
    ap.add_argument("--out", default=None)
    ap.add_argument("--no-transcribe", action="store_true")
    ap.add_argument("--video-offset", type=float, default=0.0)
    ap.add_argument("--ambient", action="store_true",
                    help="also mix the camera stream's own audio (noisy: camera-module mic, always on)")
    a = ap.parse_args()

    d = Path(a.folder).resolve()
    events = [json.loads(l) for l in (d / "events.jsonl").read_text().splitlines() if l.strip()]
    total = max(e["t"] for e in events) / 1000 + 1.0
    work = d / "_render"
    if work.exists():
        shutil.rmtree(work)
    work.mkdir()
    print(f"{len(events)} events, {total:.1f}s")

    # ---------------------------------------------------------------- timeline
    ai_lines, user_lines, badges, controls, status, thumbs = [], [], [], [], [], []
    sounds, audio_clips, videos = [], [], []
    open_ai = {}
    key = None if a.no_transcribe else gemini_key()
    cur_controls = None
    pending_video = None
    last_gesture = None

    for e in events:
        t, ty = e["t"] / 1000, e["type"]
        if ty == "video_start":
            pending_video = d / e["file"]
        elif ty == "video_sync" and pending_video is not None:
            if pending_video.exists() and pending_video.stat().st_size > 0:
                videos.append((e["video_t0"] / 1000 + a.video_offset, pending_video))
            pending_video = None
        elif ty == "ai_speech_start":
            open_ai[e["id"]] = (t, e.get("text", ""))
            if e.get("file") and (d / e["file"]).exists():
                audio_clips.append((t, d / e["file"], "ai"))
        elif ty == "ai_speech_end" and e["id"] in open_ai:
            s, text = open_ai.pop(e["id"])
            ai_lines.append((s, t, text))
            status.append((s, t, "AI SPEAKING"))
        elif ty == "listen_start":
            status.append((t, t + 1.2, "LISTENING - speak now"))
        elif ty == "user_speech":
            wav = d / e["file"]
            start = e.get("start", e["t"] - e["seconds"] * 1000) / 1000
            if wav.exists():
                audio_clips.append((start, wav, "user"))
            text = transcribe(wav, key) if key and wav.exists() else None
            user_lines.append((start, start + e["seconds"], text or "(speaking)"))
            status.append((start, start + e["seconds"], "LISTENING"))
        elif ty == "gemini":
            ms = e.get("ms", 0) / 1000
            status.append((t - ms, t, f"THINKING  (Gemini {ms:.1f}s)"))
        elif ty == "gesture":
            if e["gesture"] == "WAKE_UP_WORD":
                continue   # shown by the "wake" badge
            if last_gesture and last_gesture[0] > t - 0.4 and last_gesture[1] == e["gesture"]:
                continue   # same gesture reported twice
            last_gesture = (t, e["gesture"])
            name = GESTURE_NAMES.get(e["gesture"], e["gesture"])
            label = f"{name}  ->  {e['action']}" if e.get("action") else f"{name}  (nothing bound)"
            badges.append((t, t + 1.8, label))
        elif ty == "wake":
            who = GESTURE_NAMES.get(e.get('by'), e.get('by'))
            badges.append((t, t + 1.8, f"INTERRUPTION: {who}" if e.get("mid_task") else f"WAKE: {who}"))
        elif ty == "search":
            badges.append((t, t + 3.0, "Web search: " + "; ".join(e.get("queries", []))[:70]))
        elif ty == "sound":
            sounds.append((t, e.get("sound")))
        elif ty in ("bind", "bind_pop", "bind_release"):
            if cur_controls:
                controls.append((cur_controls[0], t, cur_controls[1]))
            if ty == "bind":
                m = e.get("mapping") or {}
                rows = [f"{GESTURE_NAMES.get(g, g)}: {act}" for g, act in m.items()]
                cur_controls = (t, "\\N".join(rows) or "(none)")
                badges.append((t, t + 2.5, "CONTROLS CHANGED"))
            elif ty == "bind_pop":
                m = e.get("now") or {}
                cur_controls = (t, "\\N".join(f"{GESTURE_NAMES.get(g, g)}: {x}" for g, x in m.items()) or "(none)")
            else:
                cur_controls = None
        elif ty == "frames_sent":
            files = [d / f for f in e.get("files", [])]
            labels = e.get("labels", [])
            step = 0.9 if len(files) > 1 else 4.0
            for i, f in enumerate(files):
                if f.exists():
                    lab = labels[i] if i < len(labels) else ""
                    thumbs.append((t + i * step, t + (i + 1) * step, f, f"{e.get('tool')}  {i + 1}/{len(files)}", lab))
    if cur_controls:
        controls.append((cur_controls[0], total, cur_controls[1]))
    for id_, (s, text) in open_ai.items():
        ai_lines.append((s, s + 3, text))

    # The last image sent stays on screen until the next one (at most 12 s).
    thumbs.sort(key=lambda x: x[0])
    for k in range(len(thumbs)):
        s0, e0, f0, t1, t2 = thumbs[k]
        nxt = thumbs[k + 1][0] if k + 1 < len(thumbs) else total
        thumbs[k] = (s0, max(e0, min(nxt, s0 + 12)), f0, t1, t2)

    # ---------------------------------------------------------------- subtitles (ASS)
    ass = [
        "[Script Info]", "ScriptType: v4.00+", f"PlayResX: {W}", f"PlayResY: {H}", "",
        "[V4+ Styles]",
        "Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, "
        "Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, "
        "Alignment, MarginL, MarginR, MarginV, Encoding",
        "Style: AI,Noto Sans,30,&H0000E6FF,&H00FFFFFF,&H00000000,&H90000000,1,0,0,0,100,100,0,0,3,2,0,2,40,360,40,1",
        "Style: USER,Noto Sans,30,&H00FFFFFF,&H00FFFFFF,&H00000000,&H90000000,1,0,0,0,100,100,0,0,3,2,0,2,40,360,95,1",
        "Style: BADGE,Noto Sans,34,&H00FFFFFF,&H00FFFFFF,&H00000000,&HC0B05A00,1,0,0,0,100,100,0,0,3,3,0,8,40,360,30,1",
        "Style: PANEL,Noto Sans,20,&H00FFFFFF,&H00FFFFFF,&H00000000,&H00000000,0,0,0,0,100,100,0,0,1,0,0,7,0,0,0,1",
        "Style: PTITLE,Noto Sans,17,&H00A0A0A0,&H00FFFFFF,&H00000000,&H00000000,1,0,0,0,100,100,0,0,1,0,0,7,0,0,0,1",
        "Style: STATUS,Noto Sans,22,&H0060FF60,&H00FFFFFF,&H00000000,&H00000000,1,0,0,0,100,100,0,0,1,0,0,7,0,0,0,1",
        "", "[Events]", "Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text",
    ]

    def ev(s, e, style, text, pos=None):
        tag = f"{{\\pos({pos[0]},{pos[1]})}}" if pos else ""
        ass.append(f"Dialogue: 0,{ts(s)},{ts(e)},{style},,0,0,0,,{tag}{text}")

    px = PANEL_X + 16
    ev(0, total, "PTITLE", "CONTROLS RIGHT NOW", (px, 20))
    ev(0, total, "PTITLE", "WHAT THE AI SEES (sent to Gemini)", (px, THUMB_Y - 26))
    ev(0, total, "PTITLE", "STATUS", (px, 600))
    for s, e_, text in controls:
        ev(s, e_, "PANEL", esc(text).replace("/N", "\\N"), (px, 46))
    for s, e_, _, title, lab in thumbs:
        ev(s, e_, "PANEL", f"{{\\fs16}}{esc(title)}\\N{esc(lab)[:60]}", (px, THUMB_Y + THUMB_H + 8))
    rows = {"LISTENING": 626, "THINKING": 652, "AI SPEAKING": 678}
    for s, e_, text in status:
        y = next((v for k, v in rows.items() if text.startswith(k)), 626)
        ev(s, e_, "STATUS", esc(text), (px, y))
    for s, e_, text in ai_lines:
        ev(s, max(e_, s + 1.2), "AI", "AI: " + esc(text))
    for s, e_, text in user_lines:
        ev(s, max(e_, s + 1.2), "USER", "YOU: " + esc(text))
    for s, e_, text in badges:
        ev(s, e_, "BADGE", esc(text))
    (work / "overlay.ass").write_text("\n".join(ass) + "\n")

    # ---------------------------------------------------------------- thumbnails track
    # concat needs every image in the same format and size: normalise to 320x240 JPEG.
    blank = work / "blank.jpg"
    sh(["ffmpeg", "-y", "-loglevel", "error", "-f", "lavfi", "-i", f"color=c=0x202020:s={THUMB_W}x{THUMB_H}",
        "-frames:v", "1", "-pix_fmt", "yuvj420p", str(blank)])
    norm = []
    for k, (s, e_, f, t1, t2) in enumerate(thumbs):
        g = work / f"thumb_{k:04d}.jpg"
        sh(["ffmpeg", "-y", "-loglevel", "error", "-i", str(f), "-vf",
            f"scale={THUMB_W}:{THUMB_H}:force_original_aspect_ratio=decrease,"
            f"pad={THUMB_W}:{THUMB_H}:(ow-iw)/2:(oh-ih)/2,format=yuvj420p", str(g)])
        norm.append((s, e_, g, t1, t2))
    thumbs = norm
    lst, cursor = [], 0.0
    for s, e_, f, _, _ in sorted(thumbs, key=lambda x: x[0]):
        if s > cursor:
            lst += [f"file '{blank}'", f"duration {s - cursor:.3f}"]
        dur = max(0.1, e_ - max(s, cursor))
        lst += [f"file '{f.resolve()}'", f"duration {dur:.3f}"]
        cursor = max(cursor, e_)
    if cursor < total:
        lst += [f"file '{blank}'", f"duration {total - cursor:.3f}"]
    lst.append(f"file '{blank}'")
    (work / "thumbs.txt").write_text("\n".join(lst) + "\n")

    # ---------------------------------------------------------------- audio
    beeps = work / "beeps.wav"
    beeps_track(sounds, total, beeps)

    # ---------------------------------------------------------------- ffmpeg graph
    inputs = ["-f", "lavfi", "-i", f"color=c=black:s={W}x{H}:r=30:d={total:.2f}",
              "-f", "concat", "-safe", "0", "-i", str(work / "thumbs.txt"),
              "-i", str(beeps)]
    idx = 3
    vid_idx = []
    for off, f in videos:
        inputs += ["-itsoffset", f"{off:.3f}", "-i", str(f)]
        vid_idx.append(idx); idx += 1
    aud_idx = []
    for s, f, vol in audio_clips:
        inputs += ["-i", str(f)]
        aud_idx.append((idx, s, vol)); idx += 1

    fc = []
    base = "[0:v]"
    for n, i in enumerate(vid_idx):
        fc.append(f"[{i}:v]scale={VW}:{H}:force_original_aspect_ratio=decrease,pad={VW}:{H}:(ow-iw)/2:(oh-ih)/2[cam{n}]")
        fc.append(f"{base}[cam{n}]overlay=0:0:eof_action=pass[b{n}]")
        base = f"[b{n}]"
    fc.append(f"[1:v]scale={THUMB_W}:{THUMB_H},setsar=1[th]")
    fc.append(f"{base}[th]overlay={PANEL_X}:{THUMB_Y}:shortest=0[withth]")
    ass_path = str(work / "overlay.ass").replace(":", "\\:").replace("'", "\\'")
    fc.append(f"[withth]subtitles='{ass_path}'[vout]")

    amix_in = ["[2:a]"]
    for n, (i, s, kind) in enumerate(aud_idx):
        ms = int(max(0, s) * 1000)
        # User voice (glasses HFP mic): cut rumble, light denoise, bring up to the AI's level.
        clean = ("highpass=f=90,afftdn=nf=-25,dynaudnorm=f=150:g=15,volume=1.1," if kind == "user"
                 else "volume=1.0,")
        fc.append(f"[{i}:a]aresample=44100,{clean}adelay={ms}|{ms}[a{n}]")
        amix_in.append(f"[a{n}]")
    for n, i in enumerate(vid_idx):   # the camera's own sound: off unless --ambient
        if a.ambient and "audio" in sh(["ffprobe", "-v", "error", "-show_entries", "stream=codec_type", "-of", "csv=p=0",
                          str(videos[n][1])]):
            fc.append(f"[{i}:a]aresample=44100,volume=0.35[va{n}]")
            amix_in.append(f"[va{n}]")
    fc.append(f"{''.join(amix_in)}amix=inputs={len(amix_in)}:normalize=0:duration=longest,alimiter=limit=0.89[aout]")

    out = Path(a.out) if a.out else d / "demo.mp4"
    cmd = ["ffmpeg", "-y", "-loglevel", "error"] + inputs + [
        "-filter_complex", ";".join(fc), "-map", "[vout]", "-map", "[aout]",
        "-t", f"{total:.2f}", "-c:v", "libx264", "-preset", "veryfast", "-crf", "22", "-pix_fmt", "yuv420p",
        "-c:a", "aac", "-b:a", "160k", str(out)]
    print(f"rendering: {len(videos)} video segment(s), {len(audio_clips)} voice clips, "
          f"{len(thumbs)} AI frames, {len(badges)} badges ...")
    sh(cmd)
    print(f"done -> {out}")


if __name__ == "__main__":
    main()
