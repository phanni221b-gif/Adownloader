'use strict';
const fs=require('fs'),vm=require('vm'),assert=require('assert/strict');
function fixture(script,kind='hls'){
 const handlers={},sent=[],filters=new Map(),event=name=>({addListener(fn){handlers[name]=fn;}});
 const browser={webRequest:{onBeforeSendHeaders:event('before'),onHeadersReceived:event('response'),onCompleted:event('completed'),onErrorOccurred:event('error'),filterResponseData(id){const f={written:[],disconnect(){this.disconnected=true;},close(){this.closed=true;},write(data){this.written.push(Buffer.from(data));}};filters.set(id,f);return f;}},runtime:{onMessage:event('message'),sendNativeMessage(app,payload){sent.push(payload);return Promise.resolve();}},tabs:{onRemoved:event('removed')}};
 const context=vm.createContext({browser,URL,Date,Map,Set,TextDecoder,Uint8Array,ArrayBuffer,performance,setTimeout:()=>1,clearTimeout:()=>{}});vm.runInContext(script,context);
 handlers.message({kind:'page-meta',pageTitle:'Top post title',pageUrl:'https://page.test/post'},{frameId:0,tab:{id:4,url:'https://page.test/post'}});
 handlers.message({kind:'stream-signal',signal:'MSE_PLAYBACK_DETECTED',videoElements:1},{frameId:2,tab:{id:4,url:'https://page.test/post'}});
 const url='https://cdn.test/api/play?id=42';handlers.before({requestId:'root',tabId:4,frameId:2,type:'xmlhttprequest',url,requestHeaders:[{name:'cookie',value:'fixture=PRIVATE'},{name:'Referer',value:'https://player.test/frame'},{name:'Authorization',value:'DO_NOT_COLLECT'}]});
 handlers.response({requestId:'root',tabId:4,frameId:2,type:'xmlhttprequest',url,statusCode:200,documentUrl:'https://player.test/frame',responseHeaders:[{name:'Content-Type',value:kind==='hls'?'text/plain':'application/octet-stream'}]});
 const body=Buffer.from(kind==='hls'?'#EXTM3U\n#EXTINF:3,\nseg.ts\n#EXT-X-ENDLIST\n':'<?xml version="1.0"?><MPD type="static"></MPD>');const filter=filters.get('root');if(filter){filter.ondata({data:Uint8Array.from(body.subarray(0,3)).buffer});if(!filter.disconnected)filter.ondata({data:Uint8Array.from(body.subarray(3)).buffer});if(!filter.disconnected&&filter.onstop)filter.onstop();assert.deepEqual(Buffer.concat(filter.written),body,'Prefix inspection must write the original bytes unchanged');}
 return {sent,filters,handlers,context};
}
const baseline=fixture(fs.readFileSync('ci/fixtures/sniffer-v061-baseline.js','utf8'));assert.equal(baseline.sent.filter(x=>x.kind==='candidate').length,0);console.log('v0.6.1 synthetic MSE + extensionless manifest: candidate count 0 reproduced');
const script=fs.readFileSync('app/src/main/assets/media_sniffer/background.js','utf8');for(const kind of ['hls','dash']){const r=fixture(script,kind),c=r.sent.filter(x=>x.kind==='candidate');assert.equal(c.length,1,'Signature must recover the actual manifest');assert.equal(c[0].url,'https://cdn.test/api/play?id=42');assert.equal(c[0].metadata.title,'Top post title');assert.equal(c[0].headers.Cookie,'fixture=PRIVATE');assert.equal(c[0].headers.Authorization,undefined);assert.match(c[0].contentType,kind==='hls'?/mpegurl/:/dash\+xml/);}
console.log('Scoped prefix detection and byte-exact response pass-through: PASS');
