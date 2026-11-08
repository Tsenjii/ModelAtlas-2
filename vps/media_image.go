package main

import (
	"bytes"
	"encoding/base64"
	"encoding/binary"
	"hash/crc32"
	"image"
	_ "image/jpeg"
	_ "image/png"
	"io"
	"strings"

	"golang.org/x/image/riff"
	"golang.org/x/image/vp8"
	"golang.org/x/image/vp8l"
	_ "golang.org/x/image/webp"
)

// The VPS checks encoded files and dimensions without allocating raster pixels.
// The shared desktop engine retains its existing full-decode validation.
func (s *Server) mediaImageType(encoded string, pixelsLeft int64, secrets ...string) (string, int, int64) {
	if len(encoded) == 0 || len(encoded) > ((maxAttachment+2)/3)*4 {
		return "", 0, 0
	}
	for _, secret := range secrets {
		if secret != "" && strings.Contains(encoded, secret) {
			return "", 0, 0
		}
	}
	raw, err := base64.StdEncoding.Strict().DecodeString(encoded)
	if err != nil || len(raw) == 0 || len(raw) > maxAttachment {
		return "", 0, 0
	}
	for _, secret := range secrets {
		if secret != "" && bytes.Contains(raw, []byte(secret)) {
			return "", 0, 0
		}
	}
	cfg, format, err := image.DecodeConfig(bytes.NewReader(raw))
	if err != nil || cfg.Width < 1 || cfg.Height < 1 || cfg.Width > 4096 || cfg.Height > 4096 {
		return "", 0, 0
	}
	pixels := int64(cfg.Width) * int64(cfg.Height)
	if pixels > pixelsLeft || !oneOf(format, "png", "jpeg", "webp") {
		return "", 0, 0
	}
	if s.desktop {
		decoded, decodedFormat, err := image.Decode(bytes.NewReader(raw))
		if err != nil || decodedFormat != format || decoded.Bounds().Dx() != cfg.Width || decoded.Bounds().Dy() != cfg.Height {
			return "", 0, 0
		}
	} else {
		switch format {
		case "png":
			if !completePNG(raw[8:]) {
				return "", 0, 0
			}
		case "jpeg":
			if !bytes.HasSuffix(raw, []byte{0xff, 0xd9}) {
				return "", 0, 0
			}
		case "webp":
			if !completeWebP(raw, cfg) {
				return "", 0, 0
			}
		}
	}
	return "image/" + format, len(raw), pixels
}

// Check chunk boundaries and checksums without inflating IDAT data.
func completePNG(chunks []byte) bool {
	hasData := false
	for len(chunks) >= 12 {
		size := binary.BigEndian.Uint32(chunks[:4])
		if size > uint32(len(chunks)-12) {
			return false
		}
		end := 8 + int(size)
		if crc32.ChecksumIEEE(chunks[4:end]) != binary.BigEndian.Uint32(chunks[end:end+4]) {
			return false
		}
		switch string(chunks[4:8]) {
		case "IDAT":
			hasData = hasData || size > 0
		case "IEND":
			return hasData && size == 0 && len(chunks) == 12
		}
		chunks = chunks[end+4:]
	}
	return false
}

func completeWebP(raw []byte, canvas image.Config) bool {
	_, chunks, err := riff.NewReader(bytes.NewReader(raw))
	if err != nil {
		return false
	}
	hasFrame := false
	for {
		kind, size, body, err := chunks.Next()
		if err == io.EOF {
			return hasFrame
		}
		if err != nil {
			return false
		}
		// DecodeConfig may report the extended canvas before seeing its frame.
		// Check the coded frame header too so that canvas sizes cannot mask it.
		switch string(kind[:]) {
		case "VP8 ":
			decoder := vp8.NewDecoder()
			decoder.Init(body, int(size))
			frame, err := decoder.DecodeFrameHeader()
			if err != nil || hasFrame || frame.Width != canvas.Width || frame.Height != canvas.Height {
				return false
			}
		case "VP8L":
			frame, err := vp8l.DecodeConfig(body)
			if err != nil || hasFrame || frame.Width != canvas.Width || frame.Height != canvas.Height {
				return false
			}
		case "ANIM", "ANMF":
			return false
		default:
			continue
		}
		hasFrame = true
	}
}
