package main

import (
	"context"
	"net/http"
	"runtime"
	"sync"
	"time"
)

type webUpdater struct {
	root   string
	tag    string
	ctx    context.Context
	stop   context.CancelFunc
	client *http.Client

	mu        sync.Mutex
	selection *updateRelease
	busy      bool
	failed    bool
}

type updateView struct {
	Current   string `json:"current"`
	Commit    string `json:"commit"`
	Available string `json:"available,omitempty"`
	Notes     string `json:"notes,omitempty"`
	Enabled   bool   `json:"enabled"`
	Phase     string `json:"phase,omitempty"`
	Result    string `json:"result,omitempty"`
}

func (u *webUpdater) status() updateView {
	view := updateView{Current: buildReleaseTag, Commit: versionInfo().Commit}
	if u == nil || runtime.GOOS != "linux" || !oneOf(runtime.GOARCH, "amd64", "arm64") || updateTagParts(u.tag) == nil {
		return view
	}
	view.Current = u.tag
	u.mu.Lock()
	defer u.mu.Unlock()
	state, err := readUpdateJournal(u.root)
	if err != nil {
		view.Result = "unavailable"
		return view
	}
	view.Enabled, view.Result = true, state.Result
	switch {
	case state.Trial != nil:
		view.Phase = "restarting"
	case u.busy:
		view.Phase = "downloading"
	case u.failed:
		view.Result = "download_failed"
	}
	if u.selection != nil {
		view.Available = u.selection.Tag
		view.Notes = "https://github.com/" + updateRepository + "/releases/tag/" + u.selection.Tag
	}
	return view
}

func (s *Server) updateRoutes(admin *http.ServeMux) {
	admin.HandleFunc("GET /update/status", s.getUpdateStatus)
	admin.HandleFunc("POST /update/check", s.checkUpdate)
	admin.HandleFunc("POST /update/install", s.installUpdate)
}

func (s *Server) getUpdateStatus(w http.ResponseWriter, r *http.Request) {
	jsonOK(w, http.StatusOK, s.updates.status())
}

func (s *Server) checkUpdate(w http.ResponseWriter, r *http.Request) {
	u := s.updates
	status := u.status()
	if !status.Enabled || status.Phase != "" {
		http.Error(w, "update service unavailable or busy", http.StatusConflict)
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 12*time.Second)
	defer cancel()
	release, err := selectUpdate(ctx, u.client, u.tag)
	if err != nil {
		http.Error(w, "cannot check official releases", http.StatusBadGateway)
		return
	}
	u.mu.Lock()
	u.selection, u.failed = release, false
	u.mu.Unlock()
	jsonOK(w, http.StatusOK, u.status())
}

func (s *Server) installUpdate(w http.ResponseWriter, r *http.Request) {
	var request struct {
		Tag string `json:"tag"`
	}
	if !decodeJSON(w, r, &request) {
		return
	}
	u := s.updates
	status := u.status()
	if !status.Enabled || status.Phase != "" {
		http.Error(w, "update service unavailable or busy", http.StatusConflict)
		return
	}
	u.mu.Lock()
	if u.busy || u.selection == nil || u.selection.Tag != request.Tag {
		u.mu.Unlock()
		http.Error(w, "check the release before installing it", http.StatusConflict)
		return
	}
	release := *u.selection
	u.busy, u.failed = true, false
	u.mu.Unlock()
	jsonOK(w, http.StatusAccepted, map[string]string{"phase": "downloading"})
	go u.install(release)
}

func (u *webUpdater) install(release updateRelease) {
	ctx, cancel := context.WithTimeout(u.ctx, 3*time.Minute)
	defer cancel()
	installed, err := stageUpdateRelease(ctx, u.client, u.root, release)
	if err == nil && ctx.Err() == nil {
		var state updateJournal
		state, err = readUpdateJournal(u.root)
		if err == nil {
			state.Trial, state.Result = &installed, ""
			err = saveUpdateJournal(u.root, state)
		}
		if err == nil {
			u.stop()
			return
		}
	}
	u.mu.Lock()
	u.busy, u.failed = false, true
	u.mu.Unlock()
}
