"""pbr_apply.py table of the SSG 08 (VIEWMODEL_SPEC sections 20, 21, 23 and 40), plugged in through MODEL_MODULES:
its own anodised aluminium, nitrided barrel and polymer sets (pbr_sets_ssg08.py) and the common steel, rubber and
brass."""


def models():
    return {
        "gun/ssg08": {
            # the bolt slides and turns, the magazine leaves, the trigger rocks: they only shade themselves
            "ao": {"mode": "together", "root": "gun", "distance": 0.028,
                   "separate": [["bolt"], ["mag_standard"], ["trigger"]]},
            "materials": {
                "steel": ("ssg08/chassis", "body"),
                "handguard": ("ssg08/foreend", "handguard"),
                "stock": ("ssg08/stock", "stock"),
                "cheek": ("ssg08/cheek", "stock"),
                "grip": ("ssg08/grip", "grip"),
                "pad": ("common/rubber", "none"),
                "barrel": ("ssg08/barrel", "barrel"),
                "steel_dark": ("common/steel_dark", "detail"),
                "mag": ("ssg08/mag", "magazine"),
                "mag_base": ("ssg08/mag", "magazine"),
                "brass": ("common/brass", "none"),
            },
        },
    }
