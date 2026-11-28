'use strict';
// Live playback diagnostics (background half). While the user runs a diagnostic session the app
// returns {active, salt} to this page's poll; every player-type request outcome is then recorded,
// successes included (the regular observer only reports failed media requests). A record is
// type, status, MIME class, host, a session-salted origin+path key, file extension and whether a
// Cookie/Referer/Origin/Range header was sent: never a URL, query, header value or body.
(()=>{
 const NATIVE='adownloader',TYPES=new Set(['media','xmlhttprequest','other','object','main_frame','sub_frame']);
 let pollFailed=false,active=false,salt='',session='',net=[],media=[],dropped=0;const tabs=new Set(),sentHeaders=new Map(),started=new Map();
 const fnv=(s,h)=>{for(let i=0;i<s.length;i++)h=Math.imul(h^s.charCodeAt(i),16777619);return(h>>>0).toString(16).padStart(8,'0');};
 const keyOf=u=>{try{const x=new URL(u);if(x.protocol!=='https:'&&x.protocol!=='http:')return'';const b=x.protocol+'//'+x.host+(x.pathname||'/');return fnv(salt+'|'+b,2166136261)+fnv(b+'|'+salt,2166136261);}catch(_){return'';}};
 const parts=u=>{try{const x=new URL(u);const m=/\.([a-z0-9]{1,5})$/i.exec(x.pathname);return{host:x.hostname,ext:m?m[1].toLowerCase():''};}catch(_){return{host:'',ext:''};}};
 const mimeClass=list=>{for(const h of list||[])if(h&&String(h.name).toLowerCase()==='content-type'){const v=String(h.value||'').toLowerCase().split(';',1)[0].trim();return /^[a-z0-9.+-]{1,40}\/[a-z0-9.+-]{1,60}$/.test(v)?v:'other';}return'';};
 function push(r){if(net.length>=600){dropped++;return;}net.push(r);}
 function record(d,status,mime,error){
  if(!active||!TYPES.has(d.type))return;
  const p=parts(d.url),h=sentHeaders.get(d.requestId)||{};sentHeaders.delete(d.requestId);const t0=started.get(d.requestId);started.delete(d.requestId);
  push({tab:Number.isInteger(d.tabId)?d.tabId:-1,frame:d.frameId|0,type:d.type,status:status|0,mime,host:p.host,ext:p.ext,key:keyOf(d.url),fromCache:!!d.fromCache,error:error?String(error).replace(/[^A-Z0-9_]/g,'').slice(0,48):'',cookie:!!h.cookie,referer:!!h.referer,origin:!!h.origin,range:!!h.range,ms:t0?Math.max(0,Math.round(d.timeStamp-t0)):-1,thirdParty:d.thirdParty===true});
 }
 const filter={urls:['http://*/*','https://*/*']};
 browser.webRequest.onBeforeRequest.addListener(d=>{if(active&&TYPES.has(d.type)){if(started.size>=1024)started.delete(started.keys().next().value);started.set(d.requestId,d.timeStamp);}},filter);
 browser.webRequest.onSendHeaders.addListener(d=>{if(!active||!TYPES.has(d.type))return;const seen={};for(const x of d.requestHeaders||[]){const n=String(x&&x.name||'').toLowerCase();if(n==='cookie'||n==='referer'||n==='origin'||n==='range')seen[n]=true;}if(sentHeaders.size>=1024)sentHeaders.delete(sentHeaders.keys().next().value);sentHeaders.set(d.requestId,seen);},filter,['requestHeaders']);
 browser.webRequest.onCompleted.addListener(d=>record(d,d.statusCode,mimeClass(d.responseHeaders),''),filter,['responseHeaders']);
 browser.webRequest.onErrorOccurred.addListener(d=>record(d,0,'',d.error||'NETWORK_ERROR'),filter);
 function broadcast(){const state={kind:'diag-state',active,salt};for(const id of tabs)browser.tabs.sendMessage(id,state).catch(()=>tabs.delete(id));}
 function apply(r){
  if(!r||typeof r.active!=='boolean')return;
  const nextSalt=r.active&&/^[a-f0-9]{16,64}$/.test(r.salt||'')?r.salt:'';
  const changed=(r.active&&!!nextSalt)!==active||nextSalt!==salt;
  active=r.active&&!!nextSalt;salt=nextSalt;session=active?String(r.session||''):'';
  if(changed){net=[];media=[];dropped=0;sentHeaders.clear();started.clear();broadcast();}
 }
 browser.runtime.onMessage.addListener((m,sender)=>{
  if(!m||!sender||!sender.tab)return;
  if(m.kind==='diag-query'){tabs.add(sender.tab.id);return Promise.resolve({kind:'diag-state',active,salt});}
  if(m.kind==='diag-media'&&active&&Array.isArray(m.samples)){if(media.length<240)media.push({tab:sender.tab.id,frame:sender.frameId|0,top:!!m.top,samples:m.samples.slice(0,8)});return;}
 });
 async function poll(){
  const batch={kind:'diag-poll',session,net,media,dropped};net=[];media=[];dropped=0;
  try{apply(await browser.runtime.sendNativeMessage(NATIVE,batch));}catch(e){if(!pollFailed){pollFailed=true;console.warn('playback-diag poll failed: '+String(e&&e.message||e).slice(0,120));}}
  setTimeout(poll,active?1000:3000);
 }
 poll();
})();
