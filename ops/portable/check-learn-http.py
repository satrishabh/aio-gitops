"""Read-only acceptance checks for a locally forwarded isolated Learn deployment."""
import argparse
import json
import re
from urllib.error import HTTPError
from urllib.request import urlopen

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--base-url', default='http://127.0.0.1:18091')
args = parser.parse_args()
results = []


def request(path, expected=200):
    try:
        with urlopen(args.base_url.rstrip('/') + path, timeout=20) as response:
            status, body = response.status, response.read()
    except HTTPError as error:
        status, body = error.code, error.read()
    if status != expected:
        raise RuntimeError(f'{path}: expected {expected}, received {status}')
    results.append({'path': path, 'status': status})
    return body


for path in ['/health', '/ready', '/api/auth/health', '/api/progress/health']:
    request(path)
html = request('/').decode()
assets = sorted(set(re.findall(r'(?:src|href)=["\'](/assets/[^"\']+)', html)))
if not assets:
    raise RuntimeError('Homepage did not reference any built UI assets')
for asset in assets:
    request(asset)
categories = json.loads(request('/api/content/categories'))
if not isinstance(categories, list) or not categories:
    raise RuntimeError('Restored category API returned no categories')
request('/api/content/reels')
request('/api/auth/me', 401)
request('/api/progress/me', 401)
print(json.dumps({'passed': True, 'categoryCount': len(categories), 'assetCount': len(assets),
                  'checks': results, 'visualBrowserTestRun': False, 'authenticatedIdentityTestRun': False}, indent=2))
