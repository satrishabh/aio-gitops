#!/usr/bin/env sh
# Run on the identity-service pod. Prints public settings only, never credentials.
set -eu
cfg=$(mktemp /tmp/aio-idp-inspection.XXXXXX)
trap 'rm -f "$cfg"' EXIT
/opt/keycloak/bin/kcadm.sh config credentials --config "$cfg" \
  --server http://127.0.0.1:8080 --realm master \
  --user "$KC_BOOTSTRAP_ADMIN_USERNAME" --password "$KC_BOOTSTRAP_ADMIN_PASSWORD" >/dev/null
for realm in aio sarvaikam; do
  echo "Realm: $realm"
  /opt/keycloak/bin/kcadm.sh get identity-provider/instances/github --config "$cfg" -r "$realm" \
    | jq '{alias,enabled,clientId:.config.clientId}'
done
/opt/keycloak/bin/kcadm.sh get clients --config "$cfg" -r aio -q clientId=aio-platform \
  --fields clientId,redirectUris,rootUrl
