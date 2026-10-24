"""Numerical rig regression, without launching a client or declaring visual acceptance."""
from pathlib import Path
import sys
sys.path.insert(0,str(Path(__file__).resolve().parent))
import numpy as np
import arsenal_rig as rig
from arsenal_data import GUNS,gameplay


def main():
    data=gameplay();worst=0
    for name in GUNS:
        maximum=0
        for clip in rig.clips(name):
            for t in np.linspace(0,clip.duration,int(np.ceil(clip.duration*60))+1):
                _,info=clip.pose(float(t)).solve()
                for side in ['R','L']:
                    err=float(np.linalg.norm(info[side]['hand']-info['target_'+side][0]))
                    maximum=max(maximum,err)
            if clip.name in ('reload','reload_empty'):
                assert clip.duration==data[name]['reload']['empty' if clip.name=='reload_empty' else 'tactical']
            if name=='ssg08' and clip.name.startswith('fire'):
                assert {'bolt_back','bolt_forward'}<={event for _,event in clip.events}
        assert maximum<.002,(name,maximum)
        worst=max(worst,maximum)
        print(name,'IK wrist error mm',round(maximum*1000,6),'reload',data[name]['reload'])
    print('PASS: 9 rigs sampled at 60 Hz, maximum wrist target error mm',worst*1000)


if __name__=='__main__':main()
