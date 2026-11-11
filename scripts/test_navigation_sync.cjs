const fs=require('node:fs'),vm=require('node:vm'),assert=require('node:assert/strict');
const source=fs.readFileSync(__dirname+'/live_demo.js','utf8');
const code=source.slice(source.indexOf('function syncNavigation(s)'),source.indexOf('async function refresh()'));
const labels={},lines=[];
const c={syncedNavigation:'',syncedRoute:'',destination:null,origin:{point:[1,2]},originMarker:{old:true},destinationMarker:null,navigationLine:null,
 $:id=>labels[id]??(labels[id]={}),marker:(old,p)=>({p}),coords:p=>p.join(','),
 map:{removeLayer:()=>{},fitBounds:()=>{}},L:{polyline:points=>({addTo(){lines.push(points);return this},getBounds(){return points}})}};
vm.createContext(c);vm.runInContext(code,c);
const s={connected:true,active:true,navigationStartId:'first',navigationRouteId:'a',destinationPoint:[127,37],destination:'선택 목적지',navigationRoute:[[127,37],[128,38]]};
c.syncNavigation({...s,active:false});assert.equal(c.destination,null);
c.syncNavigation(s);assert.equal(c.destination.name,'선택 목적지');assert.equal(c.origin,null);assert.equal(c.originMarker,null);assert.equal(lines.length,1);
c.destination={name:'사용자가 선택한 다음 장소'};c.syncNavigation(s);assert.equal(c.destination.name,'사용자가 선택한 다음 장소');assert.equal(lines.length,1);
c.syncNavigation({...s,navigationStartId:'second',destination:'다음 목적지',navigationRouteId:'b'});assert.equal(c.destination.name,'다음 목적지');assert.equal(lines.length,2);
c.syncNavigation({...s,connected:false});assert.equal(c.destination.name,'다음 목적지');
console.log('Navigation panel sync: preview, start, editing, next route, disconnect passed');
