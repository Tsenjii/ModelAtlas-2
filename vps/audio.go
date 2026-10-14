package main

import (
	"bytes"
	"encoding/binary"
)

// Audio stays bounded and is never persisted. WAV chunks are checked instead
// of trusting an upstream Content-Type. PCM is explicitly 24 kHz mono s16le.
func generatedAudioType(raw []byte, format string, secrets ...string) string {
	if len(raw) == 0 || len(raw) > maxAttachment {
		return ""
	}
	for _, secret := range secrets {
		if secret != "" && bytes.Contains(raw, []byte(secret)) {
			return ""
		}
	}
	switch format {
	case "wav":
		if len(raw) < 44 || string(raw[:4]) != "RIFF" || string(raw[8:12]) != "WAVE" || int(binary.LittleEndian.Uint32(raw[4:8]))+8 != len(raw) {
			return ""
		}
		fmtOK, dataOK := false, false
		for off := 12; off+8 <= len(raw); {
			n := int(binary.LittleEndian.Uint32(raw[off+4 : off+8]))
			if n < 0 || n > len(raw)-off-8 {
				return ""
			}
			chunk := raw[off+8 : off+8+n]
			switch string(raw[off : off+4]) {
			case "fmt ":
				if len(chunk) < 16 {
					return ""
				}
				codec := binary.LittleEndian.Uint16(chunk[:2])
				channels := binary.LittleEndian.Uint16(chunk[2:4])
				rate := binary.LittleEndian.Uint32(chunk[4:8])
				if !oneOfCodec(codec) || channels < 1 || channels > 8 || rate < 8000 || rate > 192000 {
					return ""
				}
				fmtOK = true
			case "data":
				dataOK = n > 0
			}
			off += 8 + n + (n % 2)
		}
		if fmtOK && dataOK {
			return "audio/wav"
		}
	case "mp3":
		a := Attachment{Kind: "audio", Filename: "speech.mp3", MIME: "audio/mpeg"}
		if len(raw) >= 10 && string(raw[:3]) == "ID3" && raw[3] >= 2 && raw[3] <= 4 {
			return a.MIME
		}
		if len(raw) >= 4 && raw[0] == 255 && raw[1]&0xe0 == 0xe0 && raw[2]>>4 > 0 && raw[2]>>4 < 15 {
			return a.MIME
		}
	case "flac":
		if len(raw) > 42 && string(raw[:4]) == "fLaC" {
			return "audio/flac"
		}
	case "opus":
		if len(raw) > 40 && string(raw[:4]) == "OggS" && bytes.Contains(raw[:min(len(raw), 256)], []byte("OpusHead")) {
			return "audio/ogg"
		}
	case "aac":
		if len(raw) > 7 && raw[0] == 255 && raw[1]&0xf6 == 0xf0 {
			return "audio/aac"
		}
	case "pcm":
		if len(raw) >= 2 && len(raw)%2 == 0 {
			return "audio/pcm"
		}
	}
	return ""
}
func oneOfCodec(v uint16) bool { return v == 1 || v == 3 || v == 0xfffe }
