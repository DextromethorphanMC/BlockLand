"""pbr_apply table of the revolver (VIEWMODEL_SPEC sections 20, 21 and 49), plugged in through MODEL_MODULES. Regions:
the frame, cylinder and crane `body`; the barrel with its underlug and rib `barrel`; the walnut grips `grip`; the
hammer, trigger, latch, sights and screws `detail`; the speed loader `magazine`; the bore, the front sight's insert, the
brass and the bullets `none`."""


def models():
    return {
        "gun/revolver": {
            # the mechanism's parts move apart (the crane swings the cylinder out, the loader comes and goes)
            "ao": {"mode": "together", "root": "gun", "distance": 0.020,
                   "separate": [["crane", "cylinder", "ejector", "rounds"], ["mag_standard", "loader_rounds"],
                                ["hammer"], ["trigger"], ["latch"]]},
            "materials": {
                "steel": ("revolver/blued", "body"),
                "barrel": ("revolver/blued", "barrel"),
                "grip": ("tommy/walnut", "grip"),
                "grip_checker": ("revolver/checkered_walnut", "grip"),
                "steel_dark": ("common/steel_dark", "detail"),
                "bore": (None, "none"),
                "insert": (None, "none"),
                "brass": ("common/brass", "none"),
                "bullet": ("common/brass", "none"),
                "loader": ("p9/polymer", "magazine"),
            },
        },
    }
