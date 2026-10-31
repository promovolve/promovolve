# Browser worker rollout

Kubernetes uses one `promovolve-browser` Deployment for LP analysis and banner
screenshots. Only this pod has the `browser` role, UID/GID 1000, dropped
capabilities, no privilege escalation, and the custom Localhost seccomp profile.
It uses the same API image digest, but does not initialize API repositories,
serving actors, HTTP routes or durable DData. It has no PVC. Only the four R2
keys are selected from the existing Secret; JDBC, Gemini and API keys are not
injected. Prefer bucket-scoped R2 credentials. This is workload separation,
not a separate trust domain: it still joins Pekko and can access the network.

The API/singleton image stays root-compatible and existing DData ownership is
unchanged. CI only swaps images (including the browser if already installed).
The browser uses the same `promovolve/api` kustomize image entry, so the existing
API digest pin-back also records its desired image. No separate image build is
needed. Profile installation and manifest changes remain operator actions.

## Existing GKE cluster: staged cutover

1. Merge/build the candidate and wait for image-only CI and its digest pin-back
   PR to complete. Use that updated checkout. Do **not** apply the new manifests
   against an old image that does not implement the browser-only startup path.
   Old manifests keep their local browser path (`LP_WORKERS_ENABLED` defaults
   false in the image), including their existing `CHROMIUM_NO_SANDBOX=true`
   override. This compatibility path exists only for local dev and migration;
   the committed Kubernetes API overlays enable the remote path.
2. Run `k8s-gke/setup.sh --browser-only`. It installs/waits for the seccomp
   DaemonSet and applies just the browser Deployment and its shared config/
   Secret dependencies. It does not switch API roles or dispatch. Confirm the
   browser node has joined the existing cluster, its imageID is the intended
   digest, and its environment contains no JDBC/API/Gemini credentials.
3. Validate sandboxed Chromium on the candidate before cutover using
   `scripts/docker-smoke/BrowserSmoke.java` (copy a compiled class into `/tmp`
   and run Java with `/app/lib/*:/tmp`). A Ready pod does not prove browser
   functionality or R2 upload. Record logs, settings, node/runtime versions,
   installed profile checksum and imageID.
4. Run `k8s-gke/setup.sh --deploy-only` to remove the old `crawler` role from
   API pods and activate remote LP analysis and banner rendering. Check one
   real LP analysis, progress/screenshot output, and one banner publish/draft
   render, including R2 objects and resulting DB asset records. Confirm API
   pods do not launch Chromium. Keep #177 open until live checks are recorded.

The new LP shard type is `browser-lp-worker`; it deliberately does not reuse
the old `lp-worker` type while old and new images coexist. Keep the browser
on the same code revision as API clients; new banner messages must not be sent
to old worker images. Normal CI updates both and waits for their rollouts.
Absent browser workers produce bounded ask failures (LP 120s, banner 90s), not
a fallback to Chromium on an API pod. Banner failures retain the existing
CreativeProcessor fail-soft behavior; inspect logs, not only HTTP success.

## Rollback

Before applying anything, save the live API/singleton/browser image digests,
replica counts, API mounted role config, and runtime env overrides. If the
cutover fails, restore the previous API role/config with remote dispatch off
**before** rolling API images back to code without worker support. Leave the
browser workload running until API local LP/banner processing is confirmed;
then remove the browser Deployment if it is no longer needed. Do not change
PVC ownership or delete DData to recover. Coordinate rollback with CI so an
in-flight image update cannot overwrite it.

## Profile updates and node replacement

The root installer writes only `/var/lib/kubelet/seccomp/promovolve`; it is
not privileged and drops all capabilities. It tolerates all taints, while the
browser workload does not. Setup waits for installer readiness and records
the profile SHA on the browser pod template to restart processes on profile
changes. A replacement node may briefly reject browser creation until its
installer finishes; inspect installer readiness/events rather than disabling
sandboxing. Deleting the DaemonSet alone does not delete host files.

Use setup helpers rather than a raw full-stack apply. Local helpers also
install/wait for the profile. A profile checksum or Ready status alone is not
proof of Chromium's additional renderer sandbox; retain the positive and
disabled-renderer-seccomp negative smoke tests.

## Local checks

```sh
python3 -m pip install 'PyYAML>=6,<7'
python3 scripts/test-browser-runtime.py
sbt scalafmtCheckAll "api/compile" \
  "core/testOnly promovolve.browser.BannerWorkerSpec promovolve.browser.LPWorkerSpec promovolve.browser.LPWorkerSerializationSpec" \
  "api/testOnly promovolve.api.BrowserRuntimeSpec"
python3 scripts/smoke-docker.py api YOUR_ARM64_API_IMAGE
```

Manifest tests render dummy secrets in a temporary directory; they do not
apply anything. The prior disposable GKE comparison validated the custom
profile, not this new topology, remote worker paths or the target cluster.
