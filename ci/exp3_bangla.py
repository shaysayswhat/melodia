#!/usr/bin/env python3
"""Experiment 3a: does the singer pronounce Bangla better from Bengali script or from
Latin-letter (romanized) Bangla? Same song, songwriter plan on and off."""
import json
import os
import sys

sys.path.insert(0, os.path.dirname(__file__))
from song_experiment import BN, CAPTION_BN, song  # noqa: E402

OUT = os.environ.get("OUT", "exp-out")
ROMAN = """[Verse 1]
Brishti name amar shohore
Tomar kotha mone pore
Megher bhelay bhese bhese
Mon je shudhu tomay khoje

[Chorus]
Phire esho, phire esho
Ei meghla dine phire esho
Bhalobese, bhalobese
Amar gaane phire esho"""

results = []
for name, lyrics, lm in (("bn-script-lm", BN, "1.7B"), ("bn-roman-lm", ROMAN, "1.7B"), ("bn-roman-dit", ROMAN, None)):
    print("making", name, flush=True)
    results.append(song(name, CAPTION_BN, lyrics, "bn", lm))
    # The recognizer writes Bangla in Bengali script, so compare every version with the script lyrics.
    with open(os.path.join(OUT, name + "-lyrics.txt"), "w", encoding="utf-8") as f:
        f.write(BN)
    print(json.dumps(results[-1]), flush=True)
with open(os.path.join(OUT, "songs.json"), "w") as f:
    json.dump(results, f, indent=1)
