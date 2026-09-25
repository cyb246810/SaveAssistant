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
  el.innerHTML = items.slice(0, 20).map((u) => `
    <div class="q-item">
      <div class="q-row">
        <span class="tag ${u.category}">${esc(u.category_label)}</span>
        <span class="q-name" title="${esc(u.name)}">${esc(u.name)}</span>
        <span class="q-size">${u.size ? fmtSize(u.size) : ""}</span>
      </div>
      <div class="q-status ${u.done ? "done" : ""}">${u.done ? (u.failed ? "失败" : "已完成") : "进行中…"}</div>
      ${u.done ? "" : '<div class="prog"><div class="prog-fill"></div></div>'}
    </div>`).join("");
}

function connectSSE() {
  const es = new EventSource("/api/events");
  es.onmessage = (e) => {
    let ev; try { ev = JSON.parse(e.data); } catch { return; }
    handleEvent(ev);
  };
}
function handleEvent(ev) {
  switch (ev.type) {
    case "upload_started":
      activeUploads.set(ev.id, { id: ev.id, name: ev.name, category: ev.category,
        category_label: ev.category_label, size: ev.size, done: false });
      renderQueue(); break;
    case "upload_completed": {
      const u = activeUploads.get(ev.id) || {};
      activeUploads.set(ev.id, { ...u, name: ev.record.name, category: ev.record.category,
        category_label: ev.record.category_label, size: ev.record.size, done: true });
      loadFiles(); loadStatus(); renderQueue(); break;
    }
    case "upload_failed": {
      const f = activeUploads.get(ev.id) || {};
      activeUploads.set(ev.id, { ...f, name: ev.name, done: true, failed: true });
      renderQueue(); break;
    }
    case "devices": devicesCache = ev.devices; renderDevices(); break;
    case "config":
      $("deviceName").textContent = ev.config.device_name;
      $("transferDir").textContent = ev.config.transfer_dir; break;
    case "toast": showToast(ev.msg, ev.level); break;
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
$("btnOpenFolder").onclick = () => api("/api/open-folder");

loadConfig();
loadStatus();
loadFiles();
connectSSE();
setInterval(() => { loadStatus(); loadFiles(); }, 5000);
