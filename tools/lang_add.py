"""Adds or updates translation keys in both language files at once (zh_cn + en_us), keeping their order and format.
Locked, so several people (or agents) can add keys at the same time without losing each other's.

python -I tools/lang_add.py keys.json [--after <existing key>]

keys.json: {"<key>": ["中文", "English"], ...}. New keys go after `--after` (or at the end), existing keys are updated
in place. Run `python tools/fonts/subset_fonts.py --check` afterwards when new Chinese characters were added."""
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "assets"))

import filelock                                   # noqa: E402

LANG = os.path.join(HERE, "..", "mod", "src", "main", "resources", "assets", "blockland", "lang")


def add(entries, after=None):
    """entries: {key: (zh, en)}."""
    for index, name in enumerate(("zh_cn.json", "en_us.json")):
        path = os.path.join(LANG, name)
        with filelock.locked(path):
            with open(path, encoding="utf-8") as f:
                data = json.load(f)
            new = {k: v[index] for k, v in entries.items() if k not in data}
            for k, v in entries.items():
                if k in data:
                    data[k] = v[index]
            if new:
                items = list(data.items())
                at = len(items)
                if after is not None and after in data:
                    at = [k for k, _ in items].index(after) + 1
                items[at:at] = list(new.items())
                data = dict(items)
            with open(path, "w", encoding="utf-8", newline="\n") as f:
                f.write(json.dumps(data, ensure_ascii=False, indent=2) + "\n")


def main(argv):
    if not argv:
        print(__doc__)
        return 2
    after = None
    if "--after" in argv:
        i = argv.index("--after")
        after = argv[i + 1]
        argv = argv[:i] + argv[i + 2:]
    with open(argv[0], encoding="utf-8") as f:
        entries = json.load(f)
    add({k: tuple(v) for k, v in entries.items()}, after)
    print(f"lang: {len(entries)} keys")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
