"""pbr_apply.py table of the Vector (VIEWMODEL_SPEC sections 20, 21 and 44), plugged in through MODEL_MODULES. The
Vector wears its own sets (pbr_sets_vector.py: the anodised upper, the polymer lower and stock, the polymer magazines,
the nitrided barrel) and the common ones (the rails' and small parts' dark steel, the butt pad's rubber, the brass).
Baked AO: the parts that leave their rest place in clips or are taken off by a slot only occlude themselves."""


def models():
    return {
        "gun/vector": {
            "ao": {"mode": "together", "root": "gun", "distance": 0.024,
                   "separate": [["bolt"], ["handle_lever"], ["carrier"], ["mag_standard"], ["mag_extended"], ["stock"],
                                ["muzzle_brake"], ["sight_rear"], ["sight_iron"], ["front_sight_hood"], ["trigger"],
                                ["paddle"]]},
            "materials": {
                "upper": ("vector/upper", "body"),
                "lower": ("vector/lower", "grip"),
                "rail": ("common/steel_dark", "detail"),
                "barrel": ("vector/barrel", "barrel"),
                "stock": ("vector/stock", "stock"),
                "pad": ("common/rubber", "stock"),
                "mag": ("vector/mag", "magazine"),
                "mag_base": ("vector/mag", "magazine"),
                "brass": ("common/brass", "none"),
            },
        },
    }
