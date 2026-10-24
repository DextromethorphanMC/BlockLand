"""Section 20 / 21 table of the crowbar (pbr_apply.MODEL_MODULES): the bar's worn red enamel, the ground bare steel of
the claw and chisel, the friction tape. Rigid bar: every part occludes the others."""


def models():
    return {
        "knife/crowbar": {
            "ao": {"mode": "together", "root": "knife", "distance": 0.010},
            "materials": {
                "bar": ("crowbar/paint_red", "blade"),
                "ends": ("crowbar/ends", "hardware"),
                "grip": ("crowbar/tape", "handle"),
            },
        },
    }
