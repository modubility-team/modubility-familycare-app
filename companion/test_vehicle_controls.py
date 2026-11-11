import copy, unittest
from vehicle_controls import validate_control
class ControlValidationTest(unittest.TestCase):
    def setUp(self):
        self.state={'parked':True,'controls':[dict(id='hvac.fan:ROW_1_LEFT',min=0,max=8,available=True,ownerOnly=False),dict(id='door.lock:ROW_1_LEFT',min=0,max=1,available=True,ownerOnly=True)]}
    def test_owner_and_approval_requests(self):
        self.assertIsNone(validate_control(self.state,'owner',dict(control='door.lock:ROW_1_LEFT',value=1)))
        self.state['parked']=False
        self.assertIsNone(validate_control(self.state,'family',dict(control='hvac.fan:ROW_1_LEFT',value=3)))
        self.assertEqual(409,validate_control(self.state,'owner',dict(control='hvac.fan:ROW_1_LEFT',value=3))[0])
    def test_roles_cannot_escalate(self):
        for role in ['family','guest','observer','car']:
            self.assertEqual(403,validate_control(self.state,role,dict(control='door.lock:ROW_1_LEFT',value=1))[0])
    def test_range_type_and_capability(self):
        for value in [True,'1',float('nan'),float('inf'),-1,9,1.5]:
            self.assertEqual(400,validate_control(self.state,'owner',dict(control='hvac.fan:ROW_1_LEFT',value=value))[0])
        self.assertEqual(409,validate_control(self.state,'owner',dict(control='ignition',value=1))[0])
        self.state['controls'][0]['available']=False
        self.assertEqual(409,validate_control(self.state,'owner',dict(control='hvac.fan:ROW_1_LEFT',value=1))[0])
    def test_rejects_extra_fields(self):
        for options in [None,{},dict(control='hvac.fan:ROW_1_LEFT',value=1,ownerOnly=False)]:
            self.assertEqual(400,validate_control(self.state,'owner',options)[0])
if __name__=='__main__':unittest.main()
