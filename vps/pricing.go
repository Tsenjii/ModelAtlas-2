package main

import (
	"encoding/json"
	"errors"
	"math/big"
	"net/http"
	"strconv"
	"strings"
	"unicode"
)

type expressionParser struct {
	tokens     []string
	pos, depth int
	vars       map[string]*big.Rat
}

func evaluateExpression(expression string, vars map[string]string) (*big.Rat, error) {
	if len(expression) > 2000 || len(vars) > 64 {
		return nil, errors.New("LIMIT")
	}
	tokens := []string{}
	for i := 0; i < len(expression); {
		c := expression[i]
		if unicode.IsSpace(rune(c)) {
			i++
			continue
		}
		if strings.ContainsRune("+-*/()", rune(c)) {
			tokens = append(tokens, string(c))
			i++
		} else if c >= '0' && c <= '9' || c == '.' {
			start := i
			for i < len(expression) && (expression[i] >= '0' && expression[i] <= '9' || expression[i] == '.') {
				i++
			}
			if i-start > 128 {
				return nil, errors.New("NUMBER_LIMIT")
			}
			tokens = append(tokens, expression[start:i])
		} else if c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z' || c == '_' {
			start := i
			for i < len(expression) && (expression[i] >= 'A' && expression[i] <= 'Z' || expression[i] >= 'a' && expression[i] <= 'z' || expression[i] >= '0' && expression[i] <= '9' || expression[i] == '_') {
				i++
			}
			if i-start > 64 {
				return nil, errors.New("IDENTIFIER_LIMIT")
			}
			tokens = append(tokens, expression[start:i])
		} else {
			return nil, errors.New("SYNTAX")
		}
		if len(tokens) > 500 {
			return nil, errors.New("COMPLEXITY")
		}
	}
	if len(tokens) == 0 {
		return nil, errors.New("EMPTY")
	}
	p := expressionParser{tokens: tokens, vars: map[string]*big.Rat{}}
	for name, value := range vars {
		n := decimal(value)
		if n == nil || n.Num().BitLen() > 16384 || n.Denom().BitLen() > 16384 {
			return nil, errors.New("VARIABLE_INVALID")
		}
		p.vars[name] = n
	}
	n, err := p.expr()
	if err != nil {
		return nil, err
	}
	if p.pos != len(tokens) {
		return nil, errors.New("SYNTAX")
	}
	return n, nil
}
func (p *expressionParser) peek() string {
	if p.pos >= len(p.tokens) {
		return ""
	}
	return p.tokens[p.pos]
}
func boundRat(n *big.Rat) (*big.Rat, error) {
	if n.Num().BitLen() > 16384 || n.Denom().BitLen() > 16384 {
		return nil, errors.New("VALUE_LIMIT")
	}
	return n, nil
}
func (p *expressionParser) expr() (*big.Rat, error) {
	n, err := p.term()
	if err != nil {
		return nil, err
	}
	for p.peek() == "+" || p.peek() == "-" {
		op := p.peek()
		p.pos++
		b, err := p.term()
		if err != nil {
			return nil, err
		}
		if op == "+" {
			n.Add(n, b)
		} else {
			n.Sub(n, b)
		}
		if _, err = boundRat(n); err != nil {
			return nil, err
		}
	}
	return n, nil
}
func (p *expressionParser) term() (*big.Rat, error) {
	n, err := p.factor()
	if err != nil {
		return nil, err
	}
	for p.peek() == "*" || p.peek() == "/" {
		op := p.peek()
		p.pos++
		b, err := p.factor()
		if err != nil {
			return nil, err
		}
		if op == "*" {
			n.Mul(n, b)
		} else {
			if b.Sign() == 0 {
				return nil, errors.New("DIVISION_BY_ZERO")
			}
			n.Quo(n, b)
		}
		if _, err = boundRat(n); err != nil {
			return nil, err
		}
	}
	return n, nil
}
func (p *expressionParser) factor() (*big.Rat, error) {
	p.depth++
	defer func() { p.depth-- }()
	if p.depth > 32 {
		return nil, errors.New("DEPTH")
	}
	token := p.peek()
	if token == "" {
		return nil, errors.New("SYNTAX")
	}
	p.pos++
	if token == "+" || token == "-" {
		n, err := p.factor()
		if err != nil {
			return nil, err
		}
		if token == "-" {
			n.Neg(n)
		}
		return n, nil
	}
	if token == "(" {
		n, err := p.expr()
		if err != nil {
			return nil, err
		}
		if p.peek() != ")" {
			return nil, errors.New("SYNTAX")
		}
		p.pos++
		return n, nil
	}
	if n := p.vars[token]; n != nil {
		return new(big.Rat).Set(n), nil
	}
	if token[0] >= '0' && token[0] <= '9' || token[0] == '.' {
		if n := decimal(token); n != nil {
			return n, nil
		}
	}
	return nil, errors.New("UNKNOWN_VARIABLE_OR_SYNTAX")
}
func (s *Server) evaluatePrice(w http.ResponseWriter, r *http.Request) {
	var v struct {
		Expression string            `json:"expression"`
		Variables  map[string]string `json:"variables"`
	}
	if !decodeJSON(w, r, &v) {
		return
	}
	n, err := evaluateExpression(v.Expression, v.Variables)
	if err != nil {
		jsonOK(w, 200, map[string]any{"state": "UNKNOWN", "reason": err.Error()})
		return
	}
	jsonOK(w, 200, map[string]any{"state": "EVALUATED", "value": exactString(n), "source": "EXPLICIT_USER_EVALUATION"})
}
func (s *Server) manualFacts(w http.ResponseWriter, r *http.Request) {
	var v struct {
		Selection
		Price     *PriceLegs        `json:"price,omitempty"`
		Quota     map[string]*int64 `json:"quota,omitempty"`
		ScopeKind string            `json:"scope_kind"`
		Slice     string            `json:"slice"`
		Note      string            `json:"note"`
	}
	if !decodeJSON(w, r, &v) {
		return
	}
	if !s.validateSelection(r.Context(), v.Selection) || len(v.Note) > 512 {
		http.Error(w, "selected scope required", 422)
		return
	}
	if v.Price != nil {
		for _, leg := range []*string{v.Price.Input, v.Price.Output, v.Price.Fixed, v.Price.Cache} {
			if leg != nil && decimal(*leg) == nil {
				http.Error(w, "invalid exact decimal", 400)
				return
			}
		}
		if !oneOf(v.Price.Unit, "USD/1K tokens", "USD/request") {
			http.Error(w, "unit required", 400)
			return
		}
		if err := s.observe(r.Context(), v.Selection, "MANUAL_PRICE", "USER", "SUCCESS", map[string]any{"price": v.Price, "manual_price_state": priceState(*v.Price), "note": v.Note}); err != nil {
			http.Error(w, "database error", 500)
			return
		}
	}
	if v.Quota != nil {
		if !oneOf(v.ScopeKind, "PROVIDER", "ACCOUNT", "CREDENTIAL", "GROUP", "MODEL", "PROJECT_MODEL", "CUSTOM") || !oneOf(v.Slice, "", "STANDARD_INTERACTIVE", "PRIORITY", "BATCH") {
			http.Error(w, "quota pool scope required", 400)
			return
		}
		for k, n := range v.Quota {
			if !oneOf(k, "rpm", "rpd", "tpm", "tpd", "remaining", "used", "granted") || n != nil && *n < 0 {
				http.Error(w, "invalid quota fact", 400)
				return
			}
		}
		if err := s.observe(r.Context(), v.Selection, "MANUAL_QUOTA:"+v.ScopeKind+":"+v.Slice, "USER", "SUCCESS", map[string]any{"values": v.Quota, "scope_kind": v.ScopeKind, "slice": v.Slice, "note": v.Note}); err != nil {
			http.Error(w, "database error", 500)
			return
		}
	}
	jsonOK(w, 200, map[string]bool{"saved": true})
}

var _ = json.Number(strconv.Itoa(0))
