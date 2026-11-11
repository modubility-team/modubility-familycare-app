// Unit tests for asynchronous camera replacement, without a real browser or camera.
const vm=require('node:vm'),fs=require('node:fs'),assert=require('node:assert/strict');
const nodes=new Map();
function element(id){if(!nodes.has(id))nodes.set(id,{textContent:'',value:'',hidden:true,disabled:false,removeAttribute(name){delete this[name]},setAttribute(name,value){this[name]=value},replaceChildren(){}});return nodes.get(id)}
const frames=[];let status={configured:true,url:'http://192.168.0.10:8080/shot.jpg'};
const context=vm.createContext({key:'test',URL,Date,JSON,Error,Number,console,setInterval(){},document:{hidden:false,getElementById:element,querySelectorAll(){return[]},addEventListener(){}},fetch:async(path,options)=>{
  if(path==='/api/state')return{json:async()=>({connected:false,links:{}})};
  const body=JSON.parse(options.body);
  if(body.action==='camera-frame')return new Promise(resolve=>frames.push(result=>resolve({ok:true,json:async()=>result})));
  if(body.action==='camera-status')return{ok:true,json:async()=>status};
  throw Error('Unexpected request: '+body.action);
}});
vm.runInContext(fs.readFileSync(__dirname+'/live_demo.js','utf8'),context);
const run=code=>vm.runInContext(code,context);
(async()=>{
  await run('refresh()');
  element('query').value='부산역';run('origin={point:[129.1,35.2]};controls()');
  assert.equal(element('searchButton').disabled,true);
  assert.equal(element('connectionRecovery').hidden,false);
  assert.equal(element('reconnect').disabled,false,'Recovery must remain available while the vehicle is disconnected');
  run('state={connected:true,sharing:true,busy:false};controls()');
  assert.equal(element('searchButton').disabled,false,'Search must return after recovery');
  assert.equal(element('connectionRecovery').hidden,true);
  assert.equal(element('query').value,'부산역');
  assert.deepEqual(Array.from(run('origin.point')),[129.1,35.2]);
  run('state.sharing=false;controls()');
  assert.equal(element('searchButton').disabled,true,'Expired family sharing must expose recovery');
  assert.equal(element('connectionRecovery').hidden,false);
  run('working=true;controls()');assert.equal(element('reconnect').disabled,true);run('working=false');
  run('setCameraViewing(true)');const old=run('cameraFrame()');
  run('setCameraViewing(false);setCameraViewing(true)');
  frames.shift()({image:'OLD',capturedAt:1});await old;
  assert.equal(element('cameraPreview').hidden,true,'Old source must not appear after changing the camera');
  const current=run('cameraFrame()');frames.shift()({image:'NEW',capturedAt:2});await current;
  assert.equal(element('cameraPreview').src,'data:image/jpeg;base64,NEW');
  assert.equal(element('cameraPreview').hidden,false);
  const pending=run('cameraFrame()');run('setCameraViewing(false)');frames.shift()({image:'LATE',capturedAt:3});await pending;
  assert.equal(element('cameraPreview').hidden,true,'Closing preview must win over a pending frame');
  assert.equal(element('cameraPreview').src,undefined);
  element('cameraUrl').value='192.168.0.20:8080';run('cameraAddressDirty=true');
  await run('loadCameraSettings(false)');assert.equal(element('cameraUrl').value,'192.168.0.20:8080','Background status must preserve an edited address');
  await run('loadCameraSettings(true)');assert.equal(element('cameraUrl').value,'http://192.168.0.10:8080');
  assert.equal(element('cameraAddress').textContent,'설정된 카메라 주소: http://192.168.0.10:8080');
  console.log('PASS: search recovery, expired sharing, selection preservation, camera replacement and reload');
})().catch(e=>{console.error(e);process.exitCode=1});
