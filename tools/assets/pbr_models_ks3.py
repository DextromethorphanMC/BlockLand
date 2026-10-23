"""KS-3 texture-set table for pbr_apply (VIEWMODEL_SPEC sections 20 / 21 / 25), plugged in through MODEL_MODULES."""


def models():
    return {
        "gun/ks3": {
            # the pump and the loading gate move, only one magazine tube shows at a time: they only shade themselves
            "ao": {"mode": "together", "root": "gun", "distance": 0.03,
                   "separate": [["pump"], ["tube_standard"], ["tube_extended"], ["loading_gate"], ["trigger"]]},
            "materials": {
                "receiver": ("awp/receiver", "body"),
                "barrel": ("common/parkerized", "barrel"),
                "pump": ("p9/polymer", "handguard"),
                "stock": ("p9/polymer", "stock"),
                "grip": ("p9/grip", "grip"),
                "steel_dark": ("common/steel_dark", "detail"),
                "shell": ("ks3/shell", "none"),
                "brass": ("common/brass", "none"),
            },
        },
    }
