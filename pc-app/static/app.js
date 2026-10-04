// 保存助手 · 电脑端传输工作台 前端逻辑
const $ = (id) => document.getElementById(id);
const activeUploads = new Map();
let devicesCache = [];
let filesCache = [];

function esc(s) {
  return String(s == null ? "" : s)
    .replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;").replace(/'/g, "&#39;");
}
function fmtSize(n) {
  n = Number(n) || 0;
  if (n < 1024) return n + " B";
  if (n < 1048576) return (n / 1024).toFixed(1) + " KB";
  if (n < 1073741824) return (n / 1048576).toFixed(1) + " MB";
  return (n / 1073741824).toFixed(2) + " GB";
}
async function api(path, opts) {
  try {
    const r = await fetch(path, opts);
    if (!r.ok) return null;
    const ct = r.headers.get("content-type") || "";
    return ct.indexOf("json") >= 0 ? await r.json() : await r.text();
  } catch (e) { return null; }
}

async function loadConfig() {
  const c = await api("/api/config");
  if (!c) return;
  $("deviceName").textContent = c.device_name;
  $("localIps").textContent = (c.local_ips || []).join("   ");
  $("port").textContent = c.port;
  $("transferDir").textContent = c.transfer_dir;
  $("verBadge").textContent = "v" + c.version;
}
async function loadStatus() {
  const s = await api("/api/status");
  if (!s) return;
  $("statToday").textContent = s.today_count;
  $("statCount").textContent = s.file_count;
  $("statSize").textContent = fmtSize(s.file_total_size);
  const free = s.free_space;
  if (free && free.total) {
    const usedPct = (free.used / free.total) * 100;
    $("spaceFill").style.width = usedPct.toFixed(1) + "%";
    $("freeSpace").textContent = fmtSize(free.free) + " 可用 / " + fmtSize(free.total);
  }
  devicesCache = s.devices || [];
  renderDevices();
}
async function loadFiles() {
  const r = await api("/api/files");
  if (!r) return;
  filesCache = r.files || [];
  renderFiles();
}

function renderDevices() {
  const el = $("deviceList");
  if (!devicesCache.length) {
    el.innerHTML = '<div class="empty">等待手机端连接…<br><small>手机端「电脑传输」会在此出现</small></div>';
    return;
  }
  el.innerHTML = devicesCache.map((d) => `
    <div class="device-item">
      <div class="device-ico">📱</div>
      <div class="device-meta">
        <div class="device-name">${esc(d.name)}</div>
        <div class="device-sub">${esc(d.model || "")} · ${esc(d.ip || "")}</div>
      </div>
      <div class="device-online">${d.online ? "在线" : "离线"}</div>
    </div>`).join("");
}
function renderFiles() {
  const el = $("fileGrid");
  if (!filesCache.length) {
    el.innerHTML = '<div class="empty small">还没有收到文件</div>';
    return;
  }
  el.innerHTML = filesCache.slice(0, 60).map((f) => `
    <div class="file-card">
      <div class="file-top">
        <span class="tag ${f.category}">${esc(f.category_label)}</span>
        <span class="q-size">${fmtSize(f.size)}</span>
      </div>
      <div class="file-name" title="${esc(f.name)}">${esc(f.name)}</div>
      <div class="file-sub">${esc(f.time)} · ${esc(f.device || "—")}</div>
      <button class="file-open" data-path="${esc(f.path)}">打开所在文件夹</button>
    </div>`).join("");
  el.querySelectorAll(".file-open").forEach((b) => {
    b.onclick = () => openFile(b.dataset.path);
  });
}
function renderQueue() {
  const el = $("queue");
  const items = [...activeUploads.values()];
  if (!items.length) { el.innerHTML = '<div class="empty small">暂无传输</div>'; return; }
  el.innerHTML = items.slice(-20).reverse().map((u) => {
    let status = "进行中…", cls = "";
    if (u.done) {
      cls = u.failed ? "failed" : "done";
      status = u.failed ? "失败" : "已完成";
    } else if (u.stale) {
      status = "状态未知（连接已中断）";
    }
    const err = u.failed && u.error
      ? `<div class="q-err" title="${esc(u.error)}">${esc(u.error)}</div>` : "";
    return `
    <div class="q-item">
      <div class="q-row">
        <span class="tag ${u.category}">${esc(u.category_label)}</span>
        <span class="q-name" title="${esc(u.name)}">${esc(u.name)}</span>
        <span class="q-size">${u.size ? fmtSize(u.size) : ""}</span>
      </div>
      <div class="q-status ${cls}">${status}</div>
      ${err}
      ${u.done ? "" : '<div class="prog"><div class="prog-fill"></div></div>'}
    </div>`;
  }).join("");
}

function connectSSE() {
  const es = new EventSource("/api/events");
  // 服务端不会重放历史事件，所以连接（重新）建立时，页面里残留的「进行中…」
  // 一定是上一次连接遗留下来的僵尸条目 —— 必须清掉，
  // 否则关掉工作台再打开、或者服务重启之后，那条记录会一直卡在「进行中…」。
  es.onopen = () => {
    if (activeUploads.size) {
      const stale = activeUploads.size;
      activeUploads.clear();
      renderQueue();
      showToast(`已重置 ${stale} 条过期传输状态`, "info");
    }
  };
  es.onmessage = (e) => {
    let ev; try { ev = JSON.parse(e.data); } catch { return; }
    handleEvent(ev);
  };
}
function handleEvent(ev) {
  switch (ev.type) {
    case "upload_started":
      activeUploads.set(ev.id, { id: ev.id, name: ev.name, category: ev.category,
        category_label: ev.category_label, size: ev.size, done: false,
        t: Date.now() });
      renderQueue(); break;
    case "upload_completed": {
      // 没有 id 就无从匹配「进行中」那一条。以前这里会 set(undefined, ...)，
      // 凭空多出一条键为 undefined 的"已完成"，而原记录永远停在"正在接收"。
      // 服务端的 emit 已经补上 id；这里再兜一层，免得哪天又漏了再演一次。
      if (!ev.id) break;
      const u = activeUploads.get(ev.id) || {};
      activeUploads.set(ev.id, { ...u, name: ev.record.name, category: ev.record.category,
        category_label: ev.record.category_label, size: ev.record.size, done: true });
      loadFiles(); loadStatus(); renderQueue(); break;
    }
    case "upload_failed": {
      if (!ev.id) break;
      const f = activeUploads.get(ev.id) || {};
      activeUploads.set(ev.id, { ...f, name: ev.name, done: true, failed: true,
        error: ev.error || "未知原因" });
      renderQueue(); break;
    }
    case "devices": devicesCache = ev.devices; renderDevices(); break;
    // 服务端重扫磁盘后发现记录变了（多半是文件被删/移走了）。
    // 两个页面看的是同一份记录，所以一起更新。
    case "files":
      filesCache = ev.files || [];
      downloadsCache = filesCache;
      renderFiles(); renderDownloads(); loadStatus(); break;
    case "config":
      $("deviceName").textContent = ev.config.device_name;
      $("transferDir").textContent = ev.config.transfer_dir; break;
    case "toast": showToast(ev.msg, ev.level); break;
    case "save_progress":
    case "save_completed":
    case "save_failed": handleSaveEvent(ev); break;
  }
}

let toastTimer = null;
function showToast(msg, level) {
  const t = $("toast");
  t.textContent = msg;
  t.className = "toast show " + (level || "info");
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => { t.className = "toast"; }, 2600);
}

function openFile(path) { api("/api/open-file?path=" + encodeURIComponent(path)); }

/**
 * 规整用户填的目录：剥掉首尾成对引号与结尾分隔符。
 * 资源管理器「复制文件地址」复制出来的路径自带双引号，直接粘进来会让目录不可用。
 * 服务端也会做同样处理，这里做一遍是为了让用户当场看到「实际会用哪个路径」。
 */
function cleanDir(p) {
  let s = String(p == null ? "" : p).trim();
  const pairs = [['"', '"'], ["'", "'"], ["\u201c", "\u201d"], ["\u2018", "\u2019"],
                 ["\u300c", "\u300d"], ["\u300e", "\u300f"]];
  for (let k = 0; k < 3; k++) {
    let hit = false;
    for (const [a, b] of pairs) {
      if (s.length >= 2 && s.startsWith(a) && s.endsWith(b)) {
        s = s.slice(a.length, s.length - b.length).trim();
        hit = true;
        break;
      }
    }
    if (!hit) break;
  }
  // 去掉结尾多余的分隔符，但别把 "D:\\" 这种根路径削成 "D:"（与服务端逻辑保持一致）
  while (s.length > 3 && /[\\/]$/.test(s)) s = s.slice(0, -1);
  return s;
}

$("btnChangeDir").onclick = async () => {
  const raw = prompt("设置接收目录（绝对路径）：", $("transferDir").textContent);
  if (!raw) return;
  const cleaned = cleanDir(raw);
  if (cleaned !== raw.trim()) {
    showToast("已自动去掉路径里的引号", "info");
  }
  let resp, data;
  try {
    resp = await fetch("/api/config", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ transfer_dir: cleaned }),
    });
    data = await resp.json().catch(() => null);
  } catch (e) {
    showToast("保存失败：网络错误", "error");
    return;
  }
  if (!resp.ok || !data || data.ok === false) {
    showToast("目录不可用：" + ((data && data.error) || ("HTTP " + resp.status)), "error");
    return;
  }
  showToast("接收目录已改为：" + data.transfer_dir, "success");
  loadConfig(); loadStatus(); loadFiles();
};
/**
 * 把长时间没有终态事件的「进行中」标成「状态未知」。
 * 手机端掉线/进程被杀时不会发出任何终态事件，那条记录会一直卡在「进行中…」，
 * 让人误以为还在传。
 */
function markStaleUploads() {
  const now = Date.now();
  let changed = false;
  activeUploads.forEach((u) => {
    if (!u.done && !u.stale && u.t && now - u.t > 300000) {
      u.stale = true;
      changed = true;
    }
  });
  if (changed) renderQueue();
}

$("btnOpenFolder").onclick = () => api("/api/open-folder");

// ---------------------------------------------------------------------------
// 下载页：解析 → 勾选 → 保存
// ---------------------------------------------------------------------------
let parseJob = null;                 // 当前解析结果（Job）
const selectedIds = new Set();       // 勾选的条目 id
const activeSaves = new Map();       // 保存任务
let downloadsCache = [];
let ffmpegInfo = null;

function switchMode(mode) {
  const isTransfer = mode === "transfer";
  $("tabTransfer").classList.toggle("active", isTransfer);
  $("tabDownload").classList.toggle("active", !isTransfer);
  $("pageTransfer").style.display = isTransfer ? "grid" : "none";
  $("pageDownload").style.display = isTransfer ? "none" : "grid";
  $("brandSub").textContent = isTransfer ? "传输工作台" : "下载工作台";
  if (!isTransfer) loadDownloads();
}

$("tabTransfer").onclick = () => switchMode("transfer");
$("tabDownload").onclick = () => switchMode("download");

$("btnPasteUrl").onclick = async () => {
  try {
    const text = await navigator.clipboard.readText();
    if (text) $("dlUrl").value = text.trim();
  } catch (e) {
    showToast("读取剪贴板失败，请手动粘贴", "error");
  }
};

// --- 解析 -----------------------------------------------------------------
$("btnParse").onclick = async () => {
  const raw = $("dlUrl").value.trim();
  if (!raw) { showToast("请先粘贴链接", "info"); return; }
  const btn = $("btnParse");
  btn.disabled = true;
  btn.textContent = "解析中…";
  $("dlPlatformNote").textContent = "正在解析，抖音/视频号都要走好几步网络请求…";
  const resp = await fetch("/api/parse", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ url: raw }),
  });
  const data = await resp.json().catch(() => null);
  btn.disabled = false;
  btn.textContent = "解析";
  if (!resp.ok || !data || data.ok === false) {
    $("dlPlatformNote").textContent = "";
    showToast((data && data.error) || "解析失败", "error");
    return;
  }
  parseJob = data;
  selectedIds.clear();
  data.items.forEach((it) => selectedIds.add(it.id));
  renderResult();
  $("dlPlatformNote").textContent =
    "解析完成，共 " + data.items.length + " 项。核对后点「保存选中」。";
};

function platformName(p) {
  return { douyin: "抖音", channels: "微信视频号", direct: "直链" }[p] || p;
}

function renderResult() {
  if (!parseJob) { $("resultCard").style.display = "none"; return; }
  $("resultCard").style.display = "flex";
  const title = parseJob.title || "（没有标题）";
  $("resultTitle").textContent =
    "【" + platformName(parseJob.platform) + "】" + title +
    (parseJob.author ? " · " + parseJob.author : "");

  const el = $("itemList");
  el.innerHTML = parseJob.items.map((it) => {
    const checked = selectedIds.has(it.id) ? "checked" : "";
    const flag = it.has_stickers ? '<span class="item-flag">含贴纸</span>' : "";
    let thumb;
    if (it.kind === "image" || it.kind === "cover") {
      thumb = '<img class="item-thumb" alt="" loading="lazy" src="/api/thumb?url=' +
              encodeURIComponent(it.preview_url) + '" onerror="thumbFail(this)" />';
    } else {
      const glyph = it.kind === "video" ? "▶" : (it.kind === "music" ? "♪" : "●");
      thumb = '<div class="item-thumb placeholder">' + glyph + "</div>";
    }
    const sub = it.url ? it.url.slice(0, 96) : "";
    return '<label class="item-row">' +
      '<input type="checkbox" data-id="' + esc(it.id) + '" ' + checked + " />" +
      thumb +
      '<div class="item-meta">' +
        '<div class="item-label">' + esc(it.label) + "</div>" +
        '<div class="item-sub" title="' + esc(it.url || "") + '">' + esc(sub) + "</div>" +
      "</div>" + flag + "</label>";
  }).join("");

  el.querySelectorAll("input[type=checkbox]").forEach((cb) => {
    cb.onchange = () => {
      if (cb.checked) selectedIds.add(cb.dataset.id);
      else selectedIds.delete(cb.dataset.id);
    };
  });
}

// 缩略图可能因为 CDN 防盗链 403，退化成占位符而不是留一个碎图标
function thumbFail(img) {
  const d = document.createElement("div");
  d.className = "item-thumb placeholder";
  d.textContent = "图";
  img.replaceWith(d);
}

$("btnSelAll").onclick = () => {
  if (!parseJob) return;
  parseJob.items.forEach((it) => selectedIds.add(it.id));
  renderResult();
};
$("btnSelNone").onclick = () => {
  selectedIds.clear();
  renderResult();
};

// --- 保存 -----------------------------------------------------------------
$("btnSave").onclick = async () => {
  if (!parseJob) return;
  if (!selectedIds.size) { showToast("请至少勾选一项", "info"); return; }
  const options = {
    apply_cover: $("optCover").checked,
    burn_title: $("optTitle").checked,
    composite_stickers: $("optSticker").checked,
    stitch_images: $("optStitch").checked,
    title_text: parseJob.title,
  };
  $("btnSave").disabled = true;
  const resp = await fetch("/api/save", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ job_id: parseJob.job_id, items: [...selectedIds], options }),
  });
  const data = await resp.json().catch(() => null);
  $("btnSave").disabled = false;
  if (!resp.ok || !data || data.ok === false) {
    showToast((data && data.error) || "提交保存失败", "error");
    return;
  }
  showToast("已开始保存 " + selectedIds.size + " 项", "success");
};

function renderSaveQueue() {
  const el = $("dlQueue");
  const items = [...activeSaves.values()];
  if (!items.length) { el.innerHTML = '<div class="empty small">暂无任务</div>'; return; }
  el.innerHTML = items.slice(-20).reverse().map((u) => {
    let status = u.label ? u.label + "…" : "准备中…";
    let cls = "";
    if (u.done) {
      cls = u.failed ? "failed" : "done";
      status = u.failed
        ? "完成 " + (u.saved || 0) + " 项，失败 " + u.failed + " 项"
        : "已保存 " + (u.saved || 0) + " 项";
    } else if (u.total) {
      status = (u.label || "") + " (" + u.done + "/" + u.total + ")";
    }
    const err = u.error
      ? '<div class="q-err" title="' + esc(u.error) + '">' + esc(u.error) + "</div>" : "";
    const bar = u.done ? "" : '<div class="prog"><div class="prog-fill"></div></div>';
    return '<div class="q-item"><div class="q-status ' + cls + '">' + esc(status) +
           "</div>" + err + bar + "</div>";
  }).join("");
}

function renderDownloads() {
  const el = $("dlGrid");
  if (!downloadsCache.length) {
    el.innerHTML = '<div class="empty small">还没有文件</div>';
    return;
  }
  el.innerHTML = downloadsCache.slice(0, 60).map((f) =>
    '<div class="file-card">' +
      '<div class="file-top">' +
        '<span class="tag ' + f.category + '">' + esc(f.category_label) + "</span>" +
        '<span class="q-size">' + fmtSize(f.size) + "</span>" +
      "</div>" +
      '<div class="file-name" title="' + esc(f.name) + '">' + esc(f.name) + "</div>" +
      '<div class="file-sub">' + esc(f.time) + "</div>" +
      '<button class="file-open" data-path="' + esc(f.path) + '">打开所在文件夹</button>' +
    "</div>").join("");
  el.querySelectorAll(".file-open").forEach((b) => {
    b.onclick = () => openFile(b.dataset.path);
  });
}

async function loadDownloads() {
  const r = await api("/api/downloads");
  if (!r) return;
  downloadsCache = r.files || [];
  renderDownloads();
}

function handleSaveEvent(ev) {
  if (ev.type === "save_progress") {
    const u = activeSaves.get(ev.id) || {};
    activeSaves.set(ev.id, { ...u, done: ev.done, total: ev.total, label: ev.label });
    renderSaveQueue();
  } else if (ev.type === "save_completed") {
    const u = activeSaves.get(ev.id) || {};
    activeSaves.set(ev.id, { ...u, done: true, saved: ev.saved, failed: ev.failed });
    renderSaveQueue();
    loadDownloads(); loadFiles(); loadStatus();
    if (ev.failed) showToast("保存完成，" + ev.saved + " 项成功、" + ev.failed + " 项失败", "error");
    else showToast("已保存 " + ev.saved + " 项到接收目录", "success");
  } else if (ev.type === "save_failed") {
    const u = activeSaves.get(ev.id) || {};
    activeSaves.set(ev.id, { ...u, done: true, failed: 1, error: ev.error });
    renderSaveQueue();
    showToast(ev.error || "保存失败", "error");
  }
}

// --- 视频号登录态 ----------------------------------------------------------
function renderCredential(cred) {
  const el = $("credStatus");
  if (!cred) { el.textContent = "读取中…"; return; }
  if (!cred.configured) {
    el.style.color = "";
    el.textContent = "未配置。视频号视频需要登录态，抖音不需要。";
    return;
  }
  el.textContent = cred.usable
    ? "已配置，校验通过。"
    : "已配置，但校验没通过：" + (cred.diagnose || "原因未知");
  el.style.color = cred.usable ? "#34D399" : "#F0A0AC";
}

$("btnSaveCred").onclick = async () => {
  const raw = $("credRaw").value.trim();
  if (!raw) { showToast("请先粘贴请求内容", "info"); return; }
  $("btnSaveCred").disabled = true;
  $("credStatus").textContent = "正在校验…";
  $("credStatus").style.color = "";
  const resp = await fetch("/api/credential", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ raw }),
  });
  const data = await resp.json().catch(() => null);
  $("btnSaveCred").disabled = false;
  if (!resp.ok || !data || data.ok === false) {
    $("credStatus").textContent = (data && data.error) || "保存失败";
    $("credStatus").style.color = "#F0A0AC";
    return;
  }
  $("credRaw").value = "";
  renderCredential(data);
  showToast(data.usable ? "登录态可用" : "已保存，但校验未通过",
            data.usable ? "success" : "error");
};

function renderFfmpeg(info) {
  const warn = $("ffmpegWarn");
  if (!info || info.available) {
    if (info && !info.features.burn_title) {
      warn.style.display = "block";
      warn.textContent = "本机有 ffmpeg 但没找到中文字体，烧标题不可用。";
    } else {
      warn.style.display = "none";
    }
    return;
  }
  warn.style.display = "block";
  warn.textContent = "本机没装 ffmpeg：封面、烧标题、贴纸、拼图这四项都不可用，"
                   + "仍可正常保存原片。";
}

async function loadDownloadConfig() {
  const c = await api("/api/config");
  if (!c) return;
  ffmpegInfo = c.ffmpeg;
  renderFfmpeg(c.ffmpeg);
  renderCredential(c.credential);
}

loadConfig();
loadStatus();
loadFiles();
connectSSE();
loadDownloadConfig();
// 记录变化现在由服务端通过 files 事件推送了，这里再轮询一次是**兜底**：
// SSE 万一断了（代理、休眠唤醒），列表不至于一直停在旧数据上。别把 loadFiles 去掉。
setInterval(() => { loadStatus(); loadFiles(); markStaleUploads(); }, 5000);
