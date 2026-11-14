// Preview chrome labels follow the host page's <html lang> (the Designer
// and the publisher approval page both set it from the user's language).
// Kept out of magazine-preview.ts so it imports without a DOM.
export function previewLabels(lang: string): { ad: string; pc: string; mobile: string } {
  return /^ja(-|$)/i.test(lang)
    ? { ad: "広告", pc: "PC", mobile: "モバイル" }
    : { ad: "Advertisement", pc: "PC", mobile: "Mobile" };
}
