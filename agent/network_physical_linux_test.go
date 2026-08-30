//go:build linux

package agent

import (
	"os"
	"path/filepath"
	"testing"
)

func TestIsPhysicalNetworkInterfaceAt(t *testing.T) {
	sysfsRoot := t.TempDir()

	// Real sysfs backs a physical NIC's "device" entry with a symlink into
	// /sys/devices, not a plain directory - use a symlink here so the test
	// actually exercises the same lookup os.Stat performs in production.
	pciDevice := filepath.Join(sysfsRoot, "devices", "pci0000:00", "0000:00:1f.6")
	if err := os.MkdirAll(pciDevice, 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.MkdirAll(filepath.Join(sysfsRoot, "eth0"), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.Symlink(pciDevice, filepath.Join(sysfsRoot, "eth0", "device")); err != nil {
		t.Fatal(err)
	}

	if err := os.MkdirAll(filepath.Join(sysfsRoot, "veth1001i0"), 0o755); err != nil {
		t.Fatal(err)
	}

	if !isPhysicalNetworkInterfaceAt(sysfsRoot, "eth0") {
		t.Fatal("expected symlinked device-backed interface to be included")
	}
	if isPhysicalNetworkInterfaceAt(sysfsRoot, "veth1001i0") {
		t.Fatal("expected software-only interface to be excluded")
	}
	if isPhysicalNetworkInterfaceAt(sysfsRoot, "lo") {
		t.Fatal("expected interface without a device path to be excluded")
	}
}
