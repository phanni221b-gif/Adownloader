'use strict';
const fs=require('fs'),vm=require('vm'),assert=require('assert/strict');
let now=100000;class Clock extends Date{static now(){return now;}}
function setup(rejectKind=""){
 const callbacks={},sent=[],filters=new Map(),timers=new Map();let tid=0;
 const event=n=>({addListener(fn,options){(callbacks[n]??=[]).push(d=>{if(!options?.types||options.types.includes(d.type))return fn(d);});}});
 const browser={webRequest:{onBeforeSendHeaders:event('before'),onHeadersReceived:event('response'),onCompleted:event('complete'),onErrorOccurred:event('error'),filterResponseData(id){let f={written:[],write(d){this.written.push(Buffer.from(d));},disconnect(){this.disconnected=true;},close(){this.closed=true;}};filters.set(id,f);return f;}},runtime:{onMessage:{addListener(fn){(callbacks.message??=[]).push(fn);}},sendNativeMessage(_,m){sent.push(m);return Promise.resolve(m.kind!==rejectKind);}},tabs:{onRemoved:event('removed')}};
 const ctx=vm.createContext({browser,URL,Date:Clock,Map,Set,performance,TextDecoder,Uint8Array,ArrayBuffer,btoa:s=>Buffer.from(s,'binary').toString('base64'),setTimeout:(fn,delay)=>{timers.set(++tid,{fn,delay});return tid;},clearTimeout:id=>timers.delete(id)});
 for(const name of ['request_context','background'])vm.runInContext(fs.readFileSync('app/src/main/assets/media_sniffer/'+name+'.js','utf8'),ctx);
 const fire=(n,d,s)=>callbacks[n]?.map(fn=>fn(d,s));const sender={tab:{id:6,url:'https://page.test/a'},frameId:0};
 fire('message',{kind:'page-meta',pageUrl:sender.tab.url,pageTitle:'Synthetic capture'},sender);
 function request(id,url,mime='application/vnd.apple.mpegurl',status=200){const d={requestId:id,url,tabId:6,frameId:0,type:'media',statusCode:status,requestHeaders:[{name:'Referer',value:sender.tab.url},{name:'Cookie',value:'sid=PRIVATE'},{name:'Authorization',value:'Bearer PRIVATE_MEDIA'}],responseHeaders:[{name:'Content-Type',value:mime}]};fire('before',d);fire('response',d);return d;}
 function data(id,body){const f=filters.get(id);assert(f,'Successful known manifests must be filtered as well as extensionless responses');for(let at=0;at<body.length&&!f.disconnected;at+=3000)f.ondata({data:Uint8Array.from(body.subarray(at,at+3000)).buffer});if(!f.disconnected)f.onstop();return f;}
 return {callbacks,sent,filters,timers,ctx,fire,sender,request,data};
}
const drain=()=>new Promise(resolve=>setImmediate(resolve));
(async()=>{
 let r=setup();const url='https://cdn.test/movie/master.m3u8?private-token=1';
 const d=r.request('root',url);r.fire('complete',d);const body=Buffer.from('#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=100\n1080.m3u8\n');const f=r.data('root',body);await drain();
 assert.deepEqual(Buffer.concat(f.written),body,'Player receives every original response byte');
 const end=r.sent.find(x=>x.kind==='manifest-capture-end');assert(end,'A completed successful response must be captured, not just a signature');
 assert.equal(end.bytes,body.length);const chunks=r.sent.filter(x=>x.kind==='manifest-capture-chunk');assert.deepEqual(Buffer.concat(chunks.map(x=>Buffer.from(x.data,'base64'))),body);
 const start=r.sent.find(x=>x.kind==='manifest-capture-start');assert.equal(start.pageUrl,r.sender.tab.url);assert.equal(start.frameId,0);assert.equal(start.headers.Cookie,'sid=PRIVATE');assert.equal(start.geckoStatus,200);
 r.request('variant','https://cdn.test/movie/1080.m3u8');r.data('variant',Buffer.from('#EXTM3U\n#EXTINF:1,\npart.ts\n#EXT-X-ENDLIST\n'));await drain();assert.equal(r.sent.filter(x=>x.kind==='manifest-capture-end').length,2,'Master latch must not block variant capture');const mediaMetrics=JSON.parse(JSON.stringify(vm.runInContext('counters(6)',r.ctx)));fs.mkdirSync('app/build/reports',{recursive:true});fs.writeFileSync('app/build/reports/response-capture-media-metrics.json',JSON.stringify({scenario:'synthetic-master-and-variant',metrics:mediaMetrics,logicalBufferLimit:16777216,perManifestLimit:8388608},null,2)+'\n');
 r.request('segment','https://cdn.test/movie/part.ts','application/octet-stream');assert(!r.filters.has('segment'),'Segments never receive full-body capture');assert(r.sent.some(x=>x.kind==='resource-context'&&x.url.endsWith('/part.ts')&&x.mediaSuccess&&x.headers.Authorization==='Bearer PRIVATE_MEDIA'),'A successful media segment keeps its exact scoped authentication even with an octet-stream MIME');
 r.request('error','https://cdn.test/movie/error.m3u8','application/vnd.apple.mpegurl',403);assert(!r.filters.has('error'));
 r=setup();r.request('old',url);const old=r.filters.get('old');r.fire('message',{kind:'page-meta',pageUrl:'https://page.test/b',pageTitle:'B'},r.sender);if(!old.disconnected){old.ondata({data:Uint8Array.from(body).buffer});if(!old.disconnected)old.onstop();}await drain();assert(!r.sent.some(x=>x.kind==='manifest-capture-end'),'Navigation must invalidate old-page captures');const stale=await r.fire('message',{kind:'authorize-manifest',id:'stale-bootstrap-123456',url},r.sender)[0];assert(!stale.ok,'Old-page successful context cannot authorize a new-page bootstrap');
 r=setup();r.request('large',url);const large=r.filters.get('large');const huge=Buffer.concat([Buffer.from('#EXTM3U\n'),Buffer.alloc(8*1024*1024,120)]);for(let at=0;at<huge.length&&!large.disconnected;at+=65536)large.ondata({data:Uint8Array.from(huge.subarray(at,at+65536)).buffer});assert(large.disconnected,'Oversize capture detaches without blocking playback');await drain();assert(!r.sent.some(x=>x.kind==='manifest-capture-end'));
 r=setup();r.request('truncated',url);const cut=r.filters.get('truncated');cut.ondata({data:Uint8Array.from(body).buffer});cut.onerror();await drain();assert(!r.sent.some(x=>x.kind==='manifest-capture-end'),'Incomplete/error response is never a successful cache entry');
 for(const rejected of ['manifest-capture-start','manifest-capture-chunk']){r=setup(rejected);r.request('denied',url);r.data('denied',body);await drain();assert(!r.sent.some(x=>x.kind==='manifest-capture-end'),'Rejected native acknowledgement must stop export');assert.equal(vm.runInContext('captureMemory',r.ctx),0);assert.equal(vm.runInContext('captureExports',r.ctx),0);}
 console.log('Successful response capture: known master/variant, byte-exact pass-through, async context, navigation, segment exclusion, size/error bounds: PASS');
})().catch(e=>{console.error(e);process.exitCode=1;});
