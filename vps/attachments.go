package main

import (
	"bytes"
	"encoding/base64"
	"errors"
	"strings"
)

const maxAttachment = 4 << 20

type Attachment struct {
	Kind     string `json:"kind"`
	Filename string `json:"filename"`
	MIME     string `json:"mime"`
	Data     string `json:"data,omitempty"`
}

func (a Attachment) required() string {
	return map[string]string{"image": "VISION", "pdf": "FILES", "audio": "AUDIO_INPUT"}[a.Kind]
}
func (a Attachment) validate() error {
	if a.Filename == "" || safeText(a.Filename, 80) != a.Filename || strings.ContainsAny(a.Filename, "/\\") || len(a.Data) > ((maxAttachment+2)/3)*4 {
		return errors.New("invalid local attachment")
	}
	b, err := base64.StdEncoding.Strict().DecodeString(a.Data)
	if err != nil || len(b) == 0 || len(b) > maxAttachment {
		return errors.New("attachment exceeds 4 MiB or is invalid")
	}
	mime := ""
	switch {
	case bytes.HasPrefix(b, []byte{0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a}):
		mime = "image/png"
	case bytes.HasPrefix(b, []byte{0xff, 0xd8, 0xff}):
		mime = "image/jpeg"
	case len(b) >= 12 && string(b[:4]) == "RIFF" && string(b[8:12]) == "WEBP":
		mime = "image/webp"
	case bytes.HasPrefix(b, []byte("%PDF-")):
		mime = "application/pdf"
	case len(b) >= 12 && string(b[:4]) == "RIFF" && string(b[8:12]) == "WAVE":
		mime = "audio/wav"
	case len(b) >= 10 && string(b[:3]) == "ID3" && b[3] >= 2 && b[3] <= 4:
		mime = "audio/mpeg"
	case len(b) >= 4 && b[0] == 255 && b[1]&0xe0 == 0xe0 && (b[1]>>3)&3 != 1 && (b[1]>>1)&3 != 0 && (b[2]>>4) >= 1 && (b[2]>>4) <= 14 && (b[2]>>2)&3 != 3:
		mime = "audio/mpeg"
	}
	if mime == "" || mime != a.MIME || a.Kind == "image" && !strings.HasPrefix(mime, "image/") || a.Kind == "pdf" && (mime != "application/pdf" || !strings.HasSuffix(strings.ToLower(a.Filename), ".pdf")) || a.Kind == "audio" && !strings.HasPrefix(mime, "audio/") || a.required() == "" {
		return errors.New("unsupported or forged attachment signature")
	}
	return nil
}
func (a Attachment) wire() map[string]any {
	switch a.Kind {
	case "image":
		return map[string]any{"type": "image_url", "image_url": map[string]string{"url": "data:" + a.MIME + ";base64," + a.Data, "detail": "auto"}}
	case "pdf":
		return map[string]any{"type": "file", "file": map[string]string{"filename": a.Filename, "file_data": "data:application/pdf;base64," + a.Data}}
	case "audio":
		format := "mp3"
		if a.MIME == "audio/wav" {
			format = "wav"
		}
		return map[string]any{"type": "input_audio", "input_audio": map[string]string{"data": a.Data, "format": format}}
	}
	return nil
}
func metadataOnly(a []Attachment) []Attachment {
	out := make([]Attachment, len(a))
	for i, v := range a {
		v.Data = ""
		out[i] = v
	}
	return out
}
func (s *Server) rememberMedia(id string, a []Attachment) bool {
	size := 0
	for _, v := range a {
		size += len(v.Data)
	}
	s.flightMu.Lock()
	defer s.flightMu.Unlock()
	if s.mediaBytes+size > 32<<20 {
		return false
	}
	if s.media == nil {
		s.media = map[string][]Attachment{}
	}
	s.media[id] = a
	s.mediaBytes += size
	return true
}
func (s *Server) mediaFor(id string) []Attachment {
	s.flightMu.Lock()
	defer s.flightMu.Unlock()
	return append([]Attachment(nil), s.media[id]...)
}
