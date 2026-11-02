'use strict';
const assert=require('assert/strict'),fs=require('fs'),vm=require('vm');
function fixture(top=true){
 const sent=[],selectors=new Map(),listeners={},intervals=[],state={fullQueries:0,children:[]};let mutation,resources;
 const element=(tag,src='')=>({nodeType:1,tagName:tag.toUpperCase(),src,currentSrc:src,currentTime:0,paused:true,_events:{},addEventListener(n,fn){this._events[n]=fn;},fire(n){this._events[n]?.();},matches(q){return q.split(',').includes(tag);},getAttribute(q){return q==='src'?src:null;},querySelectorAll(){return[];}});
 const root={nodeType:1,matches:()=>false,querySelectorAll(){state.fullQueries++;return state.children;}};
 const document={documentElement:root,title:'Document - Example',querySelector:q=>selectors.get(q)||null,addEventListener:(name,fn)=>listeners[name]=fn};
 const location={href:'https://example.test/post',hostname:'example.test'},window={addEventListener:(name,fn)=>listeners[name]=fn};window.top=top?window:{};
 const browser={runtime:{sendMessage(m){sent.push(m);return Promise.resolve({tabId:1});},sendNativeMessage(){return Promise.resolve();}}};
 const context=vm.createContext({document,window,location,browser,URL,WeakMap,WeakSet,Set,Map,Promise,performance:{now:()=>0,getEntriesByType:()=>[]},MutationObserver:class{constructor(fn){mutation=fn;}observe(){}disconnect(){}},PerformanceObserver:class{constructor(fn){resources=fn;}observe(){}disconnect(){}},setInterval:fn=>(intervals.push(fn),intervals.length),clearInterval:()=>{}});
 vm.runInContext(fs.readFileSync('app/src/main/assets/media_sniffer/content.js','utf8'),context);
 return{sent,selectors,state,location,element,mutate:records=>mutation(records),resources:e=>resources({getEntries:()=>e}),intervals,root};
}
const flush=()=>new Promise(resolve=>setImmediate(resolve));
(async()=>{
 const f=fixture();f.selectors.set('meta[property="og:title"]',{content:'OG title'});f.selectors.set('meta[name="twitter:title"]',{content:'Twitter title'});f.selectors.set('main h1',{textContent:'Main heading'});f.selectors.set('h1',{textContent:'Unrelated first heading'});
 const metadata=async()=>{f.mutate([{type:'attributes',attributeName:'content',target:{}}]);await flush();return f.sent.filter(x=>x.kind==='page-meta').at(-1);};
 assert.equal((await metadata()).pageTitle,'OG title');f.selectors.delete('meta[property="og:title"]');assert.equal((await metadata()).pageTitle,'Twitter title');f.selectors.delete('meta[name="twitter:title"]');assert.equal((await metadata()).pageTitle,'Main heading');f.selectors.delete('main h1');assert.equal((await metadata()).pageTitle,'Unrelated first heading');f.selectors.delete('h1');f.selectors.set('meta[property="og:site_name"]',{content:'Example'});assert.equal((await metadata()).pageTitle,'Document');
 for(let n=0;n<300;n++)f.mutate([{type:'childList',addedNodes:[f.element('span')]}]);await flush();assert.equal(f.state.fullQueries,1,'Unrelated mutations must not rescan the whole document');
 f.resources([{name:'https://cdn.test/a.m3u8'}]);f.mutate([{type:'childList',addedNodes:[f.element('span')]}]);await flush();assert.equal(f.sent.filter(x=>x.source==='performance-resource').length,1,'Resource observation must not replay the full buffer');
 const video=f.element('video','blob:https://example.test/private');f.mutate([{type:'childList',addedNodes:[video]}]);assert(f.sent.some(x=>x.signal==='MSE_PLAYBACK_DETECTED'));assert(!f.sent.some(x=>x.kind==='candidate'&&x.url.startsWith('blob:')));assert(!f.sent.some(x=>x.signal==='PLAYBACK_PROGRESS'));video.currentTime=1;video.paused=false;video.fire('timeupdate');video.fire('timeupdate');assert.equal(f.sent.filter(x=>x.signal==='PLAYBACK_PROGRESS').length,1,'Playback evidence requires actual time progress and is emitted once per source');
 f.state.children=[f.element('video','https://cdn.test/real.mp4')];f.location.href='https://example.test/new-post';f.selectors.set('meta[property="og:title"]',{content:'Late new post'});await metadata();assert(f.sent.some(x=>x.kind==='candidate'&&x.pageUrl.endsWith('/new-post')&&x.pageTitle==='Late new post'));assert.equal(f.state.fullQueries,2,'One document rescan is allowed for a new SPA page');
 const sub=fixture(false);sub.selectors.set('meta[property="og:title"]',{content:'Frame advertisement title'});sub.mutate([{type:'childList',addedNodes:[sub.element('video','https://cdn.test/frame.mp4')]}]);await flush();assert(!sub.sent.some(x=>x.kind==='page-meta'));assert.equal(sub.sent.find(x=>x.kind==='candidate').pageTitle,'');
 console.log('Content metadata priority, late title, incremental DOM/resources, SPA and iframe isolation: PASS');
})().catch(error=>{console.error(error);process.exitCode=1;});
