package platform

import "embed"

//go:embed templates
var Templates embed.FS

//go:embed static
var Static embed.FS

// Help holds the in-app help pages. These are COPIES of the canonical user
// guides in docs/guides/ (the Docker build context is platform/ only, so the
// originals can't be embedded); help_sync_test.go fails if they drift, and
// scripts/sync-help.sh refreshes them.
//
//go:embed help/*.md
var Help embed.FS
