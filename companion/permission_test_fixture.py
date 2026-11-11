"""Previously-approved device fixture for legacy validation tests.
Full real pairing/claim/result coverage lives in test_delegation.py.
"""
import delegation
def prepared_device(relay,sid,tokens):
    s=relay.sessions[sid]
    if getattr(relay,'fixture_paired',False):return
    relay.call(sid,tokens['car'],'sync',dict(s['state'],ignitionOn=True,ignitionEpoch='fixture-cycle',parked=True))
    d=delegation.pair(s,None)
    d.update(epoch='fixture-cycle',seat='ROW_1_LEFT',limits={'climate':{'min':18,'max':28}},scopes=['climate','climatePower','timer','profile','hold','route'])
    tokens['family']=d['token'];relay.fixture_paired=True
