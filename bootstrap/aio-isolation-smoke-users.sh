#!/usr/bin/env sh
# Run only on the identity-service pod. Capture create-mode stdout to a private
# temporary file; it contains disposable test credentials. Never commit that file.
set -eu
mode=${1:-create}
prefix=${2:-}
case "$prefix" in codex-isolation-[a-f0-9]*) ;; *) echo 'Invalid test prefix' >&2; exit 1;; esac
cfg=$(mktemp /tmp/aio-smoke-config.XXXXXX)
role_file=$(mktemp /tmp/aio-smoke-role.XXXXXX)
trap 'rm -f "$cfg" "$role_file"' EXIT
/opt/keycloak/bin/kcadm.sh config credentials --config "$cfg" --server http://127.0.0.1:8080 \
  --realm master --user "$KC_BOOTSTRAP_ADMIN_USERNAME" --password "$KC_BOOTSTRAP_ADMIN_PASSWORD" >/dev/null
if [ "$mode" = cleanup ]; then
  for tier in business admin; do
    username="$prefix-$tier"
    user_id=$(/opt/keycloak/bin/kcadm.sh get users --config "$cfg" -r aio -q username="$username" \
      | jq -r --arg username "$username" '.[] | select(.username == $username) | .id')
    if [ -n "$user_id" ]; then /opt/keycloak/bin/kcadm.sh delete "users/$user_id" --config "$cfg" -r aio; fi
  done
  echo 'Disposable test users removed'
  exit 0
fi
[ "$mode" = create ] || exit 1
client_id=$(/opt/keycloak/bin/kcadm.sh get clients --config "$cfg" -r aio -q clientId=aio-platform | jq -r '.[0].id')
result='[]'
for tier in business admin; do
  username="$prefix-$tier"
  password="Aa1!$(head -c 24 /dev/urandom | base64 | tr -dc A-Za-z0-9)"
  user_id=$(/opt/keycloak/bin/kcadm.sh create users --config "$cfg" -r aio -i \
    -s username="$username" -s enabled=true -s emailVerified=true \
    -s email="$username@example.invalid" -s firstName=Isolation -s lastName=Test)
  /opt/keycloak/bin/kcadm.sh set-password --config "$cfg" -r aio --userid "$user_id" --new-password "$password" >/dev/null
  /opt/keycloak/bin/kcadm.sh get "clients/$client_id/roles/aio-$tier" --config "$cfg" -r aio | jq -s . >"$role_file"
  /opt/keycloak/bin/kcadm.sh create "users/$user_id/role-mappings/clients/$client_id" --config "$cfg" -r aio -f "$role_file" >/dev/null
  result=$(printf '%s' "$result" | jq --arg username "$username" --arg password "$password" --arg tier "$tier" \
    '. + [{username:$username,password:$password,tier:$tier}]')
done
printf '%s\n' "$result"
