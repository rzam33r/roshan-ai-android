#!/usr/bin/env bash
# Loads the production assets/app.html in headless Chrome with stubbed bridges and runs task-mode scenarios against its real functions.
# Prints JSTEST PASS/FAIL lines. Needs google-chrome (preinstalled on GitHub ubuntu runners).
set -e
cd "$(dirname "$0")/../.."
CH="$(command -v google-chrome || command -v chromium || true)"; [ -n "$CH" ] || { echo "JSTEST SKIPPED: no chrome"; exit 0; }
mkdir -p out; python3 - <<'PY'
h=open('assets/app.html').read()
stub=open('tests/js/stubs.js').read(); drv=open('tests/js/scenarios.js').read()
open('out/js_test.html','w').write(h.replace('<script>','<script>'+stub+'</script><script>',1).replace('</body>','<script>'+drv+'</script></body>'))
PY
D=$(mktemp -d)
"$CH" --headless=new --no-sandbox --disable-gpu --user-data-dir="$D" --dump-dom file://$PWD/out/js_test.html 2>/dev/null > out/js_dom.html
python3 - <<'PY' > out/js_res.txt
import re,html
d=open('out/js_dom.html').read()
m=re.search(r'<pre id="jsres">(.*?)</pre>',d,re.S)
print(html.unescape(m.group(1)) if m else '')
PY
cat out/js_res.txt
grep -q 'JSTEST FAIL' out/js_res.txt && exit 1
grep -q 'JSTEST DONE' out/js_res.txt || { echo "JSTEST did not finish"; exit 1; }
