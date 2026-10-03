// Cloudflare Worker for the Spicy EX Telegram bot.
//  - Telegram webhook: /release, /beta, /ci, /help in the discussion group; each release reply has a
//    "Full notes here" button that prints the untouched release body in-chat (not just a GitHub link).
//  - Feature-request / bug-report detection: a keyword match in the discussion group gets a "File as
//    GitHub issue" button (labeled bug or enhancement); clicking it (original author or a group admin)
//    files the issue, deduped by message text.
//  - POST /ci-update (from CI, header X-CI-Secret): stores the Telegram file_ids of the newest CI build.
// Bindings: KV "CI". Secrets: TELEGRAM_BOT_TOKEN, WEBHOOK_SECRET, CI_NOTIFY_SECRET, GH_TOKEN (optional
// but strongly recommended: raises the GitHub API rate limit from 60/hr per shared Worker IP to 5000/hr,
// and is required for filing issues). Vars: GITHUB_REPO, DISCUSSION_CHAT_USERNAME.
const TG = (env, method) => `https://api.telegram.org/bot${env.TELEGRAM_BOT_TOKEN}/${method}`;
const ghHeaders = (env) => ({
  "User-Agent": "spicy-ex-bot",
  Accept: "application/vnd.github+json",
  ...(env.GH_TOKEN ? { Authorization: `Bearer ${env.GH_TOKEN}` } : {}),
});
const gh = (env, path, init) =>
  fetch(`https://api.github.com/repos/${env.GITHUB_REPO}${path}`, { ...init, headers: { ...ghHeaders(env), ...(init?.headers || {}) } }).then((r) => r.json());

const esc = (s) => String(s ?? "").replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
const tgCall = (env, method, body) =>
  fetch(TG(env, method), { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) }).then((r) => r.json());

// ---------- /release, /beta, /ci ----------

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
  if (h) c += `\n${h}`;
  return c.length > 1024 ? c.slice(0, 1000) + "…" : c;
}

// Open on GitHub, or print the untouched release body right here (the caption above is a trimmed summary).
function releaseButtons(r) {
  return { inline_keyboard: [[{ text: "🔗 Open on GitHub", url: r.html_url }, { text: "📄 Full notes here", callback_data: `full:${r.tag_name}` }]] };
}

async function sendRelease(env, chatId, replyTo, label, r) {
  if (!r) return tgCall(env, "sendMessage", { chat_id: chatId, text: `No ${label.toLowerCase()} found.`, reply_to_message_id: replyTo });
  const apk = (r.assets || []).find((a) => a.name.endsWith(".apk"));
  const caption = releaseCaption(label, r, apk);
  const reply_markup = releaseButtons(r);
  if (!apk) return tgCall(env, "sendMessage", { chat_id: chatId, text: caption, parse_mode: "HTML", reply_to_message_id: replyTo, disable_web_page_preview: true, reply_markup });
  const key = `rel:${r.tag_name}:${apk.id}`;
  const cached = await env.CI.get(key);
  if (cached) {
    return tgCall(env, "sendDocument", { chat_id: chatId, document: cached, caption, parse_mode: "HTML", reply_to_message_id: replyTo, reply_markup });
  }
  const file = await fetch(apk.browser_download_url);
  const form = new FormData();
  form.set("chat_id", String(chatId));
  form.set("caption", caption);
  form.set("parse_mode", "HTML");
  form.set("reply_to_message_id", String(replyTo));
  form.set("reply_markup", JSON.stringify(reply_markup));
  form.set("document", new File([await file.arrayBuffer()], `spicy-ex-${r.tag_name}.apk`, { type: "application/vnd.android.package-archive" }));
  const res = await fetch(TG(env, "sendDocument"), { method: "POST", body: form }).then((x) => x.json());
  const id = res.result?.document?.file_id;
  if (id) await env.CI.put(key, id);
  return res;
}

// "Full notes here" button: re-fetch that one release and print its untouched body as plain messages,
// split under Telegram's 4096-char limit, instead of just linking out to GitHub.
async function sendFullNotes(env, cq, tag) {
  const answer = (text) => tgCall(env, "answerCallbackQuery", { callback_query_id: cq.id, text });
  const r = await gh(env, `/releases/tags/${encodeURIComponent(tag)}`);
  if (!r?.body) return answer("Couldn't load the full notes right now.");
  await answer("Sending…");
  const chunks = r.body.match(/[\s\S]{1,3500}/g) || [r.body]; // chunk raw text first so no HTML entity is split across messages
  for (const chunk of chunks) {
    await tgCall(env, "sendMessage", { chat_id: cq.message.chat.id, reply_to_message_id: cq.message.message_id, text: `<pre>${esc(chunk)}</pre>`, parse_mode: "HTML" });
  }
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

// Release list cached in KV (GitHub's API is rate limited per IP when unauthenticated, and Worker IPs
// are shared across many Cloudflare customers); a stale copy is used if GitHub errors out.
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

const HELP_TEXT = [
  "<b>Spicy EX bot</b>",
  "",
  "<b>/release</b> — latest stable release (signed APK)",
  "<b>/beta</b> — newest build, stable or pre-release, whichever is newer",
  "<b>/ci</b> — latest CI build (release + debug APK)",
  "<b>/help</b> — this message",
  "",
  "Every release reply has a <b>Full notes here</b> button that prints the untouched release notes in this chat, and an <b>Open on GitHub</b> link.",
  "",
  "Write something like \"feature request: …\", \"could you add …\" or \"it'd be nice if …\" — or a bug report like \"X crashes when…\" / \"Y doesn't work\" — and I'll offer a button to file it as a GitHub issue, right here in Telegram. Nothing is filed without that confirmation, and only you or a group admin can confirm it.",
].join("\n");

async function command(env, cmd, msg) {
  const chatId = msg.chat.id, replyTo = msg.message_id;
  const say = (text) => tgCall(env, "sendMessage", { chat_id: chatId, text, reply_to_message_id: replyTo });
  try {
    if (cmd === "help") return await tgCall(env, "sendMessage", { chat_id: chatId, text: HELP_TEXT, parse_mode: "HTML", reply_to_message_id: replyTo, disable_web_page_preview: true });
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

// ---------- Feature-request / bug-report detection ----------

// English only, matching the group's English-only rule. Loose substrings on purpose - a false
// positive just shows an unused button, which is cheap, so flexible wins over strict. Checked in
// order: a bug report takes priority when a message happens to match both (e.g. "crashes, please
// add a fallback").
const BUG_RE = new RegExp(
  [
    "\\bbug\\b", "\\bcrash(ed|es|ing)?\\b", "doesn'?t work", "isn'?t working", "not working",
    "won'?t (open|load|start|launch)", "\\bbroken\\b", "force ?close", "\\banr\\b", "keeps? (crashing|freezing|stopping)",
    "stuck on", "\\bfreez(e|es|ing)\\b", "\\bregression\\b", "stack ?trace", "\\bexception\\b", "null ?pointer",
    "throws? an error", "error:", "doesn'?t load", "fails? to",
  ].join("|"),
  "i"
);
const FEATURE_RE = new RegExp(
  [
    "feature request", "feature idea", "\\bfr:", "#feature",
    "(could|can|would) you add", "please add", "add support for", "support for",
    "it('?d| would) be (nice|great|cool|awesome)", "i wish", "suggestion:", "idea:",
  ].join("|"),
  "i"
);

const normalize = (s) => s.toLowerCase().replace(/[^\p{L}\p{N}]+/gu, " ").trim();
async function sha1(s) {
  const buf = await crypto.subtle.digest("SHA-1", new TextEncoder().encode(s));
  return [...new Uint8Array(buf)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

async function isAdmin(env, chatId, userId) {
  const cacheKey = `admins:${chatId}`;
  let ids = JSON.parse((await env.CI.get(cacheKey)) || "null");
  if (!ids) {
    const res = await tgCall(env, "getChatAdministrators", { chat_id: chatId });
    ids = (res.result || []).map((m) => m.user.id);
    await env.CI.put(cacheKey, JSON.stringify(ids), { expirationTtl: 300 });
  }
  return ids.includes(userId);
}

// Asked for bug reports specifically (matches the group rule: version, device, steps to reproduce).
// The "Share for a bug report" button in Settings > About generates the first three lines, so most
// people only need to paste that and add steps - the version is never something they have to recall.
const BUG_DETAILS_ASK = [
  "Reply to this message with:",
  "1) Spicy EX version, Spotify version, Android version/device — Settings → About → <b>Share for a bug report</b> generates this for you",
  "2) What you were doing when it happened and the exact steps to reproduce it — be specific, this is the part people skip and it's the part that actually gets bugs fixed",
  "3) A log file, if you have one (attach it to your reply) — if you don't have one, the description in (2) matters even more",
  "",
  "Don't include tokens, passwords, or anything else private — this becomes a public issue.",
  "",
  "Then tap File (or tap File now without replying).",
].join("\n");

async function maybeOfferFeatureRequest(env, msg) {
  const text = msg.text || "";
  if (text.startsWith("/") || text.length < 12) return;
  const kind = BUG_RE.test(text) ? "bug" : FEATURE_RE.test(text) ? "feature" : null;
  if (!kind) return;
  if (env.DISCUSSION_CHAT_USERNAME && msg.chat.username !== env.DISCUSSION_CHAT_USERNAME) return;

  const id = crypto.randomUUID().slice(0, 8);
  const author = msg.from.username ? `@${msg.from.username}` : [msg.from.first_name, msg.from.last_name].filter(Boolean).join(" ");
  const [intro, label] = kind === "bug" ? ["🐛 Sounds like a bug report.", "🐛 File as GitHub issue"] : ["📝 Sounds like a feature request.", "📝 File as GitHub issue"];
  const ask = kind === "bug" ? `\n\n${BUG_DETAILS_ASK}` : "\n\nReply with any extra details first if you'd like, then tap File.";
  const sent = await tgCall(env, "sendMessage", {
    chat_id: msg.chat.id,
    reply_to_message_id: msg.message_id,
    text: `${intro}${ask}`,
    parse_mode: "HTML",
    disable_web_page_preview: true,
    reply_markup: { inline_keyboard: [[{ text: label, callback_data: `fr:${id}` }]] },
  });
  await env.CI.put(
    `fr:${id}`,
    JSON.stringify({ text, kind, authorId: msg.from.id, author, chatId: msg.chat.id, messageId: msg.message_id, promptId: sent.result?.message_id }),
    { expirationTtl: 1209600 } // 2 weeks to decide is plenty
  );
  if (sent.result?.message_id) await env.CI.put(`frprompt:${msg.chat.id}:${sent.result.message_id}`, id, { expirationTtl: 1209600 });
}

const LOG_FILE_RE = /\.(txt|log|json|xml|ya?ml)$/i;
const LOG_MAX_BYTES = 20000; // embedded verbatim in the issue body, so kept well under GitHub's limit
const LOG_EMBED_CHARS = 6000;

// A text file attached to a details reply: fetched and embedded verbatim if it looks like a plain-text
// log and is small enough; otherwise just noted (no public URL exists to link - Telegram's file URLs
// require the bot token and expire).
async function captureLogFile(env, document) {
  const looksLikeText = LOG_FILE_RE.test(document.file_name || "") || (document.mime_type || "").startsWith("text/");
  if (!looksLikeText || document.file_size > LOG_MAX_BYTES) {
    return { name: document.file_name || "file", size: document.file_size, note: "attached in Telegram, not embedded (not a small text file)" };
  }
  const f = await tgCall(env, "getFile", { file_id: document.file_id });
  const path = f.result?.file_path;
  if (!path) return { name: document.file_name || "file", size: document.file_size, note: "attached in Telegram, could not be fetched" };
  const content = await fetch(`https://api.telegram.org/file/bot${env.TELEGRAM_BOT_TOKEN}/${path}`).then((r) => r.text());
  return { name: document.file_name || "log.txt", content: content.length > LOG_EMBED_CHARS ? content.slice(0, LOG_EMBED_CHARS) + "\n…(truncated)" : content };
}

// A reply to the bot's "add details" prompt: append it to the filed report instead of re-running
// bug/feature detection on it (replies like "Android 14, crashes on launch" would otherwise re-trigger).
async function maybeCollectDetails(env, msg) {
  const replyId = msg.reply_to_message?.message_id;
  if (!replyId) return false;
  const id = await env.CI.get(`frprompt:${msg.chat.id}:${replyId}`);
  if (!id) return false;
  const entry = JSON.parse((await env.CI.get(`fr:${id}`)) || "null");
  if (!entry) return true;
  if (msg.from.id !== entry.authorId && !(await isAdmin(env, msg.chat.id, msg.from.id))) return true;

  const text = msg.text || msg.caption || "";
  if (text) entry.details = entry.details ? `${entry.details}\n${text}` : text;
  if (msg.document) entry.logFile = await captureLogFile(env, msg.document);
  if (!text && !msg.document) return true; // reply had neither text nor a file - nothing to add

  await env.CI.put(`fr:${id}`, JSON.stringify(entry), { expirationTtl: 1209600 });
  const ack = msg.document && entry.logFile?.content ? `Added, with \`${entry.logFile.name}\` attached. Tap File when ready.` : "Added to the report. Tap File when ready.";
  await tgCall(env, "sendMessage", { chat_id: msg.chat.id, reply_to_message_id: msg.message_id, text: ack, parse_mode: "Markdown" });
  return true;
}

async function fileIssue(env, cq) {
  const answer = (text, alert) => tgCall(env, "answerCallbackQuery", { callback_query_id: cq.id, text, show_alert: !!alert });
  const [, id] = cq.data.split(":");
  const entry = JSON.parse((await env.CI.get(`fr:${id}`)) || "null");
  if (!entry) return answer("This request has expired.", true);

  const allowed = cq.from.id === entry.authorId || (await isAdmin(env, entry.chatId, cq.from.id));
  if (!allowed) return answer("Only the original author or a group admin can file this.", true);

  const hash = await sha1(normalize(entry.text));
  const existing = await env.CI.get(`filed:${hash}`);
  if (existing) {
    await answer("Already filed.");
    return tgCall(env, "editMessageReplyMarkup", {
      chat_id: cq.message.chat.id, message_id: cq.message.message_id,
      reply_markup: { inline_keyboard: [[{ text: "Already filed — view issue", url: existing }]] },
    });
  }

  await answer("Filing…");
  const link = env.DISCUSSION_CHAT_USERNAME ? `https://t.me/${env.DISCUSSION_CHAT_USERNAME}/${entry.messageId}` : null;
  const isBug = entry.kind === "bug";
  const prefix = isBug ? "Bug: " : "";
  const titleBody = entry.text.length > 80 - prefix.length ? entry.text.slice(0, 77 - prefix.length) + "…" : entry.text;
  const title = prefix + titleBody;
  const bodyParts = [entry.text];
  if (entry.details) bodyParts.push("", "**Additional details:**", entry.details);
  if (entry.logFile?.content) bodyParts.push("", `**Attached log — \`${entry.logFile.name}\`:**`, "```", entry.logFile.content, "```");
  else if (entry.logFile) bodyParts.push("", `**Attached file:** \`${entry.logFile.name}\` (${entry.logFile.size} bytes) — ${entry.logFile.note}`);
  bodyParts.push("", `Filed from Telegram by ${entry.author}${link ? ` ([message](${link}))` : ""}.`);
  const body = bodyParts.join("\n");

  const res = await gh(env, "/issues", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ title, body, labels: [isBug ? "bug" : "enhancement", "from-telegram"] }),
  });

  if (!res.html_url) {
    console.error("issue create failed", JSON.stringify(res));
    return tgCall(env, "editMessageReplyMarkup", {
      chat_id: cq.message.chat.id, message_id: cq.message.message_id,
      reply_markup: { inline_keyboard: [[{ text: "⚠️ Filing failed — tap to retry", callback_data: cq.data }]] },
    });
  }
  await env.CI.put(`filed:${hash}`, res.html_url);
  return tgCall(env, "editMessageReplyMarkup", {
    chat_id: cq.message.chat.id, message_id: cq.message.message_id,
    reply_markup: { inline_keyboard: [[{ text: `✅ Filed as #${res.number}`, url: res.html_url }]] },
  });
}

// ---------- entrypoint ----------

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
    const update = await req.json();

    const cq = update.callback_query;
    if (cq) console.log("callback_query", cq.data, "from", cq.from?.id, cq.from?.username);
    if (cq?.data?.startsWith("fr:")) {
      ctx.waitUntil(fileIssue(env, cq).catch((e) => console.error("fileIssue error", e.message, e.stack)));
      return new Response("ok");
    }
    if (cq?.data?.startsWith("full:")) {
      ctx.waitUntil(sendFullNotes(env, cq, cq.data.slice("full:".length)).catch((e) => console.error("sendFullNotes error", e.message, e.stack)));
      return new Response("ok");
    }

    const msg = update.message;
    if (!msg || msg.from?.is_bot) return new Response("ok");
    const m = msg.text?.match(/^\/(release|beta|ci|help)(@\w+)?(\s|$)/i);
    if (m) {
      ctx.waitUntil(command(env, m[1].toLowerCase(), msg));
    } else if (msg.reply_to_message && (msg.text || msg.caption || msg.document)) {
      // A reply with a log file attached has no msg.text at all, so this can't be gated on msg.text.
      ctx.waitUntil(maybeCollectDetails(env, msg).then((handled) => handled || (msg.text && maybeOfferFeatureRequest(env, msg))));
    } else if (msg.text) {
      ctx.waitUntil(maybeOfferFeatureRequest(env, msg));
    }
    return new Response("ok");
  },
};
