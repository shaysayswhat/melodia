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
import unicodedata
from difflib import SequenceMatcher


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


def silence_ratio(path, total):
    r = run(["ffmpeg", "-hide_banner", "-i", path, "-af", "silencedetect=noise=-45dB:d=1", "-f", "null", "-"])
    quiet = sum(float(x) for x in re.findall(r"silence_duration: ([\d.]+)", r.stderr))
    return quiet / total if total > 0 else 0.0


def letters(text):
    """Lyric text without section tags, punctuation and spacing, for comparing across scripts."""
    text = re.sub(r"\[[^\]]*\]", " ", text)
    return "".join(ch for ch in text.lower() if unicodedata.category(ch)[0] in "LM")


def spectrogram(path, png):
    run(["ffmpeg", "-hide_banner", "-y", "-i", path, "-lavfi",
         "showspectrumpic=s=1200x500:legend=1:scale=log:fscale=log", png])


def transcribe(path):
    """Runs the recognizer in a child process so a native crash can't stop the report."""
    r = run([sys.executable, os.path.abspath(__file__), "--transcribe", path])
    try:
        return json.loads(r.stdout.strip().splitlines()[-1])
    except Exception:
        return {"error": (r.stderr or r.stdout or "no output").strip()[-300:]}


def transcribe_here(path):
    from faster_whisper import WhisperModel
    model = WhisperModel("small", device="cpu", compute_type="int8")
    segments, info = model.transcribe(path, beam_size=5, vad_filter=False)
    text = " ".join(s.text.strip() for s in segments).strip()
    print(json.dumps({"text": text, "language": info.language, "probability": info.language_probability}))


def words(text):
    return re.findall(r"[\w']+", text.lower())


def main():
    folder = sys.argv[1]
    lyrics_dir = sys.argv[2] if len(sys.argv) > 2 else folder
    mp3s = sorted(f for f in os.listdir(folder) if f.lower().endswith(".mp3"))
    model = True
    try:
        import faster_whisper  # noqa: F401
    except Exception as e:  # analysis is best-effort
        print("speech recognizer unavailable:", e)
        model = None

    lines = ["# Audio check", ""]
    report_path = os.path.join(folder, "report.md")
    for name in mp3s:
        path = os.path.join(folder, name)
        stem = os.path.splitext(name)[0]
        try:
            d = duration(path)
            mean, peak = loudness(path)
            spectrogram(path, os.path.join(folder, stem + ".png"))
            quiet = silence_ratio(path, d)
            lines += [f"## {name}", f"- length: {d:.1f} s, size {os.path.getsize(path) // 1024} KB",
                      f"- loudness: mean {mean} dB, peak {peak} dB" + ("  (SILENT?)" if mean is not None and mean < -45 else ""),
                      f"- silent parts: {quiet * 100:.0f}% of the song"]
            if model is not None:
                t = transcribe(path)
                heard = t.get("text", "")
                if "error" in t:
                    lines.append(f"- recognizer failed: {t['error']}")
                lines.append(f"- recognizer language: {t.get('language')} ({t.get('probability', 0):.2f})")
                lines.append(f"- heard: \"{heard}\"")
                prefix = re.sub(r"-?v?\d+$", "", stem)
                for cand in (prefix + "-lyrics.txt", stem + "-lyrics.txt"):
                    lp = os.path.join(lyrics_dir, cand)
                    if os.path.isfile(lp):
                        tags = ("verse", "chorus", "bridge", "intro", "outro", "pre", "soft", "powerful")
                        sent = [w for w in words(open(lp, encoding="utf-8").read()) if not w.isdigit() and w not in tags]
                        got = set(words(heard))
                        hit = sum(1 for w in set(sent) if w in got)
                        lines.append(f"- lyric words recognized: {hit}/{len(set(sent))}")
                        sim = SequenceMatcher(None, letters(open(lp, encoding="utf-8").read()), letters(heard)).ratio()
                        lines.append(f"- lyric text similarity: {sim:.2f}")
                        break
        except Exception as e:
            lines.append(f"- analysis error: {e!r}")
        lines.append("")
        with open(report_path, "w", encoding="utf-8") as f:
            f.write("\n".join(lines))
        print("\n".join(lines[-8:]), flush=True)


if __name__ == "__main__":
    if len(sys.argv) == 3 and sys.argv[1] == "--transcribe":
        transcribe_here(sys.argv[2])
    else:
        main()
