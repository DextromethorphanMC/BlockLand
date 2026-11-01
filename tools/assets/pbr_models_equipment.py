"""Section 20 / 21 tables of the equipment (pbr_apply.MODEL_MODULES, VIEWMODEL_SPEC section 36). Grenades: olive
enamel bodies, the blackened fuze, zinc-plated levers, polished pins; the levers and pins leave the item in clips, so
they only occlude themselves. Molotov: the glass, the fuel and the flame keep their glTF look (see-through / self-lit),
the rag is cloth, the lighter (in the left hand) polished steel. Throwing knife: the balisong's satin steel and edge,
paracord. Injector: moulded polymers and a printed label; the cap and the shroud move, so they shade themselves.
Flashlight: hard-anodised aluminium, a steel bezel and clip, the rubber tail switch (it moves: it shades itself); the
reflector, the lens and the lens's glow keep their glTF look."""

_GRENADE = {"body": ("equipment/olive", "body"), "fuze": ("common/steel_dark", "body"),
            "lever": ("equipment/zinc", "lever"), "pin": ("common/polished_steel", "pin")}


def _grenade(band=None):
    mats = dict(_GRENADE)
    if band:
        mats["label"] = (band, "label")
        mats["vent"] = (None, "body")
    return {"ao": {"mode": "together", "root": "equipment", "distance": 0.008, "separate": [["lever"], ["pin"]]},
            "materials": mats}


def models():
    return {
        "equipment/frag_grenade": _grenade(),
        "equipment/flashbang": _grenade("equipment/band_flash"),
        "equipment/smoke_grenade": _grenade("equipment/band_smoke"),
        "equipment/molotov": {
            "ao": {"mode": "together", "root": "equipment", "distance": 0.010, "separate": [["flame"]]},
            "materials": {"glass": (None, "glass"), "liquid": (None, "liquid"), "rag": ("equipment/rag", "rag"),
                          "flame": (None, "none"), "lighter": ("common/polished_steel", "none")},
        },
        "equipment/throwing_knife": {
            "ao": {"mode": "together", "root": "equipment", "distance": 0.006},
            "materials": {"blade": ("butterfly/blade", "blade"), "blade_edge": ("butterfly/blade_edge", "blade"),
                          "grip": ("equipment/paracord", "grip")},
        },
        "equipment/flashlight": {
            "ao": {"mode": "together", "root": "equipment", "distance": 0.006, "separate": [["button"], ["lit_glow"]]},
            "materials": {"body": ("common/anodized_black", "body"), "bezel": ("common/steel_dark", "bezel"),
                          "clip": ("common/parkerized", "body"), "reflector": (None, "none"), "lens": (None, "none"),
                          "button": ("common/rubber", "button"), "lit_glow": (None, "none")},
        },
        "equipment/adrenaline": {
            "ao": {"mode": "together", "root": "equipment", "distance": 0.006, "separate": [["cap"], ["plunger"]]},
            "materials": {"barrel": ("equipment/pen_barrel", "barrel"), "label": ("equipment/pen_label", "label"),
                          "plunger": ("equipment/pen_orange", "plunger"), "cap": ("equipment/pen_blue", "cap")},
        },
    }
