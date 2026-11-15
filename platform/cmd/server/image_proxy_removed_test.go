package main

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// #181: unused arbitrary-URL proxy must stay removed; designer keeps /proxy/asset/.
func TestImageProxyRemoved(t *testing.T) {
	mainGo, err := os.ReadFile("main.go")
	if err != nil {
		t.Fatal(err)
	}
	src := string(mainGo)
	if strings.Contains(src, "/api/image-proxy") {
		t.Fatal("main.go must not register /api/image-proxy")
	}
	if !strings.Contains(src, "/proxy/asset/") {
		t.Fatal("main.go must still register /proxy/asset/ for the creative designer")
	}

	handlerGo, err := os.ReadFile(filepath.Join("..", "..", "internal", "handler", "handler.go"))
	if err != nil {
		t.Fatal(err)
	}
	if strings.Contains(string(handlerGo), "func (h *Handler) ImageProxy(") {
		t.Fatal("ImageProxy handler must stay removed")
	}
}
