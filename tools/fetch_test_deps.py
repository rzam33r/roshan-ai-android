#!/usr/bin/env python3
"""Download the pinned dependencies in tests.lock into .testdeps/ and verify sha256.
.testdeps/libs : test classpath jars (plus classes.jar of each aar)
Uses only the Python standard library."""
import hashlib, os, sys, urllib.request, urllib.error, zipfile, shutil
GM = 'https://dl.google.com/dl/android/maven2'
MC = 'https://repo1.maven.org/maven2'
root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
out = os.path.join(root, '.testdeps')
shutil.rmtree(out, ignore_errors=True)
os.makedirs(os.path.join(out, 'libs')); os.makedirs(os.path.join(out, 'dl'))
def get(url):
    for _ in range(3):
        try:
            with urllib.request.urlopen(url, timeout=120) as r: return r.read()
        except urllib.error.HTTPError as e:
            if e.code == 404: return None
        except Exception: pass
    return None
bad = 0
for line in open(os.path.join(root, 'tests.lock')):
    line = line.strip()
    if not line or line.startswith('#'): continue
    g, a, v, ext, want = line.split(':')
    p = f"{g.replace('.', '/')}/{a}/{v}/{a}-{v}.{ext}"
    data = None
    cached = os.path.join(os.environ.get('RAI_DEP_CACHE', ''), f'{a}-{v}.{ext}') if os.environ.get('RAI_DEP_CACHE') else None
    if cached and os.path.exists(cached):
        c = open(cached, 'rb').read()
        if hashlib.sha256(c).hexdigest() == want: data = c
    for base in ((GM, MC) if data is None else ()):
        data = get(f'{base}/{p}')
        if data and hashlib.sha256(data).hexdigest() == want: break
        data = None
    if data is None:
        print('FAILED or hash mismatch:', line); bad += 1; continue
    f = os.path.join(out, 'dl', f'{a}-{v}.{ext}')
    open(f, 'wb').write(data)
    if os.environ.get('RAI_DEP_CACHE') and not (cached and os.path.exists(cached)):
        os.makedirs(os.environ['RAI_DEP_CACHE'], exist_ok=True); open(cached, 'wb').write(data)
    if ext == 'jar':
        os.replace(f, os.path.join(out, 'libs', f'{a}-{v}.jar'))
    else:
        z = zipfile.ZipFile(f)
        if 'classes.jar' in z.namelist():
            open(os.path.join(out, 'libs', f'{a}-{v}.aar.jar'), 'wb').write(z.read('classes.jar'))
shutil.rmtree(os.path.join(out, 'dl'), ignore_errors=True)
print('test deps ok' if not bad else f'{bad} problems'); sys.exit(1 if bad else 0)
