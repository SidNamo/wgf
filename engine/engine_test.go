package engine

import "testing"

func TestRulesValid(t *testing.T) {
	rules, err := validateRules(`[{"name":"Cloudreve","localPort":5212,"targetHost":"192.168.0.230","targetPort":5212,"enabled":true}]`)
	if err != nil || len(rules) != 1 {
		t.Fatalf("want one rule: %v", err)
	}
}

func TestDuplicatePorts(t *testing.T) {
	_, err := validateRules(`[{"localPort":5212,"targetHost":"192.168.0.230","targetPort":5212,"enabled":true},{"localPort":5212,"targetHost":"192.168.0.230","targetPort":2283,"enabled":true}]`)
	if err == nil {
		t.Fatal("expected duplicate port error")
	}
}

// The unmodified TCP path also carries TLS ClientHello/SNI to the target.
// Port 443 can be configured, but Android may reject binding it at runtime.
func TestHTTPSPassthroughLocalPort443(t *testing.T) {
	rules, err := validateRules(`[{"name":"HTTPS TCP passthrough","localPort":443,"targetHost":"192.168.0.230","targetPort":443,"enabled":true,"mode":"tcp"}]`)
	if err != nil || len(rules) != 1 || rules[0].LocalPort != 443 || rules[0].Mode != "tcp" {
		t.Fatalf("expected a valid raw TCP passthrough on local 443: %v, %+v", err, rules)
	}
}

func TestInvalidInputs(t *testing.T) {
	cases := []string{
		`[{"localPort":0,"targetHost":"192.168.0.230","targetPort":443,"enabled":true}]`,
		`[{"localPort":80,"targetHost":"192.168.0.230","targetPort":5212,"enabled":true}]`,
		`[{"localPort":5212,"targetHost":"localhost","targetPort":5212,"enabled":true}]`,
		`[{"localPort":5212,"targetHost":"127.0.0.1","targetPort":5212,"enabled":true}]`,
		`[{"localPort":5212,"targetHost":"192.168.0.230","targetPort":70000,"enabled":true}]`,
		`[]`,
	}
	for _, input := range cases {
		if _, err := validateRules(input); err == nil {
			t.Errorf("expected error for %s", input)
		}
	}
}

func TestInvalidWireguardConfig(t *testing.T) {
	if _, err := parseConfig("[Interface]\nPrivateKey = no\n"); err == nil {
		t.Fatal("invalid config should be rejected")
	}
}

func TestForwardModes(t *testing.T) {
	rules, err := validateRules(`[
		{"name":"Existing TCP","localPort":5212,"targetHost":"192.168.0.230","targetPort":5212,"enabled":true},
		{"name":"NPM HTTPS","localPort":5213,"targetHost":"192.168.0.230","targetPort":443,"enabled":true,"mode":"http-https","tlsHost":"dav.example.com"}
	]`)
	if err != nil || len(rules) != 2 {
		t.Fatalf("both forwarding modes should work together: %v", err)
	}
	if rules[0].Mode != "tcp" || rules[1].Mode != "http-https" {
		t.Fatalf("TCP must remain the default: %+v", rules)
	}
}

func TestHTTPSRuleRequiresDomain(t *testing.T) {
	for _, host := range []string{"", "https://dav.example.com", "127.0.0.1", "999.999.999.999", "dav.example.com:443"} {
		input := `[{"localPort":5213,"targetHost":"192.168.0.230","targetPort":443,"enabled":true,"mode":"http-https","tlsHost":"` + host + `"}]`
		if _, err := validateRules(input); err == nil {
			t.Errorf("invalid HTTPS host %q was accepted", host)
		}
	}
}
