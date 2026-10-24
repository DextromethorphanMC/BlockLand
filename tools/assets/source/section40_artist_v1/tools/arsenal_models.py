"""Section-40 meshes: distinct receiver, stock and feed layouts; no renamed donor GLBs.
All geometry is game art, not engineering geometry. Metres; -Z forward; grip pivot at origin.
"""
import numpy as np
import shapes as sh
from meshkit import Mesh
from arsenal_data import GUNS


def box(x0,y0,z0,x1,y1,z1,mat='steel',bevel=.0012):
    return sh.box(x0,y0,z0,x1,y1,z1,bevel,mat)


def tube(r,z0,z1,mat='barrel',inner=None,segments=24):
    inner=inner if inner is not None else r*.56
    return sh.revolve([(r,z0),(r,z1),(inner,z1),(inner,z0),(r,z0)],segments,mat)


def cylinder(r,z0,z1,mat='steel_dark',segments=20):
    c=min(.001,(z1-z0)/5,r/5)
    return sh.revolve([(0,z0),(r-c,z0),(r,z0+c),(r,z1-c),(r-c,z1),(0,z1)],segments,mat)


def side_profile(points,half,mat,holes=(),bevel=.0015):
    return sh.extrude_x(points,holes,half,((bevel,0),(0,bevel)),mat)


def rod(a,b,r,mat='steel_dark',segments=12):
    a,b=np.array(a),np.array(b);z=(b-a)/np.linalg.norm(b-a)
    ref=np.array([0.,1,0]) if abs(z[1])<.9 else np.array([1.,0,0])
    x=np.cross(ref,z);x/=np.linalg.norm(x);y=np.cross(z,x)
    return sh.place(cylinder(r,0,np.linalg.norm(b-a),mat,segments),origin=a,x_axis=x,y_axis=y,z_axis=z)


def rail(z0,z1,y,w=.018):
    m=box(-w,y-.004,z0,w,y,z1,'steel_dark',.0007)
    for z in np.arange(z0+.003,z1-.004,.010):
        m.merge(box(-w-.002,y,z,w+.002,y+.003,z+.005,'steel_dark',.0005))
    return m


def fasteners(m,width,y,zs):
    for z in zs:
        for s in [-1,1]:
            a=(s*(width-.0006),y,z);b=(s*(width+.0016),y,z)
            m.merge(rod(a,b,.0022))
    return m


def magazine(d,extended=False):
    length=d.get('extended',d['mag_len']) if extended else d['mag_len'];kind=d['mag_kind']
    if kind=='drum':
        # Ten lobed outer chambers around a closed central rotary body; swapped as one unit.
        m=cylinder(.051,-.042,.042,'magazine',40)
        for a in np.linspace(0,2*np.pi,10,endpoint=False):
            m.merge(cylinder(.010,-.039,.039,'magazine',12).translated((.044*np.cos(a),.044*np.sin(a),0)))
        m.merge(cylinder(.015,.042,.045,'steel_dark',20))
        return m
    if kind=='helical':
        r=.033 if not extended else .037
        m=cylinder(r,-length,0,'magazine',32)
        for z in np.arange(-length+.012,-.005,.018):
            m.merge(tube(r+.0008,z-.0015,z+.0015,'magazine',r-.0002,32))
        m.merge(cylinder(r*.72,.0001,.003,'steel_dark',24))
        for a in np.linspace(0,2*np.pi,6,endpoint=False):
            m.merge(box(-.003,-.003,-.001,.003,.003,.002,'steel_dark').translated((.018*np.cos(a),.018*np.sin(a),.003)))
        return m
    if kind=='top':
        # Opaque amber polymer avoids a second transparency pass; no fake exposed rounds.
        m=box(-.024,-.010,-length,.024,.010,0,'magazine',.003)
        m.merge(cylinder(.025,-.008,.008,'magazine',32).transformed(np.array([[1,0,0,0],[0,0,1,0],[0,-1,0,-length+.026],[0,0,0,1]])))
        for z in np.arange(-length+.07,-.02,.022):
            m.merge(box(-.025,-.009,z,.025,.011,z+.002,'magazine',.0005))
        return m
    # Sheet magazines with crowned corners, a rolled floor plate and longitudinal pressed ribs.
    w=.011 if kind in ('grip','side') else .014
    depth=.032 if kind in ('grip','side','curved') else .037
    curve=.025 if kind=='curved' else .004
    sections=[]
    for t in np.linspace(0,1,9 if kind=='curved' else 3):
        ring=sh.rounded_rect(-w,-depth/2,w,depth/2,.002,3)
        sections.append(np.stack([ring[:,0],np.full(len(ring),-length*t),ring[:,1]-curve*t*t],1))
    m=sh.loft(sections,'magazine')
    m.merge(box(-w-.002,-length-.004,-depth/2-curve-.002,w+.002,-length+.001,depth/2-curve+.002,'magazine'))
    for side in [-1,1]:
        for z in [-.009,.009]:
            pts=[]
            for t in np.linspace(.1,.9,3):pts.append((side*(w+.0004),-length*t,z-curve*t*t))
            for a,b in zip(pts,pts[1:]):m.merge(rod(a,b,.0008,'magazine',8))
    # Feed lips with the visible top round, independent of the receiver and hand animation.
    for side in [-1,1]:m.merge(box(side*w-.001,-.003,-.013,side*w+.001,.003,.013,'magazine',.0004))
    m.merge(cylinder(.0048,-.015,.013,'brass',16).translated((0,.002,0)))
    if kind=='side':
        m=sh.place(m,x_axis=(0,-1,0),y_axis=(1,0,0),z_axis=(0,0,1))
    return m


def shell(name):
    r,length=(.0048,.019)
    if name=='p90':r,length=.0039,.029
    elif name in ('ssg08','scar'):r,length=.006,.051
    elif name=='m4a1':r,length=.0048,.045
    elif name=='jackhammer':r,length=.0097,.058
    m=sh.revolve([(0,-length/2),(r,-length/2),(r,length/2-.002),(r*1.06,length/2-.002),
                  (r*1.06,length/2),(r*.78,length/2),(r*.78,-length/2+.002),(0,-length/2+.002)],24,'brass')
    return m


def build(name):
    d=GUNS[name];kind=d['kind'];w=d['width'];z0,z1=d['body'];y=.055
    parts={}
    # Receiver silhouettes, including real thumb openings and recognisable feed layouts.
    if kind in ('tube','roller','bolt_action'):
        radius=.021 if kind!='bolt_action' else .018
        body=cylinder(radius,z0,z1,'steel',32).translated((0,y,0))
        body.merge(box(-w,-.014,-.075,w,.045,.065,'polymer' if kind=='roller' else 'steel',.003))
        if kind=='roller':
            body.merge(cylinder(.010,-.29,-.10,'steel',24).translated((0,.082,0)))
            body.merge(box(-.025,.026,-.12,.025,.044,-.052,'steel',.002))
        if kind=='bolt_action':
            body.merge(side_profile([(-.32,-.02),(-.30,.037),(.12,.037),(.16,-.015),(.06,-.045),(-.17,-.045)],.025,'polymer',bevel=.004))
    elif kind=='bullpup':
        outer=[(-.245,-.065),(-.255,.018),(-.205,.083),(.21,.083),(.245,.03),(.245,-.085),(.16,-.10),(.08,-.075),(-.02,-.095),(-.08,-.035),(-.14,-.075)]
        holes=[[(-.12,-.01),(-.08,.027),(-.02,.025),(-.012,-.025),(-.055,-.055)],[(.025,-.038),(.08,.012),(.13,.013),(.14,-.04),(.08,-.062)]]
        body=side_profile(outer,w,'polymer',holes,.003)
        body.merge(box(-.019,.075,-.22,.019,.093,.21,'steel',.002))
    elif kind=='rotary':
        body=side_profile([(-.30,-.018),(-.30,.07),(-.18,.09),(.24,.078),(.255,-.08),(.20,-.10),(.14,-.01),(.06,-.01),(.02,-.065),(-.08,-.048)],w,'polymer',bevel=.004)
        body.merge(box(-.026,.078,-.23,.026,.105,.16,'steel',.003))
    elif kind=='carbine':
        body=cylinder(.023,-.18,.11,'steel',24).translated((0,y,0))
        body.merge(side_profile([(-.17,.04),(-.13,-.029),(-.045,-.032),(-.025,-.004),(.09,-.005),(.12,.04)],w,'steel'))
        body.merge(box(.023,.04,-.07,.033,.065,.043,'steel_dark',.003))
        body.merge(rail(-.18,.09,.081))
    else:
        top=.081 if kind!='helical' else .070
        body=side_profile([(z0+.01,-.01),(z0,.015),(z0,top-.015),(z0+.02,top),(z1-.015,top),(z1,top-.015),(z1,-.01)],w,'steel',bevel=.002)
        if kind=='monolithic':body.merge(rail(z0+.01,z1-.015,.083))
        if kind=='box_smg':
            body.merge(box(-.018,-.025,-.13,.018,.015,-.045,'handguard',.003))
            for z in [-.12,-.10,-.08,-.06]:body.merge(box(-.02,-.026,z,.02,-.018,z+.004,'handguard'))
    # Small fasteners, selector lever and a shallow ejection recess: silhouette/detail, not extra textures.
    fasteners(body,w,.028,np.linspace(max(z0+.03,-.18),z1-.025,4))
    body.merge(box(w+.0001,.036,-.035,w+.0008,.061,.035,'steel_dark',.001))
    body.merge(rod((-w-.002,.002,.025),(-w-.002,-.008,.004),.0025))
    if name in ('uzi','calico','p90'):
        oy=d['optic_y'];oz=d['optic_z'];span=.025 if name!='calico' else .027
        # Open riser: the iron line stays below the bridge; the feed path stays behind it.
        for side in [-1,1]:
            body.merge(box(side*span-.002,.078,oz-.018,side*span+.002,oy-.002,oz+.018,'steel_dark',.0006))
        body.merge(box(-span-.002,oy-.002,oz-.023,span+.002,oy,oz+.023,'steel_dark',.0006))
    parts['receiver']=body
    # Trigger/grip assembly (P90's integrated grip stays part of its thumb-hole receiver).
    if kind!='bullpup':
        parts['pistol_grip']=side_profile([(-.016,.004),(.025,.002),(.052,-.101),(.016,-.112),(-.015,-.033)],.016,'grip',bevel=.004)
        guard_outer=[(-.068,.002),(.012,.002),(.018,-.04),(-.055,-.045),(-.073,-.023)]
        guard_hole=[(-.059,-.006),(.002,-.007),(.005,-.030),(-.051,-.034),(-.062,-.02)]
        parts['trigger_guard']=side_profile(guard_outer,.006,'steel_dark',[guard_hole],.0008)
        parts['trigger']=side_profile([(-.028,-.004),(-.021,-.005),(-.024,-.025),(-.014,-.029),(-.019,-.033),(-.033,-.028)],.003,'steel_dark',bevel=.0006)
    # Barrel has an open bore and stepped crown, never a capped cylinder in front of the muzzle.
    front=d['front'];rear=min(-.09,z0+.02)
    parts['barrel']=tube(.0095 if name!='jackhammer' else .016,front+.016,rear,'barrel',segments=32).translated((0,y,0))
    parts['muzzle_brake']=tube(.014 if name!='jackhammer' else .022,front,front+.035,'barrel',.005 if name!='jackhammer' else .010,32).translated((0,y,0))
    for z in np.linspace(front+.010,front+.027,3):
        parts['muzzle_brake'].merge(tube(.0148 if name!='jackhammer' else .0228,z,z+.0012,'steel_dark',.014 if name!='jackhammer' else .022,32).translated((0,y,0)))
    # Fore-ends: perforated sheet tube on STEN, ribbed polymer on MP5, rail channels on rifles.
    if kind=='tube':
        band=sh.perforated_band(.025,-.050,.050,.004,.0015,'handguard')
        parts['handguard']=sh.place(band,origin=(0,y,-.285),x_axis=(1,0,0),y_axis=(0,0,1),z_axis=(0,-1,0))
    elif kind in ('carbine','roller','monolithic','bolt_action','rotary'):
        lo=max(front+.07,-.39);hi=min(z0+.07,-.13)
        if hi<=lo:hi=lo+.10
        if kind=='monolithic':lo,hi=-.31,-.15
        h=box(-.025,-.01,lo,.025,.035,hi,'handguard',.005)
        for z in np.arange(lo+.01,hi-.008,.018):
            for side in [-1,1]:h.merge(box(side*.026-.0007,.004,z,side*.026+.0007,.023,z+.008,'steel_dark',.001))
        if kind in ('carbine','monolithic'):h.merge(rail(lo+.005,hi-.005,.076,.014))
        parts['handguard']=h
    # Stocks are separate hideable meshes; fixed bullpup bodies deliberately have no stock node.
    if name in ('uzi','sten'):
        st=Mesh()
        for x in [-.016,.016]:st.merge(rod((x,.025,.055),(x,.005,d['rear']),.004))
        st.merge(rod((-.016,.005,d['rear']),(-.016,-.085,d['rear']),.004))
        st.merge(rod((.016,.005,d['rear']),(.016,-.085,d['rear']),.004))
        st.merge(box(-.02,-.09,d['rear']-.007,.02,.016,d['rear']+.005,'stock',.003));parts['stock']=st
    elif name in ('mp5','scar','m4a1','ssg08'):
        back=d['rear'];st=side_profile([(.09,.037),(back-.025,.04),(back,.025),(back,-.105),(back-.025,-.11),(.11,-.035)],.024,'stock',bevel=.004)
        st.merge(box(-.027,-.11,back-.003,.027,.04,back+.008,'grip',.003))
        st.merge(box(-.025,.026,.13,.025,.049,back-.025,'stock',.003))
        if name=='m4a1':st.merge(cylinder(.014,.08,.19,'steel_dark').translated((0,.035,0)))
        parts['stock']=st
    # Calico has no default stock, but its small capped attachment interface is explicit.
    if name=='calico':parts['stock']=box(-.012,-.075,.045,.012,-.049,.065,'grip',.002)
    # Iron sights: front post/ears and a genuine rear aperture, at one shared sight height.
    if name!='ssg08':
        sy=d['sight'];fz=front+.05;rz=min(z1-.015,.10)
        post=box(-.0011,sy-.010,fz-.002,.0011,sy,fz+.002,'steel_dark',.0003)
        for x in [-.009,.009]:post.merge(box(x-.0015,sy-.015,fz-.003,x+.0015,sy+.003,fz+.003,'steel_dark',.0004))
        post.merge(box(-.012,y,fz-.005,.012,sy-.014,fz+.005,'steel_dark'))
        rear=tube(.007,-.002,.002,'steel_dark',.0032,24).translated((0,sy,rz))
        rear.merge(box(-.009,y,rz-.004,.009,sy-.006,rz+.004,'steel_dark'))
        parts['sight_iron']=post;parts['sight_rear']=rear
    # Charging handle / bolt pivots at the receiver, with an independent visible travel channel.
    cp=np.array(d['charging'])
    if name=='ssg08':
        b=cylinder(.006,-.04,.045,'steel_dark',16).translated((0,.06,0))
        b.merge(rod((0,.06,.025),cp,.003))
        b.merge(cylinder(.007,-.004,.004,'steel_dark',16).translated(cp))
    elif name=='uzi':
        b=box(-.004,.081,-.024,.004,.089,-.016,'steel_dark')
        b.merge(box(-.012,.088,-.027,.012,.092,-.013,'steel_dark'))
    elif name=='p90':
        b=Mesh()
        for side in [-1,1]:b.merge(box(side*.035-.004,.066,-.205,side*.035+.004,.079,-.178,'steel_dark',.002))
    elif name=='m4a1':
        # Carrier at the port reciprocates; the separate T charging handle does not move on firing.
        b=box(.024,.043,-.032,.027,.060,.031,'steel_dark',.001)
        parts['charging_handle']=box(-.027,.079,.109,.027,.087,.123,'steel_dark')
    elif name=='jackhammer':
        b=box(-.027,-.026,-.28,.027,-.007,-.19,'handguard',.003)
        for z in np.arange(-.275,-.19,.015):b.merge(box(-.028,-.027,z,.028,-.021,z+.003,'handguard'))
    else:
        base=cp.copy();base[0]=0
        b=rod(base,cp,.003)
        b.merge(box(cp[0]-.004,cp[1]-.004,cp[2]-.009,cp[0]+.004,cp[1]+.004,cp[2]+.009,'steel_dark'))
    parts['bolt']=b
    parts['mag_standard']=magazine(d)
    if 'extended' in d:parts['mag_extended']=magazine(d,True)
    parts['fx_shell']=shell(name)
    return parts
