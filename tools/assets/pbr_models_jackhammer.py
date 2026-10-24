"""pbr_apply.py table of the Jackhammer (VIEWMODEL_SPEC sections 20, 21 and 40), plugged in through MODEL_MODULES.
Regions: receiver, lower and handle `body`; butt and bay housing `stock`; the operating sleeve `handguard`; the rubber
grip panels `grip`; barrel, collar and flash hider `barrel`; the cassette `magazine`; small steel parts `detail`; the
butt pad, hulls and brass `none`."""


def models():
    return {
        "gun/jackhammer": {
            # the sleeve, the cassette, the trigger and the latch move: they only shade themselves
            "ao": {"mode": "together", "root": "gun", "distance": 0.028,
                   "separate": [["bolt"], ["mag_standard"], ["trigger"], ["latch"]]},
            "materials": {
                "steel": ("jackhammer/receiver", "body"),
                "stock": ("jackhammer/polymer", "stock"),
                "forend": ("jackhammer/polymer", "handguard"),
                "grip": ("jackhammer/grip", "grip"),
                "barrel": ("jackhammer/barrel", "barrel"),
                "steel_dark": ("common/steel_dark", "detail"),
                "cassette": ("jackhammer/cassette", "magazine"),
                "rubber": ("common/rubber", "none"),
                "shell": ("ks3/shell", "none"),
                "brass": ("common/brass", "none"),
            },
        },
    }
