// Cloudflare Worker: answers /release, /beta, /ci in the Telegram discussion group.
// Secrets: TELEGRAM_BOT_TOKEN, WEBHOOK_SECRET. Var: GITHUB_REPO (e.g. "wnsdn517/spicy-ex").
const gh = (env, path) =>
  fetch(`https://api.github.com/repos/${env.GITHUB_REPO}${path}`, {
    headers: { "User-Agent": "spicy-ex-bot", Accept: "application/vnd.github+json" },
  }).then((r) => r.json());

function releaseText(label, r) {
  if (!r || !r.html_url) return `No ${label} found.`;
  const apk = (r.assets || []).find((a) => a.name.endsWith(".apk"));
  return `${label}: ${r.name || r.tag_name}\n${r.published_at?.slice(0, 10) ?? ""}\n` +
    (apk ? `APK: ${apk.browser_download_url}\n` : "") + r.html_url;
}

async function answer(env, cmd) {
  if (cmd === "release") {
    const list = await gh(env, "/releases?per_page=30");
    return releaseText("Latest release", list.find((r) => !r.prerelease && !r.draft));
  }
  if (cmd === "beta") {
    const list = await gh(env, "/releases?per_page=30");
    return releaseText("Latest beta", list.find((r) => r.prerelease && !r.draft));
  }
  if (cmd === "ci") {
    const d = await gh(env, "/actions/runs?status=success&per_page=1");
    const run = d.workflow_runs?.[0];
    if (!run) return "No successful CI run found.";
    return `Latest CI build: ${run.head_sha.slice(0, 7)} (${run.head_branch})\n${run.created_at.slice(0, 16).replace("T", " ")} UTC\n${run.html_url}\nAPK is also posted in https://t.me/spicy_ex_ci`;
  }
  return null;
}

export default {
  async fetch(req, env) {
    if (req.headers.get("X-Telegram-Bot-Api-Secret-Token") !== env.WEBHOOK_SECRET)
      return new Response("forbidden", { status: 403 });
    const msg = (await req.json()).message;
    const m = msg?.text?.match(/^\/(release|beta|ci)(@\w+)?(\s|$)/i);
    if (m) {
      const text = await answer(env, m[1].toLowerCase());
      if (text)
        await fetch(`https://api.telegram.org/bot${env.TELEGRAM_BOT_TOKEN}/sendMessage`, {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ chat_id: msg.chat.id, text, reply_to_message_id: msg.message_id, disable_web_page_preview: true }),
        });
    }
    return new Response("ok");
  },
};
