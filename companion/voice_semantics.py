"""Interprets route intent without changing any state. All destinations come from current requests."""
from pathlib import Path
import json,re,subprocess,threading
LOCK=threading.Lock()
ROUTE_KINDS={'destination','waypoint','pickup'}
def compact(s):return re.sub(r'[^가-힣a-z0-9]','',s.lower())
def denied(text):
 s=compact(text)
 return not re.search(r'[가-힣]',text) or bool(re.search(r'아니|지마|지말|하지마|하지말|말아|말자|말고|취소|거절|보류|나중에|다음에|필요없|생각없|원하지|원치|싫|안할|안해|안하|안(?:가|갈|들|경유|추가|방문)|않(?:아|고|을|는)|랬|말했|라고|달래|라는데|라던데|만약|가정|무시|규칙|프롬프트|명령어|삭제|지워|얼마나|몇분|몇시간|어디야|어디니|어디인지|영업시간|가능해|좋을까|어떨까|날씨|뉴스|창문|시동|온도|에어컨|모든권한|전부승인',s))
def predict(text):
 root=Path(__file__).resolve().parents[1]
 python=root/'dist/voice-runtime/venv/Scripts/python.exe'
 if not python.exists():raise RuntimeError('노트북에 음성 이해 모델을 먼저 설치해 주세요')
 if not LOCK.acquire(blocking=False):raise RuntimeError('앞선 음성을 해석 중이에요. 잠시 후 말해 주세요')
 try:
  r=subprocess.run([str(python),'-X','utf8',str(root/'companion/voice_model.py')],input=json.dumps({'text':text},ensure_ascii=False),encoding='utf-8',stdout=subprocess.PIPE,stderr=subprocess.DEVNULL,timeout=12,creationflags=getattr(subprocess,'CREATE_NO_WINDOW',0))
  if r.returncode:raise RuntimeError('음성 이해 모델을 시작하지 못했어요. 노트북 설치 상태를 확인하세요')
  return json.loads(r.stdout)['results'][0]
 except (subprocess.TimeoutExpired,ValueError,KeyError):raise RuntimeError('음성 해석 시간이 지났어요. 다시 말해 주세요') from None
 finally:LOCK.release()
def title(r):return r.get('options',{}).get('landmark',r['title']) if r['kind']=='pickup' else r['title']
def ordinal(text):
 s=compact(text)
 for index,words in enumerate([('첫번째','첫째','1번'),('두번째','둘째','2번'),('세번째','셋째','3번'),('네번째','넷째','4번'),('다섯번째','다섯째','5번')]):
  if any(s.startswith(w) for w in words):return index
 return None
def interpret(text,candidates,selected_id='',choice_intent='',classifier=predict):
 if not isinstance(text,str) or not 1<=len(text.strip())<=240:raise ValueError('짧은 문장으로 다시 말해 주세요')
 text=text.strip();all_candidates=[r for r in candidates if r['kind'] in ROUTE_KINDS and r['status']=='pending']
 if denied(text):return dict(intent='clarify',message='경유를 실행하라는 요청인지 확실하지 않아 경로를 바꾸지 않았어요. 들를 장소와 행동을 다시 말해 주세요',candidates=[])
 s=compact(text);number=ordinal(text)
 choice=choice_intent in ('add_stop','pickup') and (number is not None or any(compact(title(r)) in s for r in all_candidates))
 if not choice and not re.search(r'(?:줘요?|주세요|주라|줄래요?|줄수있어|줄수있니|주실래요?|주시겠어요|부탁해|부탁할게|가자|가죠|가요|갑시다|갈래요?|좋겠어요?|싶어요?|넣자|삼자|보자|들르자|출발하자|들러|들려|넣어|추가해|수정해|바꿔|해줘|해요|경유해)$',s):
  return dict(intent='clarify',message='정보를 묻는 말인지 경유 요청인지 확인이 필요해요. 들를 장소와 행동을 다시 말해 주세요',candidates=[])
 if choice:intent=choice_intent;score=1.;margin=1.
 else:
  prediction=classifier(text);intent=prediction['intent'];score=prediction['score'];margin=prediction['margin']
 if intent in ('add_stop','pickup'):intent='pickup' if any(w in s for w in ('픽업','데리','태우','대기','기다리','모시러')) else 'add_stop'
 if intent not in ('add_stop','pickup') or score<.84 or margin<.008:
  return dict(intent='clarify',message='경유 요청인지 확실하지 않아요. 들를 장소와 함께 다시 말해 주세요',candidates=[])
 eligible=[r for r in all_candidates if intent!='pickup' or r['kind']=='pickup']
 if intent=='add_stop' and any(w in s for w in ('추천','제안')):eligible=[r for r in eligible if r['kind']!='pickup']
 if not eligible:return dict(intent='clarify',message='좌표가 있는 장소 제안이나 픽업 요청이 없어요. 휴대폰에서 장소를 검색해 보내 주세요',candidates=[])
 matches=[r for r in eligible if len(compact(title(r)))>=2 and compact(title(r)) in s]
 # A name or a numbered choice takes precedence over the selected card.
 if number is not None:matches=eligible[number:number+1]
 if len(matches)>1:return dict(intent='clarify',message='같은 이름의 장소가 여러 곳이에요. 번호로 골라 주세요',candidates=[r['id'] for r in eligible],choiceIntent=intent)
 target=matches[0] if len(matches)==1 else None
 if number is not None and target is None:return dict(intent='clarify',message='그 번호의 장소는 없어요. 표시된 번호로 골라 주세요',candidates=[r['id'] for r in eligible],choiceIntent=intent)
 references=('추천','제안','거기','저기','여기','그곳','이곳','저곳','그쪽','저쪽','이쪽','그가게','저가게','그카페','저카페','그장소','이장소','보내준','보낸','보내온','말한','얘기한','알려준','가족이원한','아이들이','경유지')
 if target is None and number is None:
  # Never turn an unrecognized spoken place into the sole candidate by accident.
  explicit=[re.sub(r'(?:으로|에서|까지|을|를|에|도|부터)$','',word) for word in re.findall(r'[가-힣A-Za-z0-9]+(?:역|공원|휴게소|카페|병원|박물관|주차장|마트|편의점|센터)(?:으로|에서|까지|을|를|에|도|부터)?',text)]
  unknown=[word for word in explicit if word not in ('휴게소','카페','공원','병원','박물관','주차장','마트','편의점','센터') and not any(compact(word) in compact(title(r)) for r in eligible)]
  if unknown or not (any(w in s for w in references) or intent=='pickup' or selected_id):
   return dict(intent='clarify',message='말씀하신 장소를 제안 목록에서 확인하지 못했어요. 표시된 장소 이름이나 번호로 다시 말해 주세요',candidates=[r['id'] for r in eligible],choiceIntent=intent)
 if target is None and len(eligible)==1:target=eligible[0]
 if target is None and selected_id:target=next((r for r in eligible if r['id']==selected_id),None)
 if target is None:return dict(intent='clarify',message='어느 장소를 경유할까요? 번호나 장소 이름을 말해 주세요',candidates=[r['id'] for r in eligible],choiceIntent=intent)
 return dict(intent=intent,targetId=target['id'],label=title(target),score=score,margin=margin,engine='local-e5')
