"""Section 20 / 21 table of the dagger (pbr_apply.MODEL_MODULES): satin blade and polished edges as the balisong,
a blackened (ceramic-coated) guard and pommel, the ribbed rubber grip. Rigid knife: every part occludes the others."""


def models():
    return {
        "knife/dagger": {
            "ao": {"mode": "together", "root": "knife", "distance": 0.010},
            "materials": {
                "blade": ("butterfly/blade", "blade"),
                "blade_edge": ("butterfly/blade_edge", "blade"),
                "guard": ("dagger/blackened", "hardware"),
                "pommel": ("dagger/blackened", "hardware"),
                "handle": ("dagger/grip", "handle"),
            },
        },
    }
