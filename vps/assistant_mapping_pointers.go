package main

import (
	"encoding/json"
	"errors"
	"fmt"
	"strings"
)

func assistantMappingFieldNames(kind string) []string {
	out := []string{}
	if !oneOf(kind, "PRICING", "QUOTA", "PERFORMANCE", "USAGE", "BALANCE", "NOTICE") {
		return out
	}
	for _, name := range strings.Fields(connectorFields[kind]) {
		if !oneOf(name, "model_id", "group", "name") {
			out = append(out, name)
		}
	}
	return out
}

func assistantMappingRequirements(kind string) error {
	allowed := assistantMappingFieldNames(kind)
	if len(allowed) == 0 {
		return errors.New("Choose PRICING/QUOTA/PERFORMANCE/USAGE/BALANCE/NOTICE and observed field JSON Pointers; unsupported mapping kind.")
	}
	return fmt.Errorf("%s fields use %s with observed row JSON Pointers. units keys must match numeric fields and point to observed unit fields, not literal units. scope_field must point to observed MODEL_PROVIDER. Select a complete unique model row; never invent names or units.", kind, strings.Join(allowed, "/"))
}

// Accept the advertised typed row pointer and the original string encoding.
// Both normalize to the shared request before hashing, validation or writes.
// No unit, metric meaning or source-root authority is inferred or repaired.
func decodeAssistantMapping(raw json.RawMessage) (connectorMappingRequest, error) {
	var wire struct {
		ID        string                     `json:"id"`
		SampleID  string                     `json:"sample_id"`
		Kind      string                     `json:"kind"`
		Root      string                     `json:"root_pointer"`
		ModelPath string                     `json:"model_path"`
		GroupPath string                     `json:"group_path,omitempty"`
		Scope     mappingLocation            `json:"scope_field"`
		Fields    map[string]json.RawMessage `json:"fields"`
		Units     map[string]mappingLocation `json:"units"`
	}
	var out connectorMappingRequest
	if e := strictArguments(string(raw), &wire); e != nil {
		return out, e
	}
	allowed := assistantMappingFieldNames(wire.Kind)
	if len(allowed) == 0 {
		return out, assistantMappingRequirements(wire.Kind)
	}
	for name := range wire.Fields {
		if !oneOf(name, allowed...) {
			return out, assistantMappingRequirements(wire.Kind)
		}
	}
	out = connectorMappingRequest{ID: wire.ID, SampleID: wire.SampleID, Kind: wire.Kind, Root: wire.Root, ModelPath: wire.ModelPath, GroupPath: wire.GroupPath, Scope: wire.Scope, Fields: map[string]string{}, Units: wire.Units}
	for name, value := range wire.Fields {
		var path string
		if json.Unmarshal(value, &path) != nil {
			var pointer struct {
				Path string `json:"path"`
			}
			if e := strictArguments(string(value), &pointer); e != nil {
				return connectorMappingRequest{}, errors.New("Each field requires a row JSON Pointer string or an object containing only path; source_root is not allowed on row fields.")
			}
			path = pointer.Path
		}
		if !strings.HasPrefix(path, "/") || !connectorSafePointer(path) {
			return connectorMappingRequest{}, assistantMappingRequirements(wire.Kind)
		}
		out.Fields[name] = path
	}
	if !strings.HasPrefix(out.Scope.Path, "/") || !connectorSafePointer(out.Scope.Path) {
		return connectorMappingRequest{}, errors.New("scope_field requires an observed JSON Pointer, not the literal MODEL_PROVIDER value; copy an entry from scope_fields or mapping_context.")
	}
	for _, location := range out.Units {
		if !strings.HasPrefix(location.Path, "/") || !connectorSafePointer(location.Path) {
			return connectorMappingRequest{}, assistantMappingRequirements(wire.Kind)
		}
	}
	return out, nil
}

// These are literal locations already present in the bounded saved descriptors,
// not guesses about which numeric field they describe. The shared mapping
// validator still checks the exact requested kind, unit, scope and row.
func assistantMappingLocations(view mappingView) ([]mappingLocation, map[string][]mappingLocation) {
	scopes := []mappingLocation{}
	units := map[string][]mappingLocation{}
	collect := func(fields []mappingField, source bool) {
		for _, field := range fields {
			location := mappingLocation{Path: field.Path, Source: source}
			if field.Value == "MODEL_PROVIDER" {
				scopes = append(scopes, location)
			}
			if oneOf(field.Value, "USD/1M tokens", "USD/1K tokens", "USD/request", "USD", "CREDITS", "TOKENS", "REQUESTS", "REQUESTS/day", "TOKENS/day", "REQUESTS/minute", "TOKENS/minute", "TOKENS/second", "MS", "RATIO", "UNIX_MS") {
				units[field.Value] = append(units[field.Value], location)
			}
		}
	}
	collect(view.RootFields, true)
	collect(view.Fields, false)
	return scopes, units
}
