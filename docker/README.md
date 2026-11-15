# Browser runtime isolation

The API image remains root-compatible for its existing DData volumes. Kubernetes
runs its dedicated browser workload as UID/GID 1000 with a custom seccomp profile.
See [browser rollout and rollback](../k8s/browser-runtime.md). The browser smoke
checks use that non-root identity and profile without changing the API runtime.
