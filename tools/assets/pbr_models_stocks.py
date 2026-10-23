"""Section 20 / 21 tables of the stocks of section 28 (pbr_apply.MODEL_MODULES): as the tactical stock."""


def models():
    table = lambda: {
        "ao": {"mode": "together", "distance": 0.015},
        "materials": {"attachment": ("common/anodized_black", "stock"), "steel_dark": ("common/steel_dark", "stock"),
                      "stock_pad": ("common/rubber", "none")},
    }
    return {"attachment/heavy_stock": table(), "attachment/wire_stock": table()}
