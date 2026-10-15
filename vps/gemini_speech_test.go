package main

import (
	"bytes"
	"encoding/base64"
	"encoding/binary"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
)

func speechFixture(audio []byte, mime string) map[string]any {
	return map[string]any{"modelVersion": "fixture-voice", "candidates": []any{map[string]any{"finishReason": "STOP", "content": map[string]any{"parts": []any{map[string]any{"inlineData": map[string]any{"mimeType": mime, "data": base64.StdEncoding.EncodeToString(audio)}}}}}}, "usageMetadata": map[string]int{"promptTokenCount": 0, "candidatesTokenCount": 0}}
}
func TestGeminiSpeechActualWireAndAudio(t *testing.T) {
	requests := []map[string]any{}
	pcm := make([]byte, 32000)
	for i := 0; i < len(pcm); i += 2 {
		binary.LittleEndian.PutUint16(pcm[i:i+2], uint16(i%500))
	}
	up := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("Authorization") != "Bearer top-secret-example-key" || r.URL.Path != "/v1/models/m:generateContent" || r.URL.Query().Get("alt") != "" {
			t.Error("speech scope/path/auth changed")
		}
		var body map[string]any
		if json.NewDecoder(r.Body).Decode(&body) != nil {
			t.Error("invalid body")
		}
		requests = append(requests, body)
		json.NewEncoder(w).Encode(speechFixture(pcm, "audio/L16;codec=pcm;rate=16000"))
	}))
	defer up.Close()
	s := testServer(t)
	s.Client = up.Client()
	scope := seed(t, s, up.URL+"/v1")
	scope.Endpoint = "GEMINI_SPEECH"
	requireCode(t, req(t, s, "POST", "/api/v1/my-models", scope), 201)
	confirmCaps(t, s, scope, []string{"AUDIO_OUTPUT"})
	for _, dialect := range []string{"CURRENT", "LEGACY"} {
		v := MediaRequest{Selection: scope, Prompt: "你好", Instructions: "親切", Voice: "Kore", Format: "wav", SpeechDialect: dialect}
		r := req(t, s, "POST", "/api/v1/infer/media", v)
		requireCode(t, r, 200)
		var response MediaResponse
		json.Unmarshal(r.Body.Bytes(), &response)
		if response.Outcome != "SUCCESS" || scopeKey(response.Selection) != scopeKey(scope) || len(response.Data) != 1 {
			t.Fatalf("native result %s", r.Body.String())
		}
		wav, e := base64.StdEncoding.DecodeString(response.Data[0].Base64)
		if e != nil || generatedAudioType(wav, "wav") == "" || int(binary.LittleEndian.Uint32(wav[24:28])) != 16000 || !bytes.Equal(wav[44:], pcm) {
			t.Fatal("PCM rate/bytes lost")
		}
		if response.Details["input_tokens"] != float64(0) || response.Details["output_tokens"] != float64(0) {
			t.Fatal("known zero usage lost")
		}
		config := object(requests[len(requests)-1]["generationConfig"])
		voice := object(object(config["speechConfig"])["voiceConfig"])
		part := object(list(object(list(requests[len(requests)-1]["contents"])[0])["parts"])[0])
		if dialect == "CURRENT" && (voice["voice"] != "Kore" || object(part["speech_metadata"])["style"] != "親切" || part["text"] != "你好") {
			t.Fatal("current wire")
		}
		if dialect == "LEGACY" && (object(voice["prebuiltVoiceConfig"])["voiceName"] != "Kore" || part["text"] != "親切\n你好") {
			t.Fatal("legacy wire")
		}
	}
	v := MediaRequest{Selection: scope, Voice: "Kore", Format: "pcm", Speakers: []SpeechSpeaker{{"Joe", "Puck"}, {"Jane", "Kore"}}, Turns: []SpeechTurn{{"Joe", "你好", "親切"}, {"Jane", "早安", ""}}}
	r := req(t, s, "POST", "/api/v1/infer/media", v)
	requireCode(t, r, 200)
	var response MediaResponse
	json.Unmarshal(r.Body.Bytes(), &response)
	if response.Data[0].SampleRate != 16000 || response.Data[0].Format != "pcm" {
		t.Fatal("actual raw format lost")
	}
	config := object(requests[2]["generationConfig"])
	if object(object(config["responseFormat"])["audio"])["mimeType"] != "AUDIO_L16" || len(list(object(object(config["speechConfig"])["multiSpeakerVoiceConfig"])["speakerVoiceConfigs"])) != 2 {
		t.Fatal("multi-speaker or PCM wire")
	}
	v.SpeechDialect = "LEGACY"
	requireCode(t, req(t, s, "POST", "/api/v1/infer/media", v), 400)
	v.SpeechDialect = "CURRENT"
	v.Turns[0].Speaker = "unknown"
	requireCode(t, req(t, s, "POST", "/api/v1/infer/media", v), 400)
	v = MediaRequest{Selection: scope, Prompt: "hello", Voice: "Kore", Format: "mp3"}
	requireCode(t, req(t, s, "POST", "/api/v1/infer/media", v), 400)
	if len(requests) != 3 {
		t.Fatal("invalid parameters reached provider")
	}
	confirmCaps(t, s, scope, []string{"CODING"})
	v.Format = "wav"
	requireCode(t, req(t, s, "POST", "/api/v1/infer/media", v), 422)
}

func TestNativeSpeechRejectsMalformedTruncationAndSecret(t *testing.T) {
	v := MediaRequest{Format: "wav", SpeechDialect: "CURRENT"}
	good, _ := json.Marshal(speechFixture(pcmWAV(make([]byte, 48000), 24000), "audio/wav"))
	for _, raw := range [][]byte{append(append([]byte{}, good...), []byte(" {}")...), good[:len(good)-1], []byte(`{"candidates":[]}`)} {
		result := MediaResponse{Details: map[string]any{}}
		if _, _, ok := parseGeminiSpeech(raw, v, &result, nil); ok {
			t.Fatal("malformed response accepted")
		}
	}
	for _, fixture := range []map[string]any{speechFixture([]byte("private-speech-key"), "audio/L16;rate=24000"), speechFixture([]byte{0, 1, 2}, "audio/pcm"), speechFixture(make([]byte, 100), "audio/l16;rate=0"), speechFixture(make([]byte, 100), "text/html")} {
		raw, _ := json.Marshal(fixture)
		result := MediaResponse{Details: map[string]any{}}
		if _, _, ok := parseGeminiSpeech(raw, v, &result, []string{"private-speech-key"}); ok {
			t.Fatal("unusable audio accepted")
		}
	}
	var f map[string]any
	json.Unmarshal(good, &f)
	object(list(f["candidates"])[0])["finishReason"] = "SAFETY"
	raw, _ := json.Marshal(f)
	result := MediaResponse{Details: map[string]any{}}
	if _, _, ok := parseGeminiSpeech(raw, v, &result, nil); ok {
		t.Fatal("blocked candidate accepted")
	}
	// A key made only of base64 characters must not escape by being returned
	// directly as inlineData.data rather than as the decoded audio bytes.
	secret := "abcdefghijklmnopqrstuvwx"
	f = speechFixture(make([]byte, 18), "audio/pcm")
	candidate := object(list(f["candidates"])[0])
	part := object(list(object(candidate["content"])["parts"])[0])
	object(part["inlineData"])["data"] = secret

	raw, _ = json.Marshal(f)
	result = MediaResponse{Details: map[string]any{}}
	if _, _, ok := parseGeminiSpeech(raw, v, &result, []string{secret}); ok {
		t.Fatal("raw base64-shaped secret escaped")
	}
}
