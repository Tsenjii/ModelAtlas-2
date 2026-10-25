package main

import (
	"context"
	"crypto/aes"
	"crypto/cipher"
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"net/url"
	"os"
	"os/signal"
	"strings"
	"sync"
	"syscall"
	"time"
	_ "time/tzdata"
)

type Server struct {
	Store           *Store
	Token           string
	Key             []byte
	Client          *http.Client
	PublicClient    *http.Client
	workMu          sync.Mutex
	workspaceMu     workspaceMutex
	flightMu        sync.Mutex
	flights         map[string]bool
	cancels         map[string]context.CancelFunc
	media           map[string][]Attachment
	mediaBytes      int
	MaxFlights      int
	AutoSync        bool
	desktop         bool
	maintenanceFile string
	vaultVerified   bool
	scheduleMu      sync.Mutex
	scheduleClock   func() time.Time
	wake            chan struct{}
	reflectionDue   int64
}

func (s *Server) beginFlight(id string) bool {
	s.flightMu.Lock()
	defer s.flightMu.Unlock()
	if s.flights == nil {
		s.flights = make(map[string]bool)
	}
	if s.flights[id] || len(s.flights) >= s.flightLimit() {
		return false
	}
	s.flights[id] = true
	return true
}
func (s *Server) endFlight(id string) {
	s.flightMu.Lock()
	delete(s.flights, id)
	delete(s.cancels, id)
	s.flightMu.Unlock()
}
func (s *Server) authorized(r *http.Request) bool {
	if !strings.HasPrefix(r.Header.Get("Authorization"), "Bearer ") && !strings.HasPrefix(r.Header.Get("Authorization"), "Basic ") {
		return false
	}
	raw := strings.TrimPrefix(r.Header.Get("Authorization"), "Bearer ")
	if strings.HasPrefix(r.Header.Get("Authorization"), "Basic ") {
		b, e := base64.StdEncoding.DecodeString(strings.TrimPrefix(r.Header.Get("Authorization"), "Basic "))
		if e == nil {
			parts := strings.SplitN(string(b), ":", 2)
			if len(parts) == 2 && parts[0] == "admin" {
				raw = parts[1]
			}
		}
	}
	actual, expected := sha256.Sum256([]byte(raw)), sha256.Sum256([]byte(s.Token))
	return s.Token != "" && subtle.ConstantTimeCompare(actual[:], expected[:]) == 1
}
func (s *Server) auth(h http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		security(w)
		if !s.authorized(r) {
			w.Header().Set("WWW-Authenticate", `Basic realm="ModelAtlas VPS", charset="UTF-8"`)
			http.Error(w, "authorization required", http.StatusUnauthorized)
			return
		}
		if r.Header.Get("Sec-Fetch-Site") == "cross-site" {
			http.Error(w, "cross-site request denied", 403)
			return
		}
		if r.Method != "GET" && r.Method != "HEAD" {
			if origin := r.Header.Get("Origin"); origin != "" {
				u, err := url.Parse(origin)
				if err != nil || u.Host != r.Host || !oneOf(u.Scheme, "http", "https") {
					http.Error(w, "origin mismatch", 403)
					return
				}
			}
			if !strings.HasPrefix(strings.ToLower(r.Header.Get("Content-Type")), "application/json") {
				http.Error(w, "JSON content type required", 415)
				return
			}
		}
		h.ServeHTTP(w, r)
	})
}
func security(w http.ResponseWriter) {
	w.Header().Set("X-Content-Type-Options", "nosniff")
	w.Header().Set("X-Frame-Options", "DENY")
	w.Header().Set("Referrer-Policy", "no-referrer")
	w.Header().Set("Cache-Control", "no-store")
	w.Header().Set("Content-Security-Policy", "default-src 'self'; img-src 'self' data: blob:; media-src 'self' blob:; base-uri 'none'; frame-ancestors 'none'; object-src 'none'; form-action 'self'")
}
func jsonOK(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(v)
}
func decodeJSON(w http.ResponseWriter, r *http.Request, v any) bool {
	return decodeLimit(w, r, v, 128<<10)
}
func decodeLimit(w http.ResponseWriter, r *http.Request, v any, limit int64) bool {
	if r.ContentLength > limit {
		http.Error(w, "request too large", 413)
		return false
	}
	d := json.NewDecoder(http.MaxBytesReader(w, r.Body, limit))
	d.DisallowUnknownFields()
	if err := d.Decode(v); err != nil {
		code := 400
		var oversized *http.MaxBytesError
		if errors.As(err, &oversized) {
			code = 413
		}
		http.Error(w, "invalid JSON", code)
		return false
	}
	var extra any
	if d.Decode(&extra) != io.EOF {
		http.Error(w, "trailing JSON", 400)
		return false
	}
	return true
}
func validateHTTPS(raw string) bool {
	u, err := http.NewRequest("GET", raw, nil)
	if err != nil {
		return false
	}
	return u.URL.Scheme == "https" && u.URL.Host != "" && u.URL.User == nil && u.URL.RawQuery == "" && u.URL.Fragment == "" && u.URL.Opaque == ""
}
func (s *Server) encrypt(raw string) (string, error) {
	if len(s.Key) != 32 {
		return "", errors.New("encryption key not configured")
	}
	block, err := aes.NewCipher(s.Key)
	if err != nil {
		return "", err
	}
	aead, err := cipher.NewGCM(block)
	if err != nil {
		return "", err
	}
	nonce := make([]byte, aead.NonceSize())
	if _, err = rand.Read(nonce); err != nil {
		return "", err
	}
	encrypted := aead.Seal(nonce, nonce, []byte(raw), nil)
	return base64.StdEncoding.EncodeToString(encrypted), nil
}
func (s *Server) decrypt(enc string) (string, error) {
	if len(s.Key) != 32 {
		return "", errors.New("encryption key not configured")
	}
	data, err := base64.StdEncoding.DecodeString(enc)
	if err != nil {
		return "", err
	}
	block, err := aes.NewCipher(s.Key)
	if err != nil {
		return "", err
	}
	aead, err := cipher.NewGCM(block)
	if err != nil {
		return "", err
	}
	if len(data) < aead.NonceSize() {
		return "", errors.New("short encrypted data")
	}
	plain, err := aead.Open(nil, data[:aead.NonceSize()], data[aead.NonceSize():], nil)
	if err != nil {
		return "", err
	}
	return string(plain), nil
}
func (s *Server) Handler() http.Handler {
	api := http.NewServeMux()
	s.workspaceRoutes(api)
	api.HandleFunc("GET /status", s.status)
	api.HandleFunc("GET /providers", s.listProviders)
	api.HandleFunc("POST /providers", s.addProvider)
	api.HandleFunc("GET /accounts", s.listAccounts)
	api.HandleFunc("POST /accounts", s.addAccount)
	api.HandleFunc("GET /credentials", s.listCredentials)
	api.HandleFunc("POST /credentials", s.addCredential)
	api.HandleFunc("POST /credentials/toggle", s.toggleCredentials)
	api.HandleFunc("POST /credentials/rotate", s.rotateCredential)
	api.HandleFunc("GET /providers/config", s.getConfig)
	api.HandleFunc("POST /providers/config", s.setConfig)
	api.HandleFunc("GET /endpoints", s.listEndpoints)
	api.HandleFunc("POST /endpoints", s.addEndpoint)
	api.HandleFunc("GET /models/detail", s.modelDetail)
	api.HandleFunc("GET /models/observations", s.modelObservations)
	api.HandleFunc("POST /models/metadata", s.setMetadata)
	api.HandleFunc("POST /models/capabilities", s.confirmCapabilities)
	api.HandleFunc("POST /models/facts", s.manualFacts)
	api.HandleFunc("POST /pricing/evaluate", s.evaluatePrice)
	api.HandleFunc("POST /models/lifecycle", s.lifecycle)
	api.HandleFunc("POST /models/compare", s.compare)
	api.HandleFunc("POST /models/test", s.testModelHTTP)
	api.HandleFunc("POST /authenticity/analyze", s.analyzeAuthenticityHTTP)
	api.HandleFunc("GET /authenticity/reports", s.listAuthenticityReports)
	api.HandleFunc("POST /authenticity/references/preview", s.previewAuthenticityReferenceHTTP)
	api.HandleFunc("POST /authenticity/references/activate", s.activateAuthenticityReferenceHTTP)
	api.HandleFunc("GET /authenticity/references", s.listAuthenticityReferences)
	api.HandleFunc("POST /intelligence/sync", s.syncIntelligence)
	api.HandleFunc("GET /intelligence/history", s.syncHistory)
	api.HandleFunc("POST /newapi/notice", func(w http.ResponseWriter, r *http.Request) { s.newApiQuery(w, r, "/api/notice") })
	api.HandleFunc("POST /newapi/performance", func(w http.ResponseWriter, r *http.Request) { s.newApiQuery(w, r, "/api/perf-metrics") })
	api.HandleFunc("POST /newapi/summary", func(w http.ResponseWriter, r *http.Request) { s.newApiQuery(w, r, "/api/perf-metrics/summary") })
	api.HandleFunc("POST /lmspeed/key", s.saveLmspeedKey)
	api.HandleFunc("POST /lmspeed/sync", s.syncLmspeed)
	api.HandleFunc("GET /lmspeed/models", s.listExternal)
	api.HandleFunc("POST /onboarding/preview", s.onboardingPreview)
	api.HandleFunc("POST /onboarding/retry", s.onboardingRetry)
	api.HandleFunc("POST /onboarding/confirm", s.onboardingConfirm)
	api.HandleFunc("GET /onboarding", s.listOnboarding)
	api.HandleFunc("POST /onboarding/import", s.batchImport)
	api.HandleFunc("GET /checkins", s.listCheckins)
	api.HandleFunc("POST /checkins", s.completeCheckin)
	api.HandleFunc("GET /checkins/accounts", s.accountCheckinsHTTP)
	api.HandleFunc("POST /checkins/accounts", s.completeAccountCheckin)
	api.HandleFunc("GET /notifications", s.notificationsHTTP)
	api.HandleFunc("POST /notifications/seen", s.seenNotifications)
	api.HandleFunc("GET /settings", s.getSettings)
	api.HandleFunc("POST /settings", s.saveSettings)
	api.HandleFunc("GET /backup", s.exportBackup)
	api.HandleFunc("POST /backup/restore", s.restoreBackup)
	api.HandleFunc("GET /models", s.listModels)
	api.HandleFunc("POST /models", s.addModel)
	api.HandleFunc("POST /newapi/status", s.newApiStatusHTTP)
	api.HandleFunc("POST /newapi/pricing", s.newApiPricingHTTP)
	api.HandleFunc("POST /newapi/account", s.newApiAccountHTTP)
	api.HandleFunc("POST /newapi/token", s.newApiKeyHTTP)
	api.HandleFunc("GET /newapi/snapshots", s.listNewApiSnapshots)
	api.HandleFunc("GET /my-models", s.listMyModels)
	api.HandleFunc("POST /my-models", s.addMyModel)
	api.HandleFunc("DELETE /my-models", s.removeMyModel)
	api.HandleFunc("POST /models/sync", s.syncModels)
	api.HandleFunc("GET /sessions", s.listSessions)
	api.HandleFunc("POST /sessions", s.addSession)
	api.HandleFunc("POST /sessions/update", s.updateSession)
	api.HandleFunc("GET /defaults", s.getFactoryDefaults)
	api.HandleFunc("POST /defaults/kilo", s.addKiloFactoryDefaults)
	api.HandleFunc("GET /sessions/export", s.exportSession)
	api.HandleFunc("GET /sessions/settings", s.getSessionSettings)
	api.HandleFunc("POST /sessions/settings", s.setSessionSettings)
	api.HandleFunc("GET /messages", s.listMessages)
	api.HandleFunc("POST /infer", s.infer)
	api.HandleFunc("POST /infer/media", s.mediaInference)
	api.HandleFunc("POST /infer/cancel", s.cancelInference)
	api.HandleFunc("GET /telemetry", s.listTelemetry)
	api.HandleFunc("GET /telemetry/detail", s.telemetryDetail)
	api.HandleFunc("GET /telemetry/export", s.exportTelemetry)
	api.HandleFunc("GET /research/dashboard", s.researchDashboard)
	api.HandleFunc("GET /research/inference-budget", s.getResearchInferenceBudget)
	api.HandleFunc("POST /research/inference-budget", s.setResearchInferenceBudget)
	api.HandleFunc("GET /research/resource-budget", s.getResearchResourceBudget)
	api.HandleFunc("POST /research/resource-budget", s.setResearchResourceBudget)
	api.HandleFunc("GET /research/quota-pools", s.getResearchQuotaPools)
	api.HandleFunc("POST /research/quota-pools", s.setResearchQuotaPool)
	api.HandleFunc("GET /research/sources", s.listResearchSources)
	api.HandleFunc("POST /research/sources", s.saveResearchSource)
	api.HandleFunc("POST /research/start", s.startResearch)
	api.HandleFunc("POST /research/control", s.controlResearch)
	api.HandleFunc("GET /research/tasks", s.listResearchTasks)
	api.HandleFunc("GET /research/funnel", s.researchFunnel)
	api.HandleFunc("POST /research/review", s.reviewCandidate)
	api.HandleFunc("POST /research/integrations", s.saveResearchIntegration)
	api.HandleFunc("POST /research/aa/sync", s.syncAA)
	api.HandleFunc("GET /research/aa", s.listAA)
	api.HandleFunc("GET /research/candidates", s.listCandidates)
	api.HandleFunc("POST /research/candidates", s.addCandidate)
	api.HandleFunc("GET /research/evidence", s.listEvidence)
	api.HandleFunc("POST /research/evidence", s.addEvidence)
	api.HandleFunc("GET /research/eligibility", s.eligibility)
	api.HandleFunc("POST /research/probe", s.probeCandidate)
	api.HandleFunc("GET /research/runs", s.listResearchRuns)
	api.HandleFunc("POST /research/runs", s.addResearchRun)
	api.HandleFunc("GET /recipes", s.listRecipes)
	api.HandleFunc("POST /recipes", s.addRecipe)
	api.HandleFunc("POST /recipes/proposals", s.generateSiteRecipe)
	api.HandleFunc("POST /recipes/evaluate", s.evaluateSiteRecipeHTTP)
	api.HandleFunc("POST /recipes/plan", s.previewSiteRecipe)
	api.HandleFunc("POST /recipes/activate", s.activateSiteRecipe)
	api.HandleFunc("POST /recipes/rollback", s.rollbackRecipe)
	api.HandleFunc("GET /assistant/config", s.getAssistantConfig)
	api.HandleFunc("GET /research/reflections", s.listReflections)
	api.HandleFunc("GET /research/knowledge", s.listKnowledge)
	api.HandleFunc("POST /research/knowledge", s.proposeKnowledge)
	api.HandleFunc("POST /research/knowledge/evaluate", s.evaluateKnowledge)
	api.HandleFunc("POST /research/knowledge/activate", s.activateKnowledge)
	api.HandleFunc("POST /versions/undo", s.undoVersion)
	api.HandleFunc("GET /connectors", s.listConnectors)
	api.HandleFunc("POST /connectors", s.proposeConnector)
	api.HandleFunc("POST /connectors/discover", s.discoverConnectors)
	api.HandleFunc("GET /connectors/discoveries", s.listConnectorDiscoveries)
	api.HandleFunc("GET /connectors/mapping-sample", s.getMappingSample)
	api.HandleFunc("POST /connectors/map", s.proposeMappedConnector)
	api.HandleFunc("GET /connectors/mapping-result", s.getMappedConnectorResult)
	api.HandleFunc("POST /connectors/test", s.testConnector)
	api.HandleFunc("POST /connectors/activate", s.activateConnector)
	api.HandleFunc("GET /extraction", s.listExtraction)
	api.HandleFunc("POST /extraction/proposals", s.proposeExtraction)
	api.HandleFunc("POST /extraction/compare", s.compareExtraction)
	api.HandleFunc("POST /extraction/plan", s.previewExtraction)
	api.HandleFunc("POST /extraction/activate", s.activateExtraction)
	api.HandleFunc("POST /assistant/config", s.setAssistantConfig)
	api.HandleFunc("POST /assistant/secrets", s.saveAssistantSecret)
	api.HandleFunc("GET /assistant/secrets", s.listAssistantSecrets)
	api.HandleFunc("GET /assistant/threads", s.listAssistantThreads)
	api.HandleFunc("GET /assistant/history", s.assistantHistory)
	api.HandleFunc("POST /assistant/run", s.runAssistant)
	api.HandleFunc("POST /assistant/cancel", s.cancelAssistant)
	api.HandleFunc("GET /assistant/actions", s.listAssistantActions)
	api.HandleFunc("POST /assistant/actions/preview", s.previewControlAction)
	api.HandleFunc("POST /assistant/actions/confirm", s.confirmControlAction)
	api.HandleFunc("POST /assistant/actions/undo", s.undoControlAction)
	api.HandleFunc("GET /notes", s.listNotes)
	api.HandleFunc("POST /assistant/preview", s.assistantPreview)
	api.HandleFunc("POST /assistant/confirm", s.assistantConfirm)
	api.HandleFunc("POST /assistant/undo", s.assistantUndo)
	api.HandleFunc("GET /system", s.systemStatus)
	api.HandleFunc("GET /research/schedules", s.listResearchSchedules)
	api.HandleFunc("GET /research/schedules/review", s.reviewPrivateResearchSchedule)
	api.HandleFunc("GET /research/schedules/private-history", s.privateResearchScheduleHistory)
	api.HandleFunc("GET /research/probes", s.listResearchProbes)
	api.HandleFunc("POST /research/plan", s.previewResearchPlan)
	api.HandleFunc("POST /research/query-generation", s.generateResearchQuery)
	api.HandleFunc("POST /research/query-comparisons/plan", s.previewQueryComparison)
	api.HandleFunc("POST /research/query-comparisons", s.startQueryComparison)
	api.HandleFunc("GET /research/query-comparisons", s.listQueryComparisons)
	api.HandleFunc("POST /research/schedules", s.saveResearchSchedule)
	api.HandleFunc("POST /research/schedules/control", s.controlResearchSchedule)
	api.HandleFunc("POST /research/schedules/limit", s.setResearchScheduleLimit)
	root := http.NewServeMux()
	root.HandleFunc("POST /api/workspace/pair", s.workspaceClaim)
	root.Handle("/api/workspace/exchange", s.workspaceDeviceAuth(http.HandlerFunc(s.workspaceExchangeHTTP)))
	root.HandleFunc("GET /healthz", func(w http.ResponseWriter, r *http.Request) {
		security(w)
		if err := s.Store.DB.PingContext(r.Context()); err != nil {
			http.Error(w, "unavailable", 503)
			return
		}
		jsonOK(w, 200, map[string]any{"service": "modelatlas-vps", "status": "ok"})
	})
	root.Handle("GET /{$}", s.auth(http.HandlerFunc(s.home)))
	root.Handle("GET /app.css", s.auth(http.HandlerFunc(s.stylesheet)))
	root.Handle("GET /app.js", s.auth(http.HandlerFunc(s.script)))
	root.Handle("/api/v1/", s.auth(http.StripPrefix("/api/v1", api)))
	return s.maintenanceHandler(root)
}
func (s *Server) status(w http.ResponseWriter, r *http.Request) {
	var providers, models, sessions, samples int
	_ = s.Store.DB.QueryRowContext(r.Context(), "SELECT COUNT(*) FROM providers").Scan(&providers)
	_ = s.Store.DB.QueryRowContext(r.Context(), "SELECT COUNT(*) FROM models").Scan(&models)
	_ = s.Store.DB.QueryRowContext(r.Context(), "SELECT COUNT(*) FROM sessions").Scan(&sessions)
	_ = s.Store.DB.QueryRowContext(r.Context(), "SELECT COUNT(*) FROM telemetry").Scan(&samples)
	jsonOK(w, 200, map[string]any{"providers": providers, "models": models, "sessions": sessions, "telemetry": samples, "persistent": true})
}
func main() {
	initializeMemoryLimit()
	if len(os.Args) > 1 {
		if err := operationCommand(os.Args[1:], os.Stdout); err != nil {
			log.Fatal(err)
		}
		return
	}
	if err := runApplication(); err != nil {
		log.Fatal(err)
	}
}

func runApplication() error {
	config, err := readDeploymentConfig(os.Getenv)
	if err != nil {
		return err
	}
	store, err := openStore(config.db, true, config.key)
	if err != nil {
		return fmt.Errorf("database initialization failed: %w", err)
	}
	defer store.Close()
	listener, err := net.Listen("tcp", config.addr)
	if err != nil {
		return fmt.Errorf("HTTP server stopped: %w", err)
	}
	defer listener.Close()
	client := &http.Client{Timeout: 120 * time.Second, CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}
	srv := &Server{Store: store, Token: config.token, Key: config.key, Client: client, AutoSync: true, MaxFlights: configuredFlights(), desktop: config.desktopReady != "", maintenanceFile: config.maintenanceFile, vaultVerified: len(config.key) == 32}
	if os.Getenv("MODELATLAS_FACTORY_DEFAULTS") != "0" {
		if _, err = srv.installKiloFactory(context.Background(), true); err != nil {
			return fmt.Errorf("factory defaults initialization failed: %w", err)
		}
	}
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	if config.desktopReady != "" {
		if err = publishDesktopReady(config.desktopReady, listener.Addr().String()); err != nil {
			return err
		}
		defer os.Remove(config.desktopReady)
		go func() {
			// The native parent keeps this pipe open. EOF also covers parent crashes.
			_, _ = io.Copy(io.Discard, os.Stdin)
			stop()
		}()
	}
	workerDone := make(chan struct{})
	go func() { defer close(workerDone); srv.worker(ctx) }()
	server := &http.Server{Addr: config.addr, Handler: config.handler(srv.Handler()), ReadHeaderTimeout: 5 * time.Second, ReadTimeout: 130 * time.Second, IdleTimeout: 30 * time.Second, MaxHeaderBytes: 1 << 16}
	if config.publicOrigin != "" {
		log.Print("Platform mode requires an isolated HTTPS reverse proxy that overwrites X-Forwarded-Proto")
	}
	log.Printf("ModelAtlas engine listening on %s", listener.Addr())
	shutdownDone := make(chan struct{})
	go func() {
		defer close(shutdownDone)
		<-ctx.Done()
		shutdown, cancel := context.WithTimeout(context.Background(), 15*time.Second)
		defer cancel()
		if server.Shutdown(shutdown) != nil {
			_ = server.Close()
		}
	}()
	err = server.Serve(listener)
	stop()
	<-shutdownDone
	<-workerDone
	if err != nil && err != http.ErrServerClosed {
		return fmt.Errorf("HTTP server stopped: %w", err)
	}
	return nil
}

func (s *Server) flightLimit() int {
	if s.MaxFlights >= 2 && s.MaxFlights <= 4 {
		return s.MaxFlights
	}
	return 4
}
