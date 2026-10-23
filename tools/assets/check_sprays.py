"""Checks the sprays (VIEWMODEL_SPEC section 29): data/blockland/craft/sprays.json against the textures and the language
files. Every spray: a 256 x 256 RGBA texture with a transparent border of at least MARGIN px, soft edges (some
partly transparent pixels), real content (not empty, not a full square); its name in zh_cn and en_us. Limits sane.

python -I tools/assets/check_sprays.py     (exit code 1 on failure)"""
import json
import os
import sys

import numpy as np
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
RES = os.path.join(HERE, "..", "..", "mod", "src", "main", "resources")
SPRAYS_JSON = os.path.join(RES, "data", "blockland", "craft", "sprays.json")
ASSETS = os.path.join(RES, "assets", "blockland")
LANGS = ("zh_cn", "en_us")
SIZE = 256
MARGIN = 4
MAX_SPRAYS = 8                 # Build.MAX_SPRAYS (the wire format)


def check():
    errors, notes = [], []
    data = json.load(open(SPRAYS_JSON, encoding="utf-8"))
    limits = data.get("limits", {})
    per, lo, hi = limits.get("perWeapon", 0), limits.get("minSize", 0), limits.get("maxSize", 0)
    if not (0 < per <= MAX_SPRAYS):
        errors.append(f"limits.perWeapon {per} not in 1..{MAX_SPRAYS}")
    if not (0 < lo < hi <= 0.2):
        errors.append(f"limits sizes {lo}..{hi} m are not sane")
    langs = {name: json.load(open(os.path.join(ASSETS, "lang", f"{name}.json"), encoding="utf-8")) for name in LANGS}
    sprays = data.get("sprays", {})
    if not sprays:
        errors.append("no sprays")
    for sid, spray in sprays.items():
        tex = spray.get("texture", "")
        path = os.path.join(ASSETS, *tex.split("/"))
        if spray.get("rarity", "common") not in ("common", "legendary", "special"):
            errors.append(f"{sid}: unknown rarity {spray.get('rarity')}")
        for name, lang in langs.items():
            if f"blockland.spray.{sid}" not in lang:
                errors.append(f"{sid}: no blockland.spray.{sid} in {name}.json")
        if not tex.endswith(".png") or not os.path.exists(path):
            errors.append(f"{sid}: texture {tex} missing")
            continue
        img = Image.open(path)
        if img.mode != "RGBA" or img.size != (SIZE, SIZE):
            errors.append(f"{sid}: {tex} is {img.mode} {img.size[0]}x{img.size[1]}, want RGBA {SIZE}x{SIZE}")
            continue
        a = np.asarray(img)[..., 3].astype(np.int32)
        border = np.concatenate([a[:MARGIN].ravel(), a[-MARGIN:].ravel(), a[:, :MARGIN].ravel(), a[:, -MARGIN:].ravel()])
        if border.max() > 0:
            errors.append(f"{sid}: alpha in the {MARGIN} px border (max {border.max()})")
        covered = float((a > 128).mean())
        soft = int(((a > 8) & (a < 247)).sum())
        if not 0.08 <= covered <= 0.85:
            errors.append(f"{sid}: covers {covered:.0%} of the square (want 8..85 %)")
        if soft < 200:
            errors.append(f"{sid}: hard edges ({soft} partly transparent pixels)")
        notes.append(f"{sid}: {covered:.0%} covered, {soft} soft edge pixels, {os.path.getsize(path) // 1024} KiB")
    files = {f[:-4] for f in os.listdir(os.path.join(ASSETS, "textures", "spray")) if f.endswith(".png")} \
        if os.path.isdir(os.path.join(ASSETS, "textures", "spray")) else set()
    for extra in sorted(files - set(sprays)):
        errors.append(f"textures/spray/{extra}.png is not in sprays.json")
    return errors, notes


def main():
    errors, notes = check()
    print("== sprays")
    for n in notes:
        print("  " + n)
    if errors:
        print(f"FAILED ({len(errors)} problems):")
        for e in errors:
            print("  - " + e)
        return 1
    print("OK: sprays follow the contract")
    return 0


if __name__ == "__main__":
    sys.exit(main())
