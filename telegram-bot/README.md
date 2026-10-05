# Spicy EX Telegram bot

`worker.js` is a Cloudflare Worker that answers `/release`, `/beta`, `/ci` in the discussion group.
Push to `main` with at least one pushed commit subject ending in `nightly` (for example,
`Fix lyrics sync nightly`) to build the final pushed tree once and send the release and debug APKs
directly to the Telegram CI channel. A batch push is built once if any commit in it has the marker;
other pushes skip the build. Nightly APKs are not uploaded as GitHub Actions artifacts or GitHub
Releases. Retries and repeat builds of an already successful commit are skipped; publish an
intentional GitHub release separately using `.github/workflows/release.yml`.

## Setup
1. Bot (@BotFather): disable Group Privacy so it sees commands; add it to the discussion group, and as admin to the CI channel.
2. GitHub repo secrets: `TELEGRAM_BOT_TOKEN`, `TELEGRAM_CI_CHAT_ID` (`@spicy_ex_ci` or the numeric id).
3. Worker: `npx wrangler deploy telegram-bot/worker.js --name spicy-ex-bot --var GITHUB_REPO:wnsdn517/spicy-ex`,
   then `wrangler secret put TELEGRAM_BOT_TOKEN` and `wrangler secret put WEBHOOK_SECRET`.
4. Webhook: `curl "https://api.telegram.org/bot<TOKEN>/setWebhook" -d url=<worker url> -d secret_token=<WEBHOOK_SECRET>`
