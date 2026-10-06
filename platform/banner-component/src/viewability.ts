// Viewable-impression timing, per the MRC Viewable Ad Impression
// Guidelines v2.0 (display): the share of the ad's pixels on screen must
// meet the threshold on a visible (in-focus) tab for ONE CONTINUOUS
// SECOND. Not cumulative — dropping below the threshold, or the tab
// going hidden, restarts the clock. A user click counts at once (the
// guideline's "strong user interaction"). Video backgrounds follow this
// display rule: they are part of a display ad, not a video ad.

/** MRC large-ad line: 970×250 and bigger. */
export const LARGE_AD_PIXELS = 242_500;

/** How long the threshold must hold, continuously. */
export const VIEWABLE_DWELL_MS = 1000;

/** Share of the ad's pixels that must be on screen: 30% for large ads
  * (≥ 242,500 px), 50% otherwise. */
export function viewableThreshold(width: number, height: number): number {
  return width * height >= LARGE_AD_PIXELS ? 0.3 : 0.5;
}

export interface ViewableGate {
  /** The ad is (or isn't) at/above its threshold on screen. */
  setInView(inView: boolean): void;
  /** The tab is (or isn't) visible. */
  setTabVisible(visible: boolean): void;
  /** Count now, regardless of the clock (a click). */
  fireNow(): void;
  /** Stop for good without firing (unmount). */
  dispose(): void;
}

/** Calls `onViewable` once, after `dwellMs` of continuous in-view time on
  * a visible tab, or at `fireNow()`. Never fires after `dispose()`. */
export function createViewableGate(
  onViewable: () => void,
  tabVisible: boolean,
  dwellMs: number = VIEWABLE_DWELL_MS,
): ViewableGate {
  let inView = false;
  let visible = tabVisible;
  let done = false;
  let timer: ReturnType<typeof setTimeout> | null = null;

  const stop = (): void => {
    if (timer !== null) clearTimeout(timer);
    timer = null;
  };
  const fire = (): void => {
    if (done) return;
    done = true;
    stop();
    onViewable();
  };
  const sync = (): void => {
    if (done) return;
    if (inView && visible) {
      if (timer === null) timer = setTimeout(fire, dwellMs);
    } else {
      stop(); // continuous, not cumulative: the next qualifying moment starts a fresh second
    }
  };

  return {
    setInView(v) { inView = v; sync(); },
    setTabVisible(v) { visible = v; sync(); },
    fireNow: fire,
    dispose() { done = true; stop(); },
  };
}
