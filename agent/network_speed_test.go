package agent

import (
	"testing"

	"github.com/henrygd/beszel/internal/entities/system"
)

func TestApplyNetworkInterfaceSpeeds(t *testing.T) {
	newAgent := func() *Agent {
		return &Agent{
			systemDetails: system.Details{
				NetworkInterfaces: []system.NetworkInterface{
					{Name: "eth0", SpeedBitsPerS: 100_000_000},
					{Name: "eth1", SpeedBitsPerS: 1_000_000_000},
				},
			},
		}
	}

	t.Run("updates changed speed and marks dirty", func(t *testing.T) {
		a := newAgent()
		a.applyNetworkInterfaceSpeeds(map[string]uint64{"eth0": 1_000_000_000})

		if got := a.systemDetails.NetworkInterfaces[0].SpeedBitsPerS; got != 1_000_000_000 {
			t.Fatalf("eth0 speed = %d, want 1_000_000_000", got)
		}
		if !a.detailsDirty {
			t.Fatal("expected detailsDirty to be set after a speed change")
		}
	})

	t.Run("no-op when speed unchanged", func(t *testing.T) {
		a := newAgent()
		a.applyNetworkInterfaceSpeeds(map[string]uint64{"eth0": 100_000_000, "eth1": 1_000_000_000})

		if a.detailsDirty {
			t.Fatal("expected detailsDirty to stay false when nothing changed")
		}
	})

	t.Run("ignores unknown interfaces and empty results", func(t *testing.T) {
		a := newAgent()
		a.applyNetworkInterfaceSpeeds(map[string]uint64{"wlan0": 500_000_000})
		if a.detailsDirty {
			t.Fatal("expected detailsDirty to stay false for an interface not in the inventory")
		}

		a.applyNetworkInterfaceSpeeds(nil)
		if a.detailsDirty {
			t.Fatal("expected detailsDirty to stay false when no speeds are reported (e.g. command failed)")
		}
	})
}
