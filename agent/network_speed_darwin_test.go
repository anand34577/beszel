//go:build darwin

package agent

import "testing"

func TestParseDarwinMediaSpeed(t *testing.T) {
	cases := []struct {
		name   string
		output string
		want   uint64
	}{
		{
			name:   "gigabit",
			output: "media: autoselect (1000baseT <full-duplex>)\nstatus: active",
			want:   1_000_000_000,
		},
		{
			name:   "ten gigabit",
			output: "media: autoselect (10Gbase-T <full-duplex>)\nstatus: active",
			want:   10_000_000_000,
		},
		{
			name:   "multi-gig 2.5g reported in mbps",
			output: "media: autoselect (2500baseT <full-duplex>)\nstatus: active",
			want:   2_500_000_000,
		},
		{
			name:   "hundred megabit",
			output: "media: autoselect (100baseTX <full-duplex>)\nstatus: active",
			want:   100_000_000,
		},
		{
			name:   "ten megabit not shadowed by hundred/thousand prefixes",
			output: "media: autoselect (10baseT/UTP <half-duplex>)\nstatus: active",
			want:   10_000_000,
		},
		{
			name:   "no active media",
			output: "media: none\nstatus: inactive",
			want:   0,
		},
		{
			name:   "supported media list ignored, only active media line used",
			output: "supported media: 1000baseT 10Gbase-T\nmedia: autoselect (100baseTX <full-duplex>)\nstatus: active",
			want:   100_000_000,
		},
	}

	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			if got := parseDarwinMediaSpeed(c.output); got != c.want {
				t.Fatalf("parseDarwinMediaSpeed() = %d, want %d", got, c.want)
			}
		})
	}
}
