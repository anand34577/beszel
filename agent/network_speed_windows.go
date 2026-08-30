//go:build windows

package agent

import (
	"encoding/json"
	"log/slog"
	"os/exec"
)

type windowsNetworkAdapter struct {
	Name            string `json:"Name"`
	NetConnectionID string `json:"NetConnectionID"`
	Speed           uint64 `json:"Speed"`
}

func getNetworkInterfaceSpeeds() map[string]uint64 {
	// Win32_NetworkAdapter exposes the negotiated speed in bits per second and
	// matches the connection name returned by net.Interfaces on Windows.
	command := "Get-CimInstance Win32_NetworkAdapter | Select-Object Name,NetConnectionID,Speed | ConvertTo-Json -Compress"
	out, err := exec.Command("powershell.exe", "-NoLogo", "-NoProfile", "-NonInteractive", "-Command", command).Output()
	if err != nil {
		slog.Debug("Unable to query network adapter link speeds", "err", err)
		return nil
	}

	var raw json.RawMessage
	if err := json.Unmarshal(out, &raw); err != nil {
		slog.Debug("Unable to parse network adapter link speeds", "err", err)
		return nil
	}
	var adapters []windowsNetworkAdapter
	if len(raw) > 0 && raw[0] == '{' {
		var adapter windowsNetworkAdapter
		if err := json.Unmarshal(raw, &adapter); err != nil {
			slog.Debug("Unable to parse network adapter link speeds", "err", err)
			return nil
		}
		adapters = []windowsNetworkAdapter{adapter}
	} else if err := json.Unmarshal(raw, &adapters); err != nil {
		slog.Debug("Unable to parse network adapter link speeds", "err", err)
		return nil
	}

	result := make(map[string]uint64, len(adapters))
	for _, adapter := range adapters {
		if adapter.Speed > 0 {
			if adapter.NetConnectionID != "" {
				result[adapter.NetConnectionID] = adapter.Speed
			}
			if adapter.Name != "" {
				result[adapter.Name] = adapter.Speed
			}
		}
	}
	return result
}
