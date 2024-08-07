#!/bin/bash

function main {
    local connectionId=${1:?"connectionId should be specified"}
    local token=${2:?"Enter device token that you received on register listener"}
    local bundleId=${3:-"Enter your Bundle Id"}
    local username=${4:-"Anonymus"}

    # Check if the certificate file exists
    if [[ ! -f "app.pem" ]]; then
        echo "Certificate file app.pem not found!"
        exit 1
    fi

    local payload=$(cat <<EOF
{
    "aps": {
        "alert": "Incoming call",
        "content-available": "1"
    },
    "Username": "${username}",
    "ConnectionId": "${connectionId}"
}
EOF
)

    curl -v \
    -d "$payload" \
    -H "apns-topic: $bundleId.voip" \
    -H "apns-push-type: voip" \
    -H "apns-priority: 10" \
    --http2 \
    --cert app.pem \
"https://api.development.push.apple.com/3/device/${token}"
}

main "$@"
