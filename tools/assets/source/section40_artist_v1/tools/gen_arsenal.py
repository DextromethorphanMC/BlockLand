"""Build the nine section-40 GLBs. No gameplay/runtime or placeholder audio changes."""
from pathlib import Path
import sys
sys.path.insert(0,str(Path(__file__).resolve().parent))
import numpy as np
import armrig
import arsenal_models as mesh
import arsenal_rig as rig
from arsenal_data import GUNS,gameplay
from pbr_models_arsenal import REGIONS
from gen_p9 import bake_clip
from glb import GLB
from meshkit import build_primitives,triangle_count
from vmath import quat_axis_angle,quat_conj


def build(name,path):
    d=GUNS[name];data=gameplay()[name];parts=mesh.build(name);g=GLB()
    used={m for p in parts.values() for m in p.m}
    for m,region in REGIONS.items():
        if m in used:g.add_material(m,(.22,.23,.24,1),1 if m in ('steel_dark','barrel','brass') else 0,.5,extras={'region':region})
    ids={};counts={}
    for n,p in parts.items():
        primitives=build_primitives(p);counts[n]=triangle_count(primitives);ids[n]=g.add_mesh(n,primitives)
    rest,_=rig.Pose(name).solve();g.add_node('viewmodel');g.add_node('gun','viewmodel',rest['gun']['translation'],rest['gun']['rotation'])
    for n in parts:
        if n in ('mag_standard','mag_extended','fx_shell'):continue
        g.add_node(n,'gun',mesh=ids[n])
    g.add_node('mag','gun',d['mag'])
    for n in ['mag_standard','mag_extended']:
        if n in ids:g.add_node(n,'mag',mesh=ids[n])
    g.add_node('muzzle','gun',(0,.055,d['front']-.001))
    eject_rot=quat_axis_angle((0,0,1),-90) if name=='p90' else quat_axis_angle((0,0,1),20)
    g.add_node('ejection','gun', (0,-.04,.13) if name=='p90' else (d['width']+.004,.05,.01),eject_rot)
    if name!='ssg08':g.add_node('aim_iron','gun',(0,d['sight'],min(d['body'][1]-.015,.10)+.12))
    mounts={'optic':(0,d.get('optic_y',d['sight']-.018),d.get('optic_z',-.045)),'barrel':(0,.055,d['front']+.002),
            'laser':(d['width']+.002,.022,d['support'][2]-.02),'stock':(0,.015,d['body'][1]-.002),
            'underbarrel':(0,-.036,d['support'][2]-d.get('underbarrel_offset',.055)),'charm':(-d['width']-.002,-.015,.045)}
    for slot,xyz in mounts.items():
        if slot not in data['slots']:continue
        rot=quat_axis_angle((0,0,1),90) if slot=='laser' else (quat_conj(rig.GUN_Q) if slot=='charm' else None)
        scale=None
        if name=='calico' and slot=='stock':xyz=(0,-.075,.06);rot=quat_axis_angle((1,0,0),-28);scale=np.full(3,.70)
        g.add_node('mount_'+slot,'gun',xyz,rot,scale=scale)
    for side in ['R','L']:
        g.add_node('arm_'+side,'viewmodel',rig.SHOULDERS[side],rest['arm_'+side]['rotation'])
        g.add_node('arm_'+side+'_fore','arm_'+side,(0,-rig.UPPER_LEN,0),rest['arm_'+side+'_fore']['rotation'])
        armrig.add_hand_nodes(g,side,rest)
    g.add_node('camera','viewmodel');g.add_node('fx_shell',None,mesh=ids['fx_shell'],extras={'hidden':True})
    info={c.name:bake_clip(g,c,rest) for c in rig.clips(name)}
    g.save(str(path));print(name,'triangles',sum(counts.values()),'channels',info,flush=True)
    return counts


def build_all(path,names=None):
    import pbr_apply
    for name in names or GUNS:
        build(name,path('models','gun',name+'.glb'));pbr_apply.apply('gun/'+name)


if __name__=='__main__':
    import build_all as b
    build_all(b.path,sys.argv[1:] or None)
