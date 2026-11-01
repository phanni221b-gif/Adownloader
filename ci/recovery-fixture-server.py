#!/usr/bin/env python3
"""Synthetic local recovery fixtures; never log headers or URL queries."""
import hashlib,json,re,struct,threading,time
from http.server import BaseHTTPRequestHandler,ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlsplit,parse_qs
ROOT=Path('app/src/androidTest/assets/fixtures')
PREFIX=(ROOT/'direct.mp4').read_bytes();SIZE=32*1024*1024
HEAD=PREFIX+struct.pack('>I4sQ',1,b'free',SIZE-len(PREFIX))
ETAG='"fixture-'+hashlib.sha256(HEAD).hexdigest()+'"'
LOCK=threading.Lock();COUNTS={};RANGES=[];VERSION=1;EXPIRED=False
def block(offset,length,version=None):
 end=min(offset+length,len(HEAD));front=HEAD[offset:end] if offset<len(HEAD) else b''
 data=front+b'\0'*(length-len(front))
 if (VERSION if version is None else version)==2 and offset+length==SIZE:data=data[:-1]+b'\1'
 return data
def expected(version):
 digest=hashlib.sha256()
 for offset in range(0,SIZE,128*1024):digest.update(block(offset,min(128*1024,SIZE-offset),version))
 return digest.hexdigest()
EXPECTED={1:expected(1),2:expected(2)}
class Handler(BaseHTTPRequestHandler):
 protocol_version='HTTP/1.1'
 def log_message(self,*args):pass
 def do_GET(self):
  global VERSION,EXPIRED
  parsed=urlsplit(self.path);path=parsed.path;token=parse_qs(parsed.query).get('token',[''])[0]
  if path=='/reset':
   with LOCK:COUNTS.clear();RANGES.clear();VERSION=1;EXPIRED=False
   return self.reply(b'{}','application/json')
  if path in ('/expire','/change'):
   with LOCK:EXPIRED=True;VERSION=2 if path=='/change' else 1
   return self.reply(b'{}','application/json')
  if path=='/stats':
   with LOCK:data={'counts':dict(COUNTS),'ranges':list(RANGES),'sha256':EXPECTED[VERSION],'length':SIZE}
   return self.reply(json.dumps(data).encode(),'application/json')
  if path=='/page':return self.reply(b'<!doctype html><title>Recovery fixture</title><h1>Recovery test</h1>','text/html')
  with LOCK:COUNTS[path]=COUNTS.get(path,0)+1
  if EXPIRED and token=='old':return self.send_error(403)
  if path=='/process/fail-once' and COUNTS[path]==1:return self.send_error(500)
  if path in ('/process/direct','/process/fail-once','/process/asset-123.mp4'):
   offset=0;value=self.headers.get('Range');etag=ETAG if VERSION==1 else '"fixture-v2"'
   if value and self.headers.get('If-Range')==etag:
    try:offset=int(value.removeprefix('bytes=').split('-')[0])
    except ValueError:return self.send_error(400)
   if offset>=SIZE:return self.send_error(416)
   with LOCK:RANGES.append(offset)
   self.send_response(206 if offset else 200);self.send_header('Content-Type','video/mp4');self.send_header('Content-Length',str(SIZE-offset));self.send_header('ETag',etag);self.send_header('Accept-Ranges','bytes');self.send_header('Connection','close')
   if offset:self.send_header('Content-Range',f'bytes {offset}-{SIZE-1}/{SIZE}')
   self.end_headers()
   try:
    while offset<SIZE:
     length=min(64*1024,SIZE-offset);self.wfile.write(block(offset,length));self.wfile.flush();offset+=length;time.sleep(.015)
   except (BrokenPipeError,ConnectionResetError):pass
   return
  if path=='/process/asset-123.m3u8':
   text=(ROOT/'playlist.m3u8').read_text();text='\n'.join(line+('?token='+token) if line and not line.startswith('#') else line for line in text.splitlines())+'\n'
   return self.reply(text.encode(),'application/vnd.apple.mpegurl')
  if path=='/process/asset-123.mpd':
   text=(ROOT/'manifest.mpd').read_text();text=re.sub(r'(initialization|media)="([^"]+)"',lambda m:m[1]+'="'+m[2]+'?token='+token+'"',text)
   return self.reply(text.encode(),'application/dash+xml')
  name=path.rsplit('/',1)[-1]
  if path.startswith('/process/') and name in {p.name for p in ROOT.iterdir()}:
   data=(ROOT/name).read_bytes()
   if name.endswith(('.ts','.m4s')):time.sleep(.7)
   mime='application/vnd.apple.mpegurl' if name.endswith('.m3u8') else 'application/dash+xml' if name.endswith('.mpd') else 'application/octet-stream'
   return self.reply(data,mime)
  self.send_error(404)
 def reply(self,data,mime):
  self.send_response(200);self.send_header('Content-Type',mime);self.send_header('Content-Length',str(len(data)));self.send_header('Connection','close');self.end_headers()
  try:self.wfile.write(data)
  except (BrokenPipeError,ConnectionResetError):pass
ThreadingHTTPServer(('127.0.0.1',9009),Handler).serve_forever()
