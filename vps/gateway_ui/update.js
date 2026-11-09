"use strict";

const updateMessages = {
  downloading: "正在下載並校驗新版。",
  restarting: "正在備份、試跑新版並重啟。服務回來後請重新登入。",
  updated: "新版已通過檢查並啟用。",
  rolled_back: "新版試行未通過，已回到上一版。",
  download_failed: "下載或校驗失敗。可以重新檢查後再試。",
  unavailable: "暫時無法讀取更新狀態。"
};
let updatePolling = false;
let updateUIBusy = false;
const pendingUpdatePreference = "modelatlas.pending-update";

function renderUpdate(view, checked = false) {
  $("update-current").textContent = view.current || view.commit?.slice(0, 8) || "—";
  $("update-available").hidden = !view.available;
  $("update-available").textContent = view.available ? "可更新 " + view.available : "";
  $("update-check").disabled = !view.enabled || !!view.phase;
  $("update-install").hidden = !view.available || !view.enabled;
  $("update-install").disabled = !!view.phase;
  $("update-install").dataset.tag = view.available || "";
  $("update-notes").hidden = !view.notes;
  if (view.notes) $("update-notes").href = view.notes;
  else $("update-notes").removeAttribute("href");
  $("update-status").textContent = updateMessages[view.phase || view.result] ||
    (!view.enabled ? "此部署使用外部更新工具。啟用持久磁碟更新後可在這裡安裝。" :
     view.available ? "安裝期間會暫停服務，並在啟用新版前檢查資料庫。" :
     checked ? "目前是此發布通道的最新版本。" : "可檢查這個發布通道的正式成品。");
  if (!view.phase) {
    try { sessionStorage.removeItem(pendingUpdatePreference); } catch {}
  }
}

async function pollUpdate() {
  if (updatePolling) return;
  updatePolling = true;
  try {
    for (let i = 0; i < 440 && $("settings-dialog").open; i++) {
      await new Promise(resolve => setTimeout(resolve, 1500));
      try {
        const view = await api("/update/status");
        renderUpdate(view);
        if (!view.phase) return;
      } catch (error) {
        if (error.name === "AbortError") return;
        $("update-status").textContent = updateMessages.restarting;
      }
    }
    if ($("settings-dialog").open) $("update-status").textContent = "服務仍在重新連線，稍後可重新開啟設定查看。";
  } finally {
    updatePolling = false;
  }
}

async function loadUpdateStatus(path, payload) {
  if (updateUIBusy) return;
  updateUIBusy = true;
  $("update-check").disabled = true;
  $("update-install").disabled = true;
  try {
    const view = await api(path, payload);
    renderUpdate(view, !!payload);
    if (view.phase) void pollUpdate();
  } catch (error) {
    if (error.name !== "AbortError") {
      $("update-status").textContent = "暫時無法檢查更新，請稍後再試。";
      $("update-check").disabled = false;
    }
  } finally {
    updateUIBusy = false;
  }
}

$("settings-open").addEventListener("click", () => void loadUpdateStatus("/update/status"));
$("update-check").addEventListener("click", () => void loadUpdateStatus("/update/check", {}));
$("update-install").addEventListener("click", async event => {
  if (updateUIBusy) return;
  updateUIBusy = true;
  const install = event.currentTarget;
  $("update-check").disabled = install.disabled = true;
  try {
    const result = await api("/update/install", {tag: install.dataset.tag});
    try { sessionStorage.setItem(pendingUpdatePreference, "1"); } catch {}
    $("update-status").textContent = updateMessages[result.phase];
    void pollUpdate();
  } catch (error) {
    if (error.name !== "AbortError") {
      $("update-status").textContent = "更新尚未開始，請重新檢查版本。";
      $("update-check").disabled = false;
    }
  } finally {
    updateUIBusy = false;
  }
});
let resumeUpdate = false;
try { resumeUpdate = sessionStorage.getItem(pendingUpdatePreference) === "1"; } catch {}
if (resumeUpdate || new URLSearchParams(location.search).get("settings") === "updates") {
  $("settings-open").click();
  $("update-settings").scrollIntoView({block: "nearest"});
}
