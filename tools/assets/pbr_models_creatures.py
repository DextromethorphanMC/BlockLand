"""Section 20 tables of the creatures (pbr_apply.MODEL_MODULES; tools/creatures/SHARK_SPEC.md section 4): the skin's
sets bound to its countershading materials; eyes, teeth, gills and the mouth keep their glTF colours. AO: the body
and its fins occlude each other; the jaw (it swings open) only shades itself."""


def models():
    return {
        "creature/shark": {
            "ao": {"mode": "together", "root": "body", "distance": 0.06, "separate": [["jaw"]]},
            "materials": {
                "skin_back": ("creature/shark_back", "body"),
                "skin_belly": ("creature/shark_belly", "body"),
                "mouth": (None, "none"),
                "tooth": (None, "none"),
                "eye": (None, "none"),
                "gill": (None, "none"),
            },
        },
    }
