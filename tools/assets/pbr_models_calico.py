"""pbr_apply.py table of the Calico M950 (VIEWMODEL_SPEC sections 20, 21 and 40), plugged in through MODEL_MODULES:
the receiver `body`, the polymer lower and grip `grip`, the forend (split off the polymer) `handguard`, barrel and
thread protector `barrel`, small steel parts, rails and sights `detail`, magazines `magazine`, the case `none`."""


def models():
    return {
        "gun/calico": {
            "ao": {"mode": "together", "root": "gun", "distance": 0.022,
                   "separate": [["bolt"], ["trigger"], ["mag_standard"], ["mag_extended"]]},
            "split": {
                "polymer": [("polymer_forend", ["forend"])],
            },
            "materials": {
                "steel": ("calico/receiver", "body"),
                "steel_dark": ("common/steel_dark", "detail"),
                "barrel": ("common/parkerized", "barrel"),
                "polymer": ("calico/polymer", "grip"),
                "polymer_forend": ("calico/polymer", "handguard"),
                "mag": ("calico/mag", "magazine"),
                "mag_base": ("calico/polymer", "magazine"),
                "brass": ("common/brass", "none"),
            },
        },
    }
