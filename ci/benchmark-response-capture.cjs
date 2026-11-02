'use strict';
// Isolated synthetic handler benchmark. Android navigation timings are measured separately.
const fs=require('fs'),vm=require('vm'),cp=require('child_process'),assert=require('assert/strict');
const baseline='a13c305e91592daa8f617c09e97d29ce9640edcf';
function setup(old){
 const callbacks={},sent=[];const event=n=>({addListener(fn,filter){(callbacks[n]??=[]).push(d=>{if(!filter?.types||filter.types.includes(d.type))fn(d);});}});
 const browser={webRequest:{onBeforeSendHeaders:event('before'),onHeadersReceived:event('response'),onBeforeRedirect:event('redirect'),onCompleted:event('complete'),onErrorOccurred:event('error'),filterResponseData(){throw Error('Normal images must never be filtered');}},runtime:{onMessage:{addListener(fn){(callbacks.message??=[]).push(fn);}},sendNativeMessage(_,m){sent.push(m);return Promise.resolve();}},tabs:{onRemoved:event('removed')}};
 const context=vm.createContext({browser,URL,Date,Map,Set,performance,TextDecoder,Uint8Array,ArrayBuffer,setTimeout:()=>0,clearTimeout(){}});
 for(const name of ['request_context','background']){const path='app/src/main/assets/media_sniffer/'+name+'.js';const source=old?cp.execFileSync('git',['show',baseline+':'+path],{encoding:'utf8'}):fs.readFileSync(path,'utf8');vm.runInContext(source,context);}
 for(const fn of callbacks.message)fn({kind:'page-meta',pageUrl:'https://fixture.test/page',pageTitle:'Benchmark'},{tab:{id:1},frameId:0});
 let sequence=0;
 return {run(){const start=performance.now();for(let i=0;i<2000;i++){const d={requestId:String(++sequence),url:'https://fixture.test/pixel-'+i+'.png',tabId:1,frameId:0,type:'image',requestHeaders:[],statusCode:200,responseHeaders:[{name:'Content-Type',value:'image/png'}]};for(const fn of callbacks.before)fn(d);for(const fn of callbacks.response)fn(d);for(const fn of callbacks.complete)fn(d);}return performance.now()-start;},stats(){return JSON.parse(JSON.stringify(vm.runInContext('counters(1)',context)));}};
}
const before=setup(true),after=setup(false),a=[],b=[];before.run();after.run();
for(let i=0;i<30;i++){if(i%2){b.push(after.run());a.push(before.run());}else{a.push(before.run());b.push(after.run());}}
const percentile=(x,n)=>[...x].sort((a,b)=>a-b)[Math.floor((x.length-1)*n)];
const report={baselineCommit:baseline,scenario:'normal-page-2000-images',rounds:30,before:{medianMs:percentile(a,.5),p95Ms:percentile(a,.95),metrics:before.stats()},after:{medianMs:percentile(b,.5),p95Ms:percentile(b,.95),metrics:after.stats()}};
assert.equal(report.after.metrics.filteredResponses,0);assert.equal(report.after.metrics.manifestBytes,0);assert.equal(report.after.metrics.captureMemoryPeak,0);
assert(report.after.medianMs<=report.before.medianMs*1.5+10,'Normal-page handler overhead regressed beyond the declared synthetic tolerance');
fs.mkdirSync('app/build/reports',{recursive:true});fs.writeFileSync('app/build/reports/response-capture-normal-benchmark.json',JSON.stringify(report,null,2)+'\n');
console.log(JSON.stringify(report));
