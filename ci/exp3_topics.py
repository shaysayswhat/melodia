#!/usr/bin/env python3
"""Experiment 3b: which way of asking keeps the songwriter on topic, and how much
better is the 4B songwriter than the 1.7B one?"""
import json
import os
import re
import time
import urllib.request

URL = os.environ.get("ACE_URL", "http://127.0.0.1:8085")
OUT = os.environ.get("OUT", "exp-out")
os.makedirs(OUT, exist_ok=True)
MODELS = {"1.7B": "acestep-5Hz-lm-1.7B-Q8_0.gguf", "4B": "acestep-5Hz-lm-4B-Q5_K_M.gguf"}

TOPICS = [
    ("monsoon", "en", "missing home during the monsoon", "nostalgic", "pop", ["home", "rain", "monsoon", "miss"]),
    ("mother", "en", "my mother's birthday", "joyful", "acoustic", ["mother", "mom", "mum", "birthday", "mama"]),
    ("dhaka-rain", "bn", "the first rain in Dhaka", "romantic", "acoustic", ["brishti", "bristi", "dhaka", "megh", "বৃষ্টি", "ঢাকা", "মেঘ"]),
]
LANG = {"en": "English", "bn": "Bangla"}


def queries(topic, lang, mood, genre):
    a = "An" if mood[0] in "aeiou" else "A"
    return {
        "short": f"{a} {mood} {genre} song about {topic}",
        "app-long": (f"{a} {mood} {genre} song about {topic}, sung in {LANG[lang]} by a warm female alto singer. "
                     "Clear, simple, singable lyrics with verses and a catchy chorus that stay on this topic."),
        "topic-first": f"Lyrics about {topic}. {mood.capitalize()} {genre}, {LANG[lang]} vocals.",
    }


def call(path, body=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(URL + path, data=data, headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=900) as r:
        return json.loads(r.read())


def run(body):
    job = call("/lm", body)["id"]
    t0 = time.time()
    while call(f"/job?id={job}")["status"] == "running":
        time.sleep(1)
    res = call(f"/job?id={job}&result=1")
    r = res[0] if isinstance(res, list) else res
    return r.get("lyrics", ""), round(time.time() - t0)


rows = []
md = ["# Topic experiment", ""]
for model, file in MODELS.items():
    for key, lang, topic, mood, genre, words in TOPICS:
        for qname, q in queries(topic, lang, mood, genre).items():
            for seed in (11, 22):
                lyrics, secs = run({"caption": q, "lyrics": "", "duration": 120, "vocal_language": lang,
                                    "lm_mode": "inspire", "use_cot_caption": False, "lm_model": file, "lm_seed": seed})
                low = lyrics.lower()
                hits = sorted({w for w in words if w in low})
                lines = [l for l in lyrics.splitlines() if l.strip() and not l.strip().startswith("[")]
                rows.append({"model": model, "topic": key, "query": qname, "seed": seed, "seconds": secs,
                             "hits": hits, "lines": len(lines), "unique": len(set(lines)), "lyrics": lyrics})
                md += [f"## {model} · {key} · {qname} · seed {seed} · {secs}s · topic words {hits} · {len(set(lines))}/{len(lines)} unique",
                       "```", "\n".join(lines[:12]), "```", ""]
                print(md[-5], flush=True)
                with open(os.path.join(OUT, "topics.json"), "w") as f:
                    json.dump(rows, f, indent=1, ensure_ascii=False)
                with open(os.path.join(OUT, "topics.md"), "w") as f:
                    f.write("\n".join(md))
