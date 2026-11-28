'use strict';
// Live playback diagnostics, active only while the user runs a diagnostic session from the app.
// Reads the standard HTMLMediaElement state (ready/network state, buffered ranges, errors,
// playback quality, EME MediaKeys and `encrypted` events) without wrapping or patching any page
// API, so a page's own integrity checks see nothing. URLs never leave the page: a media source is
// reported as its kind, its host and a session-salted key of origin+path (no query, no fragment).
(()=>{
 const top=window.top===window;
 const EVENTS=['loadstart','loadedmetadata','loadeddata','canplay','canplaythrough','playing','pause','waiting','stalled','suspend','error','emptied','ended','seeking','seeked','encrypted','waitingforkey','abort','resize'];
 let active=false,salt='',timer=null,listening=false;const elements=[],info=new WeakMap();
 const fnv=(s,h)=>{for(let i=0;i<s.length;i++)h=Math.imul(h^s.charCodeAt(i),16777619);return(h>>>0).toString(16).padStart(8,'0');};
 const keyOf=u=>{try{const x=new URL(u);if(x.protocol!=='https:'&&x.protocol!=='http:')return'';const b=x.protocol+'//'+x.host+(x.pathname||'/');return fnv(salt+'|'+b,2166136261)+fnv(b+'|'+salt,2166136261);}catch(_){return'';}};
 const hostOf=u=>{try{const x=new URL(u);return x.protocol==='https:'||x.protocol==='http:'?x.hostname:'';}catch(_){return'';}};
 const kindOf=u=>{if(!u)return'none';if(u.startsWith('blob:'))return'blob';if(u.startsWith('data:'))return'data';try{const p=new URL(u).pathname.toLowerCase();return /\.m3u8$/.test(p)?'http-hls':/\.mpd$/.test(p)?'http-dash':'http-file';}catch(_){return'other';}};
 const num=(v,d=1)=>Number.isFinite(v)?Math.round(v*10**d)/10**d:-1;
 const clean=s=>String(s||'').replace(/[a-z][a-z0-9+.-]*:\/\/\S+/gi,'<url>').replace(/[?#]\S*/g,'').slice(0,120);
 function track(el){if(!el||!el.matches||!el.matches('video,audio')||info.has(el))return;if(elements.length>=8)return;info.set(el,{index:elements.length,events:{},encrypted:[],debugAt:0,debug:null});elements.push(el);}
 function onEvent(e){const el=e.composedPath?e.composedPath()[0]:e.target;track(el);const s=info.get(el);if(!s)return;s.events[e.type]=(s.events[e.type]||0)+1;if(e.type==='encrypted'&&s.encrypted.length<4)s.encrypted.push(String(e.initDataType||'').slice(0,16));}
 function scan(){try{document.querySelectorAll('video,audio').forEach(track);}catch(_){}}
 function ranges(r,t){let total=0,ahead=0,count=0;try{count=r.length;for(let i=0;i<r.length;i++){const a=r.start(i),b=r.end(i);total+=b-a;if(b>t)ahead=Math.max(ahead,b-Math.max(a,t));}}catch(_){}return{count,total:num(total),ahead:num(ahead)};}
 // Gecko's own media debug report (decoder names, hardware acceleration, demuxer and EME state)
 // when the engine exposes it to this context; leaves only, URLs and long strings removed.
 function debugInfo(el,s){if(typeof el.mozRequestDebugInfo!=='function'||Date.now()-s.debugAt<10000)return;s.debugAt=Date.now();try{el.mozRequestDebugInfo().then(d=>{const out={};let n=0;const walk=(o,p,depth)=>{if(!o||typeof o!=='object'||depth>4)return;for(const k of Object.keys(o)){if(n>=48)return;const v=o[k],path=p?p+'.'+k:k;if(v&&typeof v==='object')walk(v,path,depth+1);else if(/decoder|hardware|codec|keysystem|mime|container|channels|rate|width|height|dropped|state|ready|demuxer|playing|seekable|ended|buffer/i.test(k)){if(typeof v==='string'){if(/:\/\//.test(v)||v.length>80)continue;out[path.slice(0,80)]=v;}else if(typeof v==='number'||typeof v==='boolean')out[path.slice(0,80)]=v;else continue;n++;}}};walk(d,'',0);s.debug=out;}).catch(()=>{s.debug={unavailable:'rejected'};});}catch(_){s.debug={unavailable:'threw'};}}
 function sample(){
  scan();if(!elements.length)return;
  const samples=[];
  for(const el of elements){const s=info.get(el);const src=el.currentSrc||el.src||'';const b=ranges(el.buffered,el.currentTime),seekable=ranges(el.seekable,0);let quality=null;try{const q=el.getVideoPlaybackQuality&&el.getVideoPlaybackQuality();if(q)quality={total:q.totalVideoFrames|0,dropped:q.droppedVideoFrames|0,corrupted:q.corruptedVideoFrames|0};}catch(_){}
   debugInfo(el,s);
   samples.push({index:s.index,tag:el.tagName.toLowerCase(),connected:el.isConnected,srcKind:kindOf(src),srcHost:hostOf(src),srcKey:keyOf(src),readyState:el.readyState|0,networkState:el.networkState|0,paused:!!el.paused,ended:!!el.ended,seeking:!!el.seeking,currentTime:num(el.currentTime),duration:num(el.duration),playbackRate:num(el.playbackRate,2),bufferedRanges:b.count,bufferedSeconds:b.total,bufferedAhead:b.ahead,seekableSeconds:seekable.total,videoWidth:el.videoWidth|0,videoHeight:el.videoHeight|0,muted:!!el.muted,errorCode:el.error?el.error.code|0:0,errorMessage:el.error?clean(el.error.message):'',mediaKeys:!!el.mediaKeys,encryptedInitData:s.encrypted.slice(),events:Object.assign({},s.events),quality,debug:s.debug});
   s.events={};}
  browser.runtime.sendMessage({kind:'diag-media',top,samples}).catch(()=>{});
 }
 function apply(state){
  const next=!!(state&&state.active&&typeof state.salt==='string'&&/^[a-f0-9]{16,64}$/.test(state.salt));
  if(next&&(!active||state.salt!==salt)){active=true;salt=state.salt;if(!listening){listening=true;for(const t of EVENTS)document.addEventListener(t,onEvent,true);}scan();if(!timer)timer=setInterval(sample,1000);}
  else if(!next&&active){active=false;salt='';if(timer){clearInterval(timer);timer=null;}if(listening){listening=false;for(const t of EVENTS)document.removeEventListener(t,onEvent,true);}}
 }
 browser.runtime.onMessage.addListener(m=>{if(m&&m.kind==='diag-state')apply(m);});
 const query=()=>browser.runtime.sendMessage({kind:'diag-query'}).then(apply).catch(()=>{});
 query();
 // A media element created after load asks again, so a session started mid-page is picked up.
 document.addEventListener('loadstart',()=>{if(!active)query();},true);
 window.addEventListener('pagehide',()=>apply(null),{once:true});
})();
