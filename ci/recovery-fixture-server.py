#!/usr/bin/env python3
"""Synthetic, local-only recovery fixture. Never logs headers or URL queries."""
import hashlib, json, os, struct, threading, time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlsplit
ROOT=Path('app/src/androidTest/assets/fixtures')
PREFIX=(ROOT/'direct.mp4').read_bytes()
SIZE=32*1024*1024
HEAD=PREFIX+struct.pack('>I4sQ',1,b'free',SIZE-len(PREFIX))
ETAG='"fixture-'+hashlib.sha256(HEAD).hexdigest()+'"'
LOCK=threading.Lock(); COUNTS={}; RANGES=[]
def block(offset,length):
    end=min(offset+length,len(HEAD))
    front=HEAD[offset:end] if offset<len(HEAD) else b''
    return front+b'\0'*(length-len(front))
def expected():
    digest=hashlib.sha256()
    for offset in range(0,SIZE,128*1024): digest.update(block(offset,min(128*1024,SIZE-offset)))
    return digest.hexdigest()
EXPECTED=expected()
class Handler(BaseHTTPRequestHandler):
    protocol_version='HTTP/1.1'
    def log_message(self,*args): pass
    def do_GET(self):
        path=urlsplit(self.path).path
        if path=='/reset':
            with LOCK: COUNTS.clear(); RANGES.clear()
            return self.reply(b'{}','application/json')
        if path=='/stats':
            with LOCK: data={'counts':dict(COUNTS),'ranges':list(RANGES),'sha256':EXPECTED,'length':SIZE}
            return self.reply(json.dumps(data).encode(),'application/json')
        if path=='/page': return self.reply(b'<!doctype html><title>Recovery fixture</title><h1>Recovery test</h1>','text/html')
        with LOCK: COUNTS[path]=COUNTS.get(path,0)+1
        if path=='/process/direct':
            offset=0; value=self.headers.get('Range')
            if value and self.headers.get('If-Range')==ETAG:
                try: offset=int(value.removeprefix('bytes=').split('-')[0])
                except ValueError: return self.send_error(400)
            if offset>=SIZE: return self.send_error(416)
            with LOCK: RANGES.append(offset)
            self.send_response(206 if offset else 200);self.send_header('Content-Type','video/mp4');self.send_header('Content-Length',str(SIZE-offset));self.send_header('ETag',ETAG);self.send_header('Accept-Ranges','bytes');self.send_header('Connection','close')
            if offset:self.send_header('Content-Range',f'bytes {offset}-{SIZE-1}/{SIZE}')
            self.end_headers()
            try:
                while offset<SIZE:
                    length=min(64*1024,SIZE-offset);self.wfile.write(block(offset,length));self.wfile.flush();offset+=length;time.sleep(.015)
            except (BrokenPipeError,ConnectionResetError): pass
            return
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
