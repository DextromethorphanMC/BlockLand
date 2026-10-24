"""pbr_apply.py table of the MP5 (VIEWMODEL_SPEC sections 20, 21 and 40), plugged in through MODEL_MODULES. The MP5
wears its own sets (pbr_sets_mp5.py: the matte black paint over the stamped steel, the black phosphate barrel, the
moulded polymer of the trigger group and the handguard, the painted magazines) and the common ones."""


def models():
    return {
        "gun/mp5": {
            # parts that leave their rest place in clips or are taken off by a slot only occlude themselves
            "ao": {"mode": "together", "root": "gun", "distance": 0.024,
                   "separate": [["bolt"], ["carrier"], ["mag_standard"], ["mag_extended"], ["stock"],
                                ["muzzle_brake"], ["sight_rear"], ["sight_iron"]]},
            "split": {
                "steel": [("steel_stock", ["stock"])],
            },
            "materials": {
                "steel": ("mp5/receiver", "body"),
                "steel_stock": ("mp5/receiver", "stock"),
                "steel_dark": ("common/steel_dark", "detail"),
                "barrel": ("mp5/barrel", "barrel"),
                "polymer": ("mp5/polymer", "grip"),
                "handguard": ("mp5/handguard", "handguard"),
                "stock": ("common/rubber", "stock"),
                "mag": ("mp5/mag", "magazine"),
                "mag_base": ("mp5/mag", "magazine"),
                "brass": ("common/brass", "none"),
            },
        },
    }
