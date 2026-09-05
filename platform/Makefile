.DEFAULT_GOAL := help

.PHONY: help check build vet test build-server

# Exclude npm snippets without omitting new first-party packages.
# Discover in recipes so help needs no Go and errors propagate.
select_packages = pkgs=$$(go list ./...) && \
	pkgs=$$(printf '%s\n' "$$pkgs" | grep -v -E '/(node_modules|\.vite)/') && test -n "$$pkgs"

help:
	@printf '%s\n' \
		'Available targets:' \
		'  check         Build, vet, and race-test first-party Go packages' \
		'  build         Build first-party Go packages' \
		'  vet           Vet first-party Go packages' \
		'  test          Race-test first-party Go packages' \
		'  build-server  Build the dashboard binary as ./server' \
		'  help          Show this help (default)'

check: build vet test

build:
	$(select_packages) && go build $$pkgs

vet:
	$(select_packages) && go vet $$pkgs

test:
	$(select_packages) && go test -race $$pkgs

build-server:
	go build -o ./server ./cmd/server
