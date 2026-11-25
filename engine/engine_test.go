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

func TestInvalidInputs(t *testing.T) {
	cases := []string{
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
