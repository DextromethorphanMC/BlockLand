"""build_all.py steps of the binoculars (STEP_MODULES): `binoculars_items` the icon, item models and item definition;
`binoculars_sounds` the synthesised raise / lower / zoom sounds. The pair is a held cuboid model (no GLB, no viewmodel
rig): looking through it is the optics' view (`client/scope/HandOpticOverlay`)."""


def steps(path, apply_pbr):
    def build_binoculars_items():
        import gen_binoculars_items
        gen_binoculars_items.build(path)

    def build_binoculars_sounds():
        import gen_binoculars_sounds
        gen_binoculars_sounds.build(path)

    return {"binoculars_items": build_binoculars_items, "binoculars_sounds": build_binoculars_sounds}
