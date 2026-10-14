#!/usr/bin/env python3
"""CI helper: describes every MP3 in a folder so a person (or Claude) can judge it
without listening: length, loudness, a spectrogram picture, and what a speech
recognizer hears in the vocals compared with the lyrics that were sent.

Usage: analyze_audio.py FOLDER [LYRICS_FOLDER]
Writes FOLDER/report.md and FOLDER/<name>.png.
"""
import json
import os
import re
import subprocess
import sys


def run(cmd):
    return subprocess.run(cmd, capture_output=True, text=True)


def duration(path):
    r = run(["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "json", path])
    try:
        return float(json.loads(r.stdout)["format"]["duration"])
    except Exception:
        return 0.0


def loudness(path):
    r = run(["ffmpeg", "-hide_banner", "-i", path, "-af", "volumedetect", "-f", "null", "-"])
    mean = re.search(r"mean_volume: (-?[\d.]+) dB", r.stderr)
    peak = re.search(r"max_volume: (-?[\d.]+) dB", r.stderr)
    return (float(mean.group(1)) if mean else None, float(peak.group(1)) if peak else None)


def spectrogram(path, png):
    run(["ffmpeg", "-hide_banner", "-y", "-i", path, "-lavfi",
         "showspectrumpic=s=1200x500:legend=1:scale=log:fscale=log", png])


def words(text):
    return re.findall(r"[\w']+", text.lower())


def main():
    folder = sys.argv[1]
    lyrics_dir = sys.argv[2] if len(sys.argv) > 2 else folder
    mp3s = sorted(f for f in os.listdir(folder) if f.lower().endswith(".mp3"))
    model = None
    try:
        from faster_whisper import WhisperModel
        model = WhisperModel("small", device="cpu", compute_type="int8")
    except Exception as e:  # analysis is best-effort
        print("speech recognizer unavailable:", e)

    lines = ["# Audio check", ""]
    for name in mp3s:
        path = os.path.join(folder, name)
        stem = os.path.splitext(name)[0]
        d = duration(path)
        mean, peak = loudness(path)
        spectrogram(path, os.path.join(folder, stem + ".png"))
        lines += [f"## {name}", f"- length: {d:.1f} s, size {os.path.getsize(path) // 1024} KB",
                  f"- loudness: mean {mean} dB, peak {peak} dB" + ("  (SILENT?)" if mean is not None and mean < -45 else "")]
        if model is not None:
            segments, info = model.transcribe(path, beam_size=5, vad_filter=False)
            heard = " ".join(s.text.strip() for s in segments).strip()
            lines.append(f"- recognizer language: {info.language} ({info.language_probability:.2f})")
            lines.append(f"- heard: \"{heard}\"")
            prefix = re.sub(r"-?v?\d+$", "", stem)
            for cand in (prefix + "-lyrics.txt", stem + "-lyrics.txt"):
                lp = os.path.join(lyrics_dir, cand)
                if os.path.isfile(lp):
                    sent = [w for w in words(open(lp, encoding="utf-8").read()) if not w.isdigit()]
                    sent = [w for w in sent if w not in ("verse", "chorus", "bridge", "intro", "outro", "pre")]
                    got = set(words(heard))
                    hit = sum(1 for w in set(sent) if w in got)
                    lines.append(f"- lyric words recognized: {hit}/{len(set(sent))}")
                    break
        lines.append("")
    report = "\n".join(lines)
    open(os.path.join(folder, "report.md"), "w", encoding="utf-8").write(report)
    print(report)


if __name__ == "__main__":
    main()
