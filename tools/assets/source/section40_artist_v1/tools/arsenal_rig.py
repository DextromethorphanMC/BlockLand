"""Shared section-40 IK rig and authored, feed-layout-aware animation clips (offline only)."""
import numpy as np
import armrig
import hands
from ik import UPPER_LEN
from vmath import quat_euler,quat_mul,quat_rotate,quat_identity,frame_quat,norm
from p9_clips import Clip
from arsenal_data import GUNS,gameplay

SHOULDERS={'R':np.array([.17,-.36,.12]),'L':np.array([-.15,-.29,-.21])}
POLES={'R':np.array([.5,-.2,.7]),'L':np.array([-1,-.8,.05])}
GUN_T=np.array([.135,-.14,-.37]);GUN_Q=quat_euler(2.5,3,5)


def track(u,keys):
    if u<=keys[0][0]:return np.asarray(keys[0][1],float)
    for (a,x),(b,y) in zip(keys,keys[1:]):
        if u<=b:
            t=(u-a)/(b-a);t=t*t*(3-2*t)
            return np.asarray(x)*(1-t)+np.asarray(y)*t
    return np.asarray(keys[-1][1],float)


def anchor(centre,fwd,thumb,side,r=.016):
    q=hands.palm_frame(fwd,thumb,side)
    c=np.array(centre)-hands.wrap_distance(r)*quat_rotate(q,(0,0,1))
    return c-quat_rotate(q,(0,-hands.PALM_LEN/2,0)),q


class Pose:
    def __init__(self,name):
        self.name=name;self.d=GUNS[name]
        self.t=GUN_T.copy();self.q=GUN_Q.copy();self.mag=np.array(self.d['mag']);self.mag_q=quat_identity();self.mag_s=1
        self.bolt=0.;self.bolt_roll=0.;self.trigger=0.;self.left=0.;self.right=0.;self.charging=0.;self.cam=quat_identity()

    def target(self,side):
        if side=='R':
            centre=(0,-.045,.018) if self.name!='p90' else (0,-.04,.025)
            wrist,q=anchor(centre,(0,-.95,.25),(-1,0,0),'R')
            if self.right:
                bw,bq=anchor((.038,.045,.025+self.bolt),(0,0,-1),(0,1,0),'R',.007)
                wrist=wrist*(1-self.right)+bw*self.right
                q=hands.nlerp_quats([q,bq],[1-self.right,self.right])
        else:
            wrist,q=anchor(self.d['support'],(.85,.2,0),(0,0,-1),'L',.023)
            if self.left:
                kind=self.d['mag_kind']
                offset={'side':(-.10,0,0),'top':(-.025,.02,-.10),'helical':(-.035,.01,-.07),'drum':(-.035,-.025,0)}.get(kind,(0,-.07,0))
                mw,mq=anchor(self.mag+quat_rotate(self.mag_q,offset),(1,.1,0),(0,1,0),'L',.018)
                wrist=wrist*(1-self.left)+mw*self.left
                q=hands.nlerp_quats([q,mq],[1-self.left,self.left])
            if self.charging:
                cw,cq=anchor(np.array(self.d['charging'])+[0,0,self.bolt],(0,0,-1),(0,1,0),'L',.006)
                wrist=wrist*(1-self.charging)+cw*self.charging
                q=hands.nlerp_quats([q,cq],[1-self.charging,self.charging])
        fingers={k:np.array(v,float) for k,v in hands.POSES['grip'].items()}
        if side=='R':fingers['index']+=np.array([-8,-4])*self.trigger
        return self.t+quat_rotate(self.q,wrist),quat_mul(self.q,q),fingers

    def solve(self):
        trs={'gun':{'translation':self.t,'rotation':self.q},'bolt':{'translation':np.array([0,0,self.bolt]),'rotation':quat_euler(0,0,self.bolt_roll)},
             'mag':{'translation':self.mag,'rotation':self.mag_q,'scale':np.full(3,self.mag_s)},'camera':{'rotation':self.cam}}
        if self.name=='m4a1':trs['charging_handle']={'translation':np.array([0,0,self.bolt if self.charging>0 else 0])}
        info={}
        for side in ['R','L']:
            target=self.target(side)
            ts,ik=armrig.solve_side(side,SHOULDERS[side],POLES[side],target,limit=True)
            trs.update(ts);trs['arm_'+side]['translation']=SHOULDERS[side]
            info[side]=ik;info['target_'+side]=target
        return trs,info


def durations(name):
    g=gameplay()[name]
    return {'idle':2.,'draw':g['drawTime'],'fire':60/g['rpm'],'fire_last':60/g['rpm'],
            'reload':g['reload']['tactical'],'reload_empty':g['reload']['empty'],'inspect':3.6,'walk':1.,'sprint':.66,'tac_sprint':.66}


def pose(name,clip,t,duration):
    p=Pose(name);u=t/duration;kind=p.d['mag_kind'];zero=(0,0,0)
    if clip in ('idle','walk','sprint','tac_sprint'):
        a=2*np.pi*u;gain={'idle':.0007,'walk':.0025,'sprint':.004,'tac_sprint':.004}[clip]
        p.t+=np.array([gain*np.sin(a),gain*.7*np.sin(2*a),gain*.3*np.cos(a)])
        p.q=quat_mul(p.q,quat_euler(.3*np.sin(a),.4*np.sin(2*a),.5*np.sin(a)))
        if clip in ('sprint','tac_sprint'):
            p.q=quat_mul(p.q,quat_euler(-12,18 if clip=='sprint' else 28,-18))
            p.t+=np.array([.02,-.02,-.025])
    elif clip=='draw':
        p.t+=track(u,[(0,(.035,-.22,.04)),(.12,(.04,-.23,.04)),(.75,(-.002,.004,-.002)),(1,zero)])
        p.q=quat_mul(p.q,quat_euler(*track(u,[(0,(-8,-35,-22)),(.7,(1,2,1)),(1,zero)])))
    elif clip in ('fire','fire_last'):
        kick=float(track(u,[(0,0),(.13,1),(.55,-.10),(1,0)]))
        p.t+=np.array([0,.0015,.004])*kick;p.q=quat_mul(p.q,quat_euler(0,.9*kick,-.3*kick));p.trigger=max(0,kick)
        if name=='ssg08':
            p.bolt=float(track(u,[(0,0),(.20,0),(.38,.085),(.52,.085),(.76,0),(1,0)]))
            p.bolt_roll=float(track(u,[(0,0),(.14,0),(.24,-55),(.73,-55),(.83,0),(1,0)]))
            p.right=float(track(u,[(0,0),(.13,0),(.24,1),(.78,1),(.95,0),(1,0)]))
        else:p.bolt=float(track(u,[(0,0),(.22,.032),(.73,0),(1,0)]))
    elif clip.startswith('reload'):
        p.t+=track(u,[(0,zero),(.18,(0,.015,-.01)),(.72,(0,.015,-.01)),(1,zero)])
        p.q=quat_mul(p.q,quat_euler(*track(u,[(0,zero),(.20,(0,5,-14)),(.73,(0,5,-14)),(1,zero)])))
        out=np.array({'side':(-.25,0,0),'top':(0,.13,0),'helical':(0,.13,0),'drum':(0,-.17,0)}.get(kind,(0,-.20,0)))
        k=float(track(u,[(0,0),(.16,0),(.34,1),(.56,1),(.74,0),(1,0)]))
        p.mag+=out*k;p.mag_s=0 if .40<=u<.54 else 1
        if name=='calico':
            # Slide the helical magazine clear of the forward optic bridge before lifting it.
            p.mag=np.array(p.d['mag'])+track(u,[(0,zero),(.16,zero),(.26,(0,0,.20)),(.36,(0,.13,.20)),(.56,(0,.13,.20)),(.66,(0,0,.20)),(.74,zero),(1,zero)])
        p.left=float(track(u,[(0,0),(.14,1),(.78,1),(.94,0),(1,0)]))
        # Withdrawal remains on the actual feed axis until the magazine clears its well.
        p.mag_q=quat_euler(0,0,float(track(u,[(0,0),(.27,0),(.36,12),(.58,12),(.68,0),(1,0)])))
        if name=='p90':p.mag_q=quat_euler(0,-25*k,0)  # rear lifts first; front stays below the optic bridge
        if clip=='reload_empty':
            p.bolt=float(track(u,[(0,0),(.78,0),(.84,.032 if name!='ssg08' else .085),(.92,0),(1,0)]))
            if name=='ssg08':p.right=float(track(u,[(0,0),(.75,0),(.81,1),(.92,1),(1,0)]))
            else:p.charging=float(track(u,[(0,0),(.75,0),(.81,1),(.92,1),(1,0)]))
    elif clip=='inspect':
        p.q=quat_mul(p.q,quat_euler(*track(u,[(0,zero),(.22,(12,-8,-28)),(.43,(12,-8,-28)),(.65,(-9,5,17)),(.80,(-9,5,17)),(1,zero)])))
        p.t+=track(u,[(0,zero),(.25,(-.02,.02,-.025)),(.8,(-.02,.02,-.025)),(1,zero)])
    return p


def clips(name):
    result=[]
    for n,d in durations(name).items():
        events=[]
        if n=='draw':events=[(0,'equip')]
        elif n.startswith('reload'):
            events=[(.14*d,'mag_release'),(.34*d,'mag_out'),(.74*d,'mag_in')]
            if n=='reload_empty':events += [(.84*d,'bolt_back'),(.92*d,'bolt_forward')]
        elif n=='inspect':events=[(.2*d,'inspect_move'),(.62*d,'inspect_move')]
        elif n.startswith('fire') and name=='ssg08':events=[(.38*d,'bolt_back'),(.76*d,'bolt_forward')]
        result.append(Clip(n,d,lambda t,n=n,d=d:pose(name,n,t,d),events=events,fps=240 if n.startswith('fire') else 60,
                           loop=n in ('idle','walk','sprint','tac_sprint'),extra_times=[x[0] for x in events],force_nodes=('gun','bolt','mag')))
    return result
