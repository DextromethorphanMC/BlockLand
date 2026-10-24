"""Section 20 / 21 table of the bomb (pbr_apply.MODEL_MODULES, VIEWMODEL_SPEC section 39): the putty sticks in their
film, duct tape, the timer unit's moulded case and keys; the LCD and the LED keep their glTF look (self-lit),
the wires their plain insulation colours. The bomb is rigid: every part occludes the others."""


def models():
    return {
        "bomb/c4": {
            "ao": {"mode": "together", "root": "bomb", "distance": 0.010},
            "materials": {"explosive": ("bomb/explosive", "body"), "tape": ("bomb/tape", "tape"),
                          "housing": ("bomb/housing", "keypad"), "keys": ("bomb/keys", "keypad"),
                          "screen": (None, "none"), "led": (None, "none"), "wire_red": (None, "wires"),
                          "wire_yellow": (None, "wires"), "wire_blue": (None, "wires")},
        },
    }
