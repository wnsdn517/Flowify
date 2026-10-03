// Cloudflare Worker for the Spicy EX Telegram bot.
//  - Telegram webhook: /release, /beta, /ci in the discussion group; each answers with the APK itself.
//  - POST /ci-update (from CI, header X-CI-Secret): stores the Telegram file_ids of the newest CI build.
// Bindings: KV "CI". Secrets: TELEGRAM_BOT_TOKEN, WEBHOOK_SECRET, CI_NOTIFY_SECRET. Var: GITHUB_REPO.
const TG = (env, method) => `https://api.telegram.org/bot${env.TELEGRAM_BOT_TOKEN}/${method}`;
const gh = (env, path) =>
  fetch(`https://api.github.com/repos/${env.GITHUB_REPO}${path}`, {
    headers: { "User-Agent": "spicy-ex-bot", Accept: "application/vnd.github+json" },
  }).then((r) => r.json());

const esc = (s) => String(s ?? "").replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
const tgCall = (env, method, body) =>
  fetch(TG(env, method), { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) }).then((r) => r.json());

// Release notes -> a few readable lines: the "###" headings as highlights, else the first lines.
function highlights(body) {
  const heads = [...(body || "").matchAll(/^#{2,4}\s+(.+)$/gm)].map((m) => m[1].trim()).filter((h) => !/^Spicy EX \d/.test(h));
  if (heads.length) return heads.slice(0, 8).map((h) => `• ${esc(h)}`).join("\n");
  const text = (body || "").replace(/[#*`>]/g, "").trim().split("\n").filter(Boolean).slice(0, 4).join("\n");
  return esc(text.length > 400 ? text.slice(0, 400) + "…" : text);
}

function releaseCaption(label, r, apk) {
  const date = (r.published_at || "").slice(0, 10);
  const mb = apk ? ` · ${(apk.size / 1048576).toFixed(1)} MB` : "";
  let c = `<b>${esc(label)}  ${esc(r.name || r.tag_name)}</b>\n<code>${esc(r.tag_name)}</code> · ${date}${mb}\n`;
  const h = highlights(r.body);
  if (h) c += `\n${h}\n`;
  c += `\n<a href="${r.html_url}">Full release notes</a>`;
  return c.length > 1024 ? c.slice(0, 1000) + "…" : c;
}

async function sendRelease(env, chatId, replyTo, label, r) {
  if (!r) return tgCall(env, "sendMessage", { chat_id: chatId, text: `No ${label.toLowerCase()} found.`, reply_to_message_id: replyTo });
  const apk = (r.assets || []).find((a) => a.name.endsWith(".apk"));
  const caption = releaseCaption(label, r, apk);
  if (!apk) return tgCall(env, "sendMessage", { chat_id: chatId, text: caption, parse_mode: "HTML", reply_to_message_id: replyTo, disable_web_page_preview: true });
  const key = `rel:${r.tag_name}:${apk.id}`;
  const cached = await env.CI.get(key);
  if (cached) {
    return tgCall(env, "sendDocument", { chat_id: chatId, document: cached, caption, parse_mode: "HTML", reply_to_message_id: replyTo });
  }
  const file = await fetch(apk.browser_download_url);
  const form = new FormData();
  form.set("chat_id", String(chatId));
  form.set("caption", caption);
  form.set("parse_mode", "HTML");
  form.set("reply_to_message_id", String(replyTo));
  form.set("document", new File([await file.arrayBuffer()], `spicy-ex-${r.tag_name}.apk`, { type: "application/vnd.android.package-archive" }));
  const res = await fetch(TG(env, "sendDocument"), { method: "POST", body: form }).then((x) => x.json());
  const id = res.result?.document?.file_id;
  if (id) await env.CI.put(key, id);
  return res;
}

async function sendCi(env, chatId, replyTo) {
  const latest = JSON.parse((await env.CI.get("latest")) || "null");
  if (!latest) return tgCall(env, "sendMessage", { chat_id: chatId, text: "No CI build has been posted yet.", reply_to_message_id: replyTo });
  const media = latest.files.map((id, i) => ({
    type: "document",
    media: id,
    ...(i === latest.files.length - 1 ? { caption: latest.caption, parse_mode: "HTML" } : {}),
  }));
  return tgCall(env, "sendMediaGroup", { chat_id: chatId, media, reply_to_message_id: replyTo });
}

// Release list cached in KV (GitHub's unauthenticated API is rate limited on shared Worker IPs);
// a stale copy is used if GitHub errors out.
async function releases(env) {
  const hit = JSON.parse((await env.CI.get("releases")) || "null");
  if (hit && Date.now() - hit.at < 120000) return hit.list;
  try {
    const list = await gh(env, "/releases?per_page=30");
    if (Array.isArray(list)) {
      await env.CI.put("releases", JSON.stringify({ at: Date.now(), list }));
      return list;
    }
  } catch (e) {}
  return hit ? hit.list : null;
}

async function command(env, cmd, msg) {
  const chatId = msg.chat.id, replyTo = msg.message_id;
  const say = (text) => tgCall(env, "sendMessage", { chat_id: chatId, text, reply_to_message_id: replyTo });
  try {
    tgCall(env, "sendChatAction", { chat_id: chatId, action: "upload_document" }); // instant "sending..." feedback
    if (cmd === "ci") return await sendCi(env, chatId, replyTo);
    const list = await releases(env);
    if (!list) return await say("GitHub is not answering right now. Please try again in a minute.");
    const published = list.filter((r) => !r.draft && r.tag_name !== "ci-latest");
    if (cmd === "release") return await sendRelease(env, chatId, replyTo, "Latest release", published.find((r) => !r.prerelease));
    // /beta = the newest build of any kind, so it never comes back empty when a stable release is newer.
    const newest = published[0];
    return await sendRelease(env, chatId, replyTo, newest?.prerelease ? "Latest beta" : "Latest (stable)", newest);
  } catch (e) {
    await say("Something went wrong while sending the build. Please try again.");
  }
}

export default {
  async fetch(req, env, ctx) {
    const url = new URL(req.url);
    if (url.pathname === "/ci-update") {
      if (req.headers.get("X-CI-Secret") !== env.CI_NOTIFY_SECRET) return new Response("forbidden", { status: 403 });
      const body = await req.json(); // { caption, files: [file_id, ...] }
      if (!Array.isArray(body.files) || !body.files.length) return new Response("bad request", { status: 400 });
      await env.CI.put("latest", JSON.stringify({ caption: body.caption, files: body.files }));
      return new Response("ok");
    }
    if (req.headers.get("X-Telegram-Bot-Api-Secret-Token") !== env.WEBHOOK_SECRET) return new Response("forbidden", { status: 403 });
    const msg = (await req.json()).message;
    const m = msg?.text?.match(/^\/(release|beta|ci)(@\w+)?(\s|$)/i);
    if (m) ctx.waitUntil(command(env, m[1].toLowerCase(), msg));
    return new Response("ok");
  },
};
