package main

import (
	_ "embed"
	"encoding/json"
	"strings"
	"unicode"

	"github.com/dlclark/regexp2"
	"golang.org/x/text/cases"
	"golang.org/x/text/language"
)

//go:embed gateway_ui/model-brands.json
var gatewayBrandRulesJSON string

const gatewayUnclassifiedBrand = "unclassified"

var gatewayBrandPattern, gatewayBrandNamespaces = loadGatewayBrandRules()
var gatewayBrandRegion = regexp2.MustCompile(`^(?:us|eu|apac|global|au|ca|jp|sa)\.(?=[^.]+\.)`, regexp2.ECMAScript)

func loadGatewayBrandRules() (*regexp2.Regexp, map[string]string) {
	var config struct {
		Brands     [][3]string
		Namespaces map[string]string
	}
	if err := json.Unmarshal([]byte(gatewayBrandRulesJSON), &config); err != nil {
		panic(err)
	}
	families := make([]string, 0, len(config.Brands))
	for _, row := range config.Brands {
		name, family := row[0], row[1]
		families = append(families, `(?<`+name+`>`+family+`)`)
		config.Namespaces[name] = name
	}
	// Preserve the browser rule order in one anchored match.
	pattern := `^(?:` + strings.Join(families, "|") + `)(?=$|[^a-z]|v[0-9])`
	return regexp2.MustCompile(pattern, regexp2.ECMAScript), config.Namespaces
}

func gatewayModelBrand(names ...string) string {
	for _, name := range names {
		if name == "" {
			continue
		}
		var lowerName string
		if strings.IndexFunc(name, func(r rune) bool { return r > 127 }) >= 0 {
			// JavaScript lowercasing includes full Unicode mappings such as İ.
			lowerName = cases.Lower(language.Und).String(name)
		} else {
			lowerName = strings.ToLower(name)
		}
		path := strings.Split(lowerName, "/")
		for i, part := range path {
			// Match String.trim() for model IDs and display names.
			path[i] = strings.TrimFunc(part, func(r rune) bool { return r == '\uFEFF' || r != '\u0085' && unicode.IsSpace(r) })
		}
		id, _, _ := strings.Cut(path[len(path)-1], ":")
		runes := []rune(id)
		if region, _ := gatewayBrandRegion.FindRunesMatch(runes); region != nil {
			runes = runes[region.Length:]
		}
		if match, _ := gatewayBrandPattern.FindRunesMatch(runes); match != nil {
			for _, group := range match.Groups()[1:] {
				if len(group.Captures) != 0 {
					return group.Name
				}
			}
		}
		for i := len(path) - 2; i >= 0; i-- {
			if brand := gatewayBrandNamespaces[path[i]]; brand != "" {
				return brand
			}
		}
	}
	return gatewayUnclassifiedBrand
}
