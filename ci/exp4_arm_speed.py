#!/usr/bin/env python3
"""Experiment 4: how long does a song take on phone-class ARM cores?
Times a 30-second song (songwriter plan + singer) and a 60-second one."""
import json
import os
import sys
import time

sys.path.insert(0, os.path.dirname(__file__))
from song_experiment import CAPTION_EN, EN, song  # noqa: E402

OUT = os.environ.get("OUT", "exp-out")
SHORT = "\n".join(EN.split("\n\n")[:2])  # first verse and chorus

results = []
for name, lyrics, secs, lm in (("arm-30s-plan", SHORT, 30, "1.7B"), ("arm-30s-singer-only", SHORT, 30, None),
                               ("arm-60s-plan", EN, 60, "1.7B")):
    t0 = time.time()
    r = song(name, CAPTION_EN, lyrics, "en", lm, duration=secs)
    r["total_seconds"] = round(time.time() - t0)
    results.append(r)
    print(json.dumps(r), flush=True)
    with open(os.path.join(OUT, "speed.json"), "w") as f:
        json.dump(results, f, indent=1)
