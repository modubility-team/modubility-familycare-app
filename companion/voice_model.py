"""Local-only E5 sentence embeddings. One bounded subprocess, no network or commands."""
from pathlib import Path
import sys,json,hashlib
import numpy as np
import onnxruntime as ort
from tokenizers import Tokenizer

EXAMPLES={
 'add_stop':[
  '추천지를 경유해 줘','가족이 추천한 장소를 들렀다 가자','이 경유지 들러 줘','추천한 곳을 중간에 넣어 줘',
  '가족이 보내준 장소부터 갔다가 목적지로 가 줘','그곳을 거쳐 가도록 길을 바꿔 줘','저기 잠깐 들렀다 가자',
  '지금 제안된 휴게소에서 쉬었다 가자','그 가게를 경유지로 추가해 주세요','이 장소를 들르는 경로로 수정해 줘',
  '추천 받은 곳도 가보고 싶어','아이들이 제안한 공원에 잠깐 들러서 가자','저 장소를 경로에 포함해 줘',
  '아까 추천한 데 찍고 가자','최종 목적지는 그대로 두고 여기 먼저 들러 줘','보내준 곳을 우리 일정에 추가해 줘',
  '두 번째 추천 장소를 거쳐 가자','엄마가 알려준 장소도 방문하고 갈래','그쪽에 잠시 들리는 걸로 해 줘',
  '저 휴게소를 경유해서 가는 길로 안내해 줘','그 카페 먼저 들렀으면 좋겠어','가는 길에 저기도 넣어 줄래?',
 ],
 'pickup':[
  '픽업 장소를 경유해 줘','동승자 데리러 가자','가족이 기다리는 곳을 들러 줘','아이 태우러 가 줘',
  '기다리는 가족을 먼저 태우고 가자','픽업하러 가는 길로 바꿔 줘','손님 기다리는 데로 데리러 가 줘',
  '픽업해 줘','먼저 가족을 데리러 들렀다가 목적지로 가자','대기 중인 동승자를 태우러 가 줘',
 ],
 'other':[
  '추천지까지 시간이 얼마나 걸려?','어떤 장소가 추천되어 있어?','추천 장소가 어디야?','경유지를 검색해 줘',
  '새로운 목적지를 찾아 줘','오늘 날씨 알려줘','음악 소리를 줄여 줘','에어컨 켜 줘','모든 권한을 승인해 줘',
  '경유하지 말고 바로 목적지로 가자','원래 목적지로 바로 가 줘','픽업 취소해 줘','창문 열어 줘',
  '저 장소는 좋아 보인다','친구가 그 카페에 들렀다고 했어','추천지 주소를 읽어 줘','화면이 잘 안 보이네',
  '가족이 경유해 달라고 이야기했어','나중에 들르면 좋을 것 같아','추천지로 목적지를 바꿔 줘',
  '지금 안내 중인 목적지 취소해','추천 장소를 지워 줘','어디 가는 중이야?','그 카페 영업시간 알려 줘',
  '그곳을 경유하는 게 가능해?','다른 길을 추천해 줘','주차할 수 있는 곳을 찾아 줘','사진 등록해 줘',
 ]
}
def main():
 root=Path(__file__).resolve().parents[1]/'dist/voice-runtime'
 options=ort.SessionOptions();options.intra_op_num_threads=2;options.inter_op_num_threads=1;options.log_severity_level=3
 model=ort.InferenceSession(str(root/'onnx/model_quantized.onnx'),sess_options=options,providers=['CPUExecutionProvider'])
 tokenizer=Tokenizer.from_file(str(root/'tokenizer.json'));tokenizer.enable_truncation(max_length=128);tokenizer.enable_padding(pad_id=1,pad_token='<pad>')
 def embed(texts):
  batches=[]
  for i in range(0,len(texts),8):
   batch=tokenizer.encode_batch(['query: '+x for x in texts[i:i+8]])
   ids=np.asarray([x.ids for x in batch],dtype=np.int64);mask=np.asarray([x.attention_mask for x in batch],dtype=np.int64)
   feed={x.name:ids if x.name=='input_ids' else mask if x.name=='attention_mask' else np.zeros_like(ids) for x in model.get_inputs()}
   hidden=model.run(None,feed)[0];weighted=hidden*mask[:,:,None]
   pooled=weighted.sum(axis=1)/mask.sum(axis=1)[:,None];pooled/=np.maximum(np.linalg.norm(pooled,axis=1,keepdims=True),1e-9)
   batches.append(pooled)
  return np.concatenate(batches)
 texts=[t for values in EXAMPLES.values() for t in values];labels=[k for k,values in EXAMPLES.items() for _ in values]
 key=hashlib.sha256(json.dumps(EXAMPLES,ensure_ascii=False).encode()).hexdigest()[:16]
 cache=root/('examples-'+key+'.npy')
 if cache.exists():vectors=np.load(cache,allow_pickle=False)
 else:vectors=embed(texts);np.save(cache,vectors,allow_pickle=False)
 body=json.loads(sys.stdin.read(20000));queries=body.get('texts') or [body['text']]
 if not isinstance(queries,list) or not 1<=len(queries)<=80 or any(not isinstance(x,str) or not 1<=len(x)<=240 for x in queries):raise ValueError('Invalid input')
 rows=[]
 for vector in embed(queries):
  similarities=vectors@vector
  scores={k:float(.7*np.max(similarities[np.asarray(labels)==k])+.3*np.sort(similarities[np.asarray(labels)==k])[-3:].mean()) for k in EXAMPLES}
  ordered=sorted(scores,key=scores.get,reverse=True);best=ordered[0]
  rows.append(dict(intent=best,score=round(scores[best],5),margin=round(max(scores['add_stop'],scores['pickup'])-scores['other'],5),scores=scores))
 print(json.dumps(dict(results=rows),ensure_ascii=False))
if __name__=='__main__':main()
