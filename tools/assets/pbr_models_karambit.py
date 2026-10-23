"""Section 20 / 21 table of the karambit (pbr_apply.MODEL_MODULES): satin blade and polished edge as the balisong,
a G10 laminate handle, the blackened steel ring. Rigid knife: every part occludes the others."""


def models():
    return {
        "knife/karambit": {
            "ao": {"mode": "together", "root": "knife", "distance": 0.010},
            "materials": {
                "blade": ("butterfly/blade", "blade"),
                "blade_edge": ("butterfly/blade_edge", "blade"),
                "handle": ("karambit/g10", "handle"),
                "ring": ("dagger/blackened", "hardware"),
            },
        },
    }
