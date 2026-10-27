"""Renames a decoded (apktool d) APK and gives it the Umineko icon.

usage: python3 patch.py <decoded-dir> "<app name>"

Copies res/ from this folder into the decoded APK and points the manifest's
label and icon attributes (application, activities, aliases) at them.
"""
import os
import re
import shutil
import sys
from xml.sax.saxutils import escape

here = os.path.dirname(os.path.abspath(__file__))
dec, name = sys.argv[1], sys.argv[2]

# 1. resources: icon PNGs, adaptive icon, background colour
for root, _, files in os.walk(os.path.join(here, 'res')):
    rel = os.path.relpath(root, os.path.join(here, 'res'))
    dst = os.path.join(dec, 'res', rel)
    os.makedirs(dst, exist_ok=True)
    for f in files:
        shutil.copy2(os.path.join(root, f), os.path.join(dst, f))

# 2. manifest: label + icon on <application>, <activity> and <activity-alias>
path = os.path.join(dec, 'AndroidManifest.xml')
xml = open(path, encoding='utf-8').read()
attrs = {
    'android:label': escape(name, {'"': '&quot;'}),
    'android:icon': '@mipmap/umineko_icon',
    'android:roundIcon': '@mipmap/umineko_icon',
}


def patch_tag(m, add_missing):
    tag = m.group(0)
    for key, value in attrs.items():
        pattern = re.compile(r'\s' + re.escape(key) + r'="[^"]*"')
        if pattern.search(tag):
            tag = pattern.sub(' %s="%s"' % (key, value), tag, count=1)
        elif add_missing:
            tag = re.sub(r'^<(\S+)', lambda t: '<%s %s="%s"' % (t.group(1), key, value), tag, count=1)
    return tag


xml, n_app = re.subn(r'<application\b[^>]*>', lambda m: patch_tag(m, True), xml, count=1)
xml, n_act = re.subn(r'<activity(?:-alias)?\b[^>]*>', lambda m: patch_tag(m, False), xml)
if n_app != 1:
    sys.exit('no <application> tag found')
open(path, 'w', encoding='utf-8').write(xml)
print('patched application and %d activities' % n_act)
