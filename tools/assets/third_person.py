"""Weapon item definitions (asset spec §37): the inventory icon in the GUI, the low-poly held model in first person (the
viewmodel replaces the hand pass there) and the full 3D weapon everywhere else — third person, dropped, item frames,
heads — through the `blockland:weapon` special model, which fits the weapon into the held model's box, so the held
model's display transforms place it.

Used by the item generators (gen_*_items.py). Run on its own it rewrites the weapon item definitions already in the mod
(idempotent; ammo and other plain items are left alone):

    python -I tools/assets/third_person.py
"""
import glob
import json
import os

HERE = os.path.dirname(os.path.abspath(__file__))
ITEMS = os.path.join(HERE, "..", "..", "mod", "src", "main", "resources", "assets", "blockland", "items")
FIRST_PERSON = ["firstperson_righthand", "firstperson_lefthand"]
# weapons whose root frame is turned against their held model (degrees XYZ, applied to the weapon first)
TUNING = {"karambit": {"rotation": [0, 0, 90]}, "knuckles": {"rotation": [0, -90, 0]}}


def base_model(held):
    """The default skin's model id of a held item model (a custom_model_data skin select or a plain model)."""
    while held.get("type") == "minecraft:select":
        held = held["fallback"]
    return held["model"]


def weapon_definition(icon, held, **tuning):
    """icon / held: the item models of the GUI icon and of the held model (skin selects or plain models)."""
    model = {"type": "blockland:weapon", "fit": base_model(held)}
    model.update({k: v for k, v in tuning.items() if v is not None})
    return {"model": {"type": "minecraft:select", "property": "minecraft:display_context",
                      "cases": [{"when": ["gui"], "model": icon},
                                {"when": FIRST_PERSON, "model": held}],
                      "fallback": {"type": "minecraft:special", "base": base_model(held), "model": model}}}


def convert(definition, **tuning):
    """An item definition of the old shape (icon in gui/ground/fixed, the held model otherwise) or of the new one."""
    model = definition["model"]
    cases = model["cases"]
    if len(cases) == 2 and cases[1]["when"] == FIRST_PERSON:
        return weapon_definition(cases[0]["model"], cases[1]["model"], **tuning)
    return weapon_definition(cases[0]["model"], model["fallback"], **tuning)


def is_weapon(definition):
    model = definition.get("model", {})
    return model.get("type") == "minecraft:select" and model.get("property") == "minecraft:display_context"


def main():
    changed = 0
    for path in sorted(glob.glob(os.path.join(ITEMS, "*.json"))):
        with open(path, encoding="utf-8") as f:
            definition = json.load(f)
        if not is_weapon(definition):
            continue
        key = os.path.splitext(os.path.basename(path))[0]
        new = convert(definition, **TUNING.get(key, {}))
        if new != definition:
            with open(path, "w", encoding="utf-8", newline="\n") as f:
                json.dump(new, f, indent=2)
                f.write("\n")
            changed += 1
            print("third person:", key)
    print(f"{changed} item definitions rewritten")


if __name__ == "__main__":
    main()
