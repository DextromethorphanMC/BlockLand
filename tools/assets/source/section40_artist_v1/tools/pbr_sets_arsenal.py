"""Per-weapon finish sets at bounded resolution; same LabPBR pipeline as existing weapons."""
from arsenal_data import GUNS,ART

def sets(pt):
    out={}
    for i,(name,d) in enumerate(GUNS.items()):
        seed=1200+i*10
        finish=pt._r(pt.cerakote,colour=tuple(d['colour'])) if name in ('scar','ssg08','jackhammer') else pt._r(pt.painted_steel,paint=tuple(d['colour']),coverage=.0015)
        mag_recipe=pt._r(pt.polymer_moulded,colour={'p90':(.43,.29,.11),'calico':(.10,.105,.10),'jackhammer':(.20,.19,.15)}[name]) if name in ('p90','calico','jackhammer') else pt._r(pt.painted_steel,paint=(.16,.17,.16),coverage=.002,vertical=True)
        for j,(suffix,recipe,tile,size,detail) in enumerate([
            ('finish',finish,.15,ART['pbr_size'],.5),
            ('polymer',pt._r(pt.polymer_moulded,colour=tuple(x*.78 for x in d['colour'])),.10,ART['pbr_size'],.5),
            ('magazine',mag_recipe,.12,ART['pbr_size'],.5),
            ('machined',pt._r(pt.steel_dark),.06,256,.35)]):
            out[name+'/'+suffix]=pt.SetDef(recipe,size,tile,seed+j,detail)
    return out
