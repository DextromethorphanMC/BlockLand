"""Section 20 table of the ammo crate (pbr_apply.MODEL_MODULES): painted pine boards, the yellow band, parkerized
hardware, manila rope handles; inside, olive ammo cans, black magazines and their brass. The lid and latches move:
each mesh bakes its own AO."""


def models():
    return {
        "block/ammo_crate": {
            "ao": {"mode": "per_mesh", "distance": 0.012},
            "materials": {
                "wood": ("ammo_crate/wood", "body"),
                "marking": ("ammo_crate/marking", "body"),
                "metal": ("common/parkerized", "hardware"),
                "rope": ("ammo_crate/rope", "body"),
                "can": ("equipment/olive", "contents"),
                "mag": ("common/anodized_black", "contents"),
                "brass": ("common/brass", "contents"),
            },
        },
    }
