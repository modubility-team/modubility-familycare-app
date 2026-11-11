"""Validate against the vehicle's authenticated, current capability report."""
import math

def validate_control(state,role,options,delegated=False):
    if role not in ('owner','family'):return 403,'이 연결에서는 차량을 조절할 수 없어요'
    if not isinstance(options,dict) or set(options)!={'control','value'}:return 400,'제어 항목과 값을 확인하세요'
    key,value=options['control'],options['value']
    if not isinstance(key,str) or not 1<=len(key)<=120:return 400,'제어 항목을 확인하세요'
    item=next((c for c in state.get('controls',[]) if isinstance(c,dict) and c.get('id')==key),None)
    if not item or not item.get('available'):return 409,'현재 차량에서 지원 상태를 확인할 수 없는 기능이에요'
    if key.startswith('hvac.fan:') and state.get('climatePower') is False:return 409,'공조 전원이 꺼져 있어요. 전원을 켠 뒤 풍량을 조절하세요'
    if item.get('ownerOnly') and role!='owner' and not delegated:return 403,'문·창문·핸들 제어는 소유자 전용입니다'
    if (role=='owner' or key.startswith(('door.','window.'))) and not state.get('parked'):return 409,'P단 정차 후 차량을 조절하세요'
    if type(value) not in (float,int) or not math.isfinite(value) or value!=int(value):return 400,'올바른 조절 값을 선택하세요'
    if not item.get('min',0)<=value<=item.get('max',0):return 400,'차량이 허용하는 조절 범위를 벗어났어요'
    return None
