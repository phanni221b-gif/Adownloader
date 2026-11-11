'use strict';
const fs=require('fs'),vm=require('vm'),assert=require('assert/strict');
let now=100000;class Clock extends Date{static now(){return now;}}
function setup(rejectKind=""){
 const callbacks={},sent=[],filters=new Map(),timers=new Map();let tid=0;
 const event=n=>({addListener(fn,options){(callbacks[n]??=[]).push(d=>{if(!options?.types||options.types.includes(d.type))return fn(d);});}});
 const browser={webRequest:{onBeforeSendHeaders:event('before'),onHeadersReceived:event('response'),onBeforeRedirect:event('redirect'),onCompleted:event('complete'),onErrorOccurred:event('error'),filterResponseData(id){let f={written:[],write(d){this.written.push(Buffer.from(d));},disconnect(){this.disconnected=true;},close(){this.closed=true;}};filters.set(id,f);return f;}},runtime:{onMessage:{addListener(fn){(callbacks.message??=[]).push(fn);}},sendNativeMessage(_,m){sent.push(m);return Promise.resolve(m.kind!==rejectKind);}},tabs:{onRemoved:event('removed')}};
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
 let r=setup(); const hinted='https://cdn.test/media/opaque-signed-resource';
 r.fire('message',{kind:'candidate',url:hinted,source:'dom-media',pageUrl:r.sender.tab.url},r.sender);
 r.request('hint',hinted,'application/octet-stream');
 assert(r.filters.has('hint'),'An extensionless DOM media hint must still be sniffed, not mistaken for a known binary file');
 r.data('hint',Buffer.from('#EXTM3U\n#EXTINF:1,\npart.ts\n#EXT-X-ENDLIST\n'));await drain();
 assert(r.sent.some(m=>m.kind==='manifest-capture-end'),'Plain HLS in a hinted opaque URL must retain successful response evidence');
 // The envelope header is verified against the body, not trusted alone.
 r=setup(); let d={requestId:'encrypted',tabId:6,frameId:4294967297,type:'xmlhttprequest',url:hinted,statusCode:200,requestHeaders:[{name:'X-Playback-Session',value:'DO_NOT_RETAIN_SESSION'}],responseHeaders:[{name:'Content-Type',value:'application/octet-stream'},{name:'X-Relay-Envelope',value:'emr2'}]};
 r.fire('before',d);r.fire('response',d);r.fire('response',{...d,requestId:'second'});
 assert(r.filters.has('encrypted'),'An envelope-flagged response is inspected, not flagged on the header alone');
 r.data('encrypted',Buffer.from('EMR1'+'opaque-ciphertext'.repeat(30)));await drain();
 let p=r.sent.filter(m=>m.source==='protected-response');assert.equal(p.length,1,'Only one unsupported transport candidate per frame/page');
 assert.equal(p[0].metadata.transportProtection,'encrypted-relay');assert.equal(p[0].metadata.protectionVerified,'body','Encrypted body confirms the protection');
 assert.equal(p[0].metadata.frameId,'4294967297');assert.deepEqual(Object.keys(p[0].headers),[]);
 assert(!JSON.stringify(r.sent).includes('DO_NOT_RETAIN_SESSION'));assert(!r.sent.some(m=>m.kind==='manifest-capture-start'));

 // An envelope header over a body that is actually a plain HLS manifest is NOT protected: it is a
 // downloadable candidate (mislabelled transport, supported source recovered).
 r=setup();d={requestId:'mislabelled',tabId:6,frameId:0,type:'xmlhttprequest',url:hinted,statusCode:200,requestHeaders:[],responseHeaders:[{name:'Content-Type',value:'application/octet-stream'},{name:'X-Relay-Envelope',value:'emr1'}]};
 r.fire('before',d);r.fire('response',d);r.data('mislabelled',Buffer.from('#EXTM3U\n#EXTINF:1,\npart.ts\n#EXT-X-ENDLIST\n'));await drain();
 assert(!r.sent.some(m=>m.source==='protected-response'),'A non-encrypted body must not be flagged as protected');
 assert(r.sent.some(m=>m.kind==='manifest-capture-end'),'The real HLS manifest is captured as a downloadable source');

 // When no response filter can run, the header is the only signal: flag it, clearly as declared.
 r=setup();r.ctx.browser.webRequest.filterResponseData=undefined;
 d={requestId:'nofilter',tabId:6,frameId:0,type:'xmlhttprequest',url:hinted,statusCode:200,requestHeaders:[],responseHeaders:[{name:'Content-Type',value:'application/octet-stream'},{name:'X-Relay-Envelope',value:'emr2'}]};
 r.fire('before',d);r.fire('response',d);await drain();
 let declared=r.sent.filter(m=>m.source==='protected-response');assert.equal(declared.length,1,'Header-only fallback still reports the transport');
 assert.equal(declared[0].metadata.protectionVerified,'declared','Header-only protection is marked unverified');
 r=setup();r.fire('message',{kind:'candidate',url:hinted,source:'dom-media',pageUrl:r.sender.tab.url},r.sender);d=r.request('magic',hinted,'application/octet-stream');r.data('magic',Buffer.from('EMR1'+ 'opaque-ciphertext'.repeat(30)));await drain();
 assert(r.sent.some(m=>m.source==='protected-response'));assert(!r.sent.some(m=>m.kind==='manifest-capture-start'));
 assert(Buffer.concat(r.filters.get('magic').written).equals(Buffer.from('EMR1'+ 'opaque-ciphertext'.repeat(30))),'Encrypted player response must remain byte-identical');
 r=setup();d={requestId:'old-encrypted',tabId:6,frameId:0,type:'xmlhttprequest',url:hinted,statusCode:200,requestHeaders:[],responseHeaders:[{name:'X-Relay-Envelope',value:'emr2'}]};r.fire('before',d);
 r.fire('before',{requestId:'navigation',tabId:6,frameId:0,type:'main_frame',url:'https://page.test/next',requestHeaders:[]});r.fire('response',d);
 assert(!r.sent.some(m=>m.source==='protected-response'),'An old encrypted response must not bind to a new page');
 console.log('Hinted plain HLS capture and opaque protected transport recognition passed; no decryption/session export.');
})().catch(e=>{console.error(e);process.exitCode=1;});
