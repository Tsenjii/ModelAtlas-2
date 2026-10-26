import AtlasCore
import SwiftUI

@MainActor struct ResearchRunView: View {
    @ObservedObject var store: AtlasStore
    let run: JSONValue
    var body: some View {
        if let api = store.api { ResearchRunLoader(api: api, run: run).id(run["id"].string) }
        else { Text("引擎尚未連線，請稍後重新開啟紀錄。").padding(24) }
    }
}

@MainActor private struct ResearchRunLoader: View {
    @StateObject var model: ResearchRunController
    init(api: AtlasClient, run: JSONValue) { _model = StateObject(wrappedValue: ResearchRunController(api: api, run: run)) }
    var body: some View { ResearchRunPanel(model: model) }
}

@MainActor struct ResearchRunPanel: View {
    @ObservedObject var model: ResearchRunController
    @Environment(\.dismiss) private var dismiss
    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            HStack { Text("研究紀錄").font(.title2.weight(.semibold)); Spacer(); Button("關閉") { dismiss() }.keyboardShortcut(.cancelAction) }
            HStack {
                SmallBadge(text: ResearchRunState.title(model.run["status"].string), color: atlasAccent)
                Text(model.run["used"].display + " / " + model.run["budget"].display + " HTTP").monospacedDigit()
                Spacer()
                Button("更新執行紀錄") { Task { await model.reload() } }.disabled(model.busy || model.reading).background(AtlasLoadedMarker(name: "research-run-refresh-target"))
                if model.busy || model.reading { ProgressView().controlSize(.small) }
            }
            if let problem = model.problem { Text(problem).font(.callout).foregroundStyle(.red).textSelection(.enabled) }
            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    ForEach(model.tasks, id: \.self) { task in taskCard(task) }
                    if model.canResume { recovery }
                    AtlasCard {
                        DisclosureGroup("本輪結果與完整紀錄") { Text(model.id).font(.caption).textSelection(.enabled); JSONTree(value: model.run["summary"]) }
                    }
                }
            }
            HStack {
                if model.active { Button("暫停研究") { Task { await model.control("PAUSE") } }.background(AtlasLoadedMarker(name: "research-run-pause-target")) }
                if ResearchRunState.controllable(model.run) { Button("取消研究") { Task { await model.control("CANCEL") } }.background(AtlasLoadedMarker(name: "research-run-cancel-target")) }
            }.disabled(model.locked)
        }
        .padding(24).frame(width: 780, height: 710).background(atlasCanvas)
        .background(Group {
            if model.loaded { AtlasLoadedMarker(name: "research-run-loaded"); AtlasLoadedMarker(name: "research-run-status-" + model.run["status"].string) }
            if model.loaded && !model.locked { AtlasLoadedMarker(name: "research-run-ready") }
            if model.needsReload { AtlasLoadedMarker(name: "research-run-needs-reload") }
        })
        .task {
            await model.reload()
            while !Task.isCancelled {
                do { try await Task.sleep(nanoseconds: 2_000_000_000) } catch { return }
                if model.shouldPoll { await model.reload() }
            }
        }
    }
    private func taskCard(_ task: JSONValue) -> some View {
        AtlasCard {
            HStack { Text(task["kind"].string == "AA" ? "AA 榜單更新" : task["kind"].string).font(.headline); Spacer(); SmallBadge(text: ResearchRunState.title(task["status"].string)) }
            if task["kind"].string == "AA", task["result"]["aa_progress"] != .null { AAResearchProgress(progress: task["result"]["aa_progress"], run: model.run, task: task, ranking: model.ranking) }
            if !task["result"]["reason"].string.isEmpty { Text(task["result"]["reason"].string).font(.callout).foregroundStyle(.secondary).textSelection(.enabled) }
            DisclosureGroup("計畫與結果（\(task["attempts"].display) 次嘗試）") { JSONTree(value: task["payload"]); JSONTree(value: task["result"]) }
        }
    }
    private var recovery: some View {
        AtlasCard {
            Text("恢復與追加預算").font(.headline)
            HStack { FormField(title: "追加 HTTP 預算", text: $model.additional); FormField(title: "追加推論預算", text: $model.probes) }
            Toggle("重試失敗項目", isOn: $model.retryFailed)
            Button("確認恢復研究") { Task { await model.control("RESUME") } }.buttonStyle(AtlasActionStyle(prominent: true)).background(AtlasLoadedMarker(name: "research-run-resume-target"))
        }.disabled(model.locked)
    }
}

private struct AAResearchProgress: View {
    let progress: JSONValue, run: JSONValue, task: JSONValue, ranking: JSONValue
    private var complete: Bool { progress["complete"].boolean && task["status"].string == "SUCCESS" }
    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("已保存 " + progress["completed_pages"].display + " / " + progress["total_pages"].display + " 頁 · " + progress["model_count"].display + " 個已評分模型").font(.callout.weight(.medium))
            if complete { Label("完整榜單已驗證並更新", systemImage: "checkmark.circle").foregroundStyle(.secondary) }
            else if run["status"].string == "PAUSED" { Text("研究已暫停；頁面與恢復紀錄都已保存。") }
            else if !progress["stopped_reason"].string.isEmpty { Text("本次更新已停止，請檢查紀錄後開始新一輪。") }
            else if let at = progress["next_attempt_at"].numeric, at > 0 { Text("第 " + progress["next_page"].display + " 頁等待恢復 · 不早於 " + Date(timeIntervalSince1970: at / 1000).formatted()).fixedSize(horizontal: false, vertical: true) }
            if !complete { Text("完成全部頁面後才更新 Top-4；已保存頁面不會重抓。").font(.caption).foregroundStyle(.secondary) }
            if !complete, !ranking["snapshot"]["models"].array.isEmpty {
                DisclosureGroup("上一份榜單") {
                    ForEach(ranking["snapshot"]["models"].array.prefix(4), id: \.self) { row in Text(row["rank"].display + ". " + row["name"].string) }
                }.font(.caption)
            }
            if !progress["history"].array.isEmpty {
                DisclosureGroup("分頁恢復紀錄") {
                    ForEach(Array(progress["history"].array.enumerated()), id: \.offset) { _, event in
                        Text("第 " + event["page"].display + " 頁 · HTTP " + event["http_status"].display).font(.caption)
                    }
                }
            }
        }
    }
}
