"""pbr_apply tables of the USP, the dual USP and the Cerberus skin's model (VIEWMODEL_SPEC sections 20 / 21 / 26),
plugged into pbr_apply.MODELS (see MODEL_MODULES there). Regions as the P9's; the Cerberus body is `body`, its red
inlays `detail`, its grip `grip`."""


def _usp_materials():
    return {
        "polymer": ("p9/polymer", "grip"),           # the polymer frame is the grip module (as the P9's)
        "grip": ("p9/grip", "grip"),
        "slide": ("usp/slide", "body"),
        "barrel": ("usp/barrel", "barrel"),
        "steel_dark": ("common/steel_dark", "detail"),
        "sight_dots": (None, "none"),
        "mag": ("usp/mag", "magazine"),
        "mag_base": ("p9/polymer", "magazine"),
        "brass": ("common/brass", "none"),
    }


def _dual_ao(distance):
    # each gun occludes itself in the rest pose; parts that move away in clips only occlude themselves
    return {"mode": "together", "root": "viewmodel", "distance": distance,
            "separate": [["barrel"], ["barrel_L"], ["mag_standard"], ["mag_extended"], ["mag_standard_L"],
                         ["mag_extended_L"], ["slide"], ["slide_L"]]}


def models():
    return {
        "gun/usp": {
            "ao": {"mode": "together", "root": "gun", "distance": 0.022,
                   "separate": [["barrel"], ["mag_standard"], ["mag_extended"]]},
            "materials": _usp_materials(),
        },
        "gun/usp_dual": {"ao": _dual_ao(0.022), "materials": _usp_materials()},
        "gun/usp_dual_cerberus": {
            "ao": _dual_ao(0.024),
            "materials": {
                "cerberus_body": ("usp/cerberus_body", "body"),
                "cerberus_trim": ("usp/cerberus_trim", "detail"),
                "cerberus_grip": ("usp/cerberus_grip", "grip"),
                "barrel": ("usp/barrel", "barrel"),
                "steel_dark": ("common/steel_dark", "detail"),
                "mag": ("usp/mag", "magazine"),
                "mag_base": ("usp/cerberus_trim", "magazine"),
                "brass": ("common/brass", "none"),
            },
        },
    }
