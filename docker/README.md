# API runtime isolation

The API image runs as UID/GID 1000. Chromium explicitly enables its sandbox.
For Docker, pass `--cap-drop ALL --security-opt no-new-privileges` and
`--security-opt seccomp=docker/chromium-seccomp.json`. The host must allow
unprivileged user namespaces. Do not replace the profile with `unconfined` or
add `SYS_ADMIN` to work around launch failures.

`chromium-seccomp.json` is an OCI profile derived from the
[Playwright v1.62.0 profile](https://github.com/microsoft/playwright/blob/v1.62.0/utils/docker/seccomp_profile.json)
(Apache-2.0). It retains the unconditional and architecture-specific allowlists,
omits capability-dependent and ptrace exceptions, and allows `chroot` for the
sandbox's nested user namespace. `clone3` returns `ENOSYS` so libc can fall back
to `clone`. Docker and Kubernetes consume the same profile; Docker-specific
`includes`/`excludes` rules must not be copied into an OCI profile.

## Kubernetes deployments

Merging to `main` runs the migration as part of Deploy. No separate pre-merge
preparation or post-deploy manifest apply is required:

1. Deploy installs the node-local seccomp profile and waits for its DaemonSet.
2. `k8s/roll-api.sh` updates the image digest, UID/GID, fsGroup and container
   security settings in a single Pod template patch, singleton first, then API.
   Each tier must become ready before the next is changed. Kubelet applies
   `fsGroup: 1000` before starting the new non-root container; the storage driver
   must support fsGroup. Existing DData PVCs and mounts are preserved.
3. The explicit `CHROMIUM_NO_SANDBOX=false` container setting overrides any
   sandbox-disable value left in an older ConfigMap. Subsequent deploys use the
   same idempotent rollout path.

If profile installation fails, application templates are left unchanged. A failed
singleton rollout stops before changing the API tier. A failed API rollout is
reported by Deploy; it does not trigger automatic rollback or delete data.

The installer writes only `/var/lib/kubelet/seccomp/promovolve` and provisions
replacement nodes. Clusters with a different kubelet root need a matching
hostPath. Deploy needs permission to apply ConfigMaps/DaemonSets and patch/watch
StatefulSets in the `promovolve` namespace. A missing profile prevents application containers from starting;
kubelet retries until the installer has placed it. The target is GKE Standard;
HostPath/root restrictions require a different provisioning mechanism.

For a manual image release, use an explicit context and immutable digest:

```sh
k8s/roll-api.sh YOUR_CONTEXT registry.example/api@sha256:DIGEST
```

For rollback to an old root-only image, restore its container UID/security
settings together with its image and explicit sandbox-disable setting. Preserve
fsGroup and the DData claims; changing the image alone cannot start the old
launcher under UID 1000. Keep the profile installer while any pod references it.

For a Docker volume previously written by root, stop its writer and back it up,
then grant GID 1000 read/write access to directories and files before mounting it
in the new image. Image ownership does not change mounted volume permissions.

Local root-only development may explicitly set `CHROMIUM_NO_SANDBOX=true` for
trusted fixtures. Deployment must never set it to `true`; isolation smoke tests
leave it unset. This is browser hardening, not isolation of the browser from all API
credentials in the shared container.
