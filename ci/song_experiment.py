#!/usr/bin/env python3
"""Experiment: do 60-second songs come out better with or without the songwriter
(and which size)? Same lyrics and style, English and Bangla. Writes MP3s plus
<name>-lyrics.txt to OUT for analyze_audio.py."""
import json
import os
import time
import urllib.request

URL = os.environ.get("ACE_URL", "http://127.0.0.1:8085")
OUT = os.environ.get("OUT", "exp-out")
os.makedirs(OUT, exist_ok=True)

EN = """[Verse 1]
Morning light upon the river
Carry me home tonight
Every road I ever wandered
Leads me back to you

[Chorus]
Sing it loud and sing it free
You are the song in me
Hold me close and let it be
You are the song in me

[Verse 2]
Rain is falling on the city
Softly on the window pane
Every drop is calling, calling
Bring me home again"""

BN = """[Verse 1]
বৃষ্টি নামে আমার শহরে
তোমার কথা মনে পড়ে
মেঘের ভেলায় ভেসে ভেসে
মন যে শুধু তোমায় খোঁজে

[Chorus]
ফিরে এসো, ফিরে এসো
এই মেঘলা দিনে ফিরে এসো
ভালোবেসে, ভালোবেসে
আমার গানে ফিরে এসো"""

CAPTION_EN = ("pop, happy, female alto vocal, warm, breathy tone, soft intimate delivery, tender emotional delivery, "
              "light vibrato, acoustic guitar, piano, close-mic vocal")
CAPTION_BN = ("bangla acoustic, romantic, female soprano vocal, sweet, clear tone, soft intimate delivery, "
              "tender emotional delivery, light vibrato, acoustic guitar, tabla, flute")

LM = {"0.6B": "acestep-5Hz-lm-0.6B-Q8_0.gguf", "1.7B": "acestep-5Hz-lm-1.7B-Q8_0.gguf"}


def post(path, body):
    req = urllib.request.Request(URL + path, data=json.dumps(body).encode(), headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=60) as r:
        return json.loads(r.read())


def get(path):
    with urllib.request.urlopen(URL + path, timeout=900) as r:
        return r.read(), r.headers.get("Content-Type", "")


def wait(job):
    t0 = time.time()
    while True:
        st = json.loads(get(f"/job?id={job}")[0])["status"]
        if st != "running":
            return st, time.time() - t0
        time.sleep(2)


def first_mp3(body, ctype):
    boundary = ctype.split("boundary=")[-1].encode()
    for part in body.split(b"--" + boundary):
        head, _, data = part.partition(b"\r\n\r\n")
        if b"audio/" in head:
            return data[:-2] if data.endswith(b"\r\n") else data
    return None


def song(name, caption, lyrics, lang, lm=None):
    req = {"caption": caption, "lyrics": lyrics, "duration": 60.0, "vocal_language": lang, "seed": 1234,
           "output_format": "mp3", "mp3_bitrate": 192, "use_cot_caption": False, "lm_mode": "generate"}
    info = {"name": name}
    if lm:
        req["lm_model"] = LM[lm]
        req["lm_seed"] = 99
        lm_job = post("/lm", req)["id"]
        st, secs = wait(lm_job)
        info["lm_seconds"] = round(secs)
        if st != "done":
            info["error"] = f"lm {st}"
            return info
        res = json.loads(get(f"/job?id={lm_job}&result=1")[0])
        req = res[0] if isinstance(res, list) else res
        info["plan"] = {k: req.get(k) for k in ("bpm", "keyscale", "duration", "timesignature")}
    job = post("/synth", req)["id"]
    st, secs = wait(job)
    info["synth_seconds"] = round(secs)
    if st != "done":
        info["error"] = f"synth {st}"
        return info
    body, ctype = get(f"/job?id={job}&result=1")
    mp3 = first_mp3(body, ctype)
    with open(os.path.join(OUT, name + ".mp3"), "wb") as f:
        f.write(mp3 or b"")
    with open(os.path.join(OUT, name + "-lyrics.txt"), "w", encoding="utf-8") as f:
        f.write(lyrics)
    return info


def main():
    results = []
    for lang, caption, lyrics in (("en", CAPTION_EN, EN), ("bn", CAPTION_BN, BN)):
        for lm in (None, "0.6B", "1.7B"):
            name = f"{lang}-{'dit-only' if lm is None else 'lm-' + lm}"
            print("making", name, flush=True)
            results.append(song(name, caption, lyrics, lang, lm))
            print(json.dumps(results[-1]), flush=True)
            with open(os.path.join(OUT, "songs.json"), "w") as f:
                json.dump(results, f, indent=1)


if __name__ == "__main__":
    main()
