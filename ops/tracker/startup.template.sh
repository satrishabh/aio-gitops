#!/bin/bash
set -eu
install -d -m 755 /opt/aio-tracker
printf '%s' '__INDEX_BASE64__' | base64 -d > /opt/aio-tracker/index.html
printf '%s' '__STATUS_BASE64__' | base64 -d > /opt/aio-tracker/status.json
printf '%s' '__RESPONDER_BASE64__' | base64 -d > /usr/local/bin/aio-tracker-response
chmod 755 /usr/local/bin/aio-tracker-response
chmod 644 /opt/aio-tracker/index.html /opt/aio-tracker/status.json
cat > /etc/systemd/system/aio-tracker.socket <<'EOF'
[Unit]
Description=AIO tracker loopback HTTP socket
[Socket]
ListenStream=127.0.0.1:8080
Accept=yes
MaxConnections=16
[Install]
WantedBy=sockets.target
EOF
cat > /etc/systemd/system/aio-tracker@.service <<'EOF'
[Unit]
Description=AIO read-only migration snapshot
[Service]
ExecStart=/usr/local/bin/aio-tracker-response
StandardInput=socket
StandardOutput=inherit
User=nobody
Group=nogroup
NoNewPrivileges=true
ProtectSystem=strict
ProtectHome=true
PrivateTmp=true
TimeoutStartSec=10
EOF
systemctl daemon-reload
systemctl enable --now aio-tracker.socket
