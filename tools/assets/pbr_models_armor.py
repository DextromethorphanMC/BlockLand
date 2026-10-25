"""Section 20 / 21 tables of the body armour (pbr_apply.MODEL_MODULES): the plate carrier's nylon, webbing and plastic
hardware, and the plate's polyurea coat with its label."""


def models():
    return {
        "armor/vest": {
            "ao": {"mode": "together", "root": "armor", "distance": 0.012},
            "materials": {
                "carrier": ("armor/nylon", "body"),
                "webbing": ("armor/webbing", "body"),
                "hardware": ("p9/polymer", "hardware"),
            },
        },
        "armor/plate": {
            "ao": {"mode": "together", "root": "plate", "distance": 0.006},
            "materials": {
                "plate": ("armor/polyurea", "body"),
                "label": (None, "none"),
            },
        },
    }
