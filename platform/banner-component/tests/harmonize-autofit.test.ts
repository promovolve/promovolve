// @vitest-environment jsdom
//
// harmonizeAutofit unifies each field's fitted font size across the
// reader's pages — every member of a field group is pinned to the
// group's SMALLEST size, so pages read consistently and nothing
// overflows (the min is the size that fit the tightest page).

import { describe, expect, it } from "vitest";
import { harmonizeAutofit } from "../src/motion";

const el = (field: string, fontSize: string): HTMLElement => {
  const d = document.createElement("div");
  d.dataset.autofit = "1";
  d.dataset.field = field;
  d.style.fontSize = fontSize;
  return d;
};

describe("harmonizeAutofit", () => {
  it("pins every member of a field group to the smallest fitted size", () => {
    const root = document.createElement("div");
    root.append(el("headline", "10cqmax"), el("headline", "6cqmax"), el("headline", "8cqmax"));
    harmonizeAutofit(root);
    for (const e of root.querySelectorAll<HTMLElement>('[data-field="headline"]')) {
      expect(e.style.fontSize).toBe("6cqmax");
    }
  });

  it("groups independently by field", () => {
    const root = document.createElement("div");
    root.append(el("headline", "10cqmax"), el("headline", "7cqmax"), el("body", "4cqmax"), el("body", "3cqmax"));
    harmonizeAutofit(root);
    root.querySelectorAll<HTMLElement>('[data-field="headline"]').forEach((e) => expect(e.style.fontSize).toBe("7cqmax"));
    root.querySelectorAll<HTMLElement>('[data-field="body"]').forEach((e) => expect(e.style.fontSize).toBe("3cqmax"));
  });

  it("leaves a single-member group untouched", () => {
    const root = document.createElement("div");
    const only = el("headline", "9cqmax");
    root.append(only);
    harmonizeAutofit(root);
    expect(only.style.fontSize).toBe("9cqmax");
  });

  it("ignores elements missing data-field or data-autofit", () => {
    const root = document.createElement("div");
    const noField = document.createElement("div");
    noField.dataset.autofit = "1";
    noField.style.fontSize = "12cqmax";
    const noAuto = el("headline", "5cqmax");
    delete noAuto.dataset.autofit;
    root.append(noField, noAuto);
    harmonizeAutofit(root);
    expect(noField.style.fontSize).toBe("12cqmax");
    expect(noAuto.style.fontSize).toBe("5cqmax");
  });
});
