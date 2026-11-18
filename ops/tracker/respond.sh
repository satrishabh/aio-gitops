#!/bin/bash
set -eu
IFS= read -r request || exit 0
while IFS= read -r header && [ "$header" != $'\r' ] && [ -n "$header" ]; do :; done
case "$request" in
  'GET / HTTP/'*|'GET /index.html HTTP/'*) file=/opt/aio-tracker/index.html; mime='text/html; charset=utf-8' ;;
  'GET /status.json HTTP/'*) file=/opt/aio-tracker/status.json; mime='application/json' ;;
  *) printf 'HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n'; exit 0 ;;
esac
length=$(wc -c < "$file")
printf 'HTTP/1.1 200 OK\r\nContent-Type: %s\r\nContent-Length: %s\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nConnection: close\r\n\r\n' "$mime" "$length"
cat "$file"
