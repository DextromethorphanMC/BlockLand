"""Section 20 / 21 table of the knuckles (pbr_apply.MODEL_MODULES): cast brass dusters with a polished striking face and
paracord on the palm bar. Each duster only occludes itself (they move apart)."""


def models():
    return {
        "knife/knuckles": {
            "ao": {"mode": "per_mesh", "distance": 0.006},
            "materials": {
                "frame": ("knuckles/brass", "blade"),
                "face": ("knuckles/face", "blade"),
                "wrap": ("knuckles/wrap", "handle"),
            },
        },
    }
