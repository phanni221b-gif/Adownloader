'use strict';
// Per-request browser cookie lookup for the native downloader: the GeckoView counterpart of
// WebView's CookieManager.getCookie(url). The native side asks for one exact URL plus the
// top-level site of the page that played it; the browser applies domain/path/Secure/expiry and
// returns the unpartitioned jar and the jar partitioned under that site. Nothing is stored,
// logged or sent anywhere but back over this native port.
(()=>{
 if(typeof browser.runtime.connectNative!=='function'||!browser.cookies||typeof browser.cookies.getAll!=='function')return;
 const http=u=>{try{const x=new URL(u);return x.protocol==='https:'||x.protocol==='http:';}catch(_){return false;}};
 const shape=(list,partitioned)=>(Array.isArray(list)?list:[]).slice(0,64).filter(c=>c&&typeof c.name==='string'&&typeof c.value==='string'&&c.name.length<=256&&c.value.length<=4096).map(c=>({name:c.name,value:c.value,sameSite:String(c.sameSite||''),partitioned}));
 async function lookup(url,site){
  const out=[];
  try{out.push(...shape(await browser.cookies.getAll({url}),false));}catch(_){}
  if(/^https?:\/\/[^/]+$/i.test(site||''))try{out.push(...shape(await browser.cookies.getAll({url,partitionKey:{topLevelSite:site}}),true));}catch(_){}
  return out.slice(0,96);
 }
 // The native delegate may attach after this background page starts; a refused or dropped port is
 // reopened a bounded number of times so the bridge is present once the app is ready.
 let port=null,retries=0;
 const reconnect=()=>{port=null;if(retries++<30)setTimeout(connect,Math.min(30000,1000*retries));};
 function connect(){
  let own;
  try{own=port=browser.runtime.connectNative('adownloader');}catch(_){reconnect();return;}
  own.onMessage.addListener(async m=>{
   if(!m||m.kind!=='cookie-lookup'||typeof m.id!=='string'||m.id.length>80)return;
   retries=0;// the app answered on this port: it is live
   const cookies=http(m.url)?await lookup(m.url,m.topLevelSite):[];
   try{own.postMessage({kind:'cookie-result',id:m.id,cookies});}catch(_){}
  });
  own.onDisconnect.addListener(()=>{if(port===own)reconnect();});
  try{own.postMessage({kind:'cookie-bridge-ready'});}catch(_){}
 }
 connect();
})();
