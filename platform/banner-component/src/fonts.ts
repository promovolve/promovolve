import type { BannerConfig } from "./types";

export function fontMain(cfg: BannerConfig): string {
  return cfg.font === "serif"
    ? "'Noto Serif JP', Georgia, serif"
    : "-apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif";
}

export function fontUI(): string {
  return "-apple-system, BlinkMacSystemFont, 'Segoe UI', 'Helvetica Neue', sans-serif";
}
