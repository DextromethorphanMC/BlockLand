"""build_all.py steps of the creeper zombie (infection monster, STEP_MODULES): `creeper_zombie` paints its skin
(textures/entity/creeper_zombie.png), `creeper_zombie_sounds` synthesises its fuse."""


def steps(path, apply_pbr):
    def build_skin():
        import creeper_zombie_skin
        creeper_zombie_skin.build(path)

    def build_sounds():
        import gen_creeper_zombie_sounds
        gen_creeper_zombie_sounds.build(path)

    return {"creeper_zombie": build_skin, "creeper_zombie_sounds": build_sounds}
