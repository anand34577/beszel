//go:build darwin

package agent

import (
	"log/slog"
	"os/exec"
	"regexp"
	"strconv"
	"strings"
)

func getNetworkInterfaceSpeeds() map[string]uint64 {
	result := make(map[string]uint64)
	interfaces, err := exec.Command("ifconfig", "-l").Output()
	if err != nil {
		slog.Debug("Unable to list network interfaces for link speed", "err", err)
		return result
	}
	for _, name := range strings.Fields(string(interfaces)) {
		output, err := exec.Command("ifconfig", name).Output()
		if err != nil {
			slog.Debug("Unable to read interface link speed", "name", name, "err", err)
			continue
		}
		if speed := parseDarwinMediaSpeed(string(output)); speed > 0 {
			result[name] = speed
		}
	}
	return result
}

// darwinMediaSpeedPattern matches the numeric speed embedded in an ifconfig
// media descriptor, e.g. "1000baseT" (1000 Mbps) or "10Gbase-T" (10 Gbps).
// Parsing the number directly out of the "<n>[g]base" token avoids guessing
// from an ordered list of known prefixes, which misclassifies any token that
// happens to share a prefix with a shorter, unrelated entry.
var darwinMediaSpeedPattern = regexp.MustCompile(`(\d+(?:\.\d+)?)(g)?base`)

// parseDarwinMediaSpeed extracts the negotiated link speed, in bits per
// second, from the active "media:" line of `ifconfig <name>` output. Only the
// active media line is considered so a "supported media" listing of every
// negotiable speed can't be mistaken for the current one.
func parseDarwinMediaSpeed(output string) uint64 {
	for line := range strings.SplitSeq(output, "\n") {
		trimmed := strings.TrimSpace(strings.ToLower(line))
		if !strings.HasPrefix(trimmed, "media:") {
			continue
		}
		return parseDarwinMediaToken(trimmed)
	}
	return 0
}

func parseDarwinMediaToken(mediaLine string) uint64 {
	match := darwinMediaSpeedPattern.FindStringSubmatch(mediaLine)
	if match == nil {
		return 0
	}
	number, err := strconv.ParseFloat(match[1], 64)
	if err != nil || number <= 0 {
		return 0
	}
	// A "g" directly before "base" (e.g. "10gbase-t") means the number is in
	// Gbps; otherwise media tokens report Mbps (e.g. "1000baseT" = 1000 Mbps).
	if match[2] == "g" {
		return uint64(number * 1_000_000_000)
	}
	return uint64(number * 1_000_000)
}
