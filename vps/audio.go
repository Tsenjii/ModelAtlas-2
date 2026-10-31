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
		alignment, dataSize := 0, 0
		off := 12
		for off+8 <= len(raw) {
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
				bits := binary.LittleEndian.Uint16(chunk[14:16])
				if codec == 0xfffe {
					if len(chunk) < 40 || binary.LittleEndian.Uint16(chunk[16:18]) < 22 || binary.LittleEndian.Uint16(chunk[18:20]) > bits || !bytes.Equal(chunk[28:40], []byte{0, 0, 0x10, 0, 0x80, 0, 0, 0xaa, 0, 0x38, 0x9b, 0x71}) {
						return ""
					}
					kind := binary.LittleEndian.Uint32(chunk[24:28])
					if kind != 1 && kind != 3 {
						return ""
					}
					codec = uint16(kind)
				}
				alignment = int(channels) * int(bits) / 8
				if fmtOK || channels < 1 || channels > 8 || rate < 8000 || rate > 192000 || codec != 1 && codec != 3 || codec == 1 && bits != 8 && bits != 16 && bits != 24 && bits != 32 || codec == 3 && bits != 32 && bits != 64 || int(binary.LittleEndian.Uint16(chunk[12:14])) != alignment || uint64(binary.LittleEndian.Uint32(chunk[8:12])) != uint64(rate)*uint64(alignment) {
					return ""
				}
				fmtOK = true
			case "data":
				if dataOK {
					return ""
				}
				dataOK = n > 0
				dataSize = n
			}
			off += 8 + n + (n % 2)
		}
		if fmtOK && dataOK && alignment > 0 && dataSize%alignment == 0 && off == len(raw) {
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
