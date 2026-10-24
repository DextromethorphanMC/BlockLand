"""Independent section-40 validation, registered with check_glb (not the AK node contract)."""
from pathlib import Path
import json
import numpy as np
from glbread import GLTF
from check_glb import check_container,check_meshes,check_hand_joints,check_curve_rate,check_eye_distance

IDS=('uzi','p90','mp5','sten','ssg08','calico','scar','m4a1','jackhammer')
SINGLE={'p90','sten','ssg08','jackhammer'}
ROOT=Path(__file__).resolve().parents[2]
ASSETS=ROOT/'mod/src/main/resources/assets/blockland'
REGIONS={'body','barrel','magazine','stock','grip','handguard','detail'}


def claims(path,names):return Path(path).stem in IDS


def check(path,arms,r):
    name=Path(path).stem;g=GLTF(path)
    data=json.loads((ROOT/'mod/src/main/resources/data/blockland/guns/guns.json').read_text(encoding='utf-8'))['guns'][name]
    check_container(g,r,name);check_meshes(g,r,name);check_hand_joints(g,r,name);check_curve_rate(g,r,name)
    required={'gun','viewmodel','mag','mag_standard','bolt','muzzle','ejection','fx_shell'}
    if name not in SINGLE:required.add('mag_extended')
    if name!='ssg08':required.update(['aim_iron','sight_iron','sight_rear'])
    for slot,spec in data['slots'].items():
        if spec.get('node'):required.add(spec['node'])
        for part in spec.get('hides',[]):required.add(part)
    for node in required:r.check(node in g.names,f'{name}: missing {node}')
    if not required<=set(g.names):return
    for node in ('mag_standard','mag_extended'):
        if node in g.names:r.check(g.nodes[g.parent[g.names[node]]]['name']=='mag',f'{name}: {node} parent')
    if name in SINGLE:r.check('mag_extended' not in g.names,f'{name}: unexpected extended magazine')
    r.check(g.names['fx_shell'] not in g.parent,f'{name}: fx_shell must be root')
    r.check(g.nodes[g.names['fx_shell']].get('extras',{}).get('hidden') is True,f'{name}: shell visible')
    r.check(g.nodes[g.names['viewmodel']].get('translation',[0,0,0])==[0,0,0],f'{name}: viewmodel root offset')
    for node in ('muzzle','ejection','aim_iron'):
        if node in g.names:r.check('mesh' not in g.nodes[g.names[node]],f'{name}: {node} must be helper')
    for slot in ('optic','barrel','laser','stock','underbarrel','charm'):
        node='mount_'+slot
        r.check((node in g.names)==(slot in data['slots']),f'{name}: slot {slot} mismatch')
        if node in g.names:r.check('mesh' not in g.nodes[g.names[node]],f'{name}: {node} must be empty')
    mats=g.json['materials'];names={m['name'] for m in mats}
    r.check({'steel','steel_dark','barrel'}<=names,f'{name}: gold material names')
    for m in mats:
        ex=m.get('extras',{});r.check(ex.get('region') in REGIONS,f"{name}: region {m['name']}")
        tex=ex.get('pbr','');r.check(tex.startswith('textures/pbr/'+name+'/') or m['name']=='brass',f'{name}: weapon-local PBR {m["name"]}')
        for suffix in ('','_n','_s'):r.check((ASSETS/(tex+suffix+'.png')).is_file(),f'{name}: texture missing {tex}{suffix}')
    animations={a['name']:a for a in g.json['animations']}
    expected={'idle','draw','fire','fire_last','reload','reload_empty','inspect','walk'}
    r.check(expected<=animations.keys(),f'{name}: missing clips')
    for clip,a in animations.items():
        dur=g.duration(a);events=a.get('extras',{}).get('events',[]);event_names={e['name'] for e in events}
        if clip in ('reload','reload_empty'):
            timing=data['reload']['empty' if clip=='reload_empty' else 'tactical']
            r.check(abs(dur-timing)<1e-5,f'{name}: {clip} duration {dur} != {timing}')
            r.check({'mag_release','mag_out','mag_in'}<=event_names,f'{name}: magazine events missing')
            if clip=='reload_empty':r.check({'bolt_back','bolt_forward'}<=event_names or {'slide_back','slide_forward'}<=event_names,f'{name}: empty reload bolt events')
            rests=g.nodes[g.names['mag']]['translation'];samples=[g.sample(a,float(t)) for t in np.linspace(0,dur,41)]
            displacement=[np.array(s.get(g.names['mag'],{}).get('translation',rests))-rests for s in samples]
            axis=0 if name=='sten' else 1
            r.check(max(abs(v[axis]) for v in displacement)>.10,f'{name}: wrong magazine withdrawal axis')
            for end in (0,dur):
                m=g.sample(a,end)[g.names['mag']]
                r.check(np.allclose(m['translation'],rests,atol=1e-6) and np.allclose(m['scale'],1),f'{name}: reload endpoint magazine not seated')
        if clip=='draw':r.check('equip' in event_names,f'{name}: draw equip event')
        if clip=='inspect':r.check('inspect_move' in event_names,f'{name}: inspect event')
        if name=='ssg08' and clip in ('fire','fire_last'):
            r.check({'bolt_back','bolt_forward'}<=event_names,f'{name}: bolt ejection events')
            for e in events:
                if e['name']=='bolt_back':r.check(g.sample(a,e['time'])[g.names['bolt']]['translation'][2]>.075,f'{name}: ejection before bolt opens')
        for e in events:r.check(0<=e['time']<=dur+1e-5,f'{name}: event outside clip')
        for ch in a['channels']:
            node=g.nodes[ch['target']['node']]['name'];r.check(node!='viewmodel',f'{name}: animated viewmodel')
        if clip in ('idle','walk','sprint','tac_sprint'):
            r.check(a.get('extras',{}).get('loop',False),f'{name}: loop flag {clip}')
            for s in a['samplers']:
                v=g.accessor(s['output']);r.check(np.allclose(v[0],v[-1],atol=1e-5),f'{name}: loop seam {clip}')
        r.note(f'{clip}: {dur:.3f}s; events '+','.join(e['name'] for e in events))
    prims=[p for m in g.json['meshes'] for p in m['primitives']]
    tris=sum(len(g.accessor(p['indices']))//3 for p in prims)
    r.check(tris<=12000,f'{name}: {tris} > 12000 triangles');r.check(len(prims)<=44,f'{name}: too many material primitives')
    # The existing numerical tests, not a hand-written "looks good" result.
    check_eye_distance(g,arms,r,animations,root='gun',label=name)
    import check_attach
    check_attach.check_weapon(str(path),arms,r,name)
    r.note(f'{name}: {tris} triangles, {len(prims)} primitives; reloads match guns.json unchanged')
