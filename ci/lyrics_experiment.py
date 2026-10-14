#!/usr/bin/env python3
"""Experiment: how should the app ask the songwriter for lyrics?
Compares request phrasings, temperatures and LM sizes; writes results to OUT."""
import json
import os
import time
import urllib.request

URL = os.environ.get("ACE_URL", "http://127.0.0.1:8085")
OUT = os.environ.get("OUT", "exp-out")
os.makedirs(OUT, exist_ok=True)

LM_SMALL = "acestep-5Hz-lm-0.6B-Q8_0.gguf"
LM_MID = "acestep-5Hz-lm-1.7B-Q8_0.gguf"


def post(path, body):
    req = urllib.request.Request(URL + path, data=json.dumps(body).encode(), headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=60) as r:
        return json.loads(r.read())


def get(path):
    with urllib.request.urlopen(URL + path, timeout=600) as r:
        return r.read()


def run(body):
    job = post("/lm", body)["id"]
    t0 = time.time()
    while True:
        st = json.loads(get(f"/job?id={job}"))["status"]
        if st != "running":
            break
        time.sleep(1)
    secs = time.time() - t0
    if st != "done":
        return {"status": st, "seconds": secs}
    res = json.loads(get(f"/job?id={job}&result=1"))
    r = res[0] if isinstance(res, list) else res
    return {"status": st, "seconds": round(secs, 1), "lyrics": r.get("lyrics", ""), "caption": r.get("caption", ""),
            "bpm": r.get("bpm"), "keyscale": r.get("keyscale"), "duration": r.get("duration"),
            "vocal_language": r.get("vocal_language")}


EN_TAGS = "pop, song about missing home during the monsoon"
EN_SENTENCE = "A heartfelt pop song about missing home during the monsoon, sung in English, with simple, clear, rhyming lyrics."
BN_SENTENCE = "A soft Bangla acoustic song about the first monsoon rain in Dhaka and missing an old friend, sung in Bengali."

variants = []
for lm in (LM_SMALL, LM_MID):
    for name, caption, lang in (("en-tags", EN_TAGS, "en"), ("en-sentence", EN_SENTENCE, "en"), ("bn-sentence", BN_SENTENCE, "bn")):
        for temp in (0.85, 0.6):
            for seed in (11, 22):
                variants.append({"lm": lm, "name": name, "temp": temp, "seed": seed,
                                 "body": {"caption": caption, "lyrics": "", "duration": 90, "vocal_language": lang,
                                          "lm_mode": "inspire", "use_cot_caption": False, "lm_model": lm,
                                          "lm_temperature": temp, "lm_seed": seed}})

results = []
md = ["# Songwriter experiment", ""]
for v in variants:
    r = run(v["body"])
    r.update({k: v[k] for k in ("lm", "name", "temp", "seed")})
    results.append(r)
    head = f"## {v['lm'].split('-lm-')[1].split('-')[0]} · {v['name']} · temp {v['temp']} · seed {v['seed']} · {r.get('seconds')}s"
    md += [head, f"caption: {r.get('caption', '')[:300]}", f"bpm {r.get('bpm')} · {r.get('keyscale')} · lang {r.get('vocal_language')}",
           "", "```", (r.get("lyrics") or r.get("status", ""))[:1500], "```", ""]
    print(head, flush=True)
    with open(os.path.join(OUT, "results.json"), "w") as f:
        json.dump(results, f, indent=1, ensure_ascii=False)
    with open(os.path.join(OUT, "lyrics.md"), "w") as f:
        f.write("\n".join(md))
