"""Session-authorized in-memory PCM forwarding; never writes audio to disk."""
import base64,secrets,collections
def status(s,now):
 m=s.get('music')
 if not m:return dict(active=False)
 active=now-m['last']<4 and now<m['until']
 if not active:m['frames'].clear()
 return dict(active=active,stream=m['id'],paused=m['paused'],format='PCM 16-bit stereo 48000Hz',signal=m['peak']>20,framesReceived=m['seq']+1)
def handle(s,role,action,b,now):
 if action=='music-start':
  if role!='owner':raise ValueError('소유자 연결에서 휴대폰 음악 공유를 시작하세요')
  if s.get('heartbeat') is None or now-s['heartbeat']>15:raise ValueError('차량 연결을 먼저 확인하세요')
  m=dict(id=secrets.token_urlsafe(20),last=now,until=min(s['expires'],now+7200),paused=False,seq=-1,peak=0,frames=collections.deque(maxlen=6));s['music']=m
  return dict(stream=m['id'],sampleRate=48000,channels=2)
 if action=='music-command':
  if role not in ('car','owner'):raise ValueError('차량 또는 소유자만 재생을 조절할 수 있어요')
  if b.get('command') not in ('pause','resume','stop'):raise ValueError('재생 동작을 확인하세요')
  m=s.get('music')
  if m and ('stream' not in b or b['stream']==m['id']):
   if b['command']=='stop':s.pop('music',None)
   else:m['paused']=b['command']=='pause'
  return status(s,now)
 m=s.get('music')
 if action=='music-frame':
  if role!='owner' or not m or b.get('stream')!=m['id'] or now>=m['until']:raise ValueError('음악 공유 연결이 종료됐어요')
  if s.get('heartbeat') is None or now-s['heartbeat']>15:raise ValueError('차량 연결이 끊겨 음악 공유를 중단했어요')
  seq=b.get('seq');payload=b.get('pcm');peak=b.get('peak')
  if type(seq) is not int or seq<0 or not isinstance(payload,str) or not 4<=len(payload)<=60000 or type(peak) is not int or not 0<=peak<=32768:raise ValueError('음악 전송 형식을 확인하세요')
  try:raw=base64.b64decode(payload,validate=True)
  except Exception:raise ValueError('음악 데이터 형식을 확인하세요') from None
  if not 4<=len(raw)<=38400 or len(raw)%4:raise ValueError('지원하는 음악 데이터 크기가 아니에요')
  if seq<=m['seq']:return status(s,now)
  if seq>m['seq']+100:raise ValueError('음악 전송 순서를 다시 시작하세요')
  m['seq']=seq;m['last']=now;m['peak']=peak;m['frames'].append(dict(seq=seq,pcm=payload,at=now))
  return status(s,now)
 if action=='music-pull':
  if role!='car':raise ValueError('음악은 연결된 차량에서만 수신할 수 있어요')
  state=status(s,now)
  if not m or not state['active']:return dict(state,frames=[])
  after=b.get('after',-1) if b.get('stream')==m['id'] else -1
  if type(after) is not int:raise ValueError('수신 순서를 확인하세요')
  frames=[dict(seq=f['seq'],pcm=f['pcm']) for f in m['frames'] if f['seq']>after and now-f['at']<1.2]
  return dict(state,frames=frames[-3:])
 raise ValueError('없는 음악 기능이에요')
