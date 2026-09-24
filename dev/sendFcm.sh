#!/bin/bash
# Send a test call push (Android) through the FCM HTTP v1 API.
#
# Usage:
#   dev/sendFcm.sh <service-account.json> <fcmToken> [callId] [callerName=Alice] [hasVideo=false]
#   dev/sendFcm.sh <service-account.json> <fcmToken> <callId> --end      # caller hung up
#
# service-account.json: Firebase console → Project settings → Service accounts → Generate new private key.
# Requires: curl, openssl, python3.

set -euo pipefail

sa=${1:?"path to service-account.json"}
token=${2:?"FCM token from registerVoipToken / voipToken"}
callId=${3:-$(python3 -c 'import uuid; print(uuid.uuid4())')}
callerName=${4:-"Alice"}
hasVideo=${5:-"false"}

type="incoming_call"
if [[ "$callerName" == "--end" ]]; then
    type="call_ended"
    callerName=""
fi

read_sa() { python3 -c "import json,sys; print(json.load(open(sys.argv[1]))[sys.argv[2]])" "$sa" "$1"; }
project=$(read_sa project_id)
email=$(read_sa client_email)

b64url() { openssl base64 -e -A | tr '+/' '-_' | tr -d '='; }

# OAuth2 access token via a service-account signed JWT.
now=$(date +%s)
header=$(printf '{"alg":"RS256","typ":"JWT"}' | b64url)
claims=$(printf '{"iss":"%s","scope":"https://www.googleapis.com/auth/firebase.messaging","aud":"https://oauth2.googleapis.com/token","iat":%d,"exp":%d}' \
    "$email" "$now" "$((now + 3600))" | b64url)
keyfile=$(mktemp)
trap 'rm -f "$keyfile"' EXIT
read_sa private_key > "$keyfile"
signature=$(printf '%s.%s' "$header" "$claims" | openssl dgst -sha256 -sign "$keyfile" | b64url)
oauth=$(curl -s https://oauth2.googleapis.com/token \
    -d grant_type=urn:ietf:params:oauth:grant-type:jwt-bearer \
    -d assertion="$header.$claims.$signature")
access=$(printf '%s' "$oauth" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("access_token",""))')
if [[ -z "$access" ]]; then
    echo "Could not get an access token: $oauth" >&2
    exit 1
fi

# Data-only + high priority: required for the message to reach the app while it is killed.
body=$(cat <<JSON
{
  "message": {
    "token": "${token}",
    "android": { "priority": "high", "ttl": "30s" },
    "data": {
      "type": "${type}",
      "callId": "${callId}",
      "callerName": "${callerName}",
      "handle": "${callerName}",
      "hasVideo": "${hasVideo}"
    }
  }
}
JSON
)

echo "Sending ${type} for callId ${callId}"
curl -s -X POST "https://fcm.googleapis.com/v1/projects/${project}/messages:send" \
    -H "Authorization: Bearer ${access}" \
    -H "Content-Type: application/json" \
    -d "$body"
echo
