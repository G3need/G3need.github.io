#!/usr/bin/env python3
"""
inline_assets.py - Everdeen APK build step

Why this exists
    A WebView can refuse a subresource for half a dozen reasons and still raise
    no error event: a security policy, a service worker answering for a domain
    nothing hosts, a file-access flag. Every one of those ends as an <img> that
    requests nothing and settles on nothing, which reports as "pending" with no
    way to tell one cause from another. That has already cost four rounds of
    guessing, so the request path is removed instead of explained.

    A data URI asks nothing from the network, the file system or a worker. An
    inlined asset either renders, or the engine cannot render images at all,
    and that distinction is finally legible.

What it does
    Rewrites every src/href pointing at assets/ into a base64 data URI, so the
    APK carries its artwork inside the document itself.

What it deliberately leaves alone
    The public web build. There the same files are requested normally, because
    a browser caches them across visits and that is correct on the real
    internet. Only the APK runs this.

    python inline_assets.py <index.html> <out.html>

    Asset paths are read relative to the html file's own directory, so passing
    the shipped index.html finds its own assets without a second argument to
    get wrong.
"""
import base64
import mimetypes
import re
import sys
from pathlib import Path

# Extension to MIME by hand, because the platform database differs between
# Windows and the Linux runner.
MIME = {
    '.svg': 'image/svg+xml',
    '.png': 'image/png',
    '.jpg': 'image/jpeg',
    '.jpeg': 'image/jpeg',
    '.webp': 'image/webp',
    '.gif': 'image/gif',
}

# Anything that already resolved itself, or lives somewhere else entirely.
SKIP = ('data:', 'http:', 'https:', 'blob:', '//', '#')

# The '=' is the whole thing: src is followed by an equals sign, not by the
# quote. Writing the pattern without it matches nothing and silently produces
# an unmodified document.
PAT = re.compile(r'(\b(?:src|href))=(["\'])([^"\']+?\.(?:svg|png|jpe?g|webp|gif))\2')


def resolve(root, rel):
    """Find the file behind a relative href.

    Kept as a real function instead of one join because the document is
    inlined from two different places: the web root, where href="assets/x.png"
    sits right beside the assets dir, and the staged copy, where index.html
    sits *inside* the assets dir and the same href would need the leading
    segment stripped. Getting this wrong reads as "missing asset" and then
    nothing inlines, so try both and say which one hit.
    """
    direct = root / rel
    if direct.exists():
        return direct
    parts = rel.split('/')
    if len(parts) > 1:
        nested = root / '/'.join(parts[1:])
        if nested.exists():
            return nested
    return direct


def data_uri(path):
    if not path.exists():
        print('  missing asset:', path)
        return None
    mime = MIME.get(path.suffix.lower()) or mimetypes.guess_type(path.name)[0] \
        or 'application/octet-stream'
    b64 = base64.b64encode(path.read_bytes()).decode('ascii')
    return 'data:%s;base64,%s' % (mime, b64)


def main():
    html_file, out = Path(sys.argv[1]), Path(sys.argv[2])
    root = html_file.parent
    text = html_file.read_text(encoding='utf-8')

    cache = {}

    def inline(match):
        attr, quote, rel = match.group(1), match.group(2), match.group(3).strip()
        if rel.startswith(SKIP):
            return match.group(0)
        if rel not in cache:
            cache[rel] = data_uri(resolve(root, rel))
        uri = cache[rel]
        if not uri:
            return match.group(0)
        return '%s=%s%s%s' % (attr, quote, uri, quote)

    text = PAT.sub(inline, text)
    out.write_text(text, encoding='utf-8')

    ok = sum(1 for v in cache.values() if v)
    print('  inlined %d of %d referenced assets' % (ok, len(cache)))
    print('  document %d chars -> %d' % (len(html_file.read_text(encoding='utf-8')), len(text)))
    if not ok:
        raise SystemExit('nothing was inlined; check the assets path')


if __name__ == '__main__':
    main()
