"""Pump shotgun GLB checks (VIEWMODEL_SPEC section 25: the KS-3), used by check_glb.py for weapon files with a `pump`
node (CHECK_MODULES). Like check_sniper, with the pump instead of the bolt and the shell-mode reload: the clip set, the
pump sliding only along the bore (fully racked in fire / reload_end_empty, a short press-check in inspect, left alone in
fire_last, back at rest at the end of every clip), the loading gate turning only about X, `shell_load` collapsed
outside reload_shell (shown off screen, gone once it is in the tube), the reload clips joining without a jump, the
magazine tubes, iron sights and every attachment."""
import numpy as np

from check_glb import CAM_ROLL, CAM_TURN
from check_glb import (check_container, check_curve_rate, check_eye_distance, check_hand_joints, check_meshes,
                       check_sights, euler_deg)
from glbread import GLTF

STATIC = ("receiver", "grip", "barrel", "stock", "safety", "tube_standard", "tube_extended")
PARENTS = {
    "viewmodel": None, "gun": "viewmodel", **{n: "gun" for n in STATIC}, "pump": "gun", "loading_gate": "gun",
    "trigger": "gun", "sight_iron": "gun", "sight_rear": "gun", "aim_iron": "gun", "muzzle": "gun", "ejection": "gun",
    "arm_R": "viewmodel", "arm_R_fore": "arm_R", "arm_L": "viewmodel", "arm_L_fore": "arm_L", "camera": "viewmodel",
    "shell_load": "arm_L_hand", "fx_shell": None, "mount_optic": "gun", "mount_laser": "gun", "mount_barrel": "gun",
    "mount_stock": "gun", "mount_charm": "gun",
}
MESH_NODES = set(STATIC) | {"pump", "loading_gate", "trigger", "sight_iron", "sight_rear", "shell_load", "fx_shell"}
EMPTY_NODES = {"viewmodel", "aim_iron", "muzzle", "ejection", "arm_R", "arm_R_fore", "arm_L", "arm_L_fore", "camera",
               "mount_optic", "mount_laser", "mount_barrel", "mount_stock", "mount_charm"}
REMOVED_NODES = ("sight_reddot", "reddot_reticle", "aim_reddot", "laser", "laser_emitter", "mag", "mag_standard",
                 "mag_extended")
MATERIALS = {"receiver", "barrel", "pump", "stock", "grip", "steel_dark", "shell", "brass"}
REGIONS = {"body", "barrel", "magazine", "stock", "grip", "handguard", "optic", "laser", "detail", "blade", "handle",
           "hardware", "none"}
DURATIONS = {"idle": (2.0, 2.0), "draw": (0.6, 0.9), "fire": (0.6, 0.86), "fire_last": (0.15, 0.35),
             "reload_start": (0.35, 0.55), "reload_shell": (0.45, 0.6), "reload_end": (0.3, 0.5),
             "reload_end_empty": (0.65, 0.95), "inspect": (3.0, 4.5), "walk": (0.6, 1.6), "sprint": (0.5, 0.8),
             "tac_sprint": (0.4, 0.9)}
LOOPING = {"idle", "walk", "sprint", "tac_sprint"}
NO_CAMERA = {"idle", "walk", "sprint", "tac_sprint"}
PUMP_EVENTS = ("pump_back", "pump_forward")
EVENT_NAMES = {"equip", "inspect_move", "shell_in"} | set(PUMP_EVENTS)
REQUIRED_EVENTS = {"draw": {"equip"}, "fire": set(PUMP_EVENTS), "reload_shell": {"shell_in"},
                   "reload_end_empty": set(PUMP_EVENTS), "inspect": {"inspect_move"}}
# the shell-mode reload chain (section 25): these clip ends / starts must be the same pose
JOINS = (("reload_start", "end", "reload_shell", "start"), ("reload_shell", "end", "reload_shell", "start"),
         ("reload_shell", "end", "reload_end", "start"), ("reload_shell", "end", "reload_end_empty", "start"))
FRAMED = ("rest", "idle", "fire", "fire_last", "walk")
FREE = ("stock", "grip", "receiver", "pump", "trigger", "safety", "loading_gate", "tactical_stock", "heavy_stock",
        "wire_stock", "charm_grass")      # may run off the bottom / right edge (receiver and stock sit low right)
MIN_TRAVEL, MAX_TRAVEL = 0.05, 0.07
GATE_MAX = 30.0
ADS_CLEAR, ADS_PX = 0.03, 2.0


def check_nodes(g, r, label):
    for nm, parent in PARENTS.items():
        if not r.check(nm in g.names, f"{label}: missing node {nm}"):
            continue
        i = g.names[nm]
        par = g.parent.get(i)
        pname = g.nodes[par]["name"] if par is not None else None
        r.check(pname == parent, f"{label}: node {nm} parent is {pname}, expected {parent}")
        if nm in MESH_NODES:
            r.check("mesh" in g.nodes[i], f"{label}: node {nm} should carry a mesh")
        if nm in EMPTY_NODES:
            r.check("mesh" not in g.nodes[i], f"{label}: node {nm} should be empty")
    for n in g.nodes:
        if "rotation" in n:
            r.check(abs(np.linalg.norm(n["rotation"]) - 1) < 1e-5, f"{label}: {n.get('name')} rotation not normalized")
    for side in "RL":
        t = g.nodes[g.names[f"arm_{side}_fore"]].get("translation", [0, 0, 0])
        r.check(np.allclose(t, [0, -0.30, 0], atol=1e-6), f"{label}: arm_{side}_fore translation {t}")
    for nm in REMOVED_NODES:
        r.check(nm not in g.names, f"{label}: {nm} must not be in the file (section 25: tube magazines; section 19)")
    for nm in ("viewmodel", "camera"):
        n = g.nodes[g.names[nm]]
        r.check(not any(k in n for k in ("translation", "rotation", "scale")), f"{label}: {nm} rest pose not identity")
    s = g.nodes[g.names["shell_load"]].get("scale")
    r.check(s is not None and np.allclose(s, 0), f"{label}: shell_load must be collapsed (scale 0) at rest")


def check_materials(g, r, label):
    names = [m.get("name") for m in g.json.get("materials", [])]
    for nm in names:
        r.check(nm in MATERIALS, f"{label}: unexpected material {nm}")
    for nm in MATERIALS - set(names):
        r.check(False, f"{label}: material {nm} missing")
    for m in g.json.get("materials", []):
        region = m.get("extras", {}).get("region")
        r.check(region in REGIONS, f"{label}: material {m.get('name')} has no valid extras.region ({region})")
    r.note("regions: " + ", ".join(f"{m['name']}={m.get('extras', {}).get('region')}" for m in g.json["materials"]))


def _track(g, a, node, path):
    i = g.names[node]
    for ch in a["channels"]:
        if ch["target"]["node"] == i and ch["target"]["path"] == path:
            s = a["samplers"][ch["sampler"]]
            return g.accessor(s["input"])[:, 0].astype(np.float64), g.accessor(s["output"]).astype(np.float64), s
    return None


def check_clips(g, r, label):
    anims = {a["name"]: a for a in g.json.get("animations", [])}
    vm, cam = g.names["viewmodel"], g.names["camera"]
    for nm, (lo, hi) in DURATIONS.items():
        if not r.check(nm in anims, f"{label}: clip {nm} missing"):
            continue
        a = anims[nm]
        dur = g.duration(a)
        r.check(lo - 1e-4 <= dur <= hi + 1e-4, f"{label}: {nm} length {dur:.3f} outside [{lo}, {hi}]")
        r.note(f"{nm}: {dur:.3f}s, {len(a['channels'])} channels")
        for ch in a["channels"]:
            node, path = ch["target"]["node"], ch["target"]["path"]
            s = a["samplers"][ch["sampler"]]
            ts = g.accessor(s["input"])[:, 0]
            vs = g.accessor(s["output"]).astype(np.float64)
            r.check(node != vm, f"{label}: {nm} animates viewmodel")
            r.check(s.get("interpolation", "LINEAR") in ("LINEAR", "STEP"), f"{label}: {nm} interpolation")
            r.check(abs(ts[0]) < 1e-6 and np.all(np.diff(ts) > 0), f"{label}: {nm} bad key times")
            if path == "rotation":
                r.check(np.all(np.abs(np.linalg.norm(vs, axis=1) - 1) < 1e-4), f"{label}: {nm} quaternions")
            if path == "scale":
                r.check(s.get("interpolation") == "STEP", f"{label}: {nm} {g.nodes[node]['name']} scale must be STEP")
                r.check(np.all(np.isin(np.round(vs, 6), [0.0, 1.0])), f"{label}: {nm} scale not 0/1")
            if nm in LOOPING:
                r.check(np.allclose(vs[0], vs[-1], atol=1e-6) or (path == "rotation" and np.allclose(vs[0], -vs[-1])),
                        f"{label}: {nm} first/last keys differ on {g.nodes[node]['name']}.{path}")
            if node == cam:
                r.check(nm not in NO_CAMERA, f"{label}: {nm} must not animate the camera")
                r.check(path == "rotation", f"{label}: {nm} animates camera {path}")
                eul = np.array([euler_deg(q) for q in vs])
                r.check(np.all(np.abs(eul[:, :2]) <= CAM_TURN + 1e-3) and np.all(np.abs(eul[:, 2]) <= CAM_ROLL + 1e-3),
                        f"{label}: {nm} camera beyond +-1.5/+-3 deg (max {np.abs(eul).max(0).round(2)})")
                r.check(np.allclose(np.abs(vs[-1]), [0, 0, 0, 1], atol=1e-5), f"{label}: {nm} camera not at rest at end")
        ev = a.get("extras", {}).get("events")
        if r.check(isinstance(ev, list), f"{label}: {nm} has no extras.events"):
            for e in ev:
                r.check(e.get("name") in EVENT_NAMES, f"{label}: {nm} unknown event {e.get('name')}")
                r.check(0 <= e.get("time", -1) <= dur + 1e-6, f"{label}: {nm} event outside the clip")
            have = {e["name"] for e in ev}
            need = REQUIRED_EVENTS.get(nm, set())
            r.check(need <= have, f"{label}: {nm} missing events {need - have}")
            if set(PUMP_EVENTS) <= have:
                at = {e["name"]: e["time"] for e in ev if e["name"] in PUMP_EVENTS}
                r.check(at["pump_back"] < at["pump_forward"], f"{label}: {nm} pump events out of order")
            r.note("   events: " + ", ".join(f"{e['name']}@{e['time']:.3f}" for e in ev))
    return anims


def check_pump(g, r, label, anims):
    """The pump slides along the bore only, never forward of its rest, is fully racked in fire / reload_end_empty,
    press-checked in inspect, untouched in fire_last, and ends every clip at rest; the gate turns about X only."""
    rest = np.asarray(g.nodes[g.names["pump"]].get("translation", [0, 0, 0]), float)
    for nm, a in anims.items():
        tr = _track(g, a, "pump", "translation")
        z = np.zeros(1) if tr is None else tr[1][:, 2] - rest[2]
        side = 0.0 if tr is None else float(np.abs(tr[1][:, :2] - rest[:2]).max())
        r.check(side < 1e-6, f"{label}: {nm} moves the pump off the bore (x/y)")
        r.check(z.min() > -1e-4 and z.max() <= MAX_TRAVEL, f"{label}: {nm} pump outside 0..{MAX_TRAVEL} "
                                                           f"({z.min():.4f}..{z.max():.4f})")
        r.check(abs(z[-1]) < 1e-4, f"{label}: {nm} ends with the pump open")
        if nm in ("fire", "reload_end_empty"):
            r.check(z.max() >= MIN_TRAVEL, f"{label}: {nm} pump travel {z.max():.3f} < {MIN_TRAVEL}")
            r.note(f"{nm}: pump travel {z.max() * 1000:.1f} mm")
        if nm == "inspect":
            r.check(0.01 < z.max() < MIN_TRAVEL, f"{label}: inspect press-check travel {z.max():.3f}")
        if nm in ("fire_last", "reload_start", "reload_shell", "reload_end", "idle", "walk"):
            r.check(z.max() < 1e-6, f"{label}: {nm} must not work the pump")
        gate = _track(g, a, "loading_gate", "rotation")
        if gate is not None:
            q = gate[1]
            r.check(float(np.abs(q[:, 1:3]).max()) < 1e-6, f"{label}: {nm} turns the loading gate about another axis")
            deg = np.degrees(2 * np.arctan2(q[:, 0], q[:, 3]))
            r.check(deg.min() > -1e-3 and deg.max() <= GATE_MAX and abs(deg[-1]) < 1e-3,
                    f"{label}: {nm} loading gate {deg.min():.1f}..{deg.max():.1f} deg, ends {deg[-1]:.1f}")
            r.check(nm == "reload_shell", f"{label}: {nm} moves the loading gate (only reload_shell may)")


def _shown(g, a, t):
    s = g.sample(a, t).get(g.names["shell_load"], {}).get("scale")
    s = np.asarray(g.nodes[g.names["shell_load"]].get("scale", [1, 1, 1]), float) if s is None else s
    return bool(np.all(s > 0.5))


def check_shell(g, r, label, anims):
    """shell_load: hidden outside reload_shell; there shown once, appearing off screen (below the view) and hidden
    once it has gone into the tube (inside the receiver / tube, near the tube's axis)."""
    for nm, a in anims.items():
        tr = _track(g, a, "shell_load", "scale")
        if nm != "reload_shell":
            r.check(tr is None or np.allclose(tr[1], 0), f"{label}: {nm} shows shell_load (only reload_shell may)")
            continue
        if not r.check(tr is not None, f"{label}: reload_shell never shows shell_load"):
            continue
        ts, vs, _ = tr
        on = np.round(vs[:, 0]) > 0.5
        r.check(not on[0] and not on[-1] and np.count_nonzero(np.diff(on.astype(int)) != 0) == 2,
                f"{label}: reload_shell must show the shell once (hidden at both ends)")
        t_on, t_off = float(ts[np.argmax(on)]), float(ts[len(on) - 1 - np.argmax(on[::-1]) + 1])
        si, gi = g.names["shell_load"], g.names["gun"]
        W = g.world_matrices(g.sample(a, t_on + 1e-4))
        p = W[si][:3, 3]
        t = np.tan(np.radians(27.0))
        below = p[2] < 0 and p[1] / -p[2] < -t * 1.02
        r.check(below or p[2] > 0, f"{label}: the shell appears on screen at t={t_on:.3f} "
                                   f"(view {np.round(p, 3)}; it must pop in below the view)")
        W = g.world_matrices(g.sample(a, t_off - 1e-4))
        loc = np.linalg.inv(W[gi]) @ W[si]
        import ks3_rig
        d = loc[:3, 3] + ks3_rig.P0
        import ks3_model as km
        r.check(abs(d[0]) < 0.004 and abs(d[1] - km.TUBE_Y) < 0.006 and d[2] < 0.02,
                f"{label}: the shell disappears at t={t_off:.3f} outside the tube (design frame {np.round(d, 4)})")
        r.note(f"reload_shell: shell shown {t_on:.3f}..{t_off:.3f} s, gone into the tube at design {np.round(d, 3)}")


def _pose(g, a, t):
    s = g.sample(a, t)
    out = {}
    for i, n in enumerate(g.nodes):
        tr, rot, sc = g.local_trs(i)
        v = s.get(i, {})
        out[i] = (v.get("translation", tr), v.get("rotation", rot), v.get("scale", sc))
    return out


def check_joins(g, r, label, anims):
    """The shell-reload clips share their end / start poses, so the runtime chains them without a jump."""
    for a_name, a_end, b_name, b_end in JOINS:
        if a_name not in anims or b_name not in anims:
            continue
        a, b = anims[a_name], anims[b_name]
        pa = _pose(g, a, g.duration(a) if a_end == "end" else 0.0)
        pb = _pose(g, b, g.duration(b) if b_end == "end" else 0.0)
        worst = (0.0, "")
        for i in pa:
            if g.nodes[i].get("name") in ("shell_load", "fx_shell"):
                continue
            dt = float(np.abs(np.asarray(pa[i][0]) - np.asarray(pb[i][0])).max())
            q1, q2 = np.asarray(pa[i][1]), np.asarray(pb[i][1])
            dq = float(np.degrees(2 * np.arccos(min(1.0, abs(float(np.dot(q1, q2)))))))
            err = max(dt * 1000.0, dq)            # mm or degrees
            if err > worst[0]:
                worst = (err, g.nodes[i].get("name"))
        r.check(worst[0] < 1.0, f"{label}: {a_name} ({a_end}) and {b_name} ({b_end}) do not join: "
                                f"{worst[1]} off by {worst[0]:.2f} (mm / deg)")
        r.note(f"join {a_name}.{a_end} -> {b_name}.{b_end}: worst {worst[0]:.3f} ({worst[1]})")


def check_iron(g, r, label):
    """Aim down the iron sights (rest pose, eye on aim_iron): nothing in front closer than ADS_CLEAR, the rear notch
    and the front bead on the screen centre."""
    W0 = g.world_matrices({})
    inv = np.linalg.inv(W0[g.names["aim_iron"]])
    near = (np.inf, "")
    for i, n in enumerate(g.nodes):
        if "mesh" not in n or n["name"] in ("fx_shell", "shell_load", "tube_extended"):
            continue
        M = inv @ W0[i]
        if abs(np.linalg.det(M[:3, :3])) < 1e-12:
            continue
        pts = np.concatenate([p["positions"] for p in g.mesh_primitives(n["mesh"])]) @ M[:3, :3].T + M[:3, 3]
        front = pts[pts[:, 2] < 0]
        if len(front):
            d = float(np.linalg.norm(front, axis=1).min())
            if d < near[0]:
                near = (d, n["name"])
    r.check(near[0] >= ADS_CLEAR, f"{label}: ADS iron: {near[1]} {near[0]:.3f} m from the eye (needs >= {ADS_CLEAR})")
    focal = 540.0 / np.tan(np.radians(27.0))
    for nm in ("sight_rear", "sight_iron"):
        p = (inv @ W0[g.names[nm]])[:3, 3]
        px = focal * p[:2] / -p[2]
        r.check(p[2] < 0 and bool(np.all(np.abs(px) <= ADS_PX)), f"{label}: ADS iron: {nm} {np.round(px, 2)} px off centre")
    r.note(f"ADS iron: nearest part {near[1]} {near[0]:.3f} m")


def check_muzzle(g, r, label):
    """The hip muzzle stays out of the crosshair area (16:9, 54 deg)."""
    W = g.world_matrices({})
    p = W[g.names["muzzle"]][:3, 3]
    t = np.tan(np.radians(27.0))
    x, y = p[0] / -p[2] / t, p[1] / -p[2] / t
    r.check(abs(x) > 0.12 or abs(y) > 0.12, f"{label}: hip muzzle in the crosshair area ({x:+.3f}, {y:+.3f})")
    r.note(f"hip muzzle at ({x / 2:+.3f}, {-y / 2:+.3f}) screen heights from the centre")


def check_framing(path, r, label, margin=0.03):
    """Barrel and attachments (largest build) inside the frame at the runtime FOV with a margin in hip / idle / fire /
    walk; receiver, stock, grip and forend may run off the bottom-right edge; sprints are only reported."""
    import framing
    clips = FRAMED + ("sprint", "tac_sprint")
    for loadout in ("default", "largest"):
        for (name, asp), ((left, top, right, bottom), _) in framing.report(path, clips, exclude=FREE,
                                                                          loadout=loadout).items():
            aspect = framing.ASPECTS[asp]
            m_right, m_bottom = (1 - right) * aspect, 1 - bottom
            if name in FRAMED:
                r.check(left >= 0 and top >= 0 and m_right >= margin - 1e-4 and m_bottom >= margin - 1e-4,
                        f"{label}: {name} at {asp} ({loadout}) leaves the frame "
                        f"(box {left:.3f},{top:.3f}..{right:.3f},{bottom:.3f})")
            r.note(f"framing {loadout:8s} {name:10s} {asp:4s}: x {left:.3f}..{right:.3f} y {top:.3f}..{bottom:.3f} "
                   f"(right margin {m_right:.3f}H, bottom margin {m_bottom:.3f}H){'' if name in FRAMED else ' [report only]'}")


def claims(path, names):
    return "pump" in names


def check(path, arms, r):
    g = GLTF(path)
    label = path.replace("\\", "/").rsplit("/", 1)[-1][:-4]
    check_container(g, r, label)
    check_meshes(g, r, label)
    check_nodes(g, r, label)
    check_materials(g, r, label)
    anims = check_clips(g, r, label)
    check_pump(g, r, label, anims)
    check_shell(g, r, label, anims)
    check_joins(g, r, label, anims)
    check_sights(g, r, label)
    check_iron(g, r, label)
    check_eye_distance(g, arms, r, anims, root="gun", label=label)
    check_hand_joints(g, r, label)
    check_curve_rate(g, r, label)
    check_muzzle(g, r, label)
    check_framing(path, r, label)
    import check_attach
    check_attach.check_weapon(path, arms, r, label)
    tris = sum(len(g.accessor(p["indices"])) // 3 for n in g.nodes if "mesh" in n and n["name"] != "fx_shell"
               for p in g.json["meshes"][n["mesh"]]["primitives"])
    from model_quality import BUDGETS
    r.check(tris <= BUDGETS["long_gun"], f"{label}: {tris} triangles over the long-gun budget")
    r.note(f"{label} triangles (excluding attachments and fx_shell): {tris}")
