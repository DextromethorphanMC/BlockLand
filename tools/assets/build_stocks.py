"""build_all.py steps of section 28 (STEP_MODULES): `stocks` builds models/attachment/heavy_stock.glb and
wire_stock.glb and applies their texture sets, regions and baked AO."""
import stock_models


def steps(path, apply_pbr):
    def build_stocks():
        import gen_attachments
        built = gen_attachments.build(path, list(stock_models.MODELS))
        for name, tris in built.items():
            print(f"attachment/{name}.glb: {tris} triangles")
        apply_pbr(*(f"attachment/{name}" for name in built))

    return {"stocks": build_stocks}
