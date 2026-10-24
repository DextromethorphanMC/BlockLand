"""Stable material names/regions, with magazine AO isolated from the receiver."""
from arsenal_data import GUNS
REGIONS={'steel':'body','steel_dark':'detail','barrel':'barrel','polymer':'body','grip':'grip','stock':'stock','handguard':'handguard','magazine':'magazine','brass':'detail'}
SETS={'steel':'finish','steel_dark':'machined','barrel':'machined','polymer':'polymer','grip':'polymer','stock':'polymer','handguard':'polymer','magazine':'magazine'}
def models():
    return {'gun/'+name:{'ao':{'mode':'together','root':'gun','distance':.018,'separate':[['bolt'],['mag_standard'],['mag_extended']]},'materials':{m:((name+'/'+SETS[m]) if m in SETS else 'common/brass',region) for m,region in REGIONS.items()}} for name in GUNS}
