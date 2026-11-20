"""Delete old container image versions on ghcr.io, keeping everything still in use.

A version is KEPT when any of these hold:
  * one of its tags (or its digest) is referenced in this repo: apps/*/values-*.yaml `repository` + `tag`,
    or a literal ghcr.io/<owner>/<pkg>:<tag> / @sha256:<digest> anywhere in tracked text files
  * it is passed with --keep <pkg>@sha256:<digest> or --keep <pkg>:<tag> (e.g. the running platform image,
    whose values live outside this repo)
  * it is among the newest --keep-latest versions of its package (default 2: current + one rollback)
  * it is a child of a kept multi-arch index or an attestation of a kept image (deleting those breaks the image)
Everything else is deleted. Default is a dry run; pass --delete to act.

Token: GHCR_TOKEN, a classic PAT with read:packages + delete:packages (fine-grained tokens cannot manage packages).
  GHCR_TOKEN=... python scripts/ghcr_cleanup.py --owner satrishabh                      # dry run
  GHCR_TOKEN=... python scripts/ghcr_cleanup.py --owner satrishabh --delete --keep aio-platform@sha256:...
"""
import argparse
import base64
import json
import os
import re
import subprocess
import sys
import urllib.error
import urllib.parse
import urllib.request

API = 'https://api.github.com'
INDEX_TYPES = ('application/vnd.oci.image.index.v1+json', 'application/vnd.docker.distribution.manifest.list.v2+json')
ACCEPT = ', '.join(INDEX_TYPES + ('application/vnd.oci.image.manifest.v1+json',
                                  'application/vnd.docker.distribution.manifest.v2+json'))


def http(url, token, method='GET', accept='application/vnd.github+json', auth='Bearer'):
    req = urllib.request.Request(url, method=method, headers={
        'Accept': accept, 'Authorization': f'{auth} {token}', 'X-GitHub-Api-Version': '2022-11-28'})
    with urllib.request.urlopen(req, timeout=60) as r:
        body = r.read()
        return json.loads(body) if body else None, r.headers


def paged(url, token):
    out, page = [], 1
    while True:
        data, _ = http(f"{url}{'&' if '?' in url else '?'}per_page=100&page={page}", token)
        out += data
        if len(data) < 100:
            return out
        page += 1


def repo_refs(owner, root='.'):
    """{pkg: set(tags and digests)} referenced by tracked files."""
    refs = {}
    files = subprocess.run(['git', '-C', root, 'ls-files'], capture_output=True, text=True, check=True).stdout.split()
    lit = re.compile(rf'ghcr\.io/{re.escape(owner)}/([a-z0-9._/-]+?)(?::([\w.-]+)|@(sha256:[0-9a-f]{{64}}))')
    repo_line = re.compile(rf'^\s*repository:\s*["\']?ghcr\.io/{re.escape(owner)}/([a-z0-9._/-]+)')
    tag_line = re.compile(r'^\s*tag:\s*["\']?([\w.:-]+)')
    for f in files:
        if not f.endswith(('.yaml', '.yml', '.json', '.env', '.tf', '.txt')):
            continue
        try:
            text = open(os.path.join(root, f), encoding='utf-8').read()
        except (OSError, UnicodeDecodeError):
            continue
        for m in lit.finditer(text):
            refs.setdefault(m.group(1), set()).add(m.group(2) or m.group(3))
        pkg = None
        for line in text.splitlines():
            if m := repo_line.match(line):
                pkg = m.group(1)
            elif pkg and (m := tag_line.match(line)):
                refs.setdefault(pkg, set()).add(m.group(1))
                pkg = None
    return refs


def plan(versions, referenced, keep_latest, children):
    """versions: newest first, each {id, name(digest), tags}. children(digest) -> digests it references.
    Returns (keep, delete) lists of versions."""
    keep_digests = set()
    for i, v in enumerate(versions):
        if i < keep_latest or v['name'] in referenced or referenced & set(v['tags']):
            keep_digests.add(v['name'])
    # children of kept indexes, and attestation manifests whose subject is kept (buildx provenance/sbom)
    for d in list(keep_digests):
        keep_digests |= set(children(d))
    keep = [v for v in versions if v['name'] in keep_digests]
    delete = [v for v in versions if v['name'] not in keep_digests]
    return keep, delete


def registry_children(owner, pkg, token):
    """Digests referenced by a manifest index (platform images + attestations)."""
    cache = {}
    basic = base64.b64encode(f'{owner}:{token}'.encode()).decode()
    reg_token, _ = http(f'https://ghcr.io/token?scope=repository:{owner}/{pkg}:pull&service=ghcr.io', basic, auth='Basic')
    rt = reg_token['token']

    def children(digest):
        if digest not in cache:
            try:
                m, _ = http(f'https://ghcr.io/v2/{owner}/{pkg}/manifests/{digest}', rt, accept=ACCEPT)
                cache[digest] = [x['digest'] for x in m.get('manifests', [])] if m.get('mediaType') in INDEX_TYPES or 'manifests' in m else []
            except urllib.error.HTTPError:
                cache[digest] = []
        return cache[digest]
    return children


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('--owner', required=True)
    ap.add_argument('--keep-latest', type=int, default=2)
    ap.add_argument('--keep', action='append', default=[], help='pkg@sha256:... or pkg:tag (repeatable)')
    ap.add_argument('--package', action='append', help='limit to these packages (repeatable)')
    ap.add_argument('--delete', action='store_true', help='actually delete (default: dry run)')
    a = ap.parse_args()
    token = os.environ.get('GHCR_TOKEN') or sys.exit('set GHCR_TOKEN (classic PAT: read:packages, delete:packages)')
    if a.keep_latest < 1:
        sys.exit('--keep-latest must be at least 1')

    refs = repo_refs(a.owner)
    for k in a.keep:
        pkg, _, ref = k.partition('@') if '@' in k else k.partition(':')
        refs.setdefault(pkg, set()).add(ref)

    pkgs = [p['name'] for p in paged(f'{API}/user/packages?package_type=container', token)]
    total = 0
    for pkg in sorted(pkgs):
        if a.package and pkg not in a.package:
            continue
        enc = urllib.parse.quote(pkg, safe='')
        versions = [{'id': v['id'], 'name': v['name'], 'tags': v['metadata']['container']['tags'], 'created': v['created_at']}
                    for v in paged(f'{API}/user/packages/container/{enc}/versions', token)]
        versions.sort(key=lambda v: v['created'], reverse=True)
        keep, delete = plan(versions, refs.get(pkg, set()), a.keep_latest, registry_children(a.owner, pkg, token))
        print(f'{pkg}: {len(versions)} versions, keep {len(keep)}, delete {len(delete)}')
        for v in keep:
            print(f"  keep   {v['name'][:19]} {','.join(v['tags']) or '-'} {v['created'][:10]}")
        for v in delete:
            print(f"  {'DELETE' if a.delete else 'would delete'} {v['name'][:19]} {','.join(v['tags']) or '-'} {v['created'][:10]}")
            if a.delete:
                http(f"{API}/user/packages/container/{enc}/versions/{v['id']}", token, method='DELETE')
        total += len(delete)
    print(f"{'deleted' if a.delete else 'would delete'} {total} versions")


if __name__ == '__main__':
    main()
