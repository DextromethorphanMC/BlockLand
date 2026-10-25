"""Section 20 / 21 table of the expandable baton (pbr_apply.MODEL_MODULES): black-oxide steel shafts, the foam rubber
grip, the anodised end cap and collar. The parts slide in one another: AO per mesh."""


def models():
    return {
        "knife/baton": {
            "ao": {"mode": "per_mesh", "distance": 0.006},
            "materials": {
                "shaft": ("baton/shaft", "blade"),
                "grip": ("baton/grip", "handle"),
                "hardware": ("baton/hardware", "hardware"),
            },
        },
    }
