package main

import (
	"encoding/binary"
	"testing"
)

func TestSpeechWAVRejectsUnplayableFormatAndIncompleteFrames(t *testing.T) {
	for _, change := range []func([]byte){func(b []byte) { binary.LittleEndian.PutUint16(b[34:36], 0) }, func(b []byte) { binary.LittleEndian.PutUint32(b[28:32], 1) }, func(b []byte) { binary.LittleEndian.PutUint16(b[32:34], 1) }, func(b []byte) { binary.LittleEndian.PutUint16(b[20:22], 0xfffe) }} {
		raw := validTestWAV()
		change(raw)
		if generatedAudioType(raw, "wav") != "" {
			t.Fatal("unplayable WAV success")
		}
	}
	raw := validTestWAV()
	raw = raw[:len(raw)-1]
	binary.LittleEndian.PutUint32(raw[4:8], uint32(len(raw)-8))
	binary.LittleEndian.PutUint32(raw[40:44], uint32(len(raw)-44))
	if generatedAudioType(raw, "wav") != "" {
		t.Fatal("partial PCM frame success")
	}
	if generatedAudioType(validTestWAV(), "wav") != "audio/wav" {
		t.Fatal("valid PCM WAV rejected")
	}
}
