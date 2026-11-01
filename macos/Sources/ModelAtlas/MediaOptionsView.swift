import AtlasCore
import SwiftUI

struct MediaOptionsView: View {
    @Binding var draft: MediaDraft
    let operation: String
    @State private var expanded: Bool
    init(draft: Binding<MediaDraft>, operation: String, expanded: Bool = false) { _draft = draft; self.operation = operation; _expanded = State(initialValue: expanded) }
    var body: some View {
        DisclosureGroup(operationTitle(operation) + "選項", isExpanded: $expanded) {
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    if operation == "IMAGES_GENERATION" {
                        Picker("圖片尺寸", selection: $draft.size) { Text("供應商預設").tag(""); ForEach(["256x256", "512x512", "1024x1024", "1024x1536", "1536x1024", "auto"], id: \.self) { Text($0).tag($0) } }
                    }
                    if operation == "AUDIO" { Text("加入 WAV 或 MP3 後即可轉寫；下方文字可留空。每份檔案最多 4 MiB。").foregroundStyle(.secondary) }
                    if operation == "EMBEDDINGS" { FormField(title: "向量維度（選填，1–8192）", text: $draft.dimensions); Text("下方每行一筆輸入，最多 32 筆。").font(.caption).foregroundStyle(.secondary) }
                    if operation == "RERANK" {
                        Text("待排序文件（每行一筆，最多 32 筆）").font(.callout)
                        TextEditor(text: $draft.documents).frame(height: 90).accessibilityLabel("待排序文件")
                        FormField(title: "回傳前幾筆（選填）", text: $draft.topN)
                    }
                    if ["AUDIO_SPEECH", "GEMINI_SPEECH"].contains(operation) { speech }
                }.padding(.top, 10).padding(.trailing, 6)
            }.frame(maxHeight: 235)
        }.font(.callout).accessibilityIdentifier("media-options")
        .task(id: operation) {
            if operation == "GEMINI_SPEECH" { if !["wav", "pcm"].contains(draft.format) { draft.format = "wav" }; if draft.voice == "alloy" { draft.voice = "Kore" } }
            if operation == "AUDIO_SPEECH", draft.voice == "Kore" { draft.voice = "alloy" }
        }
        .onChange(of: draft.dialect) { if $0 == "LEGACY" { draft.dual = false } }
    }
    private var speech: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                if operation != "GEMINI_SPEECH" || !draft.dual { FormField(title: "聲音", text: $draft.voice) }
                Picker("音訊格式", selection: $draft.format) { ForEach(operation == "GEMINI_SPEECH" ? ["wav", "pcm"] : ["mp3", "wav", "opus", "flac", "aac", "pcm"], id: \.self) { Text($0.uppercased()).tag($0) } }.frame(width: 180)
            }
            if operation == "AUDIO_SPEECH" { HStack { Text("語速"); Slider(value: $draft.speed, in: 0.25...4, step: 0.25); Text(String(format: "%.2f×", draft.speed)).monospacedDigit().frame(width: 55) } }
            if operation == "GEMINI_SPEECH" {
                Picker("語音協定", selection: $draft.dialect) { Text("目前格式").tag("CURRENT"); Text("舊版格式").tag("LEGACY") }
                Toggle("雙人對話", isOn: $draft.dual).disabled(draft.dialect == "LEGACY")
                if draft.dual { dualSpeech }
            }
            if !draft.dual || operation != "GEMINI_SPEECH" { FormField(title: "說話方式（選填）", text: $draft.instructions) }
        }
    }
    private var dualSpeech: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack { FormField(title: "說話者一", text: $draft.firstName); FormField(title: "聲音", text: $draft.firstVoice) }
            HStack { FormField(title: "說話者二", text: $draft.secondName); FormField(title: "聲音", text: $draft.secondVoice) }
            Text("依序加入對話段落；會使用這些文字生成語音。").font(.caption).foregroundStyle(.secondary)
            ForEach($draft.turns) { $line in
                VStack(alignment: .leading, spacing: 7) {
                    HStack {
                        Picker("說話者", selection: Binding(get: { line.slot ?? (line.speaker == draft.secondName ? 1 : 0) }, set: { line.slot = $0 })) { Text(draft.firstName).tag(0); Text(draft.secondName).tag(1) }
                        FormField(title: "語氣（選填）", text: $line.style)
                        Button { draft.turns.removeAll { $0.id == line.id } } label: { Image(systemName: "minus.circle") }.help("移除這一段")
                    }
                    TextField("這一段要說的文字", text: $line.text).textFieldStyle(.roundedBorder)
                }.padding(10).background(Color.primary.opacity(0.035)).cornerRadius(8)
            }
            Button { draft.turns.append(SpeechLine(slot: draft.turns.last?.slot == 0 ? 1 : 0)) } label: { Label("加入一段", systemImage: "plus") }.disabled(draft.turns.count >= 64)
        }
    }
}
