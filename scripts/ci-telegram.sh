#!/usr/bin/env bash
# Posts the CI release + debug APKs to the Telegram CI channel as ONE message (media group)
# with a formatted caption. Env: TELEGRAM_BOT_TOKEN, TELEGRAM_CI_CHAT_ID (+ GitHub Actions vars).
set -euo pipefail

if [ -z "${TELEGRAM_BOT_TOKEN:-}" ] || [ -z "${TELEGRAM_CI_CHAT_ID:-}" ]; then
  echo "Telegram secrets not set; skipping."
  exit 0
fi

prop() { sed -n "s/^$1=//p" gradle.properties | tr -d '\r'; }
esc() { sed -e 's/&/\&amp;/g' -e 's/</\&lt;/g' -e 's/>/\&gt;/g'; }

short=${GITHUB_SHA::7}
version="$(prop spicyForkMajor).$(prop spicyForkMinor).$(prop spicyForkPatch)"
code=$((11000 + $(git rev-list --count HEAD)))
subject=$(git log -1 --pretty=%s | esc)
author=$(git log -1 --pretty=%an | esc)
repo_url="${GITHUB_SERVER_URL}/${GITHUB_REPOSITORY}"

caption="<b>Spicy EX CI build</b>

<b>Version</b>  <code>${version}</code> (${code})
<b>Branch</b>  <code>${GITHUB_REF_NAME}</code>
<b>Commit</b>  <a href=\"${repo_url}/commit/${GITHUB_SHA}\">${short}</a> by ${author}
<blockquote>${subject}</blockquote>
📦 Signed <b>release</b> + <b>debug</b> APK
🔗 <a href=\"${repo_url}/actions/runs/${GITHUB_RUN_ID}\">Build log</a>"

rel=$(ls app/build/outputs/apk/release/*.apk | head -1)
dbg=$(ls app/build/outputs/apk/debug/*.apk | head -1)

# In a media group the caption of the last item is shown under the whole message.
media=$(jq -nc --arg c "$caption" '[
  {type: "document", media: "attach://release"},
  {type: "document", media: "attach://debug", caption: $c, parse_mode: "HTML"}
]')

resp=$(curl -sS --fail -X POST "https://api.telegram.org/bot${TELEGRAM_BOT_TOKEN}/sendMediaGroup"   --form-string chat_id="$TELEGRAM_CI_CHAT_ID"   --form-string media="$media"   -F "release=@${rel};filename=spicy-ex-${version}-${short}-release.apk"   -F "debug=@${dbg};filename=spicy-ex-${version}-${short}-debug.apk")
echo "Sent ${version} (${code}) ${short} to Telegram."

# Hand the file ids to the bot so /ci in the discussion group can resend this build as-is.
if [ -n "${CI_BOT_URL:-}" ] && [ -n "${CI_NOTIFY_SECRET:-}" ]; then
  files=$(echo "$resp" | jq -c '[.result[].document.file_id]')
  jq -nc --arg c "$caption" --argjson f "$files" '{caption: $c, files: $f}'     | curl -sS --fail -X POST "${CI_BOT_URL}/ci-update" -H "X-CI-Secret: ${CI_NOTIFY_SECRET}"         -H "Content-Type: application/json" --data-binary @- > /dev/null
  echo "Bot updated for /ci."
fi
