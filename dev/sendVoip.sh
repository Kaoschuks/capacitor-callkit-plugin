#!/bin/bash
# Send a test VoIP push (iOS) through APNs.
# Usage: dev/sendVoip.sh <callId> <voipToken> <bundleId> [hasVideo=false] [callerName=Anonymous] [sandbox|production]
# Needs app.pem (VoIP certificate, see README) in the current directory.

function main {
    local callId=${1:?"callId should be specified"}
    local token=${2:?"Enter the VoIP token received from registerVoipToken / voipToken"}
    local bundleId=${3:?"Enter your bundle id"}
    local hasVideo=${4:-"false"}
    local callerName=${5:-"Anonymous"}
    local env=${6:-"sandbox"}

    if [[ ! -f "app.pem" ]]; then
        echo "Certificate file app.pem not found!"
        exit 1
    fi

    local host="api.development.push.apple.com"
    [[ "$env" == "production" ]] && host="api.push.apple.com"

    # ConnectionId / Username are the legacy keys still read by the current iOS code.
    local payload
    payload=$(cat <<JSON
{
    "aps": { "alert": "Incoming call", "content-available": 1 },
    "type": "incoming_call",
    "callId": "${callId}",
    "callerName": "${callerName}",
    "handle": "${callerName}",
    "hasVideo": "${hasVideo}",
    "ConnectionId": "${callId}",
    "Username": "${callerName}"
}
JSON
)

    curl -v \
        -d "$payload" \
        -H "apns-topic: ${bundleId}.voip" \
        -H "apns-push-type: voip" \
        -H "apns-priority: 10" \
        -H "apns-expiration: 0" \
        --http2 \
        --cert app.pem \
        "https://${host}/3/device/${token}"
}

main "$@"
