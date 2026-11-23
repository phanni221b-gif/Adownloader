'use strict';
// cookie_bridge.js: answers one exact-URL lookup with the unpartitioned and the page-partitioned
// jar, never for a non-HTTP URL, and reopens a refused native port.
const fs=require('fs'),vm=require('vm'),assert=require('assert/strict');
const posted=[],calls=[];let listener=null,disconnect=null,connects=0;
const port={postMessage(m){posted.push(m);},onMessage:{addListener(fn){listener=fn;}},onDisconnect:{addListener(fn){disconnect=fn;}}};
const timers=[];
const browser={runtime:{connectNative(app){assert.equal(app,'adownloader');connects++;return port;}},cookies:{async getAll(q){calls.push(q);if(q.partitionKey)return[{name:'cdn',value:'p',sameSite:'no_restriction'}];return[{name:'sid',value:'u',sameSite:'lax'},{name:'bad',value:42}];}}};
const ctx=vm.createContext({browser,URL,setTimeout:(fn,ms)=>timers.push(fn),console});
vm.runInContext(fs.readFileSync('app/src/main/assets/media_sniffer/cookie_bridge.js','utf8'),ctx);
(async()=>{
 assert.equal(connects,1);assert.equal(posted[0].kind,'cookie-bridge-ready');
 await listener({kind:'cookie-lookup',id:'1',url:'https://cdn.test/a/seg.ts',topLevelSite:'https://page.test'});
 const r=posted.find(m=>m.id==='1');
 assert.deepEqual(JSON.parse(JSON.stringify(r.cookies)),[{name:'sid',value:'u',sameSite:'lax',partitioned:false},{name:'cdn',value:'p',sameSite:'no_restriction',partitioned:true}]);
 assert.equal(calls[0].url,'https://cdn.test/a/seg.ts');assert.equal(calls[1].partitionKey.topLevelSite,'https://page.test');
 await listener({kind:'cookie-lookup',id:'2',url:'file:///etc/passwd',topLevelSite:'https://page.test'});
 assert.deepEqual(JSON.parse(JSON.stringify(posted.find(m=>m.id==='2').cookies)),[]);assert.equal(calls.length,2);
 await listener({kind:'cookie-lookup',id:'3',url:'https://cdn.test/x',topLevelSite:'javascript:alert(1)'});
 assert.equal(calls.length,3,'an invalid site never becomes a partition key');
 disconnect();assert.equal(timers.length,1);timers[0]();assert.equal(connects,2);
 console.log('Cookie bridge: exact-URL lookup, partitioned jar, non-HTTP refusal, reconnect: PASS');
})().catch(e=>{console.error(e);process.exitCode=1;});
