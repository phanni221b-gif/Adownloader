'use strict';
// playback_diag.js + playback_diag_bg.js: nothing is recorded until the app activates a session;
// then player requests (successes included) and media element state are reported as hosts, enum
// states, numbers and the session-salted key shared with DiagKeys.java — never a URL or header value.
const fs=require('fs'),vm=require('vm'),assert=require('assert/strict');
const SALT='0123456789abcdef',URL1='https://cdn.test/a/seg1.ts?x=1',KEY='dffc033f49b5c52b';
const flush=()=>new Promise(r=>setImmediate(r));
(async()=>{
 // ---- background ----
 const listeners={},timers=[],sentNative=[];let nativeState={active:false,salt:'',session:''};
 const ev=name=>({addListener(fn){listeners[name]=fn;}});
 const bg={runtime:{sendNativeMessage(app,m){assert.equal(app,'adownloader');sentNative.push(JSON.parse(JSON.stringify(m)));return Promise.resolve(nativeState);},onMessage:ev('message')},
  tabs:{sendMessage(){return Promise.resolve();}},
  webRequest:{onBeforeRequest:ev('before'),onSendHeaders:ev('send'),onCompleted:ev('completed'),onErrorOccurred:ev('error')}};
 vm.runInContext(fs.readFileSync('app/src/main/assets/media_sniffer/playback_diag_bg.js','utf8'),vm.createContext({browser:bg,URL,setTimeout:(fn)=>timers.push(fn),Map,Set,Math,Number,String,Promise,Array}));
 const req=(id,url,extra={})=>{const d={requestId:id,url,type:'media',tabId:7,frameId:0,timeStamp:1000,statusCode:200,fromCache:false,requestHeaders:[{name:'Cookie',value:'sid=SECRET'},{name:'Referer',value:'https://page.test/watch?id=SECRET'}],responseHeaders:[{name:'Content-Type',value:'video/mp2t'}],...extra};listeners.before(d);listeners.send(d);listeners.completed({...d,timeStamp:1250});};
 await flush();
 req('0',URL1);
 timers.shift()();await flush();
 assert.deepEqual(sentNative[1].net,[],'nothing is recorded before a session is active');
 nativeState={active:true,salt:SALT,session:'P-1'};
 timers.shift()();await flush(); // this poll activates
 req('1',URL1);
 listeners.error({requestId:'2',url:'https://cdn.test/b.m3u8?token=SECRET',type:'xmlhttprequest',tabId:-1,frameId:0,timeStamp:5,error:'NS_ERROR_NET_RESET'});
 listeners.completed({requestId:'3',url:'https://img.test/a.png',type:'image',tabId:7,statusCode:200});
 timers.shift()();await flush();
 const batch=sentNative[sentNative.length-1];
 assert.equal(batch.session,'P-1');
 assert.equal(batch.net.length,2,'images are not player requests');
 const r=batch.net[0];
 assert.equal(r.key,KEY,'same key as DiagKeys.java');
 assert.equal(r.host,'cdn.test');assert.equal(r.ext,'ts');assert.equal(r.status,200);assert.equal(r.mime,'video/mp2t');
 assert.equal(r.cookie,true);assert.equal(r.referer,true);assert.equal(r.ms,250);
 assert.equal(batch.net[1].tab,-1);assert.equal(batch.net[1].error,'NS_ERROR_NET_RESET');
 const text=JSON.stringify(batch);
 assert.ok(!text.includes('SECRET')&&!text.includes('://')&&!text.includes('?'),'no URL, query or header value leaves the page: '+text);
 const state=await listeners.message({kind:'diag-query'},{tab:{id:7},frameId:0});
 assert.deepEqual(JSON.parse(JSON.stringify(state)),{kind:'diag-state',active:true,salt:SALT});

 // ---- content ----
 const docListeners={},intervals=[];let onMessage=null;
 const buffered={length:1,start:()=>0,end:()=>14.5};
 const video={tagName:'VIDEO',matches:q=>q==='video,audio',isConnected:true,currentSrc:'https://cdn.test/live/index.m3u8?token=SECRET',src:'',readyState:4,networkState:2,paused:false,ended:false,seeking:false,currentTime:4.2,duration:600,playbackRate:1,buffered,seekable:buffered,videoWidth:1280,videoHeight:720,muted:false,error:{code:3,message:'NS_ERROR_DOM_MEDIA_DECODE_ERR https://cdn.test/a.ts?token=SECRET'},mediaKeys:null,getVideoPlaybackQuality:()=>({totalVideoFrames:100,droppedVideoFrames:2,corruptedVideoFrames:0})};
 const sent=[];
 const page={top:null,document:{addEventListener(t,fn,capture){(docListeners[t]=docListeners[t]||[]).push({fn,capture});},removeEventListener(t,fn){docListeners[t]=(docListeners[t]||[]).filter(x=>x.fn!==fn);},querySelectorAll:()=>[video]},
  addEventListener(){},browser:{runtime:{onMessage:{addListener(fn){onMessage=fn;}},sendMessage(m){sent.push(JSON.parse(JSON.stringify(m)));return Promise.resolve(m.kind==='diag-query'?{kind:'diag-state',active:true,salt:SALT}:undefined);}}},
  URL,Date,Math,Number,String,Object,setInterval:(fn)=>{intervals.push(fn);return intervals.length;},clearInterval(){intervals.length=0;},WeakMap,Promise};
 page.window=page;page.top=page;
 vm.runInContext(fs.readFileSync('app/src/main/assets/media_sniffer/playback_diag.js','utf8'),vm.createContext(page));
 await flush();
 assert.ok(docListeners.waiting&&docListeners.waiting[0].capture,'media events are read in the capture phase');
 assert.ok(docListeners.encrypted,'EME encrypted events are observed');
 docListeners.waiting[0].fn({type:'waiting',target:video});
 docListeners.encrypted[0].fn({type:'encrypted',target:video,initDataType:'cenc'});
 assert.equal(intervals.length,1);intervals[0]();
 const media=sent.find(m=>m.kind==='diag-media');
 const s=media.samples[0];
 assert.equal(s.srcKind,'http-hls');assert.equal(s.srcHost,'cdn.test');assert.equal(s.srcKey.length,16);
 assert.equal(s.bufferedAhead,10.3);assert.equal(s.readyState,4);assert.equal(s.errorCode,3);
 assert.equal(s.events.waiting,1);assert.deepEqual(s.encryptedInitData,['cenc']);
 assert.deepEqual(s.quality,{total:100,dropped:2,corrupted:0});
 const mtext=JSON.stringify(media);
 assert.ok(!mtext.includes('SECRET')&&!mtext.includes('://'),'no URL leaves the page: '+mtext);
 onMessage({kind:'diag-state',active:false,salt:''});
 assert.equal(intervals.length,0,'sampling stops with the session');
 assert.equal((docListeners.waiting||[]).length,0,'listeners are removed with the session');
 console.log('Playback diagnostics: gated by session, successes recorded, URL-free, shared key: PASS');
})().catch(e=>{console.error(e);process.exitCode=1;});
