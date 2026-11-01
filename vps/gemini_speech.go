package main

import (
	"encoding/base64"
	"encoding/binary"
	"encoding/json"
	"mime"
	"strconv"
	"strings"
)

type SpeechSpeaker struct {
	Name  string `json:"name"`
	Voice string `json:"voice"`
}
type SpeechTurn struct {
	Speaker string `json:"speaker"`
	Text    string `json:"text"`
	Style   string `json:"style,omitempty"`
}

// Dialects are chosen explicitly; model names never select another transport.
func validGeminiSpeech(v MediaRequest) bool {
	if !oneOf(v.SpeechDialect, "", "CURRENT", "LEGACY") || !oneOf(v.Format, "wav", "pcm") || v.Speed != 0 && v.Speed != 1 || v.Image != nil || v.Audio != nil || len(v.Prompt)+len(v.Instructions) > 8192 || len(v.Instructions) > 4096 {
		return false
	}
	if len(v.Speakers) == 0 && len(v.Turns) == 0 {
		return toolNamePattern.MatchString(v.Voice) && strings.TrimSpace(v.Prompt) != "" && len(v.Prompt) <= 4096
	}
	if v.SpeechDialect == "LEGACY" || len(v.Speakers) != 2 || len(v.Turns) < 1 || len(v.Turns) > 64 || v.Prompt != "" || v.Instructions != "" {
		return false
	}
	names := map[string]bool{}
	for _, s := range v.Speakers {
		if !toolNamePattern.MatchString(s.Name) || !toolNamePattern.MatchString(s.Voice) || names[s.Name] {
			return false
		}
		names[s.Name] = true
	}
	size := 0
	for _, turn := range v.Turns {
		size += len(turn.Text) + len(turn.Style)
		if !names[turn.Speaker] || strings.TrimSpace(turn.Text) == "" || !validTranscript(turn.Text, nil) || safeText(turn.Style, 512) != turn.Style {
			return false
		}
	}
	return size <= 4096
}

func geminiSpeechPayload(v MediaRequest) map[string]any {
	parts := []any{}
	speech := map[string]any{}
	if len(v.Turns) > 0 {
		voices := []any{}
		for _, speaker := range v.Speakers {
			voices = append(voices, map[string]any{"speaker": speaker.Name, "voiceConfig": map[string]any{"prebuiltVoiceConfig": map[string]any{"voiceName": speaker.Voice}}})
		}
		speech["multiSpeakerVoiceConfig"] = map[string]any{"speakerVoiceConfigs": voices}
		for _, turn := range v.Turns {
			parts = append(parts, map[string]any{"text": turn.Text, "speech_metadata": map[string]any{"speaker": turn.Speaker, "style": turn.Style}})
		}
	} else if v.SpeechDialect == "LEGACY" {
		text := v.Prompt
		if v.Instructions != "" {
			text = v.Instructions + "\n" + text
		}
		parts = append(parts, map[string]any{"text": text})
		speech["voiceConfig"] = map[string]any{"prebuiltVoiceConfig": map[string]any{"voiceName": v.Voice}}
	} else {
		part := map[string]any{"text": v.Prompt}
		if v.Instructions != "" {
			part["speech_metadata"] = map[string]any{"style": v.Instructions}
		}
		parts = append(parts, part)
		speech["voiceConfig"] = map[string]any{"voice": v.Voice}
	}
	config := map[string]any{"responseModalities": []string{"AUDIO"}, "speechConfig": speech}
	if v.Format == "pcm" && v.SpeechDialect != "LEGACY" {
		config["responseFormat"] = map[string]any{"audio": map[string]any{"mimeType": "AUDIO_L16", "sampleRate": 24000}}
	}
	return map[string]any{"contents": []any{map[string]any{"role": "user", "parts": parts}}, "generationConfig": config}
}

func pcmWAV(raw []byte, rate int) []byte {
	out := make([]byte, 44+len(raw))
	copy(out, "RIFF")
	binary.LittleEndian.PutUint32(out[4:8], uint32(36+len(raw)))
	copy(out[8:], "WAVEfmt ")
	binary.LittleEndian.PutUint32(out[16:20], 16)
	binary.LittleEndian.PutUint16(out[20:22], 1)
	binary.LittleEndian.PutUint16(out[22:24], 1)
	binary.LittleEndian.PutUint32(out[24:28], uint32(rate))
	binary.LittleEndian.PutUint32(out[28:32], uint32(rate*2))
	binary.LittleEndian.PutUint16(out[32:34], 2)
	binary.LittleEndian.PutUint16(out[34:36], 16)
	copy(out[36:], "data")
	binary.LittleEndian.PutUint32(out[40:44], uint32(len(raw)))
	copy(out[44:], raw)
	return out
}

func parseGeminiSpeech(raw []byte, v MediaRequest, result *MediaResponse, secrets []string) (*int64, *int64, bool) {
	var root struct {
		Error      json.RawMessage `json:"error"`
		Model      string          `json:"modelVersion"`
		Candidates []struct {
			Finish  string `json:"finishReason"`
			Content struct {
				Parts []struct {
					Data *struct {
						Base64 string `json:"data"`
						MIME   string `json:"mimeType"`
					} `json:"inlineData"`
				} `json:"parts"`
			} `json:"content"`
		} `json:"candidates"`
		Usage struct {
			Input     *int64 `json:"promptTokenCount"`
			Output    *int64 `json:"candidatesTokenCount"`
			Cache     *int64 `json:"cachedContentTokenCount"`
			Reasoning *int64 `json:"thoughtsTokenCount"`
			Total     *int64 `json:"totalTokenCount"`
		} `json:"usageMetadata"`
	}
	// Unmarshal rejects trailing JSON and avoids a second decoder buffer copy
	// of multi-MiB audio; token counts stay exact int64, including known zero.
	if json.Unmarshal(raw, &root) != nil || len(root.Error) > 0 && string(root.Error) != "null" || len(root.Candidates) != 1 || root.Candidates[0].Finish != "STOP" || len(root.Candidates[0].Content.Parts) != 1 || root.Candidates[0].Content.Parts[0].Data == nil {
		return nil, nil, false
	}
	data := root.Candidates[0].Content.Parts[0].Data
	encoded := data.Base64
	for _, secret := range secrets {
		if secret != "" && strings.Contains(encoded, secret) {
			return nil, nil, false
		}
	}
	if len(encoded) == 0 || len(encoded) > ((maxAttachment+2)/3)*4 {
		return nil, nil, false
	}
	audio, err := base64.StdEncoding.Strict().DecodeString(encoded)
	if err != nil {
		return nil, nil, false
	}
	mediaType, params, err := mime.ParseMediaType(data.MIME)
	if err != nil {
		return nil, nil, false
	}
	format, rate := "wav", 24000
	switch strings.ToLower(mediaType) {
	case "audio/wav", "audio/x-wav":
		if generatedAudioType(audio, "wav", secrets...) == "" {
			return nil, nil, false
		}
		mediaType = "audio/wav"
	case "audio/l16", "audio/pcm":
		if params["codec"] != "" && params["codec"] != "pcm" {
			return nil, nil, false
		}
		if params["rate"] != "" {
			rate, err = strconv.Atoi(params["rate"])
			if err != nil {
				return nil, nil, false
			}
		}
		if !oneOf(strconv.Itoa(rate), "8000", "16000", "22050", "24000", "44100", "48000") || generatedAudioType(audio, "pcm", secrets...) == "" {
			return nil, nil, false
		}
		format, mediaType = "pcm", "audio/pcm"
		if v.Format == "wav" {
			if len(audio)+44 > maxAttachment {
				return nil, nil, false
			}
			audio = pcmWAV(audio, rate)
			format, mediaType = "wav", "audio/wav"
		}
	default:
		return nil, nil, false
	}
	result.Data = []mediaWire{{Base64: base64.StdEncoding.EncodeToString(audio), MIME: mediaType, Format: format, SampleRate: rate}}
	result.Details["reported_model"] = safeText(root.Model, 256, secrets...)
	result.Details["speech_dialect"] = v.SpeechDialect
	for name, count := range map[string]*int64{"cache_read_tokens": root.Usage.Cache, "reasoning_tokens": root.Usage.Reasoning, "total_tokens": root.Usage.Total} {
		if count != nil && *count >= 0 {
			result.Details[name] = *count
		}
	}
	in, out := root.Usage.Input, root.Usage.Output
	if in != nil && *in < 0 {
		in = nil
	}
	if out != nil && *out < 0 {
		out = nil
	}
	return in, out, true
}
