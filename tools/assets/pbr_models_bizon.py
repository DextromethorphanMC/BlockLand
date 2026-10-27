"""pbr_apply.py table of the PP-19 Bizon (VIEWMODEL_SPEC sections 20, 21 and 54), plugged in through MODEL_MODULES.
The Bizon wears its own sets (pbr_sets_bizon.py: the satin black enamel over the stamped steel, the black phosphate
barrel, the grip's polymer, the helical magazine's polymer) and the common ones."""


def models():
    return {
        "gun/bizon": {
            # parts that leave their rest place in clips or are taken off by a slot only occlude themselves
            "ao": {"mode": "together", "root": "gun", "distance": 0.024,
                   "separate": [["bolt"], ["mag_standard"], ["stock"], ["muzzle_brake"], ["sight_rear"],
                                ["sight_iron"], ["paddle"]]},
            "split": {
                "steel": [("steel_stock", ["stock"])],
            },
            "materials": {
                "steel": ("bizon/receiver", "body"),
                "steel_stock": ("bizon/receiver", "stock"),
                "steel_dark": ("common/steel_dark", "detail"),
                "barrel": ("bizon/barrel", "barrel"),
                "polymer": ("bizon/polymer", "grip"),
                "stock": ("common/rubber", "stock"),
                "mag": ("bizon/mag", "magazine"),
                "mag_base": ("bizon/mag", "magazine"),
                "brass": ("common/brass", "none"),
            },
        },
    }
