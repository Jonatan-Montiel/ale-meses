#!/usr/bin/env python3
"""Pone en la CSP de index.html el hash (sha256) de cada <script> en línea.
Así el navegador solo ejecuta NUESTRO script y nada que se cuele en la página.
Uso: python3 tools/csp.py          -> actualiza index.html
     python3 tools/csp.py --check  -> sale con 1 si el hash no coincide"""
import base64, hashlib, re, sys, pathlib
p = pathlib.Path(__file__).resolve().parent.parent / 'index.html'
s = p.read_text(encoding='utf-8')
scripts = re.findall(r'<script(?![^>]*\bsrc=)[^>]*>(.*?)</script>', s, re.S)
hs = ' '.join("'sha256-%s'" % base64.b64encode(hashlib.sha256(x.encode('utf-8')).digest()).decode() for x in scripts)
new = re.sub(r"script-src [^;\"]*", 'script-src ' + hs, s, count=1)
if '--check' in sys.argv:
    sys.exit(0 if new == s else 1)
if new != s:
    p.write_text(new, encoding='utf-8'); print('CSP actualizada:', hs)
else:
    print('CSP al día')
