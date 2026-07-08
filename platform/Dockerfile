# syntax=docker/dockerfile:1
#
# Go platform / dashboard BFF (platform/cmd/server). Embeds the static
# bundle + templates via go:embed (embed.go). The dashboard's Tailwind
# CSS is compiled IN-IMAGE below from the templates in this build, so a
# template class change can never ship unstyled; the committed
# static/tailwind.css only serves local `go run` (scripts/build-tailwind.sh
# refreshes it). The creative-designer bundle stays a committed artifact —
# rebuild it and re-run the fanout before building this image
# (see scripts/run-dashboard.sh).
#
# Build (from repo root):
#   docker build -f platform/Dockerfile -t promovolve/platform:dev platform

# ---- css stage ----
# Pin the same tailwindcss version as scripts/build-tailwind.sh so local
# and in-image output can't drift.
FROM node:20-alpine AS css
WORKDIR /css
RUN npm install tailwindcss@3.4.17
COPY tailwind.config.js tailwind.input.css ./
COPY templates ./templates
COPY static/passkey.js ./static/passkey.js
RUN npx tailwindcss -c tailwind.config.js -i tailwind.input.css -o tailwind.css --minify

# ---- build stage ----
FROM golang:1.26 AS build
WORKDIR /src

COPY go.mod go.sum ./
RUN go mod download

COPY . .
# Freshly compiled CSS replaces the committed copy BEFORE go:embed runs.
COPY --from=css /css/tailwind.css static/tailwind.css
# Static binary so it runs on a distroless base.
RUN CGO_ENABLED=0 GOOS=linux go build -o /out/server ./cmd/server

# ---- runtime stage ----
FROM gcr.io/distroless/static-debian12:nonroot
COPY --from=build /out/server /server

EXPOSE 9090
ENTRYPOINT ["/server"]
