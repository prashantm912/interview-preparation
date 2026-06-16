# Docker & Containers

A staff-level interview guide to containers: how images and layers actually work, how to build small/secure/fast images with BuildKit and multi-stage builds, how networking and storage are wired, and how to operate and troubleshoot containers in production. Knowledge current through 2026 (Docker Engine 27+, BuildKit default, OCI v1.1, distroless/Chainguard era).

[← Back to master index](../README.md)

## Table of Contents

- [🟢 Basic (0–2 yrs)](#-basic-02-yrs)
- [🟡 Intermediate (3–7 yrs)](#-intermediate-37-yrs)
- [🟠 Advanced (8–12 yrs)](#-advanced-812-yrs)
- [🔴 Expert (15+ yrs)](#-expert-15-yrs)
- [✅ Key Takeaways](#-key-takeaways)
- [⚠️ Common Pitfalls](#-common-pitfalls)
- [📚 Further Reading](#-further-reading)

---

## 🟢 Basic (0–2 yrs)

### Q1. [Theory] What is a container and how does it differ from a virtual machine?

A container is an isolated **process** (or process tree) running on the host kernel, with its filesystem, network, PID, mount, and other namespaces sandboxed by the Linux kernel and resource-limited by cgroups. A VM, by contrast, runs a **full guest operating system** on top of a hypervisor, including its own kernel.

The key difference is the boundary of isolation. Containers share the host kernel, so they are lightweight (megabytes, start in milliseconds) but the isolation is weaker — a kernel exploit can cross the boundary. VMs virtualize hardware and ship a whole kernel, so they isolate strongly but cost gigabytes and seconds-to-minutes to boot. In practice you choose containers for density, fast deploys, and dev/prod parity; VMs (or micro-VMs like Firecracker/Kata) when you need hard multi-tenant isolation.

```
   CONTAINERS                          VIRTUAL MACHINES
 ┌──────┬──────┬──────┐             ┌────────┬────────┐
 │App A │App B │App C │             │ App A  │ App B  │
 ├──────┼──────┼──────┤             ├────────┼────────┤
 │ bins/libs (per ctr)│             │bins/lib│bins/lib│
 ├──────┴──────┴──────┤             ├────────┼────────┤
 │   Docker Engine    │             │Guest OS│Guest OS│  ← full kernels
 ├────────────────────┤             ├────────┴────────┤
 │   Host Kernel      │  shared     │   Hypervisor    │
 ├────────────────────┤             ├─────────────────┤
 │     Hardware       │             │    Hardware     │
 └────────────────────┘             └─────────────────┘
```

### Q2. [Theory] What is a Docker image, and what is a layer?

An image is a read-only template — a stack of filesystem **layers** plus a JSON config (entrypoint, env, exposed ports, etc.). Each layer is a tarball representing a set of filesystem changes (added/modified/deleted files) relative to the layer beneath it. When you run an image, Docker stacks these layers using a **union filesystem** (OverlayFS by default) and adds a thin writable layer on top — that writable layer is the container.

Layers are content-addressable: each is identified by a SHA-256 digest of its contents. This is why layers are **shared and cached** — if two images use the same `ubuntu:24.04` base, that base layer is stored once on disk and pulled once over the network. Most Dockerfile instructions (`RUN`, `COPY`, `ADD`) create a new layer; metadata-only instructions (`ENV`, `LABEL`, `CMD`, `WORKDIR`, `EXPOSE`) do not add filesystem layers.

### Q3. [Theory] What is the difference between an image and a container?

An image is the immutable, on-disk artifact (the "class"); a container is a running or stopped **instance** of that image (the "object"). You can launch many containers from one image, each with its own writable layer, network identity, and process state. Stopping a container does not delete it — its writable layer persists until you `docker rm` it. Deleting the image requires that no containers reference it. The mental model: `docker run` = `image` + `writable layer` + `runtime config (env, ports, mounts)`.

### Q4. [Practical] Write a minimal Dockerfile for a Node.js web service and explain each line.

```dockerfile
# Pin a specific, slim base for reproducibility and small size
FROM node:22-slim

# All subsequent paths are relative to /app
WORKDIR /app

# Copy manifests FIRST so dependency install is cached
# separately from source changes (cache optimization)
COPY package.json package-lock.json ./

# Deterministic install from the lockfile; omit dev deps in prod
RUN npm ci --omit=dev

# Now copy the application source
COPY . .

# Documents the port (does NOT publish it)
EXPOSE 3000

# Run as the built-in non-root 'node' user
USER node

# Default process; exec form so signals reach Node directly
CMD ["node", "server.js"]
```

The ordering matters: by copying `package.json`/`package-lock.json` before the source, the expensive `npm ci` layer is reused whenever only app code changes, dramatically speeding rebuilds.

### Q5. [Theory] What is the difference between `ENTRYPOINT` and `CMD`?

`ENTRYPOINT` defines the executable that always runs; `CMD` provides **default arguments** (or a default command if no `ENTRYPOINT` is set). When both are present, `CMD` values are appended as arguments to `ENTRYPOINT`. Crucially, arguments you pass to `docker run <image> <args>` **override `CMD`** but are appended to `ENTRYPOINT`.

```
ENTRYPOINT ["ping"]   CMD ["localhost"]
  docker run img            → ping localhost
  docker run img 8.8.8.8    → ping 8.8.8.8   (CMD overridden)
```

Use `ENTRYPOINT` for a fixed binary (making the image behave like a command) and `CMD` for the default arguments users will commonly override. Always prefer the **exec form** (`["bin","arg"]`) over the shell form (`bin arg`) — shell form wraps the process in `/bin/sh -c`, which becomes PID 1 and swallows signals like `SIGTERM`, breaking graceful shutdown.

### Q6. [Practical] What is the difference between `COPY` and `ADD`?

`COPY` does exactly one thing: copy local files/directories into the image. `ADD` does that **plus** two magic behaviors: it auto-extracts local tar archives, and it can fetch remote URLs. The community consensus (and Docker's official guidance) is to **always prefer `COPY`** for predictability. Use `ADD` only when you deliberately want local tar auto-extraction (e.g., `ADD rootfs.tar.gz /`). For remote downloads, prefer `RUN curl`/`wget` with an explicit checksum, or `ADD --checksum=sha256:...` (supported in modern BuildKit) so the build fails on tampered content.

### Q7. [Practical] How do you keep data when a container is removed? Volumes vs bind mounts.

A container's writable layer is destroyed on `docker rm`. To persist data, mount external storage. Two main options:

- **Volumes** — managed by Docker, stored under `/var/lib/docker/volumes`. Portable, work the same across hosts/OSes, support drivers (NFS, cloud block storage), and are the recommended way to persist database data. Created via `docker volume create` or implicitly by `-v name:/path`.
- **Bind mounts** — map a specific **host path** into the container (`-v /host/src:/app`). The host directory's contents shadow the container path. Great for live-reloading source in development, but tightly couples the container to the host's directory layout and can introduce permission/SELinux issues.

There is also `tmpfs` (in-memory, never touches disk — useful for secrets or scratch space).

```bash
docker volume create pgdata
docker run -v pgdata:/var/lib/postgresql/data postgres:17   # volume
docker run -v "$PWD/src":/app node:22-slim                  # bind mount (dev)
docker run --tmpfs /tmp:size=64m alpine                      # tmpfs
```

### Q8. [Theory] What does `docker run -p 8080:80` mean, and what is `EXPOSE`?

`-p 8080:80` publishes a port: it maps **host** port 8080 to **container** port 80, so traffic to the host on 8080 is forwarded into the container. The format is `host:container`. `EXPOSE 80` in a Dockerfile is purely **documentation/metadata** — it declares the intended port but does not publish anything. You still need `-p` (or `-P` to auto-publish all exposed ports to random host ports) for external access. A common beginner bug: adding `EXPOSE` and expecting the service to be reachable without `-p`.

---

## 🟡 Intermediate (3–7 yrs)

### Q9. [Theory] Explain Docker's layer/build cache and what invalidates it.

BuildKit caches the result of each instruction keyed by the instruction text **and** its inputs. On rebuild it walks instructions top-to-bottom and reuses a cached layer as long as nothing affecting it has changed; the **first** changed instruction busts the cache for itself and **every instruction after it**. For `RUN`, the cache key is the command string (BuildKit does not inspect what the command actually downloads — so `apt-get` may use a stale cache). For `COPY`/`ADD`, the key includes a checksum of the copied files' contents.

The practical rule: **order instructions from least- to most-frequently-changing.** Put base image, system packages, and dependency installs near the top; copy application source near the bottom. The classic optimization is copying the dependency manifest and installing deps before copying the rest of the code.

```
Dockerfile order        Cache behavior on a code-only change
─────────────────       ─────────────────────────────────────
FROM node:22-slim   ──►  HIT  (unchanged)
COPY package*.json  ──►  HIT  (manifest unchanged)
RUN npm ci          ──►  HIT  ← deps NOT reinstalled (fast!)
COPY . .            ──►  MISS (source changed) ─┐
RUN npm run build   ──►  MISS                    ├ rebuilt
CMD [...]           ──►  rebuilt                 ┘
```

### Q10. [Coding] Convert a naive single-stage Go Dockerfile into a multi-stage build.

**Problem:** A single-stage Go image ships the entire toolchain (compiler, source, modules cache) — often 800 MB+. Produce a tiny runtime image containing only the compiled binary.

**Naive (brute-force) version:**

```dockerfile
FROM golang:1.23
WORKDIR /app
COPY . .
RUN go build -o /app/server ./cmd/server
CMD ["/app/server"]
# ~850 MB: includes Go toolchain, source, build cache
```

**Optimal — multi-stage with a distroless runtime:**

```dockerfile
# ---- Build stage ----
FROM golang:1.23 AS build
WORKDIR /src
# Cache modules separately from source
COPY go.mod go.sum ./
RUN go mod download
COPY . .
# Static binary: no libc dependency, so it runs on a scratch/distroless base
RUN CGO_ENABLED=0 GOOS=linux go build -ldflags="-s -w" -o /out/server ./cmd/server

# ---- Runtime stage ----
FROM gcr.io/distroless/static:nonroot
COPY --from=build /out/server /server
USER nonroot:nonroot
EXPOSE 8080
ENTRYPOINT ["/server"]
# ~10-15 MB: only the binary + minimal distroless files
```

- **Time:** Slightly higher first build (two stages) but better cache reuse on rebuilds; `go mod download` layer is reused when only source changes.
- **Space:** ~850 MB → ~12 MB (≈70× smaller). Smaller images pull faster, reduce attack surface, and lower registry/storage cost.
- **Edge cases:** If you need CGO (e.g., SQLite), `CGO_ENABLED=0` won't work — use `distroless/base` (has glibc) instead of `static`, or compile statically against musl. `scratch` has no CA certs or `/etc/passwd`; distroless includes both, which is why it's preferred for binaries that make HTTPS calls.

### Q11. [Coding] Write a multi-stage Dockerfile for a Java Spring Boot app that uses layered JARs.

**Problem:** A fat Spring Boot JAR changes entirely on every code change, so re-pulling/re-pushing the whole JAR layer is wasteful. Use Spring Boot's **layered JAR** feature so dependencies (rarely changed) and application classes (frequently changed) land in separate image layers.

```dockerfile
# ---- Stage 1: extract the layered JAR ----
FROM eclipse-temurin:21-jdk AS builder
WORKDIR /app
COPY target/app.jar app.jar
# Spring Boot 2.3+ layertools splits the JAR into ordered layers
RUN java -Djarmode=layertools -jar app.jar extract

# ---- Stage 2: assemble runtime in cache-friendly order ----
FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app
# Copy least- to most-frequently-changing layers
COPY --from=builder /app/dependencies/ ./
COPY --from=builder /app/spring-boot-loader/ ./
COPY --from=builder /app/snapshot-dependencies/ ./
COPY --from=builder /app/application/ ./          # your code = top layer
RUN useradd -r -u 1001 appuser
USER appuser
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
```

- **Why it helps:** When you change application code, only the small `application/` layer is rebuilt and re-pushed; the hundreds of MB of dependency layers stay cached. On a registry, repeated deploys push only kilobytes.
- **Version note:** Java 17/21 use `org.springframework.boot.loader.launch.JarLauncher` (Spring Boot 3.2+); older Spring Boot 2 uses `org.springframework.boot.loader.JarLauncher`. Use a JRE (not JDK) for runtime to save ~200 MB. **Time/Space:** image stays similar in total size but deploy delta drops from ~250 MB to a few MB per code change.
- **Edge case:** If you build inside Docker (no pre-built JAR), add a Maven/Gradle stage first with cache mounts (see Q22).

### Q12. [Practical] How do you systematically reduce image size?

Approach it in layers of impact:

1. **Pick a smaller base.** `node:22` (~1 GB) → `node:22-slim` (~200 MB) → distroless or Alpine (~70–120 MB). Match the base to the language/runtime — distroless for compiled binaries, slim for interpreted runtimes that need a shell/package manager occasionally.
2. **Multi-stage builds.** Keep compilers, dev dependencies, and intermediate artifacts in build stages; copy only the final artifact into a minimal runtime stage.
3. **Combine and clean within a single `RUN`.** `apt-get update && apt-get install ... && rm -rf /var/lib/apt/lists/*` in one layer — cleanup in a *separate* `RUN` doesn't shrink anything because the deleted files still exist in the earlier layer.
4. **Use `.dockerignore`** to exclude `.git`, `node_modules`, build output, secrets, and test fixtures from the build context. This speeds builds and avoids accidentally baking secrets into the image.
5. **Avoid `latest` and bloated tools.** Pin versions; don't install `vim`/`curl` "just in case" in production images.

**Real-world case:** A team at a fintech shrank a Python image from 1.2 GB to 180 MB by switching `python:3.12` → `python:3.12-slim`, moving compilation of native wheels to a build stage, and adding a `.dockerignore`. Pull time on a 200-node cluster dropped from ~40s to ~6s per node, cutting rolling-deploy time by minutes and reducing CVE count by ~80% (fewer OS packages = smaller attack surface).

### Q13. [Theory] Compare Docker's default network drivers: bridge, host, none, overlay, macvlan.

```
bridge   : default for standalone containers. Each container gets a private IP on
           a virtual L2 bridge (docker0). NAT to the outside; port-publish via -p.
host     : container shares the host's network namespace directly — no isolation,
           no NAT, lowest latency. -p is ignored. Linux-only (semantics differ on
           Docker Desktop). Use for high-throughput or when binding many ports.
none     : no networking at all (loopback only). Use for batch jobs that need no net.
overlay  : multi-host networking for Swarm/clustered setups. Creates a VXLAN-based
           virtual L2 network spanning hosts so containers on different nodes talk
           as if on one LAN.
macvlan  : gives each container its own MAC/IP on the physical LAN — appears as a
           real device on the network. Useful for legacy apps expecting real IPs.
```

A critical detail with the default bridge: containers can reach each other only by **IP**, not by name. To get **DNS-based service discovery** (containers resolving each other by name), create a **user-defined bridge network** — Docker runs an embedded DNS resolver on user-defined networks. This is why Compose puts services on a user-defined network: `db` resolves automatically.

### Q14. [Practical] Two containers on the same host can't reach each other by name. Diagnose and fix.

The symptom is almost always that they're on the **default bridge**, which has no embedded DNS. The fix: put them on a user-defined network.

```bash
docker network create appnet
docker run -d --name db    --network appnet postgres:17
docker run -d --name api   --network appnet myapi   # can now resolve "db"
```

Diagnostic steps if it still fails:

1. `docker network inspect appnet` — confirm both containers are listed under `Containers`.
2. From inside the API: `docker exec api getent hosts db` or `nslookup db` — verify DNS resolves to the db's container IP.
3. Check the app is **binding to `0.0.0.0`**, not `127.0.0.1` — a service bound to loopback inside the container is unreachable from peers.
4. Confirm the target port is actually listening (`docker exec db ss -tlnp`).
5. Rule out a firewall/`iptables` issue or a corporate VPN that mangles the Docker subnet (a classic source of intermittent failures).

### Q15. [Coding] Write a Docker Compose file for a web app + Postgres with healthcheck and dependency ordering.

```yaml
# compose.yaml — Compose Specification (no top-level "version:" needed in modern Compose)
services:
  db:
    image: postgres:17
    environment:
      POSTGRES_PASSWORD_FILE: /run/secrets/db_password
      POSTGRES_DB: app
    volumes:
      - pgdata:/var/lib/postgresql/data
    secrets:
      - db_password
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U postgres"]
      interval: 5s
      timeout: 3s
      retries: 5
    restart: unless-stopped

  api:
    build: .
    ports:
      - "8080:8080"
    environment:
      DATABASE_URL: postgres://postgres@db:5432/app
    depends_on:
      db:
        condition: service_healthy   # wait until db healthcheck passes
    restart: unless-stopped

volumes:
  pgdata:

secrets:
  db_password:
    file: ./db_password.txt
```

- **Key points:** `depends_on` with `condition: service_healthy` ensures the API starts only once Postgres is genuinely accepting connections — plain `depends_on` only waits for the container to *start*, not be *ready*. Services share an auto-created user-defined network, so `api` reaches Postgres at hostname `db`. The named volume `pgdata` survives `docker compose down` (use `down -v` to also delete volumes).
- **Edge case:** Even with `service_healthy`, the app should still retry its DB connection on startup — healthchecks reduce but don't eliminate races during failover or restarts.

### Q16. [Theory] How do you handle Linux signals and graceful shutdown in containers?

When you `docker stop`, Docker sends `SIGTERM` to **PID 1** in the container, waits a grace period (default 10s, tunable with `--time`), then sends `SIGKILL`. Two common problems:

1. **Shell-form `CMD`/`ENTRYPOINT`** makes `/bin/sh -c` PID 1. Most shells don't forward signals to children, so your app never receives `SIGTERM` and gets hard-killed after the grace period — dropping in-flight requests. **Fix:** use exec form so your app *is* PID 1.
2. **App-is-PID-1 reaping.** PID 1 has special duties (reaping zombie child processes, default signal handling). If your app spawns children and doesn't reap them, you get zombies. **Fix:** run a lightweight init like `tini` (`docker run --init`, or `ENTRYPOINT ["/sbin/tini","--"]`) which reaps zombies and forwards signals.

Production-grade services should trap `SIGTERM`, stop accepting new work, drain in-flight requests, close DB connections, and exit — all within the grace window. In Kubernetes, combine this with a `preStop` hook and readiness-probe removal to drain traffic first.

### Q17. [Practical] Why should containers run as non-root, and how do you do it?

By default, containers run as `root` (UID 0). On most setups the container root maps to the **host** root, so a container breakout (kernel exploit, misconfigured bind mount, leaked socket) can hand an attacker host root. Running as an unprivileged user is defense-in-depth: it limits the blast radius and is required by hardened policies (PSS "restricted", many CIS benchmarks).

```dockerfile
FROM node:22-slim
WORKDIR /app
COPY --chown=node:node . .
RUN npm ci --omit=dev
USER node            # the official node image ships a non-root 'node' user
CMD ["node", "server.js"]
```

For images without a prebuilt user, create one (`RUN useradd -r -u 10001 app && USER app`). Complementary hardening: `--read-only` root filesystem with `tmpfs` for writable paths, `--cap-drop ALL --cap-add` only what's needed, `--security-opt no-new-privileges`, and a seccomp/AppArmor profile. **Rootless mode** (running the Docker daemon itself as a non-root user via user namespaces) closes the gap even further by mapping container root to an unprivileged host UID.

### Q18. [Theory] What is a container registry, and what is the difference between Docker Hub, a private registry, and a tag vs digest?

A registry stores and distributes images (push/pull). Docker Hub is the default public registry; cloud providers offer managed ones (ECR, GCR/Artifact Registry, ACR), and you can self-host (Harbor, the `registry:2` distribution, GitLab/GitHub Container Registry). An image reference is `[registry/]repository[:tag|@digest]`, e.g., `ghcr.io/acme/api:1.4.2` or `...@sha256:abc...`.

A **tag** is a mutable pointer — `1.4.2` can be re-pushed to point at different content, and `latest` is just a conventional default tag with no special meaning. A **digest** (`@sha256:...`) is an immutable content hash. For reproducible, tamper-evident deploys, **pin by digest** in production (and in `FROM` lines) so you always get exactly the bytes you tested. Docker Hub also imposes anonymous pull rate limits, which is a real operational concern — mirror or authenticate in CI to avoid `429 Too Many Requests`.

---

## 🟠 Advanced (8–12 yrs)

### Q19. [Theory] What is the OCI, and what are the image-spec, runtime-spec, and distribution-spec?

The **Open Container Initiative (OCI)** is a Linux Foundation governance body that standardizes container formats so the ecosystem isn't locked to Docker. There are three specs:

- **image-spec** — the on-disk/registry format: a manifest (JSON listing layers + config by digest), a config blob (env, entrypoint, rootfs diff IDs, history), and layer blobs. Docker images today are OCI-compatible.
- **runtime-spec** — how to run a "filesystem bundle" (a rootfs + `config.json`). `runc` is the reference implementation; `crun`, `gVisor` (`runsc`), and Kata implement the same interface, which is what lets you swap runtimes.
- **distribution-spec** — the registry HTTP API for pushing/pulling/discovering content (the `/v2/` API).

This standardization is why `nerdctl`, `podman`, `containerd`, and Kubernetes all interoperate with the same images and registries Docker produces.

```
docker build ──► OCI image (manifest + config + layers)
                      │ push (distribution-spec)
                      ▼
                  Registry
                      │ pull
                      ▼
            containerd ──► OCI runtime bundle ──► runc/crun/gVisor (runtime-spec)
```

### Q20. [Theory] Walk through what actually happens when you run `docker run`. What are containerd, shim, and runc?

Modern Docker is layered: the `docker` CLI talks to **dockerd** (Docker daemon), which delegates lifecycle to **containerd** (a CNCF container runtime/daemon), which spawns a **containerd-shim** per container, which invokes **runc** (the low-level OCI runtime) to actually create the container.

```
docker CLI → dockerd → containerd → containerd-shim-runc → runc
                                         │                    │
                          (keeps running, owns the          (sets up namespaces,
                           container's stdio & exit)          cgroups, then exits)
```

Step by step on `docker run nginx`:
1. CLI sends an HTTP request to dockerd's API.
2. dockerd ensures the image exists (pulls layers from the registry if not), assembles the rootfs via the storage/snapshot driver (OverlayFS).
3. dockerd asks containerd to create+start the container; containerd prepares the OCI bundle and starts a **shim**.
4. The shim calls **runc**, which sets up namespaces (PID, net, mount, UTS, IPC, user), applies cgroup limits, sets capabilities/seccomp, pivots to the new rootfs, and `exec`s the entrypoint as PID 1.
5. **runc exits** — the shim stays alive as the container's parent, owning its stdio and reporting its exit code. This shim design is why you can restart dockerd **without killing running containers** (daemonless container lifetime).

### Q21. [Theory] Explain the union filesystem (OverlayFS), copy-on-write, and the writable layer in depth.

OverlayFS merges multiple read-only directories (**lowerdirs** = image layers) with a single read-write directory (**upperdir** = the container's writable layer) into one unified view (**merged**). Reads search the upperdir first, then each lower layer top-down. The first match wins, so an upper-layer file shadows lower ones.

**Copy-on-write (CoW):** when a container modifies a file that exists only in a read-only layer, OverlayFS copies the entire file *up* into the upperdir before writing. The first write to a large file therefore incurs a copy cost; subsequent writes are local. **Deletes** are represented by **whiteout** files in the upperdir (a special character device) that hide the lower file without modifying the read-only layer.

```
merged (what the container sees)
        ▲
  ┌─────┴───────────────┐
  │ upperdir (RW, this   │ ← writes/CoW copies/whiteouts go here
  │ container's changes) │
  ├──────────────────────┤
  │ lowerdir N (RO layer)│ ┐
  │ lowerdir … (RO layer)│ ├ shared, content-addressed,
  │ lowerdir 0 (RO base) │ ┘ reused across containers/images
  └──────────────────────┘
```

Implications: write-heavy workloads (databases) on the CoW layer are slow and bloat the container — **always use a volume** for such data. Many containers from one image share the lowerdirs, so disk usage scales with *changes*, not number of containers.

### Q22. [Coding] Use BuildKit cache mounts and a multi-platform build to speed up and broaden a build.

**Problem:** Each CI build re-downloads all dependencies (no persistent package cache across builds), and you need `linux/amd64` + `linux/arm64` images for mixed clusters and Apple-silicon dev machines.

```dockerfile
# syntax=docker/dockerfile:1.7      # enable latest BuildKit frontend features
FROM python:3.12-slim AS base
WORKDIR /app

COPY requirements.txt .
# Cache mount: pip's download cache persists between builds (NOT baked into the image)
RUN --mount=type=cache,target=/root/.cache/pip \
    pip install --no-cache-dir -r requirements.txt

COPY . .
# Secret mount: token available only during this RUN, never stored in a layer
RUN --mount=type=secret,id=npm_token \
    sh -c 'export TOKEN=$(cat /run/secrets/npm_token) && ./build.sh'

USER 10001
ENTRYPOINT ["python", "main.py"]
```

```bash
# Build & push multi-arch with shared/inline cache
docker buildx build \
  --platform linux/amd64,linux/arm64 \
  --secret id=npm_token,src=./npm_token.txt \
  --cache-to   type=registry,ref=ghcr.io/acme/api:buildcache,mode=max \
  --cache-from type=registry,ref=ghcr.io/acme/api:buildcache \
  -t ghcr.io/acme/api:1.5.0 --push .
```

- **Cache mount** (`type=cache`): persists a directory across builds without adding it to the image — turns a 90s dependency install into a few seconds on warm cache. **Secret mount** (`type=secret`): the secret is bind-mounted only for that `RUN` and never lands in any layer, unlike `ARG`/`ENV` which leak into image history.
- **Multi-platform:** `buildx` produces a **manifest list** (a single tag pointing at per-arch manifests); clients auto-pull the right architecture. Cross-arch builds use QEMU emulation (slower) or native remote builders (`buildx create --driver remote`/build cloud) for speed.
- **Time/Space:** Warm-cache builds drop from minutes to seconds; the image itself is unchanged (cache/secret mounts add no layers). **Edge case:** cache mounts are not shared between concurrent builds by default — use `sharing=locked` to serialize when correctness matters.

### Q23. [Practical] How do you scan images for vulnerabilities and prevent secrets from leaking in? Walk a real CI pipeline.

Layered supply-chain approach:

1. **Scan on build (CI).** Run `trivy image`, `grype`, or `docker scout cves` against the built image; fail the pipeline on `HIGH`/`CRITICAL` with a known fix. Scan both OS packages and language dependencies.
2. **Generate an SBOM.** Produce a CycloneDX/SPDX SBOM (`syft`, `docker buildx ... --sbom=true`) and store it as an attestation so you can answer "are we affected by CVE-X?" without rebuilding.
3. **Sign and verify provenance.** Sign images with **cosign** (Sigstore) and attach SLSA provenance (`--provenance=true` in buildx). Enforce at deploy time with an admission controller (Kyverno/Gatekeeper) that rejects unsigned images.
4. **Prevent secret leakage.** Never use `ARG`/`ENV` for secrets — they persist in image history (`docker history` reveals them). Use BuildKit `--mount=type=secret` at build time and runtime secret managers (Vault, cloud secret stores, mounted `tmpfs`) at run time. Add a secret scanner (`gitleaks`, `trufflehog`) to CI and a `.dockerignore` that excludes `.env`, keys, and `.git`.
5. **Reduce attack surface at the source.** Distroless/Chainguard bases minimize the package count, so there are simply fewer CVEs to triage.

```
git push → CI:  build (BuildKit, --secret) → trivy scan (fail on CRITICAL)
              → syft SBOM → cosign sign + provenance attest → push to registry
deploy → admission controller verifies cosign signature + policy → run
runtime → Falco/eBPF runtime detection + read-only FS + dropped caps
```

A concrete example: many shops gate merges on "zero fixable CRITICALs" and pin base images by digest, then run a nightly job that re-scans *already-deployed* digests against fresh CVE feeds (because a clean image today can have a new CVE tomorrow).

### Q24. [Practical] A container keeps restarting / OOM-killing in production. How do you troubleshoot?

A systematic playbook:

1. **Exit code first.** `docker inspect --format '{{.State.ExitCode}} {{.State.OOMKilled}}' <id>`. Exit `137` = SIGKILL (often OOM if `OOMKilled=true`, or `docker stop` timeout); `143` = SIGTERM; `139` = SIGSEGV; `1`/`2` = app error.
2. **Logs.** `docker logs --tail 200 <id>` (and `--previous` equivalent — inspect the last failed instance). Look for stack traces, "out of memory," failed migrations, missing config.
3. **OOM diagnosis.** If OOM-killed, the container hit its cgroup memory limit (`--memory`). Check `docker stats`, the host `dmesg | grep -i oom`, and whether the JVM/Node heap is sized larger than the container limit. **JVM gotcha:** older JVMs ignored cgroup limits; use Java 11+/`-XX:MaxRAMPercentage` so the heap respects the container limit. **Node gotcha:** set `--max-old-space-size` below the container limit.
4. **Restart loop cause.** `docker inspect` the restart policy and `RestartCount`. A crash-loop with a healthcheck failing means either the app never becomes healthy (bad config/dependency) or the healthcheck itself is wrong (too short a `start_period`).
5. **Exec in / ephemeral debug.** `docker exec -it <id> sh` to inspect live state. For distroless/scratch images with no shell, attach a debug toolbox sharing the namespaces: `docker run --pid=container:<id> --net=container:<id> nicolaka/netshoot` (or `docker debug` / `kubectl debug --image=busybox`).
6. **Resource & quota.** Check disk (`docker system df` — a full `/var/lib/docker` causes write failures), file descriptor/ulimit limits, and host CPU throttling (`--cpus` causing latency spikes).

### Q25. [Theory] How do you build images securely and reproducibly in a CI environment without giving access to the Docker socket (DinD vs rootless vs Kaniko/Buildah)?

Mounting the host Docker socket (`/var/run/docker.sock`) into a CI container is effectively granting **host root** — anyone who can talk to that socket can launch a privileged container that mounts the host filesystem. Safer alternatives:

- **Docker-in-Docker (DinD)** with `--privileged`: a nested daemon. Avoids socket sharing but requires `--privileged`, which is itself a large attack surface; acceptable in isolated, ephemeral CI runners only.
- **Rootless BuildKit / rootless dockerd:** runs the build daemon as a non-root user via user namespaces — no `--privileged`, no host root. Strong default for CI.
- **Daemonless builders — Kaniko, Buildah/`buildah bud`:** build OCI images entirely in userspace inside an unprivileged container, no daemon and no privileged mode. Popular in Kubernetes-based CI (Tekton, Argo) where running a privileged daemon is disallowed.
- **`docker buildx` with a remote/cloud builder:** the CI job ships the build context to a managed BuildKit instance; the runner itself needs no daemon.

For **reproducibility**: pin bases by digest, set `SOURCE_DATE_EPOCH` to normalize timestamps, avoid `latest`, and prefer lockfiles. Then verify with provenance attestations so the running image is provably the CI output.

### Q26. [Practical] Compose vs Swarm vs Kubernetes — when do you reach for each, and how do you migrate?

```
Compose : single host, dev/CI/simple prod. One YAML, fast iteration.
          No autoscaling, no self-healing across hosts, no rolling-update orchestration.
Swarm   : built into Docker, multi-host, simple. Good for small clusters and teams
          that want clustering without K8s complexity. Declining ecosystem mindshare.
K8s     : multi-host, autoscaling, self-healing, rolling/canary deploys, huge ecosystem.
          Steep operational complexity; the industry standard for production at scale.
```

Decision rule: **Compose** for local dev and small/internal services on one box; **Kubernetes** when you need horizontal autoscaling, multi-AZ resilience, fine-grained rollout strategies, and a rich ecosystem (service mesh, operators, GitOps). Swarm sits in between but has lost momentum; greenfield production rarely starts there in 2026.

**Migration path Compose → K8s:** `kompose convert` gives a starting set of Deployments/Services, but treat its output as a draft — you'll re-model volumes as PVCs, secrets as `Secret`/external-secrets, `depends_on` as readiness probes + retries, and `build:` as a separate CI build step. The same OCI image runs unchanged across all three, which is the whole point: orchestration changes, the artifact doesn't.

### Q27. [Theory] What are the security risks of `--privileged`, capabilities, and the default seccomp profile?

`--privileged` disables almost all isolation: it grants **all** Linux capabilities, removes the seccomp/AppArmor restrictions, and exposes host devices. A privileged container can load kernel modules, access raw devices, and trivially escape to the host — treat it as "this container is host root." Prefer the least-privilege model:

- **Capabilities:** Docker drops most by default and keeps a small set (e.g., `CHOWN`, `SETUID`, `NET_BIND_SERVICE`). Drop everything and add back only what's needed: `--cap-drop ALL --cap-add NET_BIND_SERVICE`. Never blanket-add `SYS_ADMIN` (it's nearly equivalent to root).
- **Seccomp:** Docker applies a default seccomp profile that blocks ~44 dangerous syscalls (e.g., `keyctl`, `mount`, `ptrace` of other processes). Disabling it (`--security-opt seccomp=unconfined`) is a common but dangerous "fix" for permission errors — instead, craft a custom profile allowing the specific syscalls your app needs.
- **`no-new-privileges`:** prevents setuid binaries from escalating, blunting many privilege-escalation exploits.

A frequent real-world breakout vector is mounting the **Docker socket** into a container (CI tools, "docker-outside-of-docker"): it's equivalent to `--privileged` because the container can ask the daemon to start a privileged container mounting `/`.

---

## 🔴 Expert (15+ yrs)

### Q28. [Theory] How would you architect container isolation for a hostile multi-tenant platform where containers run untrusted user code?

Standard namespace/cgroup isolation is **insufficient** for untrusted code because all containers share the host kernel — the kernel's syscall surface is a single, large breakout target (Dirty COW, runc CVE-2019-5736, leaky vulnerabilities appear regularly). For genuinely hostile tenants you add a stronger boundary:

- **Sandboxed runtimes:** **gVisor** (`runsc`) interposes a userspace kernel that intercepts syscalls, drastically shrinking host-kernel exposure (at some performance/compat cost). **Kata Containers** / **Firecracker** micro-VMs give each container/pod its own lightweight kernel via a hypervisor — near-VM isolation with near-container startup. This is how serverless platforms (AWS Lambda/Fargate) safely co-locate tenants.
- **Defense in depth regardless of runtime:** rootless + user namespaces (container root ≠ host root), `no-new-privileges`, `--cap-drop ALL`, tight seccomp, read-only rootfs, no host networking, no socket mounts, strict cgroup limits to prevent noisy-neighbor and fork-bomb DoS, and network policy to segment tenants.
- **Per-tenant node pools** or hardware isolation for the highest-sensitivity workloads, plus **runtime threat detection** (Falco/eBPF) to catch anomalous syscalls/file access in real time.

The architectural judgment is matching the isolation tier to the threat model: bridge/namespace isolation for your own trusted microservices; micro-VMs for arbitrary tenant code. Over-isolating internal services wastes resources; under-isolating tenant code is a breach waiting to happen.

### Q29. [Theory] Discuss image-format evolution and supply-chain hardening: manifest lists, attestations, SLSA, zstd, and lazy pulling.

The image spec has evolved well past "a stack of gzip tarballs":

- **Multi-arch manifest lists / image indexes** let one tag resolve to per-architecture (and per-OS) images — essential as arm64 went mainstream in datacenters and on Apple silicon.
- **Attestations (OCI 1.1 referrers API):** SBOMs, SLSA **provenance**, and signatures are attached as *referrer* artifacts linked to an image by digest, queryable via the registry. This is the backbone of "given a running digest, prove how it was built and what's in it."
- **Compression:** **zstd** layers (vs gzip) decompress faster and compress better, cutting pull time — increasingly the default in modern BuildKit.
- **Lazy/streaming pulls:** **eStargz** and **SOCI** (Seekable OCI) let a container **start before the whole image is pulled**, fetching files on demand. For multi-GB ML/data images this turns minutes of pull latency into seconds — a real win for autoscaling and cold starts.

The throughline is the **software supply chain**: post-SolarWinds and the Log4Shell scramble, the industry standardized on signed, attested, digest-pinned artifacts with machine-verifiable provenance (SLSA levels), enforced by admission control. As a staff/principal engineer you're expected to own this end-to-end, not just "docker push."

### Q30. [Practical] Your org's container builds are slow, cache hit rates are low across a 200-engineer org, and images are bloated. Design the remediation.

I'd treat it as a platform problem, not per-team firefighting:

1. **Measure first.** Instrument build durations and cache-hit rates in CI; identify the worst offenders (usually a handful of base images and a few monorepo services dominate cost).
2. **Standardize golden base images.** Publish a small set of hardened, digest-pinned, distroless/slim bases that teams `FROM`. Centralizes CVE patching and maximizes cross-team layer sharing in the registry.
3. **Shared remote build cache.** Adopt `buildx` with `--cache-to/--cache-from type=registry,mode=max` (or a Build Cloud) so cache is shared across CI runners and engineers — not lost per-job. Add cache mounts for package managers.
4. **Enforce best practices via linting.** `hadolint` in CI to catch cache-busting instruction order, missing `.dockerignore`, `apt` without cleanup, `latest` tags, root users.
5. **Mandate multi-stage + small bases** through templates/Backstage scaffolds so the right thing is the default.
6. **Remote/native builders for multi-arch** to avoid slow QEMU emulation; consider depot.dev/Build Cloud for persistent fast builders.

**Trade-offs:** golden bases add a central team dependency (mitigate with automation and clear SLAs); aggressive registry caching costs storage (set retention/GC). Expected outcome, from doing this at scale: median build time down 50–70%, image sizes down ~60%, and CVE remediation time from weeks to days because patching one base propagates everywhere.

### Q31. [Behavioral] Tell me about a time you had to push back on a team that wanted to disable container security controls to "make it work."

**Situation:** A product team was blocked the night before a launch; their container crashed with a seccomp/permission error and they'd opened a PR adding `--privileged` and `seccomp=unconfined` to get it running, escalating to me as the platform owner.

**Task:** Unblock the launch without shipping a container that's effectively host-root in our shared cluster.

**Action:** I resisted the binary framing of "ship insecure or miss launch." I reproduced the failure, captured the blocked syscall with `strace`/audit logs, and found it was a single syscall (`io_uring`-related) the default profile blocked. We added a **narrow custom seccomp profile** allowing exactly that syscall and dropped the `--privileged` change. I also added a CI policy check so future `--privileged`/`unconfined` additions require a security review with a documented justification, so this wasn't a one-off heroics moment.

**Result:** Launch shipped on time on a least-privilege container. The reusable seccomp profile and the policy gate prevented several similar "just disable it" PRs over the next quarter. The lesson I emphasize with teams: a security error is usually telling you something specific — debug the actual constraint instead of disabling the whole control.

### Q32. [Practical] How do you debug a container that ships as distroless/scratch with no shell, in production?

You can't `exec sh` into it, so you attach tooling **alongside** it by sharing namespaces rather than entering the broken container:

1. **Ephemeral debug container sharing namespaces:**
   ```bash
   docker run -it --rm \
     --pid=container:<target> \
     --network=container:<target> \
     --cap-add SYS_PTRACE \
     nicolaka/netshoot   # or busybox/alpine
   ```
   You now see the target's processes (`ps`), its network namespace (`ss`, `tcpdump`, `curl localhost:port`), and can `strace -p <pid>`.
2. **Docker's purpose-built tooling:** `docker debug <container>` (Docker Desktop) attaches a writable toolbox to a slim/distroless container without modifying it. In Kubernetes, `kubectl debug -it pod/<name> --image=nicolaka/netshoot --target=<container>` creates an ephemeral container in the same pod sharing the process namespace.
3. **Filesystem inspection without running it:** `docker export <container> | tar -tvf -` to list files, or `docker cp <container>:/path ./local` to pull artifacts out, or `dive` to explore image layers offline.
4. **Build-time provision for debuggability:** keep a `:debug` variant tag (distroless ships `:debug` images with busybox) so you can redeploy a debuggable build temporarily.

The principle: production images should be minimal (security + size), and debugging is decoupled via ephemeral/sidecar tooling rather than fattening the shipped image. **Security note:** `SYS_PTRACE` and shared PID namespaces are powerful — gate who can attach debug containers and audit it, since it bypasses the very minimalism that keeps the image safe.

### Q33. [Coding] Write a hardened, production-grade Dockerfile incorporating non-root, read-only-friendly layout, healthcheck, pinned digest, and BuildKit features.

**Problem:** Produce a single Dockerfile for a Node service that is small, reproducible, non-root, signal-correct, scan-friendly, and ready to run with a read-only root filesystem.

```dockerfile
# syntax=docker/dockerfile:1.7
# ---- Build stage ----
# Pin by digest for reproducibility + tamper resistance
FROM node:22-slim@sha256:<pinned-digest> AS build
WORKDIR /app
COPY package.json package-lock.json ./
# Cache mount avoids re-downloading on every CI build
RUN --mount=type=cache,target=/root/.npm \
    npm ci
COPY . .
RUN npm run build && npm prune --omit=dev

# ---- Runtime stage ----
FROM gcr.io/distroless/nodejs22-debian12:nonroot AS runtime
WORKDIR /app
# Copy only what's needed to run
COPY --from=build --chown=nonroot:nonroot /app/node_modules ./node_modules
COPY --from=build --chown=nonroot:nonroot /app/dist ./dist
COPY --from=build --chown=nonroot:nonroot /app/package.json ./

ENV NODE_ENV=production
USER nonroot
EXPOSE 8080

# Healthcheck for orchestrators that honor it
HEALTHCHECK --interval=15s --timeout=3s --start-period=20s --retries=3 \
  CMD ["node", "dist/healthcheck.js"]

# Exec form → app is PID 1, receives SIGTERM directly for graceful shutdown
ENTRYPOINT ["node", "dist/server.js"]
```

Run it locked down:

```bash
docker run -d \
  --read-only --tmpfs /tmp:rw,size=32m \
  --cap-drop ALL --cap-add NET_BIND_SERVICE \
  --security-opt no-new-privileges \
  --memory 512m --cpus 1 --pids-limit 256 \
  --init \
  -p 8080:8080 myorg/api:1.0.0@sha256:<pinned>
```

- **Why each control:** distroless `nonroot` removes shell + package manager (fewer CVEs, no `exec sh` for attackers) and runs as UID 65532; cache mount speeds CI without bloating the image; `--read-only` + `tmpfs` prevents persistence of attacker artifacts; `--cap-drop ALL` + `no-new-privileges` enforce least privilege; `--init` (tini) reaps zombies and forwards signals; `--pids-limit` blunts fork bombs; digest pin guarantees byte-for-byte reproducibility.
- **Time/Space:** ~150 MB final image vs ~1 GB single-stage; warm-cache CI builds in seconds. **Edge cases:** distroless has no shell, so `HEALTHCHECK`/`CMD` must be exec-form binaries (no `CMD-SHELL`); `--read-only` requires you to identify every writable path (logs → stdout, temp → tmpfs/volume) or the app will fail to start.

---

## 🧩 Extended Questions — Set 1: Deep Theory & Internals

### 🟢 Basic — extended

#### Q34. [Theory] Which Linux kernel features actually create a container? Name the namespaces and cgroups.

There is no single "container" object in the Linux kernel — a container is an *assembly* of two independent kernel facilities glued together by the runtime (runc). **Namespaces** provide *isolation* (what a process can see), and **cgroups** provide *resource control* (how much it can use). When runc starts a container it `clone()`s/`unshare()`s a new process into a fresh set of namespaces, applies cgroup limits, sets capabilities and a seccomp filter, then `execve()`s your entrypoint inside that sandbox.

The standard namespaces a Docker container gets are: **mount (mnt)** — its own filesystem tree/root; **PID** — its own process-number space where the entrypoint is PID 1 and it cannot see host processes; **network (net)** — its own interfaces, routing table, and iptables rules; **UTS** — its own hostname/domainname; **IPC** — its own System V IPC / POSIX message queues; **user** — UID/GID remapping (only when user-namespace remapping or rootless mode is enabled); and **cgroup** — hides the host's cgroup hierarchy. There's also **time** (newer kernels) which Docker doesn't use by default.

```
A "container" = one process tree, placed in:
   namespaces (isolation)          cgroups (limits)
   ├─ mnt   : own rootfs           ├─ memory : --memory
   ├─ pid   : entrypoint = PID 1   ├─ cpu    : --cpus / --cpu-shares
   ├─ net   : own veth/iptables    ├─ pids   : --pids-limit
   ├─ uts   : own hostname         ├─ io     : --device-read-bps
   ├─ ipc   : own SysV IPC         └─ ...
   ├─ user  : UID map (optional)
   └─ cgroup: hides host hierarchy
```

The key conceptual takeaway is that "container" is a userspace abstraction. The kernel only knows about processes in namespaces with cgroup limits, which is precisely why a process can leave a namespace (e.g., `nsenter`) or why `--pid=host` makes a container see all host processes — you're just choosing which namespaces to share rather than create.

#### Q35. [Theory] Why is a Docker image not "one big tarball"? Explain the manifest, config, and layers, and the indirection between them.

An image is deliberately split into three kinds of content-addressed objects so that storage and transfer can be deduplicated. At the top is a **manifest** — a small JSON document that references, by digest, exactly one **config** blob and an ordered list of **layer** blobs. The image's identity (the `@sha256:...` digest you pin in production) is the SHA-256 of this manifest, not of the whole image. Because everything is referenced by digest, a registry stores each unique layer once even if a hundred images share it.

The **config** blob holds the runtime metadata (`Entrypoint`, `Cmd`, `Env`, `WorkingDir`, exposed ports, `User`) plus two history-related fields: `rootfs.diff_ids` and `history`. The **layers** are the actual filesystem tarballs (gzip or zstd compressed). Crucially there are *two* hashes per layer: the **digest** (hash of the *compressed* blob, used for registry transfer and content addressing) and the **diff ID** (hash of the *uncompressed* tar, used to compute the rootfs chain). This distinction is why the same filesystem content can have different layer digests if recompressed.

```
            manifest (this digest = the image's identity)
            ├─ config  → sha256:aaa…  { Entrypoint, Env, diff_ids[], history[] }
            └─ layers  → [ sha256:bbb… , sha256:ccc… , sha256:ddd… ]  (compressed blobs)
                                 │
            chain ID computed from diff_ids → how the local store names the snapshot
```

For multi-architecture images there is an additional level of indirection: an **image index** (a.k.a. manifest list) whose entries are per-platform manifests keyed by `os/architecture`. So `docker pull node:22` actually fetches an index, picks the manifest matching your platform, then pulls that manifest's config and layers. Understanding this three-tier structure (index → manifest → config + layers) is what lets you reason about digests, multi-arch, attestations, and registry deduplication coherently.

#### Q56. [Theory] What does `WORKDIR` actually do, and why is it different from `RUN cd`?

`WORKDIR /app` sets the working directory for every subsequent `RUN`, `CMD`, `ENTRYPOINT`, `COPY`, and `ADD` in the Dockerfile, and it **persists into the image config** as the default cwd for containers. If the directory doesn't exist, Docker creates it (owned by root unless you've switched users). Critically, `WORKDIR` is a *metadata + persistent state* instruction — it changes the build's and the runtime's current directory across instructions and across the container's life.

`RUN cd /app` does **not** do this. Each `RUN` executes in its own fresh shell process, so a `cd` only affects that one command and is forgotten the moment the `RUN` finishes — the next instruction starts back wherever `WORKDIR` last set it (or `/`). This is the same reason `RUN cd /app` followed by a separate `RUN make` runs `make` in the wrong directory. Within a *single* `RUN`, chaining works (`RUN cd /app && make`) because it's one shell invocation.

```
WORKDIR /app          → cwd for ALL later instructions + runtime default
RUN cd /src           → affects ONLY this RUN's shell, then discarded
RUN make              → runs back in /app (WORKDIR), NOT /src   ← common bug
RUN cd /src && make   → works: single shell, cd + make together
```

`WORKDIR` also supports relative paths that stack (`WORKDIR /a` then `WORKDIR b` → `/a/b`) and can reference build args/env. The takeaway: use `WORKDIR` to establish directory context (it's declarative and persistent); never rely on `cd` across instruction boundaries. This mirrors the general Dockerfile principle that each `RUN` is an isolated process whose only durable effect is the filesystem diff it commits.

#### Q57. [Theory] Why does cleaning up `apt` caches in a separate RUN not shrink the image, and how does this generalize?

This is a direct corollary of layers being **immutable, append-only diffs**. When `RUN apt-get update && apt-get install -y build-essential` runs, the package lists in `/var/lib/apt/lists` and the downloaded `.deb` cache become part of *that layer's* committed diff. A later `RUN rm -rf /var/lib/apt/lists/*` runs in a *new* layer, where the deletion is recorded as whiteouts that hide those files at runtime — but the bytes are still physically shipped in the earlier layer's tarball. The image's total on-disk/registry size is the sum of all layers, so it doesn't shrink.

The fix is to make the cleanup part of the **same layer's net diff**, so the files never get committed in the first place: `RUN apt-get update && apt-get install -y --no-install-recommends build-essential && rm -rf /var/lib/apt/lists/*`. Because the add and the delete happen in one `RUN`, the layer's resulting diff already excludes them. `--no-install-recommends` further trims pulled-in extras.

```
WRONG:                                  RIGHT:
RUN apt-get update && install …  ← +200MB   RUN apt-get update \
RUN rm -rf /var/lib/apt/lists/*  ← whiteout    && apt-get install -y --no-install-recommends … \
(200MB still in layer 1)                       && rm -rf /var/lib/apt/lists/*   ← net diff is clean
```

This generalizes to *any* transient file: build caches (`pip`/`npm`/`go`), downloaded archives, compiler intermediates. The rules are (1) clean within the same `RUN`, (2) better yet, use BuildKit **cache mounts** for package caches so they're never in a layer at all, and (3) best of all, use **multi-stage builds** so the entire toolchain lives in a throwaway stage. The unifying mental model an interviewer wants: "you can't delete bytes from a previous layer — you can only avoid committing them."

### 🟡 Intermediate — extended

#### Q36. [Theory] cgroups v1 vs cgroups v2 — what changed and why does it matter for containers?

cgroups is the kernel subsystem that accounts for and limits resource usage. **v1** organized each resource ("controller") into its *own independent hierarchy* — a process could be in one cgroup for `memory`, a different one for `cpu`, another for `blkio`. That orthogonality sounds flexible but made coordinated decisions hard: there was no single place to ask "what is this container's resource picture," and controllers like `memory` and `blkio` couldn't cooperate, which crippled buffered-write throttling and led to inconsistent OOM behavior.

**v2** unifies everything into a **single hierarchy**: one tree of cgroups, with controllers enabled per-node. This enables the **Pressure Stall Information (PSI)** metrics (real "how starved is this group for CPU/memory/IO" signals), proper IO+memory cooperation, better OOM accounting, and a cleaner delegation model that rootless containers depend on. Modern distros (and Docker Engine 20.10+/systemd-managed hosts) default to v2.

```
cgroups v1                         cgroups v2
─────────────                      ─────────────
/sys/fs/cgroup/memory/...          /sys/fs/cgroup/...
/sys/fs/cgroup/cpu/...      →      (single unified tree;
/sys/fs/cgroup/blkio/...            controllers toggled per node)
(multiple separate hierarchies)     + PSI, better OOM, delegation
```

Practical fallout: certain flags behave differently. On v2, `--cpu-shares` is translated to the `cpu.weight` model, `--memory-swap` semantics tightened, and **rootless Docker effectively requires v2** because delegation of a sub-tree to an unprivileged user is a v2 feature (via systemd). If you ever see "your kernel does not support cgroup swap limit" or rootless resource limits silently not applying, the host's cgroup version is the first thing to check.

#### Q37. [Theory] Explain how container networking is actually wired on the default bridge: veth pairs, the docker0 bridge, NAT, and what `-p` really does.

On the default bridge, Docker creates a Linux bridge device named **`docker0`** on the host (a virtual L2 switch). For each container it creates a **veth pair** — a virtual Ethernet cable with two ends. One end (`eth0`) is moved into the container's network namespace; the other end stays in the host namespace and is plugged into `docker0`. The container gets a private IP from the bridge subnet (e.g., `172.17.0.0/16`), and `docker0` acts as its default gateway.

Outbound traffic is **masqueraded (SNAT)**: an `iptables` rule in the `nat` table's `POSTROUTING` chain rewrites the container's private source IP to the host's IP, so the outside world sees traffic coming from the host. This is why containers can reach the internet without any port mapping. **`-p 8080:80`** adds the *inbound* half: a `DNAT` rule in `PREROUTING`/`OUTPUT` that rewrites packets arriving at host:8080 to `containerIP:80`, plus a userland `docker-proxy` process as a fallback for certain cases (e.g., hairpin, hosts where the iptables path doesn't cover loopback).

```
        host:8080  ──DNAT──►  172.17.0.2:80
 ┌───────────────────────────────────────────────┐ host netns
 │  eth0 (public)        docker0 (172.17.0.1/16)  │
 │      ▲                   │ veth-host ◄──────────┼─┐ veth pair
 │   SNAT/masq              │                      │ │
 └──────┼───────────────────┼──────────────────────┘ │
        │                   │                         │
   internet            ┌────┴───────────────┐         │
                       │ eth0 172.17.0.2     │◄────────┘
                       │ (container netns)   │
                       └─────────────────────┘
```

The big consequence is that container networking is "just" namespaces + veth + bridge + iptables — there is no magic. That's why a corrupted iptables state, a host firewall (firewalld/ufw) reordering chains, or `--iptables=false` breaks publishing, and why `host` networking (sharing the host netns directly) skips all the veth/NAT machinery for lower latency at the cost of isolation and port conflicts.

#### Q38. [Theory] How does DNS resolution work inside a container, and why does it differ between the default bridge and a user-defined network?

Every container gets a `/etc/resolv.conf` that Docker writes. On a **user-defined network**, Docker points it at an **embedded DNS server** running at `127.0.0.11` inside the container's network namespace. This embedded resolver knows the names of all other containers on that network (and their aliases/`--network-alias` entries) and answers A/AAAA queries for them; anything it can't answer (public domains) it forwards to the host's upstream resolvers. That is the entire mechanism behind Compose's "service `db` is reachable at hostname `db`."

On the **default bridge** (`docker0`), there is *no* embedded DNS for container names — Docker historically only injected `/etc/hosts` entries for explicitly `--link`ed containers (a deprecated feature). So two unrelated containers on the default bridge can only talk by IP. This is the single most common "why can't my containers find each other by name" bug, and the fix is always "use a user-defined network."

```
container /etc/resolv.conf
   nameserver 127.0.0.11        ← Docker embedded DNS (user-defined nets only)
        │
        ├─ "db"      → 172.18.0.3   (resolved locally from network members)
        └─ "api.com" → forwarded → host's real upstream (8.8.8.8 / corp DNS)
```

Two operational subtleties worth knowing: (1) the embedded DNS forwards to whatever the *host* uses, so a container's external DNS breaks if the host's `resolv.conf` lists `127.0.0.53` (systemd-resolved stub) which isn't reachable from the container's netns — Docker normally rewrites this, but VPNs and split-horizon DNS still cause grief. (2) DNS round-robins multiple containers sharing a `--network-alias`, giving you crude load balancing, which is the primitive Compose/Swarm build service discovery on top of.

#### Q39. [Theory] What exactly invalidates a build-cache layer, and why can two identical-looking Dockerfiles miss the cache?

BuildKit computes a **cache key** for each step from the operation plus its inputs, and reuses a previously built result only if that key matches. For a `RUN`, the key is essentially the *literal command string* (plus the parent step's result and any mounted inputs) — BuildKit does **not** execute the command speculatively to see what it would produce. That's why `RUN apt-get update && apt-get install -y curl` happily reuses a months-old layer even though the upstream package index has moved: the string didn't change, so the cache "hits" and you get stale packages. For `COPY`/`ADD`, the key additionally includes a **content checksum** of the files being copied, so editing a copied file *does* bust it.

Several non-obvious things break the cache between two builds that "look the same": a different **base image digest** (if `node:22` resolved to a new manifest since last build), a changed **build arg** referenced by `ARG ... ` before the step, a different **build context** (because `COPY . .` hashes context contents — and the context differs if `.dockerignore` changed), a different **`--platform`**, or simply building on a runner with an empty local cache (cache is per-builder unless you export it with `--cache-to`).

```
RUN step cache key   ≈ hash( parent-result + command-string + mounts )
COPY step cache key  ≈ hash( parent-result + file-contents-checksum )
                                    │
   change ANY input above → this step + ALL later steps rebuild
```

The deeper point is that the cache is *correct-by-construction for declared inputs* but *blind to undeclared external state* (network, time, upstream repos). That asymmetry is why reproducible builds pin base digests and package versions, and why `--no-cache` (or a cache-busting `ARG CACHEBUST` / `--build-arg`) exists for the cases where you intentionally need to re-fetch network state.

#### Q40. [Theory] Why is the exec form of CMD/ENTRYPOINT not just a style preference? Explain PID 1, signal delivery, and the shell-form trap.

In the **exec form** (`ENTRYPOINT ["node","server.js"]`), Docker `execve()`s your binary directly, so *your process is PID 1* in the container's PID namespace. In the **shell form** (`ENTRYPOINT node server.js`), Docker actually runs `/bin/sh -c "node server.js"`, so the *shell* is PID 1 and your app is a child. This single difference changes signal behavior, which determines whether graceful shutdown works at all.

When you `docker stop`, the kernel sends `SIGTERM` to **PID 1**. PID 1 is special: the kernel does **not** apply default signal dispositions to it, so signals it doesn't explicitly handle are *ignored* rather than terminating it. A typical `/bin/sh` does not forward `SIGTERM` to its children, so in the shell-form case your app never sees the signal, never drains connections, and is hard-killed by `SIGKILL` after the grace period — dropping in-flight requests on every deploy.

```
shell form:  PID 1 = /bin/sh -c "node server.js"
             docker stop → SIGTERM → sh (ignores it, doesn't forward)
             10s later → SIGKILL → node dies abruptly ✗

exec form:   PID 1 = node server.js
             docker stop → SIGTERM → node (your handler drains + exits) ✓
```

There's a second PID-1 duty: **reaping zombies**. When PID 1's grandchildren are orphaned, they're reparented to PID 1, which must `wait()` on them or they accumulate as zombies. Most app runtimes don't reap, so if your container forks subprocesses you add a tiny init (`tini`, or `docker run --init`) as PID 1 to forward signals *and* reap. So the exec-form rule isn't aesthetics — it's the difference between correct lifecycle/signal semantics and silent request loss plus zombie buildup.

#### Q41. [Theory] What is the build context, why does it matter, and what does `.dockerignore` actually do?

When you run `docker build .`, the `.` is the **build context** — the entire directory tree is packaged and sent to the builder (historically to the daemon; with BuildKit it's streamed and incrementally hashed). Two things follow: (1) `COPY`/`ADD` can only reference paths *inside* the context — you cannot `COPY ../secrets`, because the builder never receives anything outside the context root; and (2) the size and contents of the context directly affect build time and the `COPY` cache key.

`.dockerignore` is an exclusion filter applied to the context *before* it's sent/hashed. Its primary jobs are performance (don't ship a 2 GB `node_modules` or `.git` to the builder) and safety (don't accidentally bake `.env`, private keys, or CI tokens into the image via a careless `COPY . .`). It also stabilizes the cache: if `COPY . .` hashes the whole context, then an irrelevant file change (a local log, an editor swap file) busts the cache unless those paths are ignored.

```
project/
├── .git/          ─┐
├── node_modules/   ├─ excluded by .dockerignore → not sent, not hashed
├── .env            ─┘   (faster build, no secret leak, stable cache)
├── src/
└── Dockerfile     → "docker build ." packages everything NOT ignored
```

A subtle BuildKit nuance: with the modern frontend, files excluded by `.dockerignore` aren't transferred at all (the context is hashed lazily and only needed files are fetched), so a good `.dockerignore` can dramatically cut even the initial upload. The mental model to state in an interview: the context is an input artifact to the build, `.dockerignore` is its filter, and `COPY` operates strictly within that filtered context — which is exactly why "it works locally but COPY fails in CI" usually means the file was `.dockerignore`d or outside the context.

#### Q42. [Theory] ARG vs ENV — what are their scopes, lifetimes, and security implications?

`ARG` declares a **build-time** variable; `ENV` declares a variable that is **baked into the image** and present at runtime. Their scopes differ sharply. An `ARG` is only in effect from its declaration to the end of the current build stage (and, importantly, an `ARG` declared *before* `FROM` is only usable in the `FROM` line itself unless re-declared inside the stage). An `ENV` persists into the final image's config and is visible to every process the container runs.

The security implication is the one interviewers probe: **neither is a safe place for secrets.** `ARG` values appear in `docker history` and in the build cache metadata; `ENV` values are literally stored in the image config (`docker inspect` / `docker history` shows them) and inherited by child images. So passing `--build-arg NPM_TOKEN=...` leaks the token into image history even if you "only used it during build." The correct mechanism is BuildKit's `--mount=type=secret`, which exposes the secret as a file for one `RUN` and never records it in any layer or history.

```
ARG  : build-time only │ visible in `docker history` │ scope = stage
ENV  : runtime + build  │ stored in image config       │ inherited by children
secret mount : one RUN  │ never persisted anywhere      │ correct for credentials
```

A common legitimate pattern combines them: `ARG VERSION=1.2.3` then `ENV APP_VERSION=$VERSION` to promote a build-time choice into a runtime variable. Also note precedence — a Compose/`docker run -e` value overrides the image's `ENV` at runtime, while `ARG` cannot be changed after build. Getting these scopes right avoids both the "my env var isn't set at runtime" confusion (you used `ARG`) and the "we leaked a token" incident (you used `ARG`/`ENV` for a secret).

#### Q43. [Theory] How do Docker restart policies work, and how do they interact with healthchecks and orchestrators?

A restart policy tells the daemon what to do when a container's **main process exits**. The four policies are: **`no`** (default — never restart), **`on-failure[:N]`** (restart only on non-zero exit, optionally capped at N attempts), **`always`** (restart on any exit, and also start it again when the daemon itself restarts), and **`unless-stopped`** (like `always`, but if you manually `docker stop` it, it stays stopped across daemon restarts). The crucial trigger is *process exit* — a restart policy reacts to the container dying, not to it being unhealthy.

This is where people conflate two independent mechanisms. A **HEALTHCHECK** only changes the container's reported *status* (`starting` → `healthy`/`unhealthy`); on plain Docker, going `unhealthy` does **not** by itself restart the container. So `restart: unless-stopped` + a failing healthcheck gives you a container that's flagged unhealthy but keeps running. To actually act on health you need either an orchestrator (Swarm/Kubernetes) whose probes *do* recreate/kill unhealthy tasks, or `--restart` combined with the app actually crashing.

```
process exits ── restart policy decides ──► restart? (on-failure/always/unless-stopped)
HEALTHCHECK fails ── sets status=unhealthy ──► (plain Docker: NO restart by itself)
                                              └─► Swarm/K8s probe: recreate/kill task
```

To prevent crash-loops from hammering the host, Docker applies **exponential backoff** between restart attempts (starting ~100ms, doubling, capped). In Kubernetes the analog is `CrashLoopBackOff`. The interview-grade insight: restart policy = "react to exit," healthcheck = "report readiness/liveness," and only an orchestrator unifies them into "restart when unhealthy." Designing for production means making the app *exit* on unrecoverable failures (so the policy can act) and *report unhealthy* on transient ones (so a probe can drain/recreate).

#### Q44. [Theory] What are the different logging drivers, and what's the trade-off between json-file, journald, and a remote driver?

The Docker daemon captures each container's **stdout/stderr** and hands the stream to a configured **logging driver**. The default is **`json-file`**, which writes newline-delimited JSON to `/var/lib/docker/containers/<id>/<id>-json.log`. It's simple and is what `docker logs` reads from — but it has a notorious failure mode: **without rotation it grows unbounded** and can fill the disk, taking the host down. You must set `max-size`/`max-file` (per container or in `daemon.json`) in any real deployment.

Alternatives trade off where logs live and how they're queried. **`journald`** hands logs to systemd's journal — unified with host logs, rotation/retention handled by journald, queryable with `journalctl`, but `docker logs` may be unavailable depending on config. **`local`** is a more efficient binary format than `json-file` with built-in rotation. Remote drivers — **`syslog`**, **`fluentd`**, **`gelf`** (Graylog), **`awslogs`**, **`splunk`** — ship logs off-box to a centralized pipeline, which is what you want at scale, but they introduce a network dependency and (for some) **blocking behavior**: if the log backend stalls, the container's writes can block.

```
container stdout/stderr → daemon → logging driver
   json-file  : local JSON files (default) │ needs max-size/max-file or disk fills
   local      : local binary, auto-rotated │ efficient, but not docker-logs-portable
   journald   : systemd journal             │ unified host logging, journalctl
   fluentd/   : ship off-box to a pipeline  │ scalable; risk: blocking on backend stall
   syslog/gelf
```

Two design principles fall out of this. First, **apps should log to stdout/stderr, not files inside the container** — that's the only stream Docker's drivers see, and it keeps the writable layer clean. Second, pick the **delivery mode** consciously: `mode=non-blocking` with a bounded buffer prevents a slow log backend from stalling your application, at the cost of possibly dropping logs under pressure — usually the right trade for a request-serving service.

#### Q58. [Theory] How does a HEALTHCHECK actually work internally, and what do `start_period`, `interval`, and `retries` control?

A `HEALTHCHECK` instruction tells the daemon to periodically execute a command **inside the running container** and judge health from its exit code: `0` = healthy, `1` = unhealthy (any other non-zero is also treated as unhealthy). The daemon tracks a state machine — `starting` → `healthy`/`unhealthy` — and records the last few probe results (output and exit code) in `docker inspect`'s `State.Health`. The check runs in the container's namespaces, so it can hit `localhost:port` exactly as the app sees it.

The timing knobs each address a distinct failure mode. **`interval`** is the gap between probes (and between the container start and the first probe). **`timeout`** bounds each probe — exceeding it counts as a failure, which prevents a hung check from masquerading as healthy. **`retries`** is how many *consecutive* failures flip the status to `unhealthy`, smoothing over transient blips. **`start_period`** is the grace window after start during which failures **do not count** toward `retries` (and a success immediately ends the grace period) — this is what stops slow-booting apps (JVM warmup, migrations) from being marked unhealthy before they've had a chance to come up.

```
container start
   │  ← start_period (failures here are FREE, don't count)
   ├─probe(interval)──fail (timeout-bounded)
   ├─probe──fail   ┐ consecutive failures
   ├─probe──fail   ┘ == retries  → status = unhealthy
   └─probe──ok                    → status = healthy (resets failure count)
```

Two gotchas. First, on **plain Docker an `unhealthy` status does not restart the container** by itself — only Swarm/Kubernetes act on it (Q43). Second, the probe must be **lightweight and exec-form-friendly**: on distroless/scratch images there's no shell, so `CMD-SHELL` won't work — ship a small healthcheck binary and use the exec form. A well-tuned healthcheck (realistic `start_period`, bounded `timeout`, sensible `retries`) is what makes `depends_on: condition: service_healthy` and orchestrator readiness gating actually reliable.

#### Q59. [Theory] Volumes vs bind mounts vs tmpfs at the kernel level — mount propagation, permissions, and the "empty volume copies image data" behavior.

All three are **mounts** layered over the container's mount namespace, but they differ in source and lifecycle. A **named volume** is a directory Docker manages under `/var/lib/docker/volumes/<name>/_data`, bind-mounted into the container; Docker tracks it as a first-class object (drivers, labels, `docker volume` lifecycle). A **bind mount** maps an arbitrary **host path** straight into the container — no Docker bookkeeping, the host's directory contents simply *shadow* whatever was at the target path. **tmpfs** is an in-memory filesystem (`tmpfs`), never persisted to disk, gone when the container stops — ideal for secrets/scratch.

A behavior that trips people up: when you mount an **empty named volume** onto a path that **already has content in the image**, Docker **copies the image's existing files into the volume** on first use. A **bind mount** does *not* do this — it shadows the image path entirely, so if the host dir is empty the container sees an empty directory (which is exactly why bind-mounting over `/app` can "hide" your image's files in dev). This asymmetry is a classic interview/debugging gotcha.

```
                source                lifecycle        empty-mount over image content
named volume    /var/lib/docker/...   Docker-managed   COPIES image files into volume
bind mount      host path             host-managed     SHADOWS (image files hidden)
tmpfs           RAM                    dies with ctr    n/a (always empty, in-memory)
```

Two more kernel-level concerns. **Permissions/ownership**: bind mounts preserve the host's UID/GID, so a container running as UID 10001 may be unable to write a host dir owned by your laptop user — and SELinux hosts need the `:z`/`:Z` relabel suffix or writes are denied. **Mount propagation** (`rprivate` default, vs `shared`/`rslave`) controls whether mounts created *under* a bind mount propagate between host and container — relevant for tools that mount things at runtime (e.g., a container managing other mounts). Knowing these distinctions is what lets you choose volumes for portable persistence (DB data), bind mounts for dev live-reload, and tmpfs for secrets, and to debug the "my files disappeared / permission denied / data didn't persist" trio correctly.

### 🟠 Advanced — extended

#### Q45. [Theory] containerd, CRI-O, and Docker — how do they relate, and what was the "dockershim removal" actually about?

**containerd** is a CNCF graduated daemon that manages the full container lifecycle (image pull, snapshot/storage via snapshotters, container exec via shims+runc) — it's the *core engine* that Docker itself delegates to. **Docker Engine** is containerd plus higher-level developer ergonomics: the build system (BuildKit), the `docker` CLI/API, networking helpers, Compose, volumes UX. **CRI-O** is a *different*, deliberately minimal runtime built specifically to implement Kubernetes' **Container Runtime Interface (CRI)** and nothing else — no build, no Compose, just "run pods for kubelet."

The "dockershim removal" (Kubernetes 1.24, 2022) is widely misunderstood as "Kubernetes dropped Docker images." It did not — images are OCI artifacts and run identically everywhere. What was removed was **dockershim**, the adapter the kubelet used to talk to the *Docker Engine* (which historically didn't speak CRI). Since Docker is "containerd + extras," Kubernetes simply talks to **containerd's CRI plugin** (or CRI-O) directly and skips Docker Engine on the node. You still `docker build` images; the node just doesn't run the full Docker daemon to launch them.

```
Before 1.24:  kubelet → dockershim → dockerd → containerd → runc
After  1.24:  kubelet ──CRI──► containerd (CRI plugin) → runc
                       └─CRI──► CRI-O → runc            (alternative)
Docker Engine (dev box / CI): docker CLI → dockerd → containerd → runc
```

The takeaway an interviewer wants: the *image* is a portable OCI artifact independent of the runtime; the *runtime stack* on a Kubernetes node was streamlined by removing a redundant adapter and the heavyweight Docker daemon. This is also why `nerdctl` (a Docker-CLI-compatible client for containerd) and `crictl` exist — they let you operate the same containerd that Kubernetes uses without the Docker layer.

#### Q46. [Theory] runc vs crun vs gVisor vs Kata — what is the OCI runtime contract and how do these implementations differ?

All four implement the **OCI runtime-spec**: given a "bundle" (a root filesystem plus a `config.json` describing namespaces, mounts, cgroups, capabilities, seccomp, and the process to run), `create`/`start`/`kill`/`delete` it. Because the contract is standardized, containerd can invoke any of them interchangeably — you select one with `--runtime`. What differs is *how* they realize isolation, which is a direct trade between performance/compatibility and security.

**runc** (Go, the reference) and **crun** (C) are *native* runtimes: they set up real Linux namespaces/cgroups and run your process directly on the host kernel. crun is smaller and faster to start (notably better for high-density/short-lived workloads and cgroups v2). Both give full kernel compatibility but share the host kernel — the syscall surface is your attack surface. **gVisor** (`runsc`) inserts a **userspace kernel (Sentry)** that intercepts and re-implements syscalls, so the application talks to gVisor rather than the host kernel — far smaller host exposure, at a cost in syscall latency and occasional incompatibility. **Kata Containers** boots each container/pod inside a **lightweight VM** (via Firecracker/QEMU) with its own guest kernel — near-VM isolation with container-like UX.

```
                  shares host kernel?   isolation     perf/compat
runc / crun       yes (real namespaces)  standard      best perf, full compat
gVisor (runsc)    no (userspace kernel)  strong        ~syscall overhead, some gaps
Kata/Firecracker  no (guest kernel/VM)   strongest      VM-grade, slight startup cost
```

The architectural judgment: use runc/crun for your own trusted microservices (you want full compatibility and minimal overhead); reach for gVisor or Kata when you must run **untrusted code** (multi-tenant SaaS, CI for arbitrary user repos, serverless) where a single shared-kernel breakout is unacceptable. They plug into the same containerd because they honor the same runtime-spec — that's the whole value of the OCI standard.

#### Q47. [Theory] Why was the storage-driver landscape (devicemapper, AUFS, btrfs, ZFS, overlay2) such a mess, and why did overlay2 win?

Early Docker had to provide copy-on-write layering on whatever filesystem the host had, and no single approach worked everywhere — so it shipped *multiple* storage drivers, each with sharp edges. **AUFS** was the original union FS but never merged into the mainline kernel, so it required patched kernels (Debian/Ubuntu only). **devicemapper** (especially the default `loop-lvm` mode) did block-level CoW and was infamously slow and fragile, with thin-pool exhaustion taking hosts down. **btrfs** and **zfs** drivers leveraged those filesystems' native snapshots but tied you to running your whole `/var/lib/docker` on that FS and inherited their operational quirks.

**overlay2** won because it builds on **OverlayFS**, which was *merged into the mainline Linux kernel*, so it works out of the box on any modern distro without patches or special filesystems. It does file-level (not block-level) CoW, is fast for typical read-mostly container workloads, handles many shared lower layers efficiently, and avoided devicemapper's thin-pool operational hazards. By the late 2010s it became the universal default, and the others are now legacy/deprecated.

```
driver          basis                 problem that killed it
─────────       ─────────────         ──────────────────────────────
aufs            out-of-tree union FS  not in mainline kernel → patched kernels only
devicemapper    LVM thin block CoW    loop-lvm slow; thin-pool exhaustion outages
btrfs / zfs     native FS snapshots   must run /var/lib/docker on that FS; quirks
overlay2 ★      mainline OverlayFS    (the winner: in-kernel, fast, file-level CoW)
```

The deeper lesson is about *betting on the kernel*: the driver that became standard is the one whose mechanism lives in upstream Linux, so it inherits broad support, testing, and longevity. This is the same reasoning that makes you prefer features merged upstream (overlay2, cgroups v2, namespaces) over clever out-of-tree solutions, and it's why "which storage driver" is a near-non-question today even though it consumed enormous operational effort a decade ago.

#### Q48. [Theory] Explain copy-on-write at the directory and inode level in OverlayFS — what is `copy_up`, what are whiteouts and opaque directories?

OverlayFS presents a **merged** view of read-only **lowerdirs** (image layers) beneath a single read-write **upperdir** (the container's writable layer). Reads resolve top-down: the upperdir is checked first, then each lower layer, first match wins. The interesting mechanics happen on *writes*, and they operate at the granularity of whole files via an operation called **`copy_up`**: the first time a container modifies a file that exists only in a lower (read-only) layer, OverlayFS copies the *entire* file up into the upperdir, then applies the write there. This is why the first write to a 2 GB file in a base layer is slow (full copy) even if you change one byte, and why write-heavy data belongs on a volume, not the CoW layer.

**Deletes and renames** can't modify the read-only lowers, so OverlayFS uses two markers in the upperdir. A **whiteout** is a special character device (major 0, minor 0) with the deleted file's name; when the merged view sees a whiteout, it hides the corresponding lower file. An **opaque directory** is a directory in the upperdir carrying the `trusted.overlay.opaque="y"` xattr, meaning "do not merge any lower version of this directory" — used when a directory is replaced wholesale so stale lower entries don't bleed through.

```
write to lower-only file  → copy_up (whole file → upperdir) → write there
delete lower file         → create whiteout (char dev 0:0) in upperdir → hides it
replace whole dir         → opaque dir xattr → lower dir contents not merged
```

Two practical consequences interviewers like: (1) because directories aren't copied up wholesale, *metadata* changes and large nested trees can produce surprising upperdir growth and inode churn — heavy small-file workloads (e.g., extracting then deleting build artifacts in the same image) bloat layers. (2) This file-granular CoW is exactly why a single layer "deletes" still don't shrink the image: a whiteout in a *later* layer hides but does not remove the bytes in an *earlier* layer, so the earlier layer's tarball still ships the file. Understanding `copy_up`/whiteouts is what lets you reason precisely about layer size and write performance.

#### Q49. [Theory] How does a `docker pull` / `docker push` authenticate and transfer? Walk the registry token flow and content addressing.

The registry protocol (OCI distribution-spec, the `/v2/` API) uses a **token (bearer) auth** dance rather than sending credentials on every call. The client first hits `GET /v2/` unauthenticated; the registry replies `401` with a `WWW-Authenticate` header naming an **auth realm** and the **scope** required (e.g., `repository:library/nginx:pull`). The client then requests a short-lived **bearer token** from that auth server (presenting credentials from `~/.docker/config.json` for private repos), and retries the original request with `Authorization: Bearer <token>`. Docker Hub's auth server is separate from its registry, which is why an outage in one can break the other independently, and why rate-limits are enforced at the token-issuing step.

Transfer is driven by **content addressing**. To pull, the client fetches the **manifest** (or image index, then the platform-specific manifest), reads the digests of the config and layers, then issues `GET /v2/<name>/blobs/sha256:<digest>` for each blob it doesn't already have locally — so shared layers are never re-downloaded. To push, the client first does a `HEAD`/`POST` "blob mount" check for each layer; if the registry already has that digest (possibly from another repo via cross-repo mount), the layer is **skipped** entirely, and only missing blobs are uploaded before the manifest is `PUT` last.

```
pull:
  GET /v2/                → 401 + WWW-Authenticate(realm, scope)
  GET <realm>?scope=...   → bearer token  (creds from docker config.json)
  GET /v2/<n>/manifests/<tag>   (Authorization: Bearer …) → manifest (digest = identity)
  GET /v2/<n>/blobs/sha256:…     → config + each layer NOT already cached
push:
  HEAD blob digest → exists? skip : upload ; then PUT manifest last
```

The conceptual payoffs: digests make transfers **idempotent and deduplicated** (you only move bytes you don't have), the token model enables **fine-grained, short-lived, per-scope** authorization (great for CI), and pinning by digest means a pull is **tamper-evident** — if a registry returns content whose hash doesn't match the requested digest, the client rejects it. This is the foundation that cosign signatures and SLSA attestations build on top of.

#### Q50. [Theory] What is user-namespace remapping (and rootless Docker), and how does it change the "container root = host root" problem?

By default, UID 0 inside a container *is* UID 0 on the host — the kernel checks credentials against the same global UID space, so a process running as root in the container has root's privileges if it ever touches the host (via a leaked socket, a bad bind mount, or a runtime breakout). **User-namespace remapping** breaks this identity: it maps the container's UID/GID range onto a *different, unprivileged* host range. So container-root (UID 0) might actually be host UID 100000, with no real privileges on the host. You enable it with `userns-remap` in `daemon.json`, and the kernel maintains `/proc/<pid>/uid_map` to translate.

**Rootless Docker** goes further: the *entire daemon* runs as an unprivileged user, relying on user namespaces (plus `slirp4netns`/`pasta` for networking and `fuse-overlayfs` or native rootless overlay for storage) so that nothing in the stack needs real root. Container-root is mapped into the launching user's subordinate UID range (`/etc/subuid`, `/etc/subgid`). This dramatically shrinks the blast radius: a breakout lands you as an unprivileged host user, not host root.

```
default:        container UID 0  ─────────►  host UID 0   (real root — dangerous)
userns-remap:   container UID 0  ──map────►  host UID 100000 (unprivileged)
rootless:       daemon + container run entirely as unprivileged host user
                container UID 0  ──map────►  caller's /etc/subuid range
```

The trade-offs are why it isn't universal: remapped UIDs make **volume permissions** confusing (files written by container-root are owned by host UID 100000, which can surprise bind-mount workflows), some features are restricted (host networking, certain mounts), and rootless networking is slightly slower and historically couldn't bind ports <1024 without extra config. The interview-grade summary: namespaces isolate *what you see*, but UID remapping isolates *who you are* — and combining rootless + userns + `no-new-privileges` + dropped capabilities is the modern defense against the "container root is host root" foot-gun.

#### Q60. [Theory] How does the Linux capabilities model work, and why is dropping capabilities better than choosing root vs non-root alone?

Classic Unix was binary: you were either root (UID 0, allowed everything) or not. **Capabilities** decompose root's omnipotence into ~40 discrete privileges (e.g., `CAP_NET_BIND_SERVICE` = bind ports <1024, `CAP_CHOWN` = change file ownership, `CAP_SYS_ADMIN` = a huge grab-bag, `CAP_NET_ADMIN`, `CAP_SYS_PTRACE`). A process carries capability sets (effective/permitted/inheritable/bounding/ambient), and the kernel checks the *specific* capability for each privileged operation rather than just "is UID 0." Docker runs containers with a **restricted default set** — it drops most capabilities and keeps a small whitelist — so even a container running as root is *not* full host root.

This is why capabilities are an orthogonal, finer lever than the root/non-root choice. A process can be **UID 0 but nearly powerless** (all caps dropped), or **non-root but still dangerous** if it holds `CAP_SYS_ADMIN`. The least-privilege pattern is `--cap-drop ALL` then `--cap-add` only what's needed — e.g., a web server that must bind port 80 gets `--cap-drop ALL --cap-add NET_BIND_SERVICE` and nothing else. This shrinks the attack surface dramatically: a compromised process simply *cannot* perform operations whose capability it lacks, regardless of its UID.

```
old model:   root (UID 0) = ALL privileges | non-root = none
capabilities: split root into ~40 pieces, checked per-operation
   Docker default: most dropped, small whitelist kept
   hardened:  --cap-drop ALL --cap-add NET_BIND_SERVICE   (bind <1024 only)
   danger:    --cap-add SYS_ADMIN  ≈ root (mount, namespaces, etc.)
```

The interview-grade nuance: never treat "runs as non-root" as sufficient on its own, and never blanket-add `SYS_ADMIN` (it's so broad it's effectively root and enables many breakout primitives). Combine **drop-all-caps + non-root UID + `no-new-privileges`** (which blocks setuid escalation so a dropped-cap process can't regain privilege) — together they make a container that even if compromised can do very little to the host. `--privileged` is the opposite extreme: it grants *all* capabilities plus device access plus disables seccomp, which is why it's "host root in a box."

#### Q61. [Theory] What is the default seccomp profile, how does it filter syscalls, and why is disabling it the wrong fix for a permission error?

**seccomp** (secure computing mode) is a kernel facility that filters the **syscalls** a process may make, using a BPF program attached to the process. Docker ships a **default seccomp profile** (a JSON allow/deny list) that it compiles into a BPF filter and installs on each container. The profile **allows the large majority** of syscalls an app needs but **blocks ~40+ dangerous or rarely-needed ones** — e.g., `keyctl` (kernel keyring, source of CVEs), `mount`/`umount` (filesystem manipulation), `ptrace` of unrelated processes, `bpf`, `reboot`, kernel-module loading. When a container makes a denied syscall, the kernel returns `EPERM` (or kills it, depending on the action), which surfaces as a confusing "operation not permitted" error.

The reason `--security-opt seccomp=unconfined` is the *wrong* reflex is that it removes the filter for **all** syscalls, re-opening the entire dangerous set just to unblock the one your app legitimately needs. That's trading a precise control for none. The correct approach is to **identify the specific blocked syscall** (with `strace -f -e trace=all` or kernel audit logs showing `SECCOMP` denials), then craft a **custom profile** that starts from the default and adds *only* that syscall to the allow list — preserving all the other protections.

```
app makes syscall ──► seccomp BPF filter (Docker default profile)
   syscall in allow list?  → proceed
   syscall denied (e.g., keyctl, mount, ptrace) → EPERM / killed
WRONG fix: seccomp=unconfined   → removes ALL filtering (whole dangerous set open)
RIGHT fix: custom profile = default + the one needed syscall (e.g., io_uring)
```

A current, concrete example: newer kernels' **`io_uring`** syscalls and certain `clone3`/`faccessat2` calls have historically been blocked or only conditionally allowed by default profiles, so a modern runtime can hit `EPERM` on an older profile — the right fix is a profile update for those specific syscalls, not disabling seccomp. The principle to articulate: a security control denying something is *information* about what your workload does; debug the actual constraint and grant exactly it, rather than removing the control wholesale (which is the same anti-pattern as `--privileged` "to make it work").

#### Q62. [Theory] How does the daemon clean up disk, and what's the precise difference between dangling images, unused images, and `docker system prune`?

Docker accumulates four kinds of reclaimable data: stopped **containers** (and their writable layers), **images** (layers + manifests), **volumes**, and **build cache** (BuildKit's content-addressed cache). `docker system df` shows each category's size and how much is reclaimable. The cleanup commands differ in *what they consider unused*, and the distinctions cause real "where did my image go" and "why is disk still full" incidents.

A **dangling image** is one with **no tag** — typically an old image layer orphaned when a tag was moved to a rebuilt image (the `<none>:<none>` entries). `docker image prune` (no flags) removes **only dangling** images. An **unused image** is any image **not referenced by a container** (tagged or not); `docker image prune -a` removes all of those, which is far more aggressive — it will delete tagged images you're keeping around but not currently running, so it's a frequent foot-gun on build hosts. **`docker system prune`** removes stopped containers, dangling images, unused **networks**, and (with `--volumes`) volumes; add `-a` and it also removes unused images. Build cache is pruned separately via `docker builder prune`.

```
docker image prune       → dangling only  (<none>:<none> orphans)
docker image prune -a    → all images not used by a container (deletes tagged ones!)
docker system prune      → stopped containers + dangling images + unused networks
docker system prune -a --volumes
                         → + unused images + volumes  (DESTRUCTIVE — data loss risk)
docker builder prune     → BuildKit cache (often the biggest hidden consumer)
```

The operational gotchas worth stating: (1) **volumes are NOT removed by default** by `system prune` — you must pass `--volumes`, which is the safe default (you don't want to nuke a database volume) but also why volumes silently accumulate. (2) **Build cache is frequently the largest consumer** on CI hosts and is invisible to `image prune`; teams forget `builder prune` and run out of disk despite "no big images." (3) A full `/var/lib/docker` causes *write* failures (containers can't write their CoW layer, the json-file log driver stalls) — so GC policy (`--filter until=…`, scheduled prunes, log rotation) is a production necessity, not housekeeping.

### 🔴 Expert — extended

#### Q51. [Theory] How does BuildKit differ architecturally from the legacy builder? Explain LLB, the DAG, and concurrent/cache-aware execution.

The **legacy builder** executed a Dockerfile strictly **sequentially**: each instruction created an intermediate container, ran, committed a layer, and fed the next instruction — a linear chain with no parallelism and a coarse cache. **BuildKit** instead *compiles* the Dockerfile (via a frontend) into an intermediate representation called **LLB (Low-Level Build definition)** — a **content-addressed directed acyclic graph (DAG)** of operations (exec, file-copy, mount, etc.). Because it's a DAG with explicit dependencies rather than a list, BuildKit can analyze the whole build up front.

That graph model unlocks several capabilities the old builder couldn't have. **Parallelism:** independent branches (e.g., two `FROM` stages that don't depend on each other) build *concurrently*. **Pruning:** only the parts of the graph the requested target actually needs are executed — unused stages are skipped entirely. **Fine-grained, distributable cache:** each LLB vertex is content-addressed, so cache can be imported/exported to a registry (`--cache-to/--cache-from`) and shared across machines, and **cache mounts**/**secret mounts**/**SSH mounts** exist as first-class mount operations rather than hacks. The **frontend** is also pluggable — the Dockerfile syntax is just one frontend (`# syntax=docker/dockerfile:1.7`), and you can swap it to evolve features without changing BuildKit's core.

```
Dockerfile ──frontend──► LLB DAG (content-addressed vertices)
                              │
   solve: walk DAG, run independent branches in PARALLEL,
          reuse vertices from local/registry cache, skip unused stages
                              │
                              ▼
                       OCI image (+ optional SBOM/provenance attestations)
```

The practical wins flow directly from "it's a DAG, not a script": multi-stage builds get faster (parallel stages, targeted `--target`), CI cache becomes portable across ephemeral runners, secrets stop leaking into layers, and the same engine produces SBOM/provenance attestations during the solve. When asked "why BuildKit," the crisp answer is: it replaced a linear, container-per-step builder with a content-addressed, concurrent, cache-aware graph solver — and that architectural shift is what makes modern features (cache/secret mounts, multi-arch, attestations, remote cache) possible.

#### Q52. [Theory] Inside the kernel, what determines which container gets OOM-killed, and how do cgroup memory limits, reclaim, and oom_score interact?

A container's `--memory` limit sets the **memory cgroup limit** (`memory.max` on cgroups v2). The kernel charges the container's anonymous memory *and* its page cache against this limit. When the cgroup approaches the limit, the kernel first attempts **reclaim** within that cgroup — evicting clean page cache, writing back dirty pages, swapping if swap is allowed. Only when reclaim cannot free enough does the **cgroup OOM killer** fire, and critically it kills a process **within that cgroup**, not arbitrarily across the host — so one container's memory blowup shouldn't take down its neighbors (assuming limits are set).

Which process dies is governed by an **`oom_score`** the kernel computes per process, derived mainly from its memory footprint (bigger = more likely to be killed) and tunable via **`oom_score_adj`** (−1000 to +1000; −1000 makes a process unkillable by the OOM killer). The exit you observe is **137** (`128 + SIGKILL(9)`), and `docker inspect` shows `State.OOMKilled=true`. A separate, nastier failure is the **host/global OOM killer**: if containers are *unlimited* or oversubscribed beyond physical RAM, the kernel's global OOM killer picks a victim across the whole host — which is how an unlimited container OOMs an innocent neighbor.

```
container memory grows → hits memory.max
   → in-cgroup reclaim (drop clean cache, writeback, swap if allowed)
       → still over? cgroup OOM killer fires WITHIN this cgroup
           → victim chosen by oom_score (≈ footprint, adj by oom_score_adj)
               → SIGKILL → container exit 137, OOMKilled=true
(no limits / oversubscribed → GLOBAL OOM killer can hit any container)
```

Two runtime gotchas dominate real incidents. First, **runtime heaps must respect the cgroup limit**: a JVM that sizes its heap from host RAM (pre-`UseContainerSupport`/`MaxRAMPercentage`) or a Node process without `--max-old-space-size` will grow past the cgroup limit and get killed — the fix is making the runtime container-aware, not raising the limit blindly. Second, **page cache counts**: a process that reads/writes huge files inflates cgroup memory via cache and can trigger reclaim/OOM even though "the app" isn't leaking — which is another reason write-heavy data belongs on a tuned volume and why setting *some* limit (so the cgroup killer stays local) is safer than running unlimited.

#### Q53. [Theory] How do "deleted" files still bloat an image, and what are the real ways (squash, multi-stage, single-RUN, --link) to actually remove them?

Because each layer is an immutable diff over the one below, **a file added in layer N and deleted in layer N+2 is still physically present in layer N's tarball** — the deletion is recorded as a *whiteout* in the later layer that merely *hides* it at runtime. So `RUN curl -o big.tar … && make && rm big.tar` across multiple `RUN`s leaves `big.tar`'s bytes shipped in the layer where it was created. `docker history` will show a fat layer even though the running container's filesystem doesn't contain the file. This is the single most common cause of "why is my image huge when the final FS is small."

The genuine fixes all share one idea: **never let the unwanted bytes become a persisted layer in the final image.** (1) **Single `RUN`**: download, use, and delete in *one* instruction so the layer's net diff already excludes the file (`RUN curl … && make && rm big.tar`). (2) **Multi-stage**: do the dirty work (toolchains, intermediate artifacts) in a build stage and `COPY --from` only the final product into a clean runtime stage — the build stage's layers are never in the shipped image. (3) **`--squash`** (or BuildKit equivalents) flattens all layers into one, discarding intermediate add/delete history — but you lose layer sharing/cache and it's a blunt tool. (4) **`COPY --link`** (BuildKit) creates layers that are independent of the parent chain, improving cache stability and avoiding some redundant copy-up, though it's about layer independence more than deletion.

```
BAD  (file persists in layer 1):
  RUN curl -o big.tar …      # layer 1: +big.tar  (shipped forever)
  RUN make                   # layer 2
  RUN rm big.tar             # layer 3: whiteout (HIDES, doesn't remove bytes)

GOOD (net diff excludes the file):
  RUN curl -o big.tar … && make && rm big.tar   # one layer, big.tar not in diff
BEST (toolchain never in final image):
  FROM build AS b ; RUN make …
  FROM runtime ; COPY --from=b /out/app /app     # only the artifact ships
```

The interviewer is checking whether you understand that **layers are append-only diffs**, so removal must happen *before commit*, not after. In modern practice the answer is almost always **multi-stage** (cleanest, preserves caching, smallest attack surface), with single-`RUN` cleanup for within-stage temporaries; `--squash` is a last resort because it trades size for the loss of layer sharing and cache reuse that make pulls and rebuilds cheap.

#### Q54. [Theory] Compare image trust/integrity mechanisms: digest pinning, Docker Content Trust/Notary v1, and Sigstore/cosign — what does each actually guarantee?

These three operate at different points and guarantee different things. **Digest pinning** (`image@sha256:…`) guarantees **immutability and tamper-evidence on transfer**: you always get the exact bytes whose manifest hashes to that digest, and the client rejects any content that doesn't match. But a digest by itself says nothing about *who produced it* or *whether you should trust it* — anyone can publish a digest; pinning just freezes *which* one you consume.

**Docker Content Trust (DCT)**, backed by **Notary v1** and **The Update Framework (TUF)**, added *publisher signing* on top of tags: with `DOCKER_CONTENT_TRUST=1`, pushing signs the tag→digest mapping with publisher keys, and pulling verifies the signature and resolves the tag to the signed digest — protecting against tag tampering and rollback/freeze attacks via TUF's role/key hierarchy. Its weakness was operational: a separate Notary server, awkward key management, and weak ecosystem/admission-controller integration, so adoption stalled. **Sigstore/cosign** is the modern successor: it signs the image **by digest** and stores the signature **as an OCI artifact in the registry itself** (referrers), supports **keyless** signing (short-lived certs from Fulcio tied to an OIDC identity, logged in the **Rekor** transparency log), and attaches **attestations** (SBOM, SLSA provenance) the same way. Crucially it integrates with admission controllers (Kyverno/Gatekeeper/policy-controller) to *enforce* "only signed images run."

```
mechanism            guarantees                              gap / note
─────────────────    ──────────────────────────────────────  ───────────────────
digest pin @sha256   exact bytes, tamper-evident transfer     no "who" / no trust decision
DCT / Notary v1+TUF  publisher-signed tag→digest, anti-freeze  ops-heavy, weak integration
cosign / Sigstore    signed-by-digest + keyless + Rekor log    the modern standard;
                     + SBOM/provenance attestations            enforce via admission ctrl
```

The way to frame it in an interview: pinning gives you **integrity** ("the bytes didn't change"), signing gives you **authenticity + provenance** ("a trusted identity produced these exact bytes, and here's the verifiable build record"), and you want **both** — pin by digest *and* verify a cosign signature/attestation at admission. The industry moved from Notary v1 to Sigstore primarily because keyless signing + transparency log + in-registry artifacts + admission-controller enforcement made signing operationally feasible at scale, which is what post-SolarWinds supply-chain mandates (SLSA) actually require.

#### Q55. [Theory] What changed when Docker Engine adopted containerd image storage (the "containerd image store"), and why does it matter for multi-arch, lazy pulling, and attestations?

Historically Docker Engine used its **own image store and graphdrivers** (the overlay2 layer management lived in dockerd), while *containerd* — which dockerd already used for the *runtime* — had its own separate content store and snapshotters. That split meant Docker's image handling couldn't easily use containerd's newer capabilities. Recent Docker Engine versions adopt the **containerd image store** as the backend, unifying image storage on containerd's content store and **snapshotter** plugin model instead of dockerd's legacy graphdrivers.

This isn't cosmetic — it unlocks features that were awkward or impossible before. **Native multi-arch on one host**: the containerd store can hold a full image *index* (all architectures) locally, so `docker build --platform linux/amd64,linux/arm64` can load and keep multiple arches in the local store, where the old store essentially tracked one platform per tag. **Lazy/snapshotter-based pulling**: containerd's pluggable snapshotters enable **eStargz/SOCI** lazy pulling (start the container before the whole image is fetched), which the legacy overlay2 graphdriver couldn't do. **Attestation-carrying images**: build outputs with SBOM/provenance attestations (which live as extra manifests in the index) are stored faithfully rather than being flattened away.

```
legacy:   dockerd graphdrivers (overlay2)  ──┐ separate
          containerd content store          ─┘ stores
                                                ↓ unified
containerd image store:
  dockerd → containerd content store + snapshotters
     → holds full multi-arch index locally
     → pluggable snapshotters (overlayfs, stargz/SOCI lazy pull)
     → preserves SBOM/provenance attestation manifests
```

The reason it matters to a staff engineer: the containerd image store closes the gap between "what BuildKit/containerd can produce" (multi-arch indexes, attestations, lazy-pullable images) and "what the local Docker engine can faithfully store and run." It's an enabling change — the visible features (keep all arches locally, faster cold starts via lazy pull, intact provenance) are downstream of moving image storage onto containerd's content store and snapshotter architecture, which is also exactly the store Kubernetes nodes use, further unifying the dev/runtime stack.

#### Q63. [Theory] Explain how lazy/streaming image pulls (eStargz, SOCI) work and what they fundamentally trade off versus traditional pulls.

A traditional pull must download and decompress **every layer in full** before the container can start, because OverlayFS needs the complete lower directories to assemble the rootfs. For a multi-GB ML or data-science image, that's minutes of cold-start latency even though the container often only reads a small fraction of the files at startup. **Lazy pulling** breaks this assumption: it lets the container **start before the image is fully present**, fetching file contents **on demand** as the application actually `open()`s them, via a specialized snapshotter.

The two main approaches differ in whether they require a new image format. **eStargz** (enhanced stargz) is a **seekable, backward-compatible** layer format: layers are still valid gzip but indexed so individual files can be fetched by HTTP range request, plus a "prioritized files" set is prefetched for the likely startup path. It requires **converting/building** images in the eStargz format. **SOCI (Seekable OCI)** instead leaves the **original image unchanged** and generates a separate **index artifact** (stored in the registry as a referrer) that maps file offsets within standard layers, so you can lazy-pull images you didn't rebuild. Both rely on a **FUSE-based or remote snapshotter** that lazily backs the filesystem and fetches blocks on cache miss.

```
traditional:  pull ALL layers fully ──► assemble rootfs ──► start  (cold start = pull time)
lazy (eStargz/SOCI):
   start NOW with a remote snapshotter (FUSE)
   app open(file) ──► cache hit? serve : HTTP range-fetch that file's bytes ──► serve
   (background prefetch of prioritized files)
```

The fundamental trade-off is **startup latency vs. steady-state I/O and registry load**: you turn a big up-front download into many small on-demand fetches, so cold start drops from minutes to seconds (huge for autoscaling, serverless, and CI), but the first access to each file pays network latency, and the registry must serve range requests and stay reachable for the container's early life. You also accept added moving parts (a snapshotter plugin, index generation/conversion). The judgment: lazy pulling is a clear win for large images with a small startup working set and bursty scaling; for tiny images already pulling in a second, it adds complexity for little gain. Its viability on Docker specifically is one of the things the containerd image store + snapshotter model (Q55) unlocks.

#### Q64. [Theory] Why can't Linux containers run a different-OS kernel, and how do Docker Desktop on macOS/Windows and Windows containers actually work?

A container is just host processes in namespaces sharing the **host kernel** — there is no guest kernel. So a "container" can only run binaries that the *host kernel* can execute. A Linux image expects Linux syscalls; a Windows image expects the NT kernel's API. You therefore **cannot natively run a Linux container on a Windows kernel or vice versa** — the syscall ABI is fundamentally different. This is also why the *architecture* must match (or be emulated): an arm64 binary needs an arm64 kernel/CPU or QEMU user-mode emulation.

**Docker Desktop on macOS/Windows** resolves this by quietly running a **lightweight Linux VM** (on macOS via Apple's Virtualization.framework / a HyperKit-style VM; on Windows via WSL2 or Hyper-V). The `docker` CLI on your host talks to a daemon **inside that Linux VM**, where the actual containers run on a real Linux kernel. So "Docker on a Mac" is Linux containers on a hidden Linux VM — which explains why `host` networking and certain bind-mount/permission/performance behaviors differ from native Linux (filesystem sharing crosses the VM boundary).

```
macOS / Windows host
   docker CLI ──► (lightweight Linux VM: WSL2 / Virtualization.framework)
                     dockerd → containerd → runc → Linux containers
Windows containers (on Windows Server / Win client):
   process-isolated  : share host Windows kernel (must match build/version)
   Hyper-V isolated  : each container in a tiny Hyper-V VM (own kernel, stronger isolation)
```

**Windows containers** are a separate thing entirely: they run **Windows images on the Windows kernel**, in two modes. **Process isolation** shares the host's Windows kernel (fast, dense) but demands tight host/container OS **version matching** because Windows doesn't guarantee kernel ABI stability across builds. **Hyper-V isolation** wraps each container in a minimal Hyper-V VM with its own kernel, relaxing the version-match requirement and giving stronger isolation at a startup/overhead cost. The unifying principle for the interview: containers are kernel-sharing, so *the kernel and architecture are part of the contract* — cross-OS or cross-arch "just works" only because something (a hidden VM, QEMU, or Hyper-V) is supplying the matching kernel/CPU underneath.

#### Q65. [Practical] You must run an arm64 image on an amd64 host (or vice versa). What mechanisms make that possible, and what are the performance and correctness implications?

The clean path is to **not** cross-run at all: build a **multi-arch image** (a manifest list / image index via `docker buildx build --platform linux/amd64,linux/arm64 --push`) so the registry holds a native variant per architecture, and each host automatically pulls the one matching its CPU. This is the production answer — no emulation, native speed everywhere. The whole reason this works transparently is the **image index** indirection (Q35): one tag, multiple per-arch manifests, client picks the right one.

When you genuinely must run a foreign-arch image on a host that lacks that CPU, the mechanism is **user-mode CPU emulation via QEMU**, wired in by the kernel's **`binfmt_misc`** facility. You register QEMU handlers (commonly with `docker run --privileged tonistiigi/binfmt --install all`); then when the kernel sees an arm64 ELF on an amd64 host, `binfmt_misc` transparently launches it under `qemu-aarch64`, which interprets the foreign instructions. BuildKit uses exactly this to *build* foreign-arch images on a single host without native hardware.

```
native (best):   buildx --platform amd64,arm64 → image index → host pulls its native arch
emulated:        foreign ELF detected by binfmt_misc → QEMU user-mode translates instructions
   host (amd64) running arm64 binary → qemu-aarch64 → (slow, instruction-by-instruction)
faster alt:      native remote builders per arch (buildx --driver remote / Build Cloud)
```

The implications are significant. **Performance:** QEMU user-mode emulation is *interpreted* (often 2–10× slower, sometimes far worse for CPU-bound or JIT-heavy workloads), so it's fine for *building* and light tasks but a poor choice for *running* production services cross-arch. **Correctness:** emulation isn't perfect — some syscalls, threading edge cases, `mmap`/atomics behavior, and JITs (Java, V8, .NET) can misbehave or crash under QEMU, and time-sensitive tests give misleading results. The staff-level recommendation: use **native multi-arch builds** (or native remote/cloud builders per arch) for anything you ship and run; reserve QEMU/`binfmt_misc` emulation for convenience during local builds or one-off inspection, never as the steady-state way to run a foreign architecture in production.

#### Q66. [Theory] What guarantees actually make a container build reproducible, and where does Docker leak non-determinism?

"Reproducible" means: the same source inputs produce a byte-identical image (or at least an identical filesystem with an identical digest) regardless of when or where you build. Containers leak non-determinism from several places, and a reproducible pipeline closes each: (1) **floating bases** — `FROM node:22` resolves to whatever the tag points at *today*, so you must **pin by digest** (`FROM node:22@sha256:…`); (2) **network-fetched, unversioned dependencies** — `apt-get install curl` or `pip install requests` grab "latest," so you pin exact versions and use **lockfiles**; (3) **timestamps** — files get `mtime`s and the image config records build time, so identical content yields different bytes unless you normalize via **`SOURCE_DATE_EPOCH`** (BuildKit honors it to clamp timestamps); (4) **ordering/locale/UID nondeterminism** — tools that emit files in filesystem-iteration order, or embed hostnames/build paths.

```
non-determinism source        →  reproducibility control
floating FROM tag             →  pin base by @sha256 digest
unpinned pkg installs         →  exact versions + lockfiles (no "latest")
file/config timestamps        →  SOURCE_DATE_EPOCH (BuildKit clamps mtimes)
network state / build cache    →  vendored deps or hash-checked downloads
ordering / locale / build path →  sorted output, fixed LC_ALL, no embedded paths
```

Reproducibility matters because it's the foundation of **supply-chain trust**: if a build is reproducible, an independent rebuilder can verify that a published image's digest matches the source — which is what makes **SLSA provenance** meaningful (you can prove "this digest came from this commit via this builder") and what lets two people confirm they're running identical bytes. It also makes caching honest (same inputs → same cache key) and incident response precise (a digest maps to exact, re-creatable contents).

In practice perfect bit-for-bit reproducibility is hard (some toolchains embed unavoidable nondeterminism), so teams aim for **practical reproducibility**: pin everything by digest/version, set `SOURCE_DATE_EPOCH`, avoid `latest`, build in clean isolated environments (no leaking host state, which is partly why daemonless builders like Kaniko/Buildah and ephemeral CI runners help), and then **attest** the result with provenance so the running image is provably the build output rather than asserting bit-identity alone. The interview point: reproducibility is achieved by *eliminating undeclared inputs* (network, time, floating refs), which is the same discipline as digest-pinning and lockfiles taken to its logical end.

#### Q67. [Practical] When does a single host become the bottleneck for plain Docker/Compose, and what specifically does an orchestrator add that you'd otherwise have to build yourself?

Plain Docker (and Compose) manage containers on **one host**, with no notion of a cluster. You hit the wall along several axes at once: (1) **capacity** — you can't scale beyond one machine's CPU/RAM, and there's no bin-packing of containers across nodes; (2) **availability** — if that host dies, everything dies; there's no rescheduling onto healthy nodes; (3) **self-healing** — Compose restart policies react to *process exit* but won't recreate a container on a *different* node, drain an unhealthy instance, or respond to node failure; (4) **rollouts** — no built-in rolling update, canary, surge/maxUnavailable control, or automatic rollback; (5) **service networking at scale** — DNS round-robin on one host isn't load balancing across replicas on many hosts, with health-aware endpoints.

```
plain Docker/Compose (1 host)        orchestrator (K8s/Swarm, N hosts) adds
─────────────────────────────        ─────────────────────────────────────
manual placement, 1 machine     →    scheduler: bin-pack/spread across nodes
host dies = outage              →    reschedule pods/tasks onto healthy nodes
restart-on-exit only            →    liveness/readiness probes → recreate + drain
hand-rolled deploy scripts      →    declarative rolling/canary deploys + rollback
docker run -e / files for config →   Secrets/ConfigMaps, RBAC, namespaces
DNS round-robin, single host     →   cluster service discovery + LB + ingress
manual hpa/none                  →   horizontal/vertical autoscaling
```

What an orchestrator *fundamentally* provides is a **declarative control loop**: you specify desired state ("10 replicas, this image digest, these resources, this rollout strategy") and a controller continuously reconciles actual toward desired — rescheduling on node loss, replacing failed health probes, rolling out new versions while keeping N available, and scaling on metrics. Building even a fraction of that yourself (a scheduler, a health-driven supervisor across hosts, rolling-deploy logic, cluster DNS/LB, secret distribution, RBAC) is exactly the wheel Kubernetes/Swarm reinvent for you.

The decision framework: stay on **Compose** when the workload fits one box and downtime for a host reboot is acceptable (local dev, CI, small internal tools, single-tenant apps) — it's dramatically simpler. Move to an **orchestrator** when you need multi-node capacity, high availability across host failures, automated rollouts/rollbacks, autoscaling, or a rich ecosystem (service mesh, operators, GitOps). The artifact doesn't change — the same OCI image runs in all of them (Q26) — so the migration cost is in *operational model*, not in repackaging your software. Reaching for Kubernetes *before* you have these needs is a common over-engineering trap; reaching for it *after* you've hand-rolled half a scheduler is a common under-engineering one.

## 🧩 Extended Questions — Supplemental Set A: Practical & Theory

### 🟢 Basic — extended

#### Q68. [Practical] What is the difference between `docker stop`, `docker kill`, `docker rm`, and `docker rm -f`, and when do you reach for each?

These four commands sit at different points of a container's lifecycle and confusing them causes either data loss or stuck containers. **`docker stop`** sends `SIGTERM` to PID 1, waits a grace period (default 10s, `--time N` to change), then `SIGKILL` if the process hasn't exited — it's the *graceful* path that lets your app drain connections and flush state. **`docker kill`** skips the grace period and sends `SIGKILL` immediately (or `--signal` for a specific one) — use it when a container is wedged and unresponsive to `SIGTERM`. Neither deletes the container; both leave it in `Exited` state with its writable layer intact.

**`docker rm`** deletes a *stopped* container (and its writable layer/anonymous volumes if you add `-v`); it refuses to touch a running container, which is a safety guard. **`docker rm -f`** forces removal by sending `SIGKILL` first — convenient but it bypasses graceful shutdown, so you can drop in-flight work. The mental model: stop/kill change *run state*, rm changes *existence*.

```
docker stop   → SIGTERM, wait, SIGKILL   → Exited (graceful)   container still exists
docker kill   → SIGKILL now (or --signal) → Exited (abrupt)     container still exists
docker rm     → delete a STOPPED container (refuses if running)
docker rm -f  → SIGKILL + delete in one step (no graceful drain)
```

In production scripts prefer `docker stop` (and a sane `--time` matching your drain budget) over `rm -f`; reserve `kill`/`rm -f` for cleanup of hung or disposable containers. A common gotcha: `docker rm -v` removes *anonymous* volumes created by that container but **not** named volumes — so `rm -f` won't accidentally nuke your database volume, but you also can't rely on it to clean anonymous scratch volumes unless you pass `-v`.

#### Q69. [Practical] How do you set environment variables for a container, and what is the precedence order when several sources define the same variable?

There are several layers, and knowing which wins prevents the classic "why is my config the old value" confusion. From lowest to highest precedence at runtime: (1) **`ENV` baked into the image** (the Dockerfile default), (2) values from an **`--env-file`**, (3) explicit **`-e VAR=value`** on `docker run` (or `environment:` in Compose), and (4) for Compose specifically, **shell environment interpolation** into the Compose file itself. A value set later in this list overrides the same key set earlier — so `-e` overrides the image's `ENV`, and Compose `environment:` overrides the image `ENV`.

```bash
# image has ENV LOG_LEVEL=info
docker run --env-file ./defaults.env -e LOG_LEVEL=debug myapp
#   defaults.env: LOG_LEVEL=warn
# result: LOG_LEVEL=debug  (explicit -e wins over env-file wins over image ENV)
```

Two Compose-specific subtleties trip people up. First, `environment:` (passed *into* the container) is different from `env_file:` (a file of vars passed into the container) and from the `.env` file in the project directory (which does **variable substitution into the compose.yaml itself**, e.g. `image: app:${TAG}`). Mixing these up leads to "my `.env` value isn't reaching the app" — because `.env` substitutes into the YAML, it doesn't automatically become a container env var unless you reference it under `environment:`.

```yaml
services:
  api:
    image: app:${TAG}          # ${TAG} comes from project .env (YAML interpolation)
    env_file: [./api.env]      # vars from file → into container
    environment:
      LOG_LEVEL: debug         # highest: overrides env_file and image ENV
```

The operational rule: never bake secrets or environment-specific values as fixed `ENV` in the image (it ships everywhere identically); bake sane *defaults* as `ENV`, then override per-environment via `-e`/`environment:`/secret managers. And remember `ENV` is also visible in `docker inspect`, so it's not a place for credentials regardless of precedence.

#### Q70. [Practical] How do you copy files into and out of a running container, and inspect its filesystem, without `exec`-ing a shell?

`docker cp` moves files between the host and a container's filesystem in either direction, and it works on **stopped** containers too — which is invaluable for forensics on a crashed container or for extracting artifacts from a distroless image that has no shell. The syntax mirrors `scp`: `docker cp <container>:/path/in/container ./local` to pull out, and `docker cp ./local <container>:/path` to push in. It copies into the container's *merged* filesystem view (so it lands in the writable layer), and it follows the container's mounts.

```bash
docker cp web:/var/log/app/error.log ./error.log     # pull a log out of a crashed ctr
docker cp ./patched-config.yaml web:/etc/app/config.yaml  # push a fix in
docker cp web:/app/. ./snapshot/                      # copy a whole dir (trailing /.)
```

To inspect *what changed* in a container's writable layer relative to its image, use **`docker diff <container>`** — it lists `A` (added), `C` (changed), and `D` (deleted) paths. This is a fast way to spot a container writing to its CoW layer when it shouldn't (a sign you're missing a volume), or to see what a process scribbled at runtime. For a complete offline view, **`docker export <container> | tar -tvf -`** dumps the entire flattened filesystem as a tar listing without running anything.

```bash
docker diff web        # C /etc, A /app/cache/x.tmp, D /tmp/lock   (writable-layer delta)
docker export web | tar -tvf - | head    # full flattened FS, no shell needed
```

The reason this matters: for minimal/distroless images you *cannot* `exec sh`, so `cp`/`diff`/`export` (plus the namespace-sharing debug containers from Q32) are your only filesystem tools. And because `cp` works on stopped containers, the correct incident workflow for a crash-looping container is often "let it stop, `docker cp` the logs/heap dump out, then debug offline" rather than racing to exec into a container that keeps dying.

#### Q71. [Theory] What does `docker commit` do, why is it considered an anti-pattern for building images, and when is it legitimately useful?

`docker commit <container> <image:tag>` snapshots a container's current filesystem (its writable layer flattened onto the image's layers) into a **new image**, optionally setting config like `CMD`/`ENV` via `--change`. It is how images were built before Dockerfiles, and it still works — but as a *build* method it's an anti-pattern because the result is **unreproducible and opaque**. There's no recorded recipe: nobody can tell from the image how it was made, what was installed, or how to rebuild it after a base-image CVE. You also tend to bake in transient cruft (logs, caches, shell history) and lose the layer-caching/ordering benefits a Dockerfile gives.

```
Dockerfile build:  declarative recipe → reproducible, reviewable, cache-friendly, rebuildable
docker commit:     "freeze whatever this container looks like now" → opaque, one-off, unauditable
```

The deeper objection is supply-chain and operational: a committed image can't be regenerated from source, can't be scanned with confidence about *why* a package is present, and breaks the "image is a deterministic function of a Dockerfile + context" model that everything else (CI, provenance, reproducibility) relies on. Teams that "just commit" accumulate snowflake images nobody can safely patch.

That said, `commit` has legitimate niche uses: capturing the state of a **debugging session** (you exec'd in, reproduced a bug, and want a snapshot to analyze later), **checkpointing** an expensive interactive setup you'll throw away, or doing forensics on a compromised container by freezing it for offline analysis. The rule: `commit` is fine as a *forensic/throwaway snapshot tool*, never as the way you build shipping images — for that, a Dockerfile (or BuildKit/Buildpacks) is the only auditable, reproducible answer.

### 🟡 Intermediate — extended

#### Q72. [Practical] You set `--memory=512m` and `--cpus=1.5` on a container. Explain precisely what each enforces, what `--cpu-shares` and `--cpuset-cpus` add, and the common JVM/Node pitfalls.

`--memory=512m` sets the cgroup hard memory limit (`memory.max` on cgroups v2). The container's RSS *plus its page cache* is charged against it; exceeding it after in-cgroup reclaim triggers the cgroup OOM killer and the process dies with exit 137 (`OOMKilled=true`). It's a *ceiling*, not a reservation. **`--cpus=1.5`** is a hard CPU *quota* implemented via cgroup `cpu.max` (CFS bandwidth: it grants 150ms of CPU per 100ms period across all cores). The container can burst onto multiple cores but is throttled to 1.5 cores' worth of total time — and throttling shows up as latency spikes, not errors, which makes it sneaky to diagnose.

```
--memory 512m     → cgroup memory ceiling; over-limit → OOM kill (137)
--cpus 1.5        → CFS quota: 1.5 cores of CPU TIME (hard throttle → latency, not error)
--cpu-shares 512  → RELATIVE weight under contention only (default 1024); no effect when idle
--cpuset-cpus 0-1 → PIN to specific physical cores (cache locality, NUMA, noisy-neighbor)
--memory-reservation 256m → soft limit; kernel reclaims toward it under host pressure
```

The distinction interviewers probe: **`--cpus`/`cpu.max` is an absolute cap; `--cpu-shares` is only a *relative weight*** that matters when CPUs are contended — give one container 1024 and another 512 and the second gets half the *contended* time, but if the host is idle both run flat-out. `--cpuset-cpus` *pins* the container to specific cores, useful for cache locality and NUMA-sensitive workloads but it reduces scheduling flexibility.

The runtime pitfalls are the real-world killers. A **JVM** before container-awareness (and even after, if misconfigured) sizes its heap and GC thread count from *host* CPU/RAM, not the cgroup limit — so a 512m container running a JVM that thinks it has 32 GB will OOM instantly; fix with `-XX:MaxRAMPercentage=75` (Java 10+ honors cgroups by default via `UseContainerSupport`). **Node.js** doesn't shrink its old-space heap to the limit either — set `--max-old-space-size` *below* the cgroup memory limit. The principle: a CPU/memory limit only works if the runtime *inside* the container is told about it; otherwise the runtime sizes itself for the host and the limit just becomes a tripwire that kills it.

#### Q73. [Practical] How do you publish a port to only the loopback interface, and why does `-p 8080:80` versus `-p 127.0.0.1:8080:80` matter for security?

By default `-p 8080:80` binds the published port to **`0.0.0.0`** — every interface on the host, including its public IP. That means a service you "just exposed for local testing" can be reachable from the entire network/internet unless a host firewall blocks it. The fully specified form `-p 127.0.0.1:8080:80` binds the host side to **loopback only**, so the port is reachable from the host itself (and other processes on it) but not from outside.

```bash
docker run -p 8080:80 nginx              # binds 0.0.0.0:8080  → reachable from the network!
docker run -p 127.0.0.1:8080:80 nginx    # binds loopback only → host-local
docker run -p 10.0.0.5:8080:80 nginx     # bind to one specific host interface
```

This matters more than it seems because of a notorious interaction: **Docker's publish rules insert `iptables` DNAT rules that can bypass a host firewall like `ufw`/`firewalld`.** Operators frequently "block port 8080 with ufw," yet the container is still reachable because Docker's rules in the `DOCKER` chain are evaluated and the packet is DNAT'd to the container before the firewall's filter rules apply. People discover this when an internal database container they `-p 5432:5432`'d turns out to be open to the internet.

The defensive practices: publish to `127.0.0.1` for anything that should be host-local (databases, admin UIs, dev services); put services that need network exposure behind a reverse proxy/ingress rather than publishing each container directly; and if you rely on a host firewall with Docker, understand the chain ordering (or use `iptables=false` and manage rules yourself, or DOCKER-USER chain for filtering Docker traffic). In Compose, `ports: ["127.0.0.1:8080:80"]` does the same loopback binding — and `expose:` (without `ports:`) publishes nothing to the host at all, only to the Docker network, which is the safest default for inter-service ports.

#### Q74. [Theory] What is the difference between `docker attach` and `docker exec`, and why can `attach` "hang" or kill your container?

`docker attach` connects your terminal to the **existing PID 1 process's** stdin/stdout/stderr — the very streams of the container's main process. `docker exec` instead starts a **brand-new process** inside the container's namespaces (e.g., `docker exec -it web sh`), independent of PID 1. This single difference explains most of the surprising behavior.

Because `attach` is wired to PID 1, two foot-guns follow. First, if you press `Ctrl-C` while attached, the `SIGINT` goes to PID 1 — which often **stops the container** (you killed the main process, not "detached your view"). To detach without killing it you must use the detach key sequence `Ctrl-P Ctrl-Q` (and only if the container was started with `-it`). Second, `attach` can appear to "hang" because you're sharing the real process's stdout — if the app isn't currently writing anything, you see nothing, and there's no shell prompt because there's no shell, just the app's stream.

```
docker attach web    → hooks into PID 1's stdio   | Ctrl-C → SIGINT to app (may stop ctr!)
                                                   | detach safely: Ctrl-P Ctrl-Q
docker exec -it web sh → NEW process in ctr ns     | Ctrl-C kills only the exec'd shell
```

The practical guidance: use **`docker exec`** for almost everything interactive — debugging, running a one-off command, getting a shell — because it's isolated and exiting it never harms the main process. Reserve **`docker attach`** for the narrow case where you genuinely need to interact with the foreground process's own stdin (e.g., a REPL or an interactive CLI that *is* PID 1). And for just *watching* output, `docker logs -f` is safer than `attach` because it reads the captured log stream rather than hooking the live process, so there's zero risk of accidentally signaling PID 1.

#### Q75. [Practical] How do you debug "it works on my machine but fails in CI/prod" for a Docker build? Walk the systematic causes.

This class of bug almost always comes down to **hidden inputs that differ between environments**, and a Dockerfile has several. Work through them in order of likelihood: (1) **stale local cache masking a broken step** — your machine has a cached layer from a working state, CI builds clean and hits the real (broken) command; reproduce with `docker build --no-cache` locally to see what CI sees. (2) **`.dockerignore` / build-context differences** — a file present locally but git-ignored (so absent in CI's clean checkout) makes `COPY` succeed locally and fail in CI; or vice versa, a huge local file inflates the context. (3) **floating base tag drift** — `FROM node:22` resolved to a different digest in CI than the image you cached locally weeks ago; pin by digest to eliminate this.

```bash
# reproduce CI's clean state locally
docker build --no-cache --pull -t app:test .   # --pull also re-resolves the base tag
# inspect what actually got copied / what the context contains
docker build --progress=plain --no-cache . 2>&1   # see every step's real output
```

(4) **architecture mismatch** — you build on an arm64 Mac, CI/prod is amd64; an arch-specific binary or a base image without your arch behaves differently (or QEMU emulation hides a bug). Build with `--platform linux/amd64` locally to match prod. (5) **network/registry access** — CI may not have the same proxy, credentials, or private-registry access, so a `RUN curl`/`pip install`/private base pull that works on your VPN fails in CI. (6) **build args / secrets** present in your shell but not configured in CI, so a step that reads `$NPM_TOKEN` silently behaves differently.

The systematic method: make the build *hermetic* so there are no undeclared inputs. Pin the base by digest, pin package versions/lockfiles, add a complete `.dockerignore`, pass `--platform` explicitly, and run `--no-cache --pull --progress=plain` to get an apples-to-apples reproduction of CI. The mental model is identical to Q66 (reproducibility): "works on my machine" failures are non-determinism leaking in through cache, context, base drift, architecture, or environment — close each input and the discrepancy disappears.

#### Q76. [Practical] What is a `.dockerignore` miss that leaks a secret, and how do you detect and remediate a secret already baked into a published image?

The leak happens when `COPY . .` (or `ADD .`) copies the whole build context and the context contains something sensitive that `.dockerignore` didn't exclude — a `.env`, a cloud credentials file, an SSH key, a `.git` directory (which holds full history including secrets that were *removed* from current files), or a CI token file. Because layers are immutable, once that file is in a `COPY` layer it's **permanently in the image history**, and `docker history --no-trunc` or simply `docker run img cat /app/.env` reveals it. Crucially, deleting the file in a *later* `RUN` does **not** remove it — the bytes still ship in the earlier layer (Q53).

```bash
# detect: inspect layers/history and scan the image
docker history --no-trunc myimg:tag        # shows COPY/ADD steps and ENV/ARG values
trivy image --scanners secret myimg:tag    # or: docker history | grep -i token
dive myimg:tag                             # explore layer-by-layer to find the file
```

Remediation has two non-negotiable halves. **First, treat the secret as compromised and rotate it** — the moment a credential lands in a pushed image (especially a public one), assume it's exposed; scrubbing the image does not un-leak it. **Second, remove it from all artifacts:** you cannot edit a layer, so you must rebuild the image cleanly (with a proper `.dockerignore` excluding `.env`, `*.pem`, `.git`, credentials) and re-push, then delete the old tags/digests from the registry. If the secret was in `ARG`/`ENV`, switch to a BuildKit `--mount=type=secret` so it never persists in any layer.

```
prevention:  .dockerignore → exclude .env, *.pem, *.key, .git, .aws, id_rsa, *.tfvars
build-time secret: RUN --mount=type=secret,id=tok  (never in a layer)
detection:   trivy/grype secret scanner + gitleaks in CI gating the build
incident:    1) ROTATE the credential  2) rebuild clean + re-push  3) purge old digests
```

The systemic fix is to make this impossible by default: a CI secret scanner (`gitleaks`, `trufflehog`) that fails the build, a mandatory `.dockerignore` template, and a policy that secrets are *only* delivered via build secret mounts or runtime secret managers — never via the build context or `ENV`. The interview-grade point: an image is an append-only artifact, so "delete the file" is never remediation; rotation plus a clean rebuild is.

#### Q77. [Theory] Compare Docker volumes backed by the `local` driver versus volume plugins (NFS, cloud block/EBS, CSI-style), and the implications for portability and failover.

The default **`local` volume driver** stores data on the host under `/var/lib/docker/volumes/<name>/_data`. It's simple and fast, but the data is **pinned to that one host** — if the container is rescheduled to another node, or the host dies, the data does not follow. That's fine for single-host setups (a Compose stack on one VM) but it's a hard wall for any multi-host story, because a stateful container can only run where its data physically lives.

**Volume plugins / drivers** decouple the data from the host. You can create a `local`-driver volume with NFS options (so the backing store is a remote NFS export reachable from any node), or use third-party/cloud drivers that attach **network block storage** (e.g., an EBS/persistent-disk volume) which can be detached from a dead node and re-attached to a healthy one. In orchestrated environments this generalizes to CSI (Container Storage Interface) drivers that dynamically provision and attach cloud volumes per workload.

```bash
# local driver, but backed by a remote NFS export (data not pinned to one host's disk)
docker volume create --driver local \
  --opt type=nfs --opt o=addr=10.0.0.10,rw \
  --opt device=:/exports/pgdata  pgdata-nfs
```

```
local driver        : host-local disk        | fast, simple | data dies/stays with the host
local + NFS opts     : remote NFS export      | shared across hosts | network dep, NFS semantics
block/EBS/CSI driver : network block volume   | detach/reattach on failover | provider-coupled
```

The trade-offs to articulate: **portability vs. performance vs. coupling.** Host-local is fastest and simplest but offers no failover; NFS gives shared access across hosts but inherits NFS's locking/consistency quirks (often bad for databases that expect POSIX semantics and fsync guarantees); block-storage/CSI gives proper failover (volume re-attaches to the rescheduled workload) at the cost of provider coupling and attach/detach latency. The architectural rule: for genuinely stateful workloads that must survive node failure, you need network-backed storage with a failover story — which is precisely why Kubernetes models this as PersistentVolumes/CSI and why "just use a local volume" stops working the moment you go multi-node. For databases specifically, prefer block-storage semantics over NFS unless the DB explicitly supports it.

#### Q78. [Practical] Your Docker host is out of disk space (`no space left on device`). Walk the diagnosis and the safe cleanup, distinguishing the four reclaimable categories.

Start by quantifying where the space went, because the fix differs per category and a careless blanket prune can destroy data. **`docker system df`** (add `-v` for per-object detail) breaks usage into the four reclaimable buckets: **images** (layers), **containers** (writable layers of stopped containers), **local volumes**, and **build cache** (BuildKit's content store — frequently the single biggest and most-overlooked consumer on CI hosts). Also check the host itself: `df -h /var/lib/docker` (Docker's data root) and remember that a full data root causes *write* failures — containers can't grow their CoW layer and the `json-file` log driver stalls.

```bash
docker system df -v                 # which category is huge? (images/containers/volumes/cache)
df -h /var/lib/docker               # is the partition holding Docker's data root full?
docker ps -as                       # per-container writable-layer size (find a log-bloated ctr)
du -sh /var/lib/docker/containers/* # often: an unrotated json-file log filling the disk
```

Now clean **from least to most destructive**, never starting with `system prune -a --volumes`. (1) Remove stopped containers and dangling images: `docker container prune` and `docker image prune` (dangling only). (2) Prune build cache — usually the big win: `docker builder prune` (or `--filter until=168h` to keep recent). (3) Only if you're sure no needed-but-stopped images exist, `docker image prune -a`. (4) **Volumes last and deliberately** — `docker volume ls` to review, then prune *named* unused volumes individually; never reflexively `--volumes` on a host that runs databases, because that deletes data permanently.

```
SAFE order:
  docker container prune              # stopped containers
  docker image prune                  # dangling (<none>) images only
  docker builder prune                # BuildKit cache  ← often the largest hidden chunk
  docker image prune -a               # all unused images (deletes tagged ones — review first)
  docker volume prune                 # LAST, after manual review — data loss risk
```

The recurring root causes worth fixing permanently: **no log rotation** (set `max-size`/`max-file` in `daemon.json` so `json-file` logs can't fill the disk), **unbounded build cache** on CI runners (scheduled `builder prune --filter until=...`), and **a container writing big data to its CoW layer instead of a volume** (find it with `docker ps -as` / `docker diff`). The interview point: "out of disk" is rarely "too many images" — on build hosts it's usually build cache or unrotated logs, and the discipline is targeted, category-aware cleanup plus retention policy, not a panic `prune -a`.

#### Q79. [Theory] What is a multi-stage build's `--target`, how do `COPY --from` and named/external stages work, and how do you use this for test/lint/dev variants in one Dockerfile?

A multi-stage Dockerfile can declare several stages (`FROM ... AS name`), and **`docker build --target <name>`** tells BuildKit to build *only up to that stage* and emit it as the result — anything after, and any sibling stage the target doesn't depend on, is skipped entirely (BuildKit prunes the DAG, Q51). **`COPY --from=<stage>`** pulls files from another stage's filesystem, and `--from` can also reference an **external image** (`COPY --from=nginx:latest /etc/nginx/nginx.conf .`) or a named earlier stage. This lets one Dockerfile encode multiple buildable artifacts — a builder, a tester, a linter, a dev image, and a slim production image — sharing common base layers.

```dockerfile
FROM node:22-slim AS base
WORKDIR /app
COPY package*.json ./
RUN npm ci

FROM base AS test          # build target: runs the test suite
COPY . .
CMD ["npm","test"]

FROM base AS dev           # build target: full toolchain for live dev
COPY . .
CMD ["npm","run","dev"]

FROM base AS build         # produce production artifacts
COPY . .
RUN npm run build && npm prune --omit=dev

FROM gcr.io/distroless/nodejs22-debian12:nonroot AS prod   # default (last) target
WORKDIR /app
COPY --from=build /app/node_modules ./node_modules
COPY --from=build /app/dist ./dist
ENTRYPOINT ["dist/server.js"]
```

```bash
docker build --target test  -t app:test  .   # CI: build+run the test stage only
docker build --target dev   -t app:dev   .   # local dev image with full toolchain
docker build               -t app:prod   .   # no --target → builds final (prod) stage
```

The power here is **one source of truth with no drift**: the test/lint/dev/prod images all share the same base, dependency install, and version pins, so your CI test environment is byte-identical to what you build prod from, eliminating "passed in CI, broke in the prod image" gaps. Because BuildKit builds independent stages in parallel and skips unused ones, `--target test` doesn't pay for the prod stage and vice versa. The trade-off/edge case: keep stages cohesive (a giant Dockerfile with ten loosely related stages becomes hard to read), and remember `--target` builds *its dependencies* too — if `test` depends on `base`, `base` is built, but a sibling `prod` stage that `test` doesn't reference is not. This pattern (target-per-purpose) is the idiomatic way to avoid maintaining separate `Dockerfile.test`/`Dockerfile.dev` files that inevitably diverge.

#### Q80. [Practical] How do you pass build-time configuration with `ARG`, scope it across stages, and avoid the "ARG before FROM" gotcha?

`ARG` declares a build-time variable settable with `--build-arg KEY=value`. Its scope is the **single stage** in which it's declared, from the point of declaration onward — it is *not* automatically visible in other stages or before its declaration. The most common gotcha is **`ARG` declared before the first `FROM`**: such a "global" ARG is only usable *in `FROM` lines themselves* (e.g., to parameterize the base tag); to use it *inside* a stage you must **re-declare** it (a bare `ARG NAME` with no default) within that stage.

```dockerfile
ARG NODE_VERSION=22          # global ARG: usable only in FROM lines below
FROM node:${NODE_VERSION}-slim AS build
ARG NODE_VERSION             # RE-DECLARE to use it inside this stage
ARG BUILD_ENV=production     # stage-scoped build arg with a default
RUN echo "building on node ${NODE_VERSION} for ${BUILD_ENV}"

FROM gcr.io/distroless/nodejs${NODE_VERSION}-debian12 AS prod   # FROM can see global ARG
ARG BUILD_ENV                # NOT inherited from 'build' stage — re-declare if needed
```

```bash
docker build --build-arg NODE_VERSION=20 --build-arg BUILD_ENV=staging -t app .
```

Several behaviors are worth stating precisely. (1) `ARG` values **invalidate the build cache** from the point they're *used* — change a build arg and every step that references it (and everything after) rebuilds. (2) There are **predefined ARGs** BuildKit injects automatically — notably `TARGETPLATFORM`, `TARGETOS`, `TARGETARCH`, `BUILDPLATFORM` — which are the idiomatic way to write cross-compiling multi-arch Dockerfiles (`RUN GOARCH=${TARGETARCH} go build ...`). (3) `ARG` is **not for secrets** — values appear in `docker history` and build metadata (Q42, Q76); use `--mount=type=secret`.

```
ARG before FROM   → usable in FROM lines only; re-declare inside a stage to use it there
ARG inside stage  → scoped to that stage; NOT inherited by other stages
predefined ARGs   → TARGETARCH/TARGETOS/BUILDPLATFORM (multi-arch cross-compile)
ARG ≠ secret      → leaks into docker history; cache-busts steps that use it
```

The clean pattern for configurable builds: a global `ARG` for the base version (so one variable controls every `FROM`), re-declared in each stage that needs it; stage-local `ARG`s for build flags; and `TARGETARCH`-driven cross-compilation for multi-arch. The interview signal is knowing that `ARG` scope is *per-stage and post-declaration*, that the pre-`FROM` ARG is a special, narrowly-scoped case, and that build args are configuration, never credentials.

### 🟠 Advanced — extended

#### Q81. [Theory] How does Docker's embedded DNS interact with corporate VPNs, split-horizon DNS, and `systemd-resolved`, and how do you debug a container that can resolve external names but not internal ones (or vice versa)?

The container's `/etc/resolv.conf` points at the embedded resolver `127.0.0.11` on user-defined networks (Q38), which answers *container-name* queries locally and **forwards everything else to the host's upstream resolvers**. The fragility is in that forwarding: Docker copies the *host's* effective nameservers into the embedded resolver's upstream list, and several host configurations break this. If the host uses **`systemd-resolved`**, its real `/etc/resolv.conf` often contains only the stub `nameserver 127.0.0.53` — an address only reachable on the *host's* loopback, not from inside the container's netns. Docker normally detects this and substitutes the real upstreams, but **VPN split-horizon DNS** (where internal hostnames resolve only via a VPN-pushed resolver) frequently isn't captured, so containers can reach public DNS (8.8.8.8) but not `internal.corp.example`.

```bash
# inside the container — what is it actually using and can it reach it?
docker exec api cat /etc/resolv.conf            # nameserver 127.0.0.11 + search/options
docker exec api getent hosts db                 # container-name resolution (embedded DNS)
docker exec api nslookup internal.corp.example  # internal name → tests VPN/upstream forward
docker exec api nslookup google.com             # public name   → tests general forwarding
# on the host — what upstreams does Docker see?
resolvectl status      # systemd-resolved per-link resolvers (VPN link?)
cat /etc/resolv.conf   # is it the 127.0.0.53 stub? (the classic trap)
```

The asymmetric symptoms map to specific causes. **External works, internal fails** → the VPN's split-horizon resolver isn't in the forwarding set the container sees; fix by setting explicit upstreams via `--dns <vpn-resolver-ip>` on the container / `"dns": [...]` in `daemon.json`, or pointing at the VPN's DNS. **Internal works, external fails** → an internal-only resolver is configured but can't reach the public internet, or the search domain is mangling queries. **Nothing resolves** → the container was handed `127.0.0.53` (the unreachable stub) as an upstream.

```
container → 127.0.0.11 (embedded) → forwards to host upstreams
   trap 1: host upstream = 127.0.0.53 (resolved stub) → unreachable from ctr netns
   trap 2: VPN split-horizon resolver not propagated → internal names fail, public ok
   fix:    pin upstreams: docker run --dns <ip>  or daemon.json "dns":[...]; or fix host resolv.conf
```

The remediations in order: ensure the host's resolver config is container-resolvable (avoid handing containers the loopback stub), pin DNS explicitly for VPN/split-horizon environments via `--dns`/`daemon.json`, and verify with `nslookup` for *both* an internal and an external name to localize the break. The conceptual takeaway: the embedded DNS is only as good as the upstreams it inherits, and VPN/`systemd-resolved` setups are exactly where that inheritance silently goes wrong — so debug by comparing what the container *thinks* its resolvers are against what's actually reachable from its network namespace.

#### Q82. [Practical] Design and tune a HEALTHCHECK and graceful-shutdown sequence for a stateful HTTP service so deploys drop zero requests. What are the failure modes if you get the timing wrong?

Zero-drop deploys require coordinating three timers so that traffic stops arriving *before* the process dies, and in-flight requests finish *within* the kill grace period. The HEALTHCHECK (or orchestrator readiness probe) must flip the instance to "not ready" the moment shutdown begins so the load balancer stops routing to it; the app must trap `SIGTERM`, stop accepting new connections, drain in-flight requests, then exit; and the `docker stop --time` (or K8s `terminationGracePeriodSeconds`) must exceed the longest legitimate request plus drain time, or the process is `SIGKILL`'d mid-request.

```dockerfile
# Exec form so the app is PID 1 and receives SIGTERM directly (Q40)
ENTRYPOINT ["node","dist/server.js"]
# readiness flips to unhealthy as soon as shutdown starts so the LB drains us
HEALTHCHECK --interval=5s --timeout=2s --start-period=20s --retries=2 \
  CMD ["node","dist/health.js"]   # returns non-zero once a SIGTERM handler set draining=true
```

```javascript
// app shutdown sequence
let draining = false;
process.on("SIGTERM", async () => {
  draining = true;                 // health endpoint now reports NOT ready → LB stops sending
  await sleep(READINESS_PROPAGATION_MS);  // let probes/LB notice before we close listeners
  server.close();                  // stop accepting new conns, keep serving in-flight
  await drainInflight();           // wait for active requests to complete
  await db.close();                // flush/close resources
  process.exit(0);                 // exit cleanly within the grace window
});
```

```bash
docker run --init --stop-timeout 30 myapi   # grace ≥ max-request + drain + propagation slack
```

The failure modes when timers are wrong are instructive. **Grace period too short** → `docker stop` `SIGKILL`s the process before drain completes → 502s/connection resets for in-flight requests (exit 137 from the timeout, often misread as OOM). **No readiness-flip on shutdown** → the LB keeps routing new requests to a closing instance → those new requests are refused; the drain alone isn't enough, you must *also* stop attracting traffic. **`start_period` too short** → a slow-booting app (JVM warmup, migrations) is marked unhealthy and restarted before it ever serves, creating a crash-loop on every deploy. **Shell-form entrypoint** → `SIGTERM` never reaches the app at all (Q40), so none of this fires.

```
deploy step          must happen in this order, within the grace window
─────────────        ───────────────────────────────────────────────────
1 SIGTERM → app      app sets draining=true (readiness → NOT ready)
2 probe/LB notice    LB stops routing NEW requests to this instance
3 close listener     no new accepts; finish in-flight
4 drain in-flight    wait until active=0 (bounded by grace period)
5 close resources    flush DB/queues; exit 0
  grace too short → step 4 cut off by SIGKILL → dropped requests
```

The tuning rule: set the grace period to *p99 request duration + drain + readiness-propagation slack*, add a brief sleep between "flip readiness" and "close listener" so the LB definitely observed the change (the classic race in Kubernetes solved by a `preStop` sleep), and keep the healthcheck cheap and exec-form. Done right, every deploy quiesces an instance gracefully; done wrong, you silently drop a slice of traffic on every rollout and blame it on "the network."

#### Q83. [Theory] What attack surfaces does mounting `/var/run/docker.sock` into a container expose, why is it equivalent to host root, and what are the safer alternatives for "containers that need to manage containers"?

The Docker daemon listens on `/var/run/docker.sock` and exposes the **full Docker API** with no additional authentication — anything that can write to that socket can issue any daemon command. So mounting the socket into a container hands that container the ability to **launch a new, fully privileged container that bind-mounts the host's `/` and runs as root**, which is a trivial, well-known escape to host root. It is *not* "a bit of extra access" — it is functionally `--privileged` on the host, regardless of how locked-down the container itself is (non-root, dropped caps, read-only FS all become irrelevant because the attacker just asks the daemon to start a *different* container without those constraints).

```bash
# what an attacker (or compromised process) with the socket can do:
docker -H unix:///var/run/docker.sock run -v /:/host --privileged alpine \
  chroot /host sh    # → root shell on the HOST filesystem
```

This is why CI systems that mount the socket for "docker build/push" (docker-outside-of-docker) are a recurring breach vector: a malicious dependency or PR build step can pivot to the runner host and from there to other tenants' builds and secrets. The same applies to any "agent" container (log shippers, autoscalers, dashboards) that's given the socket for convenience.

```
socket mounted → container speaks full daemon API → can start a privileged ctr mounting /
   ⇒ EQUIVALENT to host root (container's own hardening is bypassable)
```

Safer alternatives depend on *why* the container needs Docker. (1) For **building images**, use daemonless/rootless builders — Kaniko, Buildah, or rootless BuildKit — which need no socket and no privilege (Q25). (2) For containers that must orchestrate others, prefer a **scoped, audited API** rather than the raw socket: a **socket proxy** (e.g., a `docker-socket-proxy` that allow-lists only the specific endpoints needed, like read-only `/containers/json`) drastically narrows the surface. (3) Run the daemon **rootless** so that even socket access maps to an unprivileged host user, shrinking the blast radius. (4) In Kubernetes, never mount the node's container runtime socket into a pod; use the proper API (the Kubernetes API with RBAC) for orchestration tasks. (5) Where truly necessary, isolate the socket-bearing workload on **dedicated, ephemeral nodes** so a compromise can't reach other tenants.

The principle to articulate: the Docker socket is an *unauthenticated root-equivalent API*, so "just mount the socket" trades all of your container hardening for convenience. The correct posture is least-privilege by purpose — daemonless builders for building, a filtering socket proxy for narrow orchestration needs, and rootless mode to cap the worst case — never the raw socket in a multi-tenant or internet-facing context.

#### Q84. [Theory] Explain how `iptables`/nftables, the `DOCKER` and `DOCKER-USER` chains, and `docker-proxy` cooperate to implement port publishing — and how this breaks host firewalls.

When Docker publishes a port it programs the host's packet-filtering stack rather than doing anything in userspace by default. It inserts rules into the **`nat` table** (a `DNAT` rule in `PREROUTING`/`OUTPUT` rewriting `host:hostport` → `containerIP:containerport`, and `MASQUERADE`/`SNAT` in `POSTROUTING` for outbound) and into the **`filter` table** via Docker-managed chains: the **`DOCKER`** chain (Docker's own allow rules for published ports, which you should *not* edit) and the **`DOCKER-USER`** chain (empty by default, *reserved for your custom rules*, and evaluated **before** the `DOCKER` chain). Because these rules live in the kernel's netfilter hooks, traffic to a published port is DNAT'd and accepted by Docker's chains.

```
inbound to host:8080
  raw/mangle → nat:PREROUTING (DNAT 8080→172.17.0.2:80)
  → filter:FORWARD → DOCKER-USER (your rules — evaluated FIRST)
                   → DOCKER (Docker's allow rules for published ports)
  → routed into the container's veth
docker-proxy (userland): fallback for loopback/hairpin & when iptables path doesn't cover a case
```

The firewall-bypass problem follows directly: tools like **`ufw`** and **`firewalld`** add their rules to the standard `INPUT`/`FORWARD` filtering, but Docker's DNAT happens in `PREROUTING` and its accept rules live in the `DOCKER` chain that the packet reaches via `FORWARD` — so a `ufw deny 8080` on the *host* often does **not** block a container published on 8080, because the packet is destined for the *container's* IP (not the host) and is handled by Docker's chains, not ufw's `INPUT`. Operators are repeatedly surprised when a database they `-p 5432:5432`'d is reachable from the internet despite "blocking it in the firewall."

```
ufw deny 8080         → adds to INPUT (host-destined traffic)
docker -p 8080:80     → DNAT in PREROUTING + accept in DOCKER chain (container-destined)
   ⇒ ufw rule never sees the packet → port is OPEN despite the "block"
```

The correct fixes: (1) **bind published ports to `127.0.0.1`** for host-local services so they're never network-reachable (Q73); (2) put custom filtering in the **`DOCKER-USER`** chain, which Docker guarantees is evaluated before its own allow rules and which Docker won't clobber (e.g., `iptables -I DOCKER-USER -i eth0 -p tcp --dport 8080 -j DROP`); (3) on nft-based hosts understand Docker still typically manages an iptables-nft compatibility layer; (4) for serious setups, front containers with a reverse proxy/ingress and publish only that. The `docker-proxy` userland process is a separate, often-misunderstood piece: it exists mainly to handle cases the iptables path doesn't (loopback access to published ports, hairpin NAT), which is why you sometimes see a `docker-proxy` process per published port. The interview-grade point: port publishing is netfilter programming, host firewalls filter a *different* path, and `DOCKER-USER` is the supported seam for inserting your own rules.

#### Q85. [Practical] A container's writable layer is growing without bound in production. How do you find what's writing, and what are the structural fixes?

Unbounded writable-layer growth means a process is writing to the **CoW upperdir** instead of a volume — logs, caches, uploads, temp files, or a runaway data file. First confirm it's the writable layer and not a volume: **`docker ps -as`** shows each container's writable-layer size in the `SIZE` column (the `(virtual N)` part is shared image layers; the leading number is the writable delta). Then locate *what* changed with **`docker diff <container>`**, which lists added/changed paths in the upperdir — the offending directory usually jumps out (`A /app/uploads/...`, `C /var/log/app.log` growing, `A /tmp/...`).

```bash
docker ps -as                              # SIZE column = writable-layer growth per container
docker diff web | sort | head -50          # A/C/D paths in the upperdir — find the culprit dir
# direct look at the overlay upperdir on the host:
docker inspect -f '{{.GraphDriver.Data.UpperDir}}' web   # path to this ctr's writable layer
du -sh "$(docker inspect -f '{{.GraphDriver.Data.UpperDir}}' web)"/*  # biggest writers
```

Common culprits and their structural fixes: **(1) application logs written to a file inside the container** — fix by logging to stdout/stderr so the logging driver handles them (and set `max-size`/`max-file`), never to a path on the CoW layer. **(2) a database or queue using its default data dir** instead of a mounted volume — mount a named volume at the data path (`-v pgdata:/var/lib/postgresql/data`); writing a DB to the CoW layer is also *slow* because of per-file `copy_up` (Q48). **(3) caches/temp** (image processing scratch, package caches) — mount a `tmpfs` or volume at the temp path. **(4) user uploads** persisted locally — move to a volume or object storage.

```
symptom                         structural fix
────────────────────────        ──────────────────────────────────────────
app logs to a file in ctr       log to stdout/stderr + json-file max-size/max-file
DB writing to CoW upperdir      mount a named volume at the data directory (also faster)
temp/scratch ballooning         tmpfs or volume at the temp path; clean up on exit
uploads stored locally          volume or object storage; never the writable layer
```

The structural guardrails beyond the immediate fix: run the container **`--read-only`** with explicit `tmpfs`/volumes for the few writable paths — this *forces* every writable location to be deliberate and makes "accidentally writing to the CoW layer" fail fast at startup rather than silently bloating disk; set a **`--storage-opt size=...`** quota where the driver supports it; and apply **log rotation** at the daemon level so the most common offender (logs) is bounded by default. The conceptual point ties back to Q21/Q48: the writable layer is for *ephemeral* process state, not data — anything that should persist, grow, or be written heavily belongs on a volume, and a read-only rootfs is how you enforce that contract.

#### Q86. [Theory] How do `docker save`/`docker load` differ from `docker export`/`docker import`, and when would you use each (air-gapped transfer, flattening, forensics)?

These two pairs operate on different objects and preserve different things, and conflating them loses either layer history or runtime config. **`docker save`** serializes one or more **images** — *all their layers, the manifest, the config (entrypoint/env/etc.), and tags* — into a tar stream; **`docker load`** reconstructs those images verbatim on another host. This is the **lossless image transfer** mechanism: layers and history are preserved, so the loaded image is byte-identical and still benefits from layer dedup.

```bash
docker save myapp:1.2 -o myapp.tar      # full image: all layers + config + tags
docker load -i myapp.tar                # restores the image exactly (history intact)
```

**`docker export`** serializes a **container's flattened filesystem** — a single tar of the merged rootfs at that moment, with **no layers, no history, and no image config** (no `ENTRYPOINT`, `ENV`, etc.); **`docker import`** turns such a tar into a *new single-layer image* (you must re-specify config via `--change`). So export/import **flattens** and **discards metadata**, which is sometimes exactly what you want and sometimes a trap.

```bash
docker export web -o web-fs.tar         # flattened container FS, NO config/history
docker import --change 'CMD ["app"]' web-fs.tar myapp:flat   # new 1-layer image, config re-added
```

```
                object        preserves layers/history   preserves config   result
docker save     image(s)      YES                        YES                identical image
docker export   container FS  NO (flattened)             NO                 single rootfs tar
```

When to use which: **`save`/`load`** for **air-gapped or registry-less transfer** of real images (move `myapp:1.2` to a disconnected environment with its config and caching intact), and for backing up an exact image. **`export`/`import`** for **flattening** (squashing a sprawling layer history into one layer to shrink/obscure, at the cost of losing cache sharing — Q53), for **forensics** (capture a suspicious running container's exact filesystem as a tar for offline analysis without preserving its potentially-tampered config), and for ingesting a rootfs built outside Docker. The decisive question: *do you need the image (layers + how-to-run it) or just the filesystem bytes?* Need a runnable, cache-friendly image → `save`/`load`. Need a flat snapshot of a filesystem and don't care about layers/config → `export`/`import`. A frequent mistake is `export`ing to "move an image," then being confused that the result won't start — because `import` dropped the `ENTRYPOINT`/`ENV` that made it runnable.

#### Q87. [Practical] How do you profile and reduce container startup latency for cold starts (autoscaling, serverless, CI)? Cover pull, unpack, init, and app-ready phases.

Cold start is the sum of distinct phases, and you optimize the dominant one — so first *measure* where the time goes: **(1) image pull** (download + decompress layers), **(2) unpack/snapshot** (assemble the rootfs), **(3) container init** (runc namespace/cgroup setup — usually milliseconds), **(4) app init** (runtime boot, framework startup, warmup, first-request readiness). For a multi-GB ML image the pull dominates; for a fat JVM app the runtime warmup dominates; the fix is completely different, so measuring first avoids optimizing the wrong phase.

```
phase            typical cost driver            primary lever
─────────        ──────────────────────────     ──────────────────────────────
1 pull           image size, layer count, net   smaller image, zstd, lazy pull (eStargz/SOCI)
2 unpack         layer size, snapshotter         fewer/smaller layers; containerd snapshotter
3 runc init      ~constant (ms)                  rarely the bottleneck
4 app init       runtime warmup, JIT, conns      AOT/CDS, lazy init, warm pools, readiness tuning
```

**Pull/unpack levers:** shrink the image (multi-stage, distroless — Q12), prefer **zstd** layers (faster decompress than gzip), reduce layer count, and for large images adopt **lazy pulling (eStargz/SOCI, Q63)** so the container starts before the whole image is present — turning minutes into seconds for big images with a small startup working set. Pre-pulling/warming images onto nodes (a DaemonSet or node image cache) eliminates the pull entirely for predictable workloads.

**App-init levers** (often the real cost for JVM/Node/.NET): use **AppCDS / Class Data Sharing** and **AOT/`-XX:+TieredStopAtLevel=1`** or GraalVM native image for the JVM to cut warmup; lazy-initialize heavy subsystems; pre-establish connection pools asynchronously rather than blocking readiness on them; and keep a **warm pool** of pre-started instances for serverless/autoscaling so scale-out doesn't pay cold start on the critical path. Tune the **readiness probe** so the instance is marked ready the instant it can serve (not after an over-conservative `start_period`), but not before — a too-eager probe sends traffic to a not-warmed app.

```bash
# measure pull vs start separately
time docker pull myimg:tag            # phases 1+2
time docker run --rm myimg:tag true   # phase 3 (+ minimal init) with image already local
# app-ready: instrument the app to log "ready" timestamp vs process start
```

The architectural judgment: cold-start optimization is about *which phase dominates your workload*. Autoscaling a 4 GB data image → attack the pull (lazy pull + smaller image + node pre-warm). Serverless JVM functions → attack app init (native image / CDS / warm pool). CI → attack both (registry cache + small images). Don't micro-optimize runc init (it's already milliseconds), and don't blindly shrink an image that pulls in under a second when the real cost is a 15-second JVM warmup. Match the lever to the measured bottleneck.

### 🔴 Expert — extended

#### Q88. [Theory] Walk through CVE-2019-5736 (the runc host-breakout via `/proc/self/exe`) and what it teaches about the container security boundary and defense-in-depth.

CVE-2019-5736 was a breakout where a malicious container could **overwrite the host's `runc` binary** and thereby gain root on the host. The mechanism abused how `runc` re-executes itself: when `runc` `exec`s into a container (or starts one), it re-runs `/proc/self/exe` (a magic symlink to the running runc binary) inside the container's context. A compromised container could replace the entrypoint with a program that, at the moment runc opened `/proc/self/exe`, held an open *write* file descriptor to it — and because `/proc/self/exe` points at the host's runc, writing through that fd **clobbered the host binary**. The next time any container ran, the attacker-controlled "runc" executed as root on the host.

```
runc start/exec → runs /proc/self/exe (= host runc) inside container context
malicious container holds O_WRONLY fd to /proc/self/exe → writes attacker payload over host runc
next runc invocation → attacker code runs as ROOT on the host  → full host compromise
```

The fix was to make runc **copy itself into a memfd (memory-only file) and execute that sealed copy**, so the binary runc executes can't be written back to the on-disk host binary. But the security lessons matter more than the specific bug. First, **the container boundary is the kernel plus the runtime**, and the *runtime itself* (runc) is part of the trusted computing base — a bug there defeats every in-container hardening you applied, because it operates at the host level before/around your container. Second, it shows why **"a single shared component is a single point of failure"**: every container on the host shares one runc, so one runc CVE is a fleet-wide breakout.

The defense-in-depth takeaways an interviewer wants: (1) **non-root containers + user namespaces** blunted this class — if container-root maps to an unprivileged host UID, overwriting the host binary fails on permissions, which is concrete evidence that rootless/userns is worth the friction. (2) **read-only host binaries / `no-new-privileges` / dropped `CAP_SYS_PTRACE`** reduce the primitives such exploits chain. (3) **stronger isolation tiers** (gVisor, Kata — Q28/Q46) would have contained it because the malicious code never touches the real host runc. (4) **keep the runtime patched and pinned**, and treat runtime CVEs as P0 because they bypass the entire in-container model. The meta-lesson: container isolation is only as strong as its weakest shared component, so you layer independent controls (rootless, caps, seccomp, sandboxed runtimes) so that no single bug — even in the runtime — is game-over.

#### Q89. [Theory] Design the storage architecture for stateful workloads in containers: why is the CoW layer wrong for databases, and how do volume drivers, fsync semantics, and snapshots factor into a correct design?

A database on the **CoW writable layer** is wrong on three independent axes. **Performance:** OverlayFS does file-granular `copy_up` (Q48) — the first write to any file in a lower layer copies the *whole file* up, which is catastrophic for a DB that does many small random writes across large files; you pay copy-up storms and double the I/O. **Durability/correctness:** the overlay upperdir's fsync and write-ordering semantics are not what a DB's crash-recovery assumes; layering can interfere with the durability guarantees (write barriers, fsync-to-stable-storage) that WAL/journaling rely on. **Lifecycle:** the writable layer is destroyed on `docker rm`, so the data is *by design* ephemeral — exactly wrong for a database.

The correct design mounts a **volume** at the data directory so writes bypass the union FS and go to a real filesystem. The choice of volume backing then drives the failover and consistency story: a **host-local volume** gives native filesystem semantics and best performance but pins the DB to one host (no failover); a **network block volume (EBS/CSI)** can detach from a dead node and re-attach to the rescheduled workload, giving failover with near-local semantics; **NFS/file-shared volumes** allow multi-host access but often violate the POSIX fsync/locking guarantees databases need, so they're usually wrong for primary DB storage (acceptable for some app data, dangerous for a DB).

```
DB write path
  CoW layer:   write → copy_up whole file → overlay fsync semantics  ✗ slow + unsafe + ephemeral
  local vol:   write → host FS directly                              ✓ fast, native fsync | no failover
  block/CSI:   write → network block dev → reattach on failover      ✓ fsync ok, failover | attach latency
  NFS:         write → remote file share                             ⚠ fsync/locking quirks → risky for DBs
```

The architecture choices to articulate: (1) **always a volume, never CoW** for any datastore. (2) Match backing to the durability/failover need — block storage with proper fsync semantics for primary DB data; reserve NFS for workloads that tolerate its semantics. (3) **Snapshots/backups belong to the storage layer or the DB, not the container** — use volume-level snapshots (cloud disk snapshots, ZFS/LVM) or DB-native backups (`pg_basebackup`, WAL archiving); never `docker commit` a running DB, which captures a torn, crash-inconsistent filesystem. (4) Understand the **consistency caveat**: a volume snapshot taken while the DB is writing is only crash-consistent, so for application-consistent backups you quiesce/flush the DB (or use its native backup) before snapshotting. (5) For HA, prefer **replication at the database layer** (Postgres streaming replication) over relying solely on storage failover, because storage reattach still incurs recovery time and risks corruption if fencing is imperfect.

The principle: containers make the *compute* ephemeral and disposable, which is precisely why the *state* must live in deliberately-chosen durable storage with the right fsync semantics and a failover/backup story — the CoW layer is an ephemeral scratchpad, and treating it as a database disk is a correctness and durability bug, not just a performance one.

#### Q90. [Theory] How would you architect image management for a 500-service organization: registry topology, retention/GC, promotion across environments, and pull-through caching? Discuss the trade-offs.

At this scale image management is a platform discipline with four interacting concerns. **Registry topology:** a single central registry is a SPOF and a latency/bandwidth bottleneck for geographically or cluster-distributed workloads, so you typically run a **primary registry** plus **pull-through caches/mirrors** near each cluster/region (a registry configured as a remote proxy that caches upstream layers on first pull). This cuts cross-region egress and protects against upstream (e.g., Docker Hub rate-limit/outage) dependencies — at the cost of cache consistency management and storage in each location.

**Promotion across environments:** the correct model is **build once, promote the same digest** — CI builds an image, and *the identical digest* flows dev → staging → prod, with promotion implemented as re-tagging/copying that exact digest (not rebuilding per environment, which reintroduces drift). This is what makes "what passed staging is exactly what runs in prod" true and is the foundation for provenance/attestation. Often realized as separate registries/repos per environment with a controlled copy step gated by tests and signature verification.

```
build (CI) → push digest D to dev repo → tests/scan/sign
   promote D (re-tag/copy, NOT rebuild) → staging repo → soak → promote D → prod repo
pull path: cluster → regional pull-through cache → primary registry → (upstream public)
```

**Retention/GC:** registries accumulate relentlessly, so you need automated policy: keep the last N tags per repo and anything referenced by a running deployment or a release tag; expire untagged/dangling manifests and old CI builds; run registry **garbage collection** to actually reclaim blobs (a two-phase mark-and-sweep that must be coordinated with writes, often requiring read-only/maintenance windows or a GC-safe registry). The trade-off is aggressiveness vs. safety — GC the wrong digest and you break a rollback; too lax and storage cost explodes. Pin retention to *what could still be deployed or rolled back to*, plus a generous safety margin.

```
retention policy (per repo)
  keep: release tags, last N builds, any digest referenced by a live deployment/rollback window
  expire: untagged manifests, CI builds older than X, superseded dev tags
  then: registry GC (mark-and-sweep) to reclaim blobs  ← coordinate with writes
```

The cross-cutting trade-offs: **standardized golden base images** (Q30) maximize cross-service layer dedup in the registry (500 services sharing 5 bases store those base layers once) and centralize CVE patching, at the cost of a central-team dependency. **Digest-pinned promotion + cosign verification at admission** gives supply-chain integrity but adds signing infrastructure and key management. **Pull-through caches** improve resilience and speed but add per-location storage and a consistency/expiry concern. The staff-level judgment is treating the registry as critical infrastructure with SLOs (availability, pull latency), an explicit retention/GC policy tied to deployability, build-once-promote-by-digest to eliminate drift, and regional caching to decouple from upstream — rather than a pile of `latest` tags in one registry that nobody can safely garbage-collect.

#### Q91. [Practical] Production incident: after a node reboot, several containers fail to start with mount/permission errors, and others come up but lose data. Diagnose root cause and design prevention.

This is a classic stateful-on-containers incident and it usually decomposes into two distinct failures that a reboot exposes simultaneously. **Containers losing data** almost always means they were writing to an **anonymous/host-local volume or the CoW layer that didn't survive** — either the data lived on the writable layer (gone when the container was recreated post-reboot, Q21) or on a host-local volume on a node that was replaced rather than rebooted, so the data didn't follow. **Mount/permission errors on start** point at volumes whose backing wasn't ready or whose ownership/labels are wrong: a network volume (NFS/block) not re-attached/re-mounted before the container started, an SELinux relabel (`:z`/`:Z`) missing so the container's UID can't access the host dir, or a UID/GID mismatch after the volume came back.

```bash
# which containers lost data vs. which can't mount?
docker inspect -f '{{json .Mounts}}' <ctr> | jq      # volume vs bind, source path, RW
docker volume ls && docker volume inspect <vol>       # driver, mountpoint — is it local & host-pinned?
mount | grep <data-path>; dmesg | grep -i -E 'nfs|mount|selinux'  # backing ready? denied?
ls -ln <host-volume-path>                              # ownership vs the container's UID/GID
getenforce; ausearch -m avc -ts recent                 # SELinux AVC denials on the mount?
```

Root-cause patterns and their fixes: **(1) data on CoW/anonymous volume** → the data was never durable; move to a *named, network-backed* volume so it's decoupled from the container/node lifecycle. **(2) host-local volume on a replaced node** → host-local doesn't survive node loss; use block/CSI storage that reattaches (Q77/Q89). **(3) network volume mounted after the container started** → ordering/race: the container must depend on the mount being ready (systemd mount unit ordering, an init/wait, or orchestrator volume-attach completing before pod start). **(4) SELinux/permission** → add the relabel suffix (`-v vol:/data:Z`) and ensure the volume's ownership matches the container's runtime UID, ideally via `--chown` at build or an init step.

```
symptom                          root cause                          prevention
─────────────────────────        ───────────────────────────────     ─────────────────────────────
lost data after recreate         CoW/anon/host-local volume          named, network-backed volume
"permission denied" on mount     SELinux relabel / UID mismatch      :Z relabel + fixed UID ownership
"no such device"/mount fails     backing not re-attached/ordered     CSI/orchestrated attach + start-order dep
some up, some not                mixed: durable vs ephemeral storage  standardize storage contract per workload
```

Prevention is architectural, not per-incident: define a **storage contract** so stateful workloads *only* use durable, network-backed volumes with a failover story; enforce **start ordering** (the workload waits for its storage to be attached and mounted — this is exactly what orchestrator volume controllers and `restart: unless-stopped` + readiness gating provide); set **explicit ownership and SELinux labels** so a remount is always accessible; and run **`--read-only` rootfs with named volumes** so it's impossible to accidentally depend on ephemeral CoW state. Finally, the incident is also a *test gap*: deliberately reboot/replace nodes in staging (chaos drills) so "does our data survive a node going away" is verified before prod proves it the hard way. The conceptual through-line: a reboot is just a forced recreation, and anything that breaks on recreation was never actually durable — the fix is making durability explicit in the storage layer rather than accidentally relying on a container or host outliving its purpose.

#### Q92. [Theory] What is the relationship between OCI artifacts, the referrers API, and storing non-image content (Helm charts, SBOMs, signatures, WASM) in a registry — and why did registries become general artifact stores?

The OCI image-spec is generic enough that a "manifest + config + blobs" structure doesn't *have* to describe a runnable image — the **config media type** is just a string, and the blobs are just content-addressed bytes. **OCI Artifacts** formalize this: you can push *any* content (a Helm chart, a WASM module, an ML model, a Tekton bundle, an SBOM, a signature) as a manifest whose `artifactType`/config media type identifies what it is, reusing the registry's existing content-addressed storage, dedup, auth, and distribution. So the registry you already run for images becomes a general-purpose, content-addressed artifact store with one access-control and replication model.

The **referrers API** (OCI 1.1) adds the crucial *linking* primitive: an artifact manifest can declare a **`subject`** field pointing at another manifest by digest, meaning "this artifact *refers to* that image." The registry then lets you **query all referrers of a digest** (`GET /v2/<name>/referrers/<digest>`). This is exactly how **signatures, SBOMs, and SLSA provenance attestations** attach to an image without changing the image's digest: cosign pushes a signature manifest with `subject = imageDigest`, and a verifier asks the registry "what signatures/attestations refer to this digest?" — enabling "given a running digest, prove how it was built and what's in it" (Q29).

```
image manifest (digest D)
   ▲ subject: D
   ├── signature artifact     (cosign)        ─┐
   ├── SBOM artifact          (syft/CycloneDX)  ├ discoverable via referrers API:
   └── SLSA provenance        (buildkit)       ─┘   GET /v2/<n>/referrers/D
Helm chart / WASM / model     → standalone OCI artifacts (own artifactType), same registry
```

Why this evolution happened: before referrers, attaching metadata to an image meant either mutating tags (the old cosign `sha256-<digest>.sig` tag convention — a hack that polluted the tag namespace and broke retention/GC) or running *separate* systems for charts (chart museums), signatures (Notary servers), and SBOM stores. Consolidating onto the registry gives **one content-addressed, authenticated, replicated, GC'd store** for the entire software supply chain — the same dedup and distribution that made image layers efficient now applies to every related artifact, and the `subject`/referrers link makes the graph (image ← its SBOM ← its signature) machine-traversable.

The trade-offs/caveats worth raising: not all registries fully implement the 1.1 referrers API yet, so cosign and others keep a **fallback tag scheme** for older registries; mixing many artifact types raises the importance of **retention/GC policy** (you don't want to GC an image but orphan its still-referenced provenance, or vice versa); and access control must treat artifacts as first-class (who can push a *signature* to a repo matters as much as who can push the image). The staff-level framing: OCI artifacts + referrers turned the registry from "a place to keep images" into "the content-addressed backbone of the software supply chain," which is what makes digest-pinned, signed, attested, policy-enforced deployment (Q23/Q29/Q54) practical with one piece of infrastructure rather than five.

#### Q93. [Practical] Containers in production exhibit periodic latency spikes with no error logs and normal average CPU. How do you confirm CFS CPU throttling as the cause, and what do you tune?

Latency spikes with healthy *averages* and no application errors are the signature of **CFS bandwidth throttling**: when you set `--cpus` (cgroup `cpu.max` = quota/period, default period 100ms), the kernel lets the container burn its quota and then **hard-stops every thread until the next period**, producing stalls of up to a full period. Average CPU looks fine because the throttling is bursty, but tail latency suffers, and nothing logs an error because the process is merely *paused*, not failing. The confirmation is to read the cgroup's throttling counters directly.

```bash
# cgroups v2: cpu.stat exposes the smoking gun
cat /sys/fs/cgroup/.../cpu.stat
#   nr_periods 12000
#   nr_throttled 3400        ← throttled in 28% of periods  → CPU throttling confirmed
#   throttled_usec 4200000   ← total time threads sat stalled
docker stats --no-stream     # CPU% near the --cpus cap during spikes
```

A high **`nr_throttled` / `nr_periods` ratio** and growing `throttled_usec` is direct proof: the workload is bursty enough to exhaust its quota within periods even though its *average* utilization is below the cap. This is extremely common for latency-sensitive services that do short, intense bursts of work per request (deserialization, GC, TLS) — exactly the workloads where tail latency matters most. JVM/Go apps with many threads are especially prone, because the quota is shared across *all* threads and a multithreaded burst drains it in a fraction of the period.

What to tune, in order of preference: **(1) raise or remove the CPU quota** for latency-sensitive services — if you don't actually need a hard cap, use CPU *requests/shares* (relative weight under contention, Q72) instead of an absolute `--cpus` ceiling, so the app can burst into idle capacity. **(2) Right-size the quota to the burst, not the average** — a service averaging 0.3 cores but bursting to 2 cores per request needs roughly its burst as quota, or it throttles every request. **(3) Tune the CFS period** (`cpu.cfs_period_us`) shorter in some cases to reduce the *duration* of each stall (smaller periods → smaller max stall), though this is a finer lever. **(4) Reduce thread/GC parallelism** to match the quota (e.g., size GC threads to allocated cores) so the app doesn't self-inflict bursts that exceed quota.

```
confirm:  cpu.stat → nr_throttled/nr_periods high, throttled_usec rising
fix priority:
  1 remove hard cap → use shares/weight (burst into idle CPU)    ← best for latency-sensitive
  2 size quota to BURST not average
  3 shorter cfs_period (smaller max stall) — finer tuning
  4 align thread/GC count to allocated cores (stop self-bursting)
```

The judgment to articulate: **`--cpus` is a throughput/fairness cap, not a latency control** — applying a tight CPU cap to a latency-sensitive bursty service is a self-inflicted SLO violation. For batch/throughput work, capping is fine; for request-serving tail-latency-sensitive work, prefer relative weights so the app can absorb bursts, and only impose a hard cap when multi-tenant fairness genuinely requires it — then size it to the burst. The diagnostic skill is knowing that "spikes + fine averages + no errors" points at `cpu.stat`'s throttle counters, not at the application.

#### Q94. [Theory] Explain the trade-offs between Alpine (musl), distroless, and Chainguard/Wolfi base images. Where does musl libc bite, and how do you choose for a given runtime?

These three represent different points on the *minimalism vs. compatibility vs. supply-chain* spectrum. **Alpine** is tiny (~5 MB) because it uses **musl libc** and BusyBox instead of glibc/GNU coreutils, and it has a package manager (`apk`) so you can install tools — convenient but the musl substitution is the source of real, subtle problems. **Distroless** (Google) ships *only* your app's runtime dependencies and a minimal set of files (CA certs, `/etc/passwd`, tzdata) with **no shell and no package manager** — usually glibc-based, so it avoids musl issues while drastically cutting attack surface; you can't `apk add` anything because there's nothing to add to. **Chainguard/Wolfi** is a glibc-based, **continuously-rebuilt, near-zero-CVE** distribution designed for the supply chain — minimal like distroless but with `apk`-style packaging (Wolfi), SBOMs and signatures by default, and aggressive CVE remediation.

```
                size     libc    shell/pkg mgr   CVE posture        best for
Alpine          ~5MB     musl    yes (apk/sh)    small but musl risk  small images, you accept musl
distroless      ~20MB+   glibc   NO / NO         low (few packages)   compiled binaries, hardened runtime
Chainguard/Wolfi minimal glibc   wolfi: apk      lowest (rebuilt)     supply-chain-strict orgs
```

**Where musl bites** (the Alpine gotchas an interviewer wants): (1) **DNS resolution differences** — musl's resolver historically didn't support some `/etc/resolv.conf` options, parsed `search` domains differently, and handled large/TCP DNS responses unlike glibc, causing intermittent resolution failures that don't reproduce on glibc. (2) **Performance/correctness of the allocator and threading** — musl's malloc and smaller default thread stack size have caused slowdowns and stack-overflow crashes in memory-heavy apps (notably some Python/numpy and JVM workloads). (3) **Native wheels / prebuilt binaries** — Python `manylinux` wheels and many vendor binaries are built against glibc, so on Alpine `pip install` falls back to *compiling from source* (slow, needs build deps) or fails; the same hits Node native addons and any glibc-linked third-party binary. (4) **Locale/`iconv`** gaps. These bugs are insidious because they pass basic testing and surface under load or specific inputs.

The selection rule: for a **statically-linked compiled binary** (Go with `CGO_ENABLED=0`, Rust), `scratch`/distroless `static` is ideal — no libc concern at all. For an **interpreted/managed runtime** (Python, Node, Java) prefer **glibc-based distroless or a `-slim` glibc image** to avoid musl's native-wheel/DNS/allocator surprises — the size win of Alpine is rarely worth debugging a musl-only production incident. Choose **Alpine** only when you genuinely need its tiny size *and* your stack is musl-clean (pure-Go/Rust, or you've validated your native deps build against musl). Choose **Chainguard/Wolfi** when supply-chain posture (near-zero CVEs, default SBOM/signing, fast patching) is a priority and you want minimalism without giving up glibc compatibility or packaging.

The staff-level point: "smallest base wins" is a trap — the meaningful axes are **libc compatibility** (musl breaks glibc-built native code and has DNS/allocator quirks), **attack surface** (distroless/Wolfi remove shell+package manager, which both shrinks CVEs and denies attackers tooling), and **patch velocity** (Chainguard rebuilds continuously). Match the base to your runtime's linking model and your org's security requirements, and prefer glibc-distroless as the safe default for managed runtimes, reserving Alpine for validated musl-clean stacks.

#### Q95. [Practical] Design a zero-downtime image update strategy for a single Docker host running Compose (no orchestrator). What are the limits, and how do you implement blue-green or rolling on one box?

On a single host you don't have an orchestrator's reconciliation loop, so "zero downtime" means manually coordinating a **second set of containers + a reverse proxy switch** — essentially hand-rolling blue-green. The core pattern: run the new version (green) alongside the old (blue), health-check green, then atomically flip a reverse proxy (Nginx/Traefik/Caddy) from blue to green, and finally retire blue. The reverse proxy is what makes the cutover atomic from the client's perspective; Compose alone can't do it because `docker compose up` recreates a service's container in place (a brief gap) and plain `up -d` on a changed image stops-then-starts.

```yaml
# blue-green on one host: proxy in front, two app stacks share a network
services:
  proxy:                       # Traefik/Nginx — the atomic switch point
    image: traefik:3
    ports: ["80:80"]
    volumes: ["/var/run/docker.sock:/var/run/docker.sock:ro"]  # (or a static config swap)
  app-blue:
    image: myapp:1.0
    labels: ["traefik.http.routers.app.service=app-blue"]   # active
    healthcheck: { test: ["CMD","app","health"], interval: 5s, retries: 3 }
  app-green:
    image: myapp:1.1
    labels: ["traefik.http.routers.app.service=app-green"]   # staged, not yet routed
    healthcheck: { test: ["CMD","app","health"], interval: 5s, retries: 3 }
```

```bash
docker compose up -d app-green                 # start new version alongside old
until docker inspect -f '{{.State.Health.Status}}' app-green | grep -q healthy; do sleep 1; done
# flip the proxy to green (label/route change + reload, or swap an Nginx upstream conf + nginx -s reload)
# verify, then:
docker compose stop app-blue                   # drain old (graceful SIGTERM, Q82)
```

For **rolling** with multiple replicas on one host, scale up the new version, health-gate it, add it to the proxy's upstream pool, then remove and stop old replicas one at a time so capacity never drops. Traefik's Docker provider (or an Nginx config with `reload`) updates the upstream set without dropping established connections; combine with proper **graceful shutdown** (Q82) so draining blue doesn't cut in-flight requests.

```
blue (v1.0) serving ──┐
                      ├─ proxy ──► clients
green (v1.1) staged ──┘  (healthy?) → flip route to green → drain+stop blue
rolling: scale green up, add to pool, drain blue replicas one-by-one (capacity never < N)
```

The **limits** to state honestly: a single host has **no protection against host failure** (the box dies → everyone dies, regardless of blue-green), **finite capacity** (blue+green must both fit on one machine during the overlap, so you need headroom), and **no automatic rollback/self-healing** — you script it. Database/stateful changes still need backward-compatible migrations (expand/contract), because blue and green run simultaneously against the same DB. This is precisely the wall from Q67: blue-green on one host gives you zero-downtime *deploys* but not high *availability*; the moment you need survival across host failure, autoscaling, or hands-off rollback, you've reimplemented a worse orchestrator and should move to Kubernetes/Swarm. The implementation answer (proxy + two stacks + health-gated flip + graceful drain) is correct and valuable for a single box; the architectural answer is knowing its ceiling.

#### Q96. [Theory] How do user namespaces, subordinate UID/GID ranges (`/etc/subuid`), and volume ownership interact in rootless Docker — and why do bind-mount permission problems get harder?

In rootless Docker (and userns-remap, Q50) the daemon and containers run inside a **user namespace** where container UIDs are *mapped* to a block of **subordinate UIDs** allocated to the launching user in `/etc/subuid` / `/etc/subgid`. So container-`root` (UID 0) is not host UID 0 — it's the *first* ID in the user's subordinate range, e.g. host UID 100000, and container UID 1000 is host UID 101000 (100000 + 1000). The kernel maintains this mapping in `/proc/<pid>/uid_map`. This is what makes rootless safe (a breakout lands you as an unprivileged, range-confined host user), but it's *exactly* what makes file ownership confusing.

```
/etc/subuid:  alice:100000:65536      → alice owns host UIDs 100000..165535
inside container        host (real) ownership
  UID 0    (root)   →   100000
  UID 1000 (app)    →   101000
  UID 65534         →   165534
```

The bind-mount problem: a **bind mount maps a host path straight in with its host ownership** (Q59), but the container sees and writes files through the *mapped* identity. So a file the container creates as "root" is owned on the host by UID 100000 — a UID your normal user account *doesn't* own, so on the host you (alice, UID 1000) can't read/delete it without `sudo` or `podman unshare`/`nsenter` tricks. Conversely, a host directory owned by alice (UID 1000) is seen *inside* the container as some high, unmapped UID (because host 1000 isn't in the container's map), so the container process can't access it — the classic "permission denied on a bind mount that's clearly mine" in rootless mode. Named volumes are easier because Docker manages their ownership within the mapped range, but bind mounts cross the host/namespace ownership boundary where the mapping bites.

```
rootful (default):   ctr writes as UID 0 → host file owned by 0  (root) — simple, but dangerous
rootless:            ctr writes as UID 0 → host file owned by 100000 (subuid) — you can't touch it
                     host dir owned by your UID 1000 → ctr sees unmapped UID → "permission denied"
```

How to manage it: (1) prefer **named volumes over bind mounts** in rootless so Docker handles ownership inside the mapped range. (2) When you must bind-mount, make the container run as a UID whose *mapped* host UID matches the host directory's owner, or pre-`chown` the host directory to the mapped UID — tools like `podman unshare chown` (or running the chown *inside* a container in the same userns) let you set ownership in the namespace's terms. (3) Use **`--userns=keep-id`** (Podman) / appropriate id-mapping options so the container user maps back to your host user for bind mounts you need to share. (4) On modern kernels, **idmapped mounts** let the mount itself apply a UID shift, cleanly resolving the host-owner-vs-container-owner mismatch without chowning the underlying files — the emerging "right" fix.

The conceptual point: rootless trades the "container root = host root" danger for a **UID-mapping indirection**, and bind mounts are where that indirection becomes visible because they straddle two ownership worldviews (the host's real UIDs and the namespace's mapped UIDs). The harder permission debugging is the *cost* of the stronger isolation — you reason about ownership in *both* the host UID space and the subordinate-mapped space, and you fix mismatches with named volumes, deliberate UID alignment, `keep-id`/userns options, or idmapped mounts rather than the rootful reflex of "just chown it to root."

#### Q97. [Practical] Your team must choose between Docker Compose, Docker Swarm, Nomad, and Kubernetes for a new platform. Build the decision framework and defend a recommendation with concrete trade-offs.

Frame the decision by *operational requirements*, not popularity, because the right answer is whichever matches the team's actual needs at the lowest complexity it can get away with — over-provisioning orchestration is as costly as under-provisioning it. The axes that decide it: **scale** (one host vs. many nodes), **availability target** (tolerate host failure?), **rollout sophistication** (need canary/automatic rollback?), **ecosystem needs** (service mesh, operators, GitOps, autoscaling), **workload heterogeneity** (containers only, or also VMs/batch/non-containerized?), and **team capacity to operate it** (the single biggest hidden cost).

```
                Compose       Swarm           Nomad              Kubernetes
scale           1 host        small multi-host multi-host (huge)  multi-host (huge)
HA/self-heal    none          basic            yes                yes (mature)
rollouts        none          rolling          rolling/canary      rolling/canary/+ecosystem
workload types  containers    containers       containers+VMs+batch+exec  containers (CRDs extend)
ops complexity  trivial       low              moderate            high
ecosystem       n/a           shrinking        modest, clean       vast (mesh, operators, GitOps)
best fit        dev/CI/1-box  simple clusters  mixed workloads,    scale + ecosystem,
                                               lean ops            have/willing platform team
```

**Concrete trade-offs:** **Compose** is unbeatable for local dev, CI, and single-host internal tools — one YAML, instant iteration — but has no multi-node, HA, or rollout story (Q67), so it's a *development and small-deployment* tool, not a platform. **Swarm** gives basic clustering built into Docker with a gentle learning curve, but its ecosystem mindshare has declined sharply, so betting a *new* platform on it risks stagnation and a thin tooling/hiring pool. **Nomad** (HashiCorp) is the underrated middle: a single small binary, far simpler to operate than Kubernetes, and uniquely good at **heterogeneous workloads** (it schedules containers *and* raw binaries, Java, batch, even VMs), integrating cleanly with Consul/Vault — the right pick for a lean ops team with mixed workloads who find K8s overkill. **Kubernetes** is the industry standard with an unmatched ecosystem (service mesh, operators, HPA/VPA, GitOps, a huge talent pool and managed offerings — EKS/GKE/AKS), but it carries real operational complexity and a steep learning curve, justified only when you actually need its capabilities.

**Recommendation logic:** Default to **managed Kubernetes (EKS/GKE/AKS)** *if* you need multi-node HA, sophisticated rollouts/autoscaling, and a rich ecosystem **and** you'll use a managed control plane (which removes most of the operational pain that makes K8s "too complex") — this is the safe long-horizon bet for a platform expected to grow, precisely because the ecosystem and hiring pool de-risk it. Choose **Nomad** if your workloads are mixed (not just containers) or your team is small and wants HA/scheduling without K8s's surface area — it's a deliberate complexity reduction with a clear-eyed acceptance of a smaller ecosystem. Keep **Compose** regardless for local dev/CI (it complements any of the above — same OCI image runs everywhere, Q26). Avoid **Swarm** for greenfield platforms given its declining trajectory, unless you specifically want minimal clustering and value Docker-native simplicity over ecosystem.

The defensible position to articulate: the artifact (the OCI image) is identical across all four, so the choice is purely about *operational model and complexity-vs-capability fit*. The most common mistakes are reaching for Kubernetes before you have multi-node/HA/ecosystem needs (paying enormous complexity tax for unused capability) and refusing it after you've hand-rolled half an orchestrator on Compose/scripts. Pick the *least* complex option that meets your real availability, scale, rollout, and workload-heterogeneity requirements, prefer *managed* control planes to amortize operational cost, and revisit the decision when requirements cross a threshold — the migration cost is in the operational model, not in repackaging the software.

## 🧩 Extended Questions — Supplemental Set B: Coding & Expert

### 🟢 Basic — extended

#### Q98. [Coding] Write a Dockerfile and `.dockerignore` for a Python Flask app, and show the build/run commands.

The most common beginner mistake is shipping the whole working directory (including `.git`, virtualenvs, and `__pycache__`) into the build context, which bloats the image and slows builds. Pair every Dockerfile with a `.dockerignore` from day one — it is to `docker build` what `.gitignore` is to `git`.

```dockerfile
# Dockerfile
FROM python:3.12-slim
WORKDIR /app

# Copy only the manifest first so the pip layer caches independently of code
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY . .
EXPOSE 5000
# Gunicorn, not the dev server, for anything beyond local play
CMD ["gunicorn", "--bind", "0.0.0.0:5000", "app:app"]
```

```gitignore
# .dockerignore
.git
.gitignore
__pycache__/
*.pyc
.venv/
venv/
.env
*.md
tests/
```

```bash
docker build -t flaskapp:1.0 .
docker run --rm -p 5000:5000 flaskapp:1.0
# Confirm it serves:
curl -s http://localhost:5000/health
```

Note `--bind 0.0.0.0` — a Flask/Gunicorn server bound to `127.0.0.1` inside the container is unreachable from the host even with `-p`, because loopback inside the container's network namespace is not the host's loopback. Excluding `.env` and `tests/` keeps secrets and bulk out of the image; excluding `.git` alone often shaves tens of MB.

#### Q99. [Practical] Show every way to list, inspect, and clean up containers and images from the CLI, and explain when each is safe.

Day-to-day Docker hygiene is mostly four verbs: list, inspect, stop/remove, prune. The danger zone is the prune family, which deletes in bulk and is irreversible.

```bash
docker ps                      # running containers
docker ps -a                   # include stopped ones
docker images                  # local images
docker inspect <id>            # full JSON: mounts, env, network, state
docker logs --tail 100 -f <id> # follow logs

# Targeted removal (safe, explicit)
docker stop <id> && docker rm <id>
docker rmi <image>             # fails if a container references it

# Bulk cleanup (read the warning every time)
docker container prune         # remove all STOPPED containers
docker image prune             # remove dangling (untagged) images only
docker image prune -a          # remove ALL images not used by a container ← aggressive
docker system prune            # containers + networks + dangling images + build cache
docker system prune -a --volumes  # ← nukes everything reclaimable INCLUDING volumes
```

The rule: explicit `rm`/`rmi` by ID is always safe because you name the target. `prune` without `-a` removes only clearly-unused artifacts (stopped containers, dangling images) and is safe to run routinely. `prune -a` and especially `--volumes` are destructive — `--volumes` can delete a database's data if the volume happens to be unattached at that moment (e.g., the DB container is stopped). On shared hosts, never run `system prune -a --volumes` reflexively; scope cleanup with filters like `docker image prune -a --filter "until=168h"` to keep recent images.

#### Q100. [Coding] Write a `docker run` invocation that applies a sensible production hardening baseline, and explain each flag.

A bare `docker run image` gives the container far more privilege than it needs. The hardening baseline below is what you would template for every production workload — it is defense-in-depth, so even an app-level RCE has little room to escalate.

```bash
docker run -d \
  --name api \
  --read-only \                       # immutable root FS — attacker can't drop a binary
  --tmpfs /tmp:rw,size=64m \          # writable scratch without persisting to disk
  --cap-drop ALL \                    # drop every Linux capability...
  --cap-add NET_BIND_SERVICE \        # ...add back only what the app needs
  --security-opt no-new-privileges \  # block setuid escalation
  --user 10001:10001 \                # non-root UID/GID
  --memory 512m --memory-swap 512m \  # cap RAM, disable swap blowout
  --cpus 1.5 \                        # CPU quota
  --pids-limit 200 \                  # contain fork bombs
  --restart on-failure:5 \
  -p 127.0.0.1:8080:8080 \            # publish to loopback only (front it with a proxy)
  myorg/api:1.4.2@sha256:abc123...    # pin by digest
```

Each flag closes a specific hole: `--read-only` + `--tmpfs` means the only writable path is ephemeral scratch, so malware can't persist; `--cap-drop ALL` removes the ~14 default capabilities and you re-add only `NET_BIND_SERVICE` (to bind port < 1024) — never blanket-add `SYS_ADMIN`. `no-new-privileges` neutralizes setuid-based escalation. `--pids-limit` is an underused but important DoS guard. `--memory-swap` set equal to `--memory` disables swap so the container is OOM-killed deterministically rather than silently thrashing. Pinning by digest guarantees byte-for-byte the image you tested. The one trade-off: `--read-only` breaks apps that write to arbitrary paths; you discover those at startup and add targeted `--tmpfs`/volume mounts.

### 🟡 Intermediate — extended

#### Q101. [Coding] Write a Bash entrypoint script that waits for a dependency, runs migrations once, and execs the app as PID 1.

A naive `CMD ["python","app.py"]` starts the app before the database is reachable and races on migrations when multiple replicas boot. The pattern below solves three problems: dependency readiness, migration idempotency, and correct signal handling via `exec`.

```bash
#!/usr/bin/env bash
set -euo pipefail   # fail fast, fail on unset vars, fail on pipe errors

DB_HOST="${DB_HOST:-db}"
DB_PORT="${DB_PORT:-5432}"

echo "Waiting for ${DB_HOST}:${DB_PORT}..."
# Pure-bash TCP probe — no nc/curl dependency needed
until (echo > "/dev/tcp/${DB_HOST}/${DB_PORT}") 2>/dev/null; do
  sleep 1
done
echo "Database reachable."

# Run migrations under an advisory lock so only ONE replica migrates.
# Postgres advisory lock survives the session and auto-releases on disconnect.
if [[ "${RUN_MIGRATIONS:-true}" == "true" ]]; then
  python manage.py migrate --noinput
fi

# exec REPLACES the shell with the app, so the app becomes PID 1 and
# receives SIGTERM directly — graceful shutdown works.
exec "$@"
```

```dockerfile
COPY entrypoint.sh /usr/local/bin/entrypoint.sh
RUN chmod +x /usr/local/bin/entrypoint.sh
ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]
CMD ["gunicorn", "--bind", "0.0.0.0:8000", "app:app"]
```

The critical line is `exec "$@"`: without `exec`, the shell stays PID 1 and your app is a child, so `docker stop`'s `SIGTERM` hits the shell (which ignores it) and the app is hard-killed after the grace period. The `set -euo pipefail` header turns silent failures (e.g., a failed migration) into a non-zero exit that crashes the container loudly rather than starting a broken app. For migrations, the real production fix is a DB-level lock (shown conceptually) or a separate one-shot migration Job — running `migrate` in every replica's entrypoint races; here `--noinput` plus a real advisory lock or init Job makes it safe.

#### Q102. [Coding] Write a healthcheck for a service that has no HTTP endpoint (a queue worker), and wire it into Compose.

`HEALTHCHECK` defaults assume an HTTP service, but background workers (consumers, cron-like jobs) have no port to curl. The trick is to make the worker write a liveness heartbeat to a file and have the healthcheck assert the file is recent.

```python
# worker.py — touch a heartbeat file each loop iteration
import time, os
HEARTBEAT = "/tmp/heartbeat"
while True:
    process_one_message()          # your real work
    os.utime(HEARTBEAT, None) if os.path.exists(HEARTBEAT) else open(HEARTBEAT, "w").close()
    time.sleep(1)
```

```bash
#!/bin/sh
# healthcheck.sh — fail if heartbeat is older than 30s (worker stuck/dead)
HEARTBEAT=/tmp/heartbeat
[ -f "$HEARTBEAT" ] || exit 1
AGE=$(( $(date +%s) - $(stat -c %Y "$HEARTBEAT") ))
[ "$AGE" -lt 30 ] && exit 0 || exit 1
```

```yaml
services:
  worker:
    build: .
    healthcheck:
      test: ["CMD", "/healthcheck.sh"]
      interval: 10s
      timeout: 3s
      retries: 3
      start_period: 20s     # grace window before failures count, for slow startup
    restart: unless-stopped
```

This catches the failure mode that a process-liveness check misses: a worker whose process is alive but **stuck** (deadlocked, blocked on a dead broker connection) stops updating the heartbeat, so the healthcheck flips to unhealthy and the orchestrator restarts it. A naive "is the process running" check would report healthy forever. Tune `start_period` to exceed cold-start time, otherwise early failures during warmup count against `retries` and you get a crash-loop on a perfectly healthy worker.

#### Q103. [Coding] Write a multi-stage Dockerfile for a React frontend that builds with Node and serves with nginx, including a custom nginx config for SPA routing.

A single-stage frontend image wrongly ships Node, the source, and `node_modules` (hundreds of MB) to serve static files. The correct shape builds with Node, then copies only the compiled `dist/` into a tiny nginx image. SPA routing also needs an nginx `try_files` rule, or deep links 404.

```dockerfile
# ---- Build stage ----
FROM node:22-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
RUN npm run build          # emits static assets to /app/dist

# ---- Serve stage ----
FROM nginx:1.27-alpine
COPY --from=build /app/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
# nginx already handles SIGTERM/SIGQUIT correctly as PID 1
CMD ["nginx", "-g", "daemon off;"]
```

```nginx
# nginx.conf
server {
    listen 80;
    root /usr/share/nginx/html;
    index index.html;

    # SPA: any unknown path falls back to index.html so client-side routing works
    location / {
        try_files $uri $uri/ /index.html;
    }

    # Cache hashed assets aggressively; never cache index.html
    location ~* \.(js|css|png|jpg|svg|woff2)$ {
        expires 1y;
        add_header Cache-Control "public, immutable";
    }
    location = /index.html { add_header Cache-Control "no-cache"; }
}
```

The result is typically a ~25 MB image instead of ~400 MB, with no Node runtime or source code in the shipped artifact (smaller attack surface, no accidental source leak). The `try_files ... /index.html` line is the load-bearing detail: without it, refreshing `https://app/users/42` returns nginx's 404 because no `/users/42` file exists on disk — the request must be rewritten to `index.html` so the SPA router can take over. The caching split (immutable hashed assets, no-cache HTML) is what makes deploys instant for users while still picking up new builds.

#### Q104. [Practical] Explain `docker compose` profiles, multiple compose files, and override precedence, with a concrete dev-vs-prod example.

Teams often maintain divergent copy-pasted compose files for dev and prod, which drift. Compose has two composition mechanisms that avoid this: **multiple files merged in order** and **profiles** to toggle optional services.

```yaml
# compose.yaml — the shared base
services:
  api:
    image: myorg/api:${TAG:-latest}
    environment:
      LOG_LEVEL: info
  db:
    image: postgres:17
  # tooling only needed sometimes — gated behind a profile
  adminer:
    image: adminer
    profiles: ["debug"]
```

```yaml
# compose.override.yaml — auto-merged in DEV (picked up automatically)
services:
  api:
    build: .                 # build locally instead of pulling
    volumes: ["./src:/app/src"]  # live-reload mount
    environment:
      LOG_LEVEL: debug       # overrides base value
```

```bash
# Dev: base + override merged automatically
docker compose up

# Enable the optional debug tool
docker compose --profile debug up

# Prod: pick files explicitly, ignore the dev override
docker compose -f compose.yaml -f compose.prod.yaml up -d
```

Precedence rules matter: later `-f` files override earlier ones, and `compose.override.yaml` is loaded automatically *after* `compose.yaml` (which is why it wins in dev). Scalars (like `LOG_LEVEL`) are replaced; some sequences are merged. Profiles let you keep optional services (admin UIs, seeders, load-test rigs) in the *same* file but inactive unless explicitly requested — so dev and prod share one source of truth and differ only by which files/profiles you compose, eliminating drift between near-identical YAML copies.

#### Q105. [Coding] Write a Compose setup that uses an `.env` file, variable interpolation, and a one-shot init container pattern.

Hardcoding image tags and credentials in compose files makes them non-portable across environments. Compose interpolates `${VAR}` from the shell and from a `.env` file in the project directory, and you can model a run-once initializer as a service the app `depends_on` with `service_completed_successfully`.

```bash
# .env  (auto-loaded; never commit secrets — use this only for non-secret config)
TAG=1.4.2
POSTGRES_DB=app
APP_PORT=8080
```

```yaml
# compose.yaml
services:
  db:
    image: postgres:17
    environment:
      POSTGRES_DB: ${POSTGRES_DB}
      POSTGRES_PASSWORD_FILE: /run/secrets/db_pw
    secrets: [db_pw]
    healthcheck:
      test: ["CMD-SHELL", "pg_isready"]
      interval: 5s
      retries: 5

  migrate:                         # one-shot init: runs, completes, exits 0
    image: myorg/api:${TAG}
    command: ["python", "manage.py", "migrate", "--noinput"]
    depends_on:
      db: { condition: service_healthy }
    restart: "no"                  # do NOT restart a one-shot job

  api:
    image: myorg/api:${TAG}
    ports: ["${APP_PORT}:8080"]
    depends_on:
      migrate: { condition: service_completed_successfully }  # start only after migrate exits 0

secrets:
  db_pw:
    file: ./db_pw.txt
```

This is the idiomatic "init container" pattern in plain Compose: `migrate` runs to completion exactly once, and `api` waits for `service_completed_successfully` so it never starts against an unmigrated schema. Using `${TAG}` from `.env` means promoting a build across environments is a one-line change, not a YAML edit. Keep real secrets out of `.env` (it is plaintext and easy to commit by accident) — model passwords as Compose `secrets` backed by files or an external manager, and reserve `.env` for non-sensitive config like ports and feature flags.

#### Q106. [Practical] How do you tag and promote a single image across dev/staging/prod without rebuilding, and why is rebuilding per environment an anti-pattern?

Rebuilding per environment breaks the core guarantee of containers: that the artifact you tested is bit-for-bit the artifact you ship. A rebuild can pull a newer base layer, a floating dependency, or a different build host state, so "it passed in staging" no longer implies "this exact thing runs in prod." The correct model is **build once, promote by re-tagging the same digest.**

```bash
# Build ONCE in CI, tag with an immutable identifier (the git SHA)
docker build -t registry.acme.io/api:git-9a99ed6 .
docker push registry.acme.io/api:git-9a99ed6

# Capture the digest — this is the immutable identity
DIGEST=$(docker inspect --format='{{index .RepoDigests 0}}' registry.acme.io/api:git-9a99ed6)

# Promotion = adding a moving tag that points at the SAME digest (no rebuild)
docker buildx imagetools create \
  --tag registry.acme.io/api:staging \
  "$DIGEST"
# ...after staging sign-off, promote the identical bytes to prod
docker buildx imagetools create \
  --tag registry.acme.io/api:prod \
  "$DIGEST"
```

`buildx imagetools create` re-tags server-side without pulling or rebuilding — it just adds a registry tag pointing at the existing manifest digest, so `staging` and `prod` provably reference identical content. Deployments should reference the **digest** (`api@sha256:...`), not the moving tag, so a re-pushed `prod` tag can't silently change what's running. The environment-specific differences (DB URLs, feature flags, replica counts) belong in *configuration* injected at runtime, never baked into per-environment image builds. This is also what makes rollback trivial: re-point the tag at the previous digest.

### 🟠 Advanced — extended

#### Q107. [Coding] Write a Dockerfile and BuildKit command that injects an SSH key to pull a private Git dependency without leaking it into any layer.

Pulling a private dependency at build time tempts engineers to `COPY` a deploy key into the image or pass it via `ARG` — both leak permanently into image history. BuildKit's `--ssh` mount forwards the agent socket only for the duration of one `RUN`, leaving nothing in any layer.

```dockerfile
# syntax=docker/dockerfile:1.7
FROM golang:1.23 AS build
WORKDIR /src

# Tell git to use SSH for the private host and trust its host key
RUN mkdir -p -m 0700 ~/.ssh && ssh-keyscan github.com >> ~/.ssh/known_hosts
ENV GOPRIVATE=github.com/acme/*

COPY go.mod go.sum ./
# The SSH agent is mounted ONLY for this RUN; no key material is persisted
RUN --mount=type=ssh \
    --mount=type=cache,target=/go/pkg/mod \
    go mod download

COPY . .
RUN CGO_ENABLED=0 go build -o /out/app ./cmd/app

FROM gcr.io/distroless/static:nonroot
COPY --from=build /out/app /app
ENTRYPOINT ["/app"]
```

```bash
# Forward the host's SSH agent into the build
eval "$(ssh-agent -s)" && ssh-add ~/.ssh/id_ed25519
DOCKER_BUILDKIT=1 docker build --ssh default -t acme/app:1.0 .
```

Verify nothing leaked: `docker history --no-trunc acme/app:1.0` and `docker save acme/app:1.0 | tar -tf - ` will show no key material, because the agent socket was a bind mount present only during `go mod download`. Compare this to the broken approaches: `COPY id_ed25519 .` persists the key in a layer forever (even if a later layer deletes it, the blob remains and is extractable), and `ARG SSH_KEY` shows up in `docker history`. The `--mount=type=cache` on the module cache is a bonus — it makes warm builds skip re-downloading dependencies entirely. For HTTPS-token-based registries, the analogous tool is `--mount=type=secret`.

#### Q108. [Coding] Write a script that diffs two image tags to find which layer added bloat, and explain how to read the output.

When an image suddenly grows, the question is *which instruction* added the weight. `docker history` shows per-layer sizes; combining it with the layer-by-layer view from `dive` (or a scripted comparison) pinpoints the culprit without guessing.

```bash
#!/usr/bin/env bash
# layer-diff.sh OLD_TAG NEW_TAG — compare per-layer sizes of two images
set -euo pipefail
OLD="$1"; NEW="$2"

echo "=== $OLD ==="
docker history --no-trunc --format '{{.Size}}\t{{.CreatedBy}}' "$OLD"
echo
echo "=== $NEW ==="
docker history --no-trunc --format '{{.Size}}\t{{.CreatedBy}}' "$NEW"

echo
echo "=== Total sizes ==="
for t in "$OLD" "$NEW"; do
  printf '%-40s %s\n' "$t" "$(docker image inspect "$t" --format '{{.Size}}' | numfmt --to=iec)"
done
```

```bash
# For an interactive, layer-by-layer file-level view (best tool for this):
dive myorg/api:1.5.0          # shows wasted space and per-layer file changes
# CI gate: fail if image efficiency drops below a threshold
CI=true dive --ci --lowestEfficiency=0.95 myorg/api:1.5.0
```

Read `docker history` bottom-up (oldest layer first): each row's `Size` is the bytes that layer *added*, and `CreatedBy` is the instruction. A common finding is a `RUN apt-get install ...` row at hundreds of MB where the matching cleanup landed in a *separate* `RUN` — so the packages were deleted in a later layer but the bytes still live in the install layer (deletes are whiteouts; they never shrink earlier layers). `dive` makes this visceral: it shows "wasted space" from files added then removed across layers, and its `--ci` mode lets you fail a build whose efficiency regresses, catching bloat before it ships. The fix is almost always collapsing install-and-cleanup into one `RUN` or moving the heavy work into a discarded build stage.

#### Q109. [Coding] Implement a graceful-shutdown HTTP server in Go that drains connections on SIGTERM, and explain how it cooperates with `docker stop`.

`docker stop` sends `SIGTERM` then waits a grace period before `SIGKILL`. A server that ignores `SIGTERM` drops every in-flight request at the kill. The fix is to trap the signal, stop accepting new connections, and let active requests finish within the grace window.

```go
package main

import (
	"context"
	"errors"
	"log"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"
)

func main() {
	mux := http.NewServeMux()
	mux.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		time.Sleep(2 * time.Second) // simulate in-flight work
		w.Write([]byte("ok"))
	})
	srv := &http.Server{Addr: ":8080", Handler: mux}

	go func() {
		if err := srv.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
			log.Fatalf("listen: %v", err)
		}
	}()
	log.Println("serving on :8080")

	// Block until SIGTERM (docker stop) or SIGINT (Ctrl-C)
	stop := make(chan os.Signal, 1)
	signal.Notify(stop, syscall.SIGTERM, syscall.SIGINT)
	<-stop
	log.Println("shutdown signal received, draining...")

	// Give in-flight requests up to 25s (must be < docker stop grace period)
	ctx, cancel := context.WithTimeout(context.Background(), 25*time.Second)
	defer cancel()
	if err := srv.Shutdown(ctx); err != nil {
		log.Printf("forced shutdown: %v", err)
	}
	log.Println("clean exit")
}
```

```bash
docker run -d --name svc -p 8080:8080 myorg/svc:1.0
docker stop --time 30 svc   # send SIGTERM, wait up to 30s before SIGKILL
```

`srv.Shutdown(ctx)` stops the listener immediately (no new connections) but lets active handlers run until they finish or `ctx` expires. The timing contract is the subtle part: the server's drain timeout (25s) must be **shorter** than `docker stop --time` (30s), or Docker SIGKILLs mid-drain and you lose the requests anyway. Because the binary is PID 1 (exec form), it receives `SIGTERM` directly — if you had used shell-form `CMD`, `/bin/sh` would eat the signal and this code would never run. In Kubernetes, pair this with `terminationGracePeriodSeconds` ≥ your drain timeout and a `preStop` sleep so the service is removed from endpoints before draining starts.

#### Q110. [Coding] Write a custom seccomp profile that starts from the default and allows one extra syscall, and run a container with it.

The default seccomp profile blocks ~44 syscalls, and the wrong reaction to a `Operation not permitted` error is `--security-opt seccomp=unconfined` (which disables *all* filtering). The right move is to start from the default profile and allow only the specific syscall your app legitimately needs.

```bash
# Identify the blocked syscall using strace inside an unconfined run (debug only)
docker run --rm --security-opt seccomp=unconfined --cap-add SYS_PTRACE \
  myorg/app strace -f -e trace=all ./app 2>&1 | grep EPERM
# e.g. you discover the app calls perf_event_open
```

```json
{
  "defaultAction": "SCMP_ACT_ERRNO",
  "archMap": [
    { "architecture": "SCMP_ARCH_X86_64",
      "subArchitectures": ["SCMP_ARCH_X86", "SCMP_ARCH_X32"] }
  ],
  "syscalls": [
    {
      "names": ["accept","accept4","access","bind","brk","clone","close",
                "connect","dup","dup2","epoll_create1","epoll_ctl","epoll_wait",
                "execve","exit","exit_group","fcntl","fstat","futex","getpid",
                "listen","mmap","mprotect","munmap","nanosleep","open","openat",
                "read","recvfrom","rt_sigaction","rt_sigprocmask","sendto",
                "setsockopt","socket","write"],
      "action": "SCMP_ACT_ALLOW"
    },
    {
      "names": ["perf_event_open"],
      "action": "SCMP_ACT_ALLOW",
      "comment": "needed by the profiler; explicitly allowed, nothing else opened"
    }
  ]
}
```

```bash
docker run --rm --security-opt seccomp=./profile.json myorg/app
```

The profile's `defaultAction: SCMP_ACT_ERRNO` denies everything not explicitly listed (an allowlist, the secure default), and you add exactly the one syscall you proved is needed. In practice you would download Docker's full `default.json` and add the single `perf_event_open` entry to it rather than hand-listing syscalls as shown, because a minimal handwritten list will break libc internals. The lesson for the interviewer: `seccomp=unconfined` trades a one-line fix for a massive expansion of kernel attack surface (it re-enables `keyctl`, `ptrace` of other processes, `mount`, etc.); a surgical allowlist keeps the other 43 dangerous syscalls blocked while unblocking your one legitimate call.

#### Q111. [Coding] Write a GitHub Actions workflow that builds multi-arch, scans with Trivy, signs with cosign, and pushes — fail closed on CRITICAL CVEs.

A production CI pipeline must do more than `docker push`: it builds for multiple architectures, gates on vulnerabilities, and produces a verifiable signature so deploy-time admission control can reject anything unsigned. Here is a complete, runnable workflow.

```yaml
# .github/workflows/build.yml
name: build-scan-sign
on:
  push: { branches: [main] }
permissions:
  contents: read
  packages: write
  id-token: write          # required for cosign keyless (OIDC) signing
jobs:
  release:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: docker/setup-qemu-action@v3      # cross-arch emulation
      - uses: docker/setup-buildx-action@v3
      - uses: docker/login-action@v3
        with:
          registry: ghcr.io
          username: ${{ github.actor }}
          password: ${{ secrets.GITHUB_TOKEN }}

      # Build a SINGLE-arch image first to scan it fast
      - name: Build (amd64) for scanning
        uses: docker/build-push-action@v6
        with:
          context: .
          load: true
          tags: scan-target:latest

      - name: Trivy scan — fail on CRITICAL with a fix
        uses: aquasecurity/trivy-action@0.24.0
        with:
          image-ref: scan-target:latest
          severity: CRITICAL
          ignore-unfixed: true
          exit-code: "1"        # ← fail the job (fail closed)

      # Only if scan passes: build + push the real multi-arch image
      - name: Build & push multi-arch
        id: push
        uses: docker/build-push-action@v6
        with:
          context: .
          platforms: linux/amd64,linux/arm64
          push: true
          tags: ghcr.io/${{ github.repository }}:${{ github.sha }}
          provenance: true
          sbom: true

      - uses: sigstore/cosign-installer@v3
      - name: Sign with cosign (keyless)
        run: cosign sign --yes ghcr.io/${{ github.repository }}@${{ steps.push.outputs.digest }}
```

The ordering encodes the policy: scan a fast single-arch build *first* and `exit-code: 1` makes a CRITICAL fixable CVE fail the job before anything is pushed (fail closed, not fail open). `ignore-unfixed: true` avoids blocking on CVEs with no available patch (otherwise you can never go green). Only on a clean scan does it build the real `linux/amd64,linux/arm64` manifest list and push, attaching SLSA `provenance` and an `sbom` attestation. Finally cosign **keyless** signing uses the workflow's OIDC identity (`id-token: write`) — no long-lived signing key to leak — and signs the exact pushed digest. A Kyverno/Gatekeeper policy in the cluster then verifies that signature at admission, closing the loop so unsigned or unscanned images can't run.

#### Q112. [Practical] A `docker build` is slow on every CI run despite "having a cache." Walk the systematic diagnosis and the fixes.

The complaint "we have caching but builds are still slow" almost always means the cache exists but isn't being *reused* — CI runners are usually ephemeral, so the local layer cache from the last build is gone. Diagnose by category before changing anything.

First, confirm whether the cache is even available. On a fresh CI runner there is no local build cache, so `docker build` starts cold every time unless you explicitly import a remote cache. The fix is `--cache-to`/`--cache-from` backed by the registry (`type=registry,mode=max`) or GitHub Actions cache (`type=gha`), so layers persist across runners. Second, check **cache-busting ordering**: a `COPY . .` placed above dependency installation invalidates the (expensive) dependency layer on *every* code change. Reorder so manifests are copied and dependencies installed before the source `COPY`. Third, look for **non-deterministic instructions** — `ADD https://...` with a moving URL, `RUN apt-get update` without pinned versions, or `ARG`s that change each build (like a timestamp) — each silently busts the cache and everything after it.

```bash
# Make the registry the cache backend (persists across ephemeral runners)
docker buildx build \
  --cache-from type=registry,ref=ghcr.io/acme/api:buildcache \
  --cache-to   type=registry,ref=ghcr.io/acme/api:buildcache,mode=max \
  -t ghcr.io/acme/api:$SHA --push .

# Add cache mounts for package managers so downloads survive across builds
# RUN --mount=type=cache,target=/root/.cache/pip pip install -r requirements.txt
```

The fourth, sneaky cause is a **fat build context**: if `.dockerignore` is missing or incomplete, BuildKit ships `node_modules`, `.git`, and build artifacts to the daemon on every run — seconds of transfer plus a context hash that changes constantly. Confirm with the "transferring context" size in build output. Fifth, for genuinely heavy dependency steps, add `--mount=type=cache` so the package manager's download cache persists independently of layers (`mode=max` in `--cache-to` also exports intermediate-stage layers, which matters for multi-stage cache reuse). After these, a typical CI build drops from minutes to seconds on warm cache; the remaining cold-build time is irreducible compile work, which you parallelize across stages (BuildKit runs independent stages concurrently).

#### Q113. [Practical] Design a logging and observability strategy for containers: stdout vs files, log drivers, rotation, and the cardinality trap.

The foundational rule is **the twelve-factor model: treat logs as an event stream to stdout/stderr, not files.** A container should never manage its own log files or rotation — it writes to stdout, and the platform (the log driver, then a collector) handles transport, storage, and retention. Writing to a file inside the container bloats the writable layer, is invisible to `docker logs`, and is lost when the container is removed.

```bash
# Per-container driver + rotation (the json-file default grows unbounded otherwise!)
docker run -d \
  --log-driver json-file \
  --log-opt max-size=10m --log-opt max-file=3 \   # cap at 30MB, rotate
  myorg/api:1.0

# Or ship straight to a central system
docker run --log-driver=fluentd --log-opt fluentd-address=logs.acme:24224 myorg/api
```

The biggest production footgun is the **default `json-file` driver with no rotation**: it grows until it fills `/var/lib/docker`, then *every* container on the host fails writes with "no space left on device." Always set `max-size`/`max-file` (per-container or in `/etc/docker/daemon.json` as a daemon-wide default). For aggregation, choose a driver by trade-off: `json-file` is local and works with `docker logs` but doesn't centralize; `journald` integrates with systemd and survives restarts; remote drivers (`fluentd`, `gelf`, `awslogs`) centralize but can *block the container* if the log backend is down unless you set `mode=non-blocking` (which then risks dropping logs under burst — a real availability-vs-completeness choice).

The subtler trap is **cardinality**: structured logs and metrics labeled with high-cardinality fields (user IDs, request IDs, full URLs as labels) explode storage and index cost and can take down your observability backend before they take down the app. Keep request IDs in the log *body* for correlation, but never as a metric/label dimension. The mature setup is structured JSON logs to stdout, a node-level collector (Fluent Bit/Vector) tailing the driver's files, with sampling on high-volume paths and trace IDs propagated for correlation across services — observability that scales sublinearly with traffic, not linearly.

#### Q114. [Practical] Explain how to run integration tests against real dependencies in CI using ephemeral containers (Testcontainers / Compose), and the gotchas.

Mocking a database in integration tests gives false confidence — the mock and the real engine diverge on SQL dialect, transactions, and constraints. The modern approach spins up the *real* dependency as an ephemeral container scoped to the test run, then tears it down. Two idioms: Testcontainers (programmatic, per-test lifecycle) and a dedicated CI Compose file.

```python
# Testcontainers (Python) — a real Postgres, started/stopped by the test
import pytest
from testcontainers.postgres import PostgresContainer

@pytest.fixture(scope="session")
def db_url():
    with PostgresContainer("postgres:17") as pg:
        yield pg.get_connection_url()   # container auto-removed after the suite

def test_user_persistence(db_url):
    repo = UserRepo(db_url)
    repo.save(User(name="ada"))
    assert repo.get_by_name("ada").name == "ada"
```

```yaml
# compose.ci.yaml — alternative: real deps + a test-runner service
services:
  db:
    image: postgres:17
    healthcheck: { test: ["CMD-SHELL", "pg_isready"], interval: 3s, retries: 5 }
  tests:
    build: { context: ., target: test }   # a test stage in the Dockerfile
    depends_on: { db: { condition: service_healthy } }
    command: ["pytest", "-q"]
```

```bash
docker compose -f compose.ci.yaml up --abort-on-container-exit --exit-code-from tests
```

The gotchas are mostly about determinism and resource hygiene. (1) **Wait for *readiness*, not start** — `depends_on` or a naive sleep races; gate on a healthcheck (Compose) or Testcontainers' built-in wait strategies, or the first query hits a not-yet-accepting Postgres. (2) **Use random host ports** — hardcoding `5432:5432` causes port clashes when tests run in parallel on a shared runner; Testcontainers maps to a random host port and tells you which, and Compose can omit the host port entirely so services talk over the internal network by service name. (3) **Docker-in-Docker access** — the CI runner needs a Docker socket or DinD service to launch containers; in Kubernetes-based CI this means a sidecar or rootless builder. (4) **Cleanup on failure** — `--abort-on-container-exit` plus `--exit-code-from tests` makes the suite's exit code the pipeline's result and stops everything; Testcontainers runs a "Ryuk" reaper container that removes leftovers even if the test process is killed. The payoff is tests that exercise the genuine engine, catching dialect and constraint bugs a mock would mask, while staying isolated and reproducible.

### 🔴 Expert — extended

#### Q115. [Coding] Write a Dockerfile using `COPY --link` and explain how it changes layer rebuild behavior versus a plain `COPY`.

BuildKit's `COPY --link` creates the copied content as an independent layer with its own identity rather than one layered on top of the previous filesystem state. The consequence is that the layer's cache validity no longer depends on the layers beneath it — changing an earlier instruction does not force the linked copy to rebuild, which is impossible with classic `COPY`.

```dockerfile
# syntax=docker/dockerfile:1.7
FROM debian:12-slim AS base
RUN apt-get update && apt-get install -y --no-install-recommends ca-certificates \
    && rm -rf /var/lib/apt/lists/*

FROM base AS final
# Plain COPY: this layer is invalidated if ANY earlier layer (the apt install) changes
# COPY app /usr/local/bin/app

# --link: the copied content is its own layer, cache-independent of base changes
COPY --link app /usr/local/bin/app
COPY --link --from=assets /static /var/www/static
ENTRYPOINT ["/usr/local/bin/app"]
```

The mechanism: with `--link`, BuildKit snapshots the source into a standalone layer using `merge`-style composition, so the destination layer's hash is a function of *only its own content and path*, not the chain of layers below it. This means if you bump the `debian:12-slim` base or change the `apt-get` line, a plain `COPY app` would rebuild (its filesystem base changed), but a `COPY --link app` is reused untouched — its bytes didn't change. This dramatically improves cache hit rates in pipelines that frequently update base images or shared lower stages. The trade-off and gotcha: `--link` copies into a *fresh* layer, so it does **not** see files created by earlier `RUN`s at the destination path the way plain `COPY` would merge into existing dirs — if your `COPY --link` target depends on ownership/permissions established by a prior layer, use `--chown`/`--chmod` on the `COPY` itself rather than relying on a preceding `RUN chown`. It also requires the BuildKit dockerfile frontend (the `# syntax=` line) and produces images best consumed by clients that understand the resulting layer structure.

#### Q116. [Coding] Build a near-empty `FROM scratch` image for a static binary, including CA certs and a non-root user, and explain what breaks and how to fix it.

`FROM scratch` is the absolute minimum — an empty filesystem with nothing: no shell, no libc, no `/etc/passwd`, no CA certificates, no `/tmp`. It is the right base for a fully static binary when you want zero attack surface, but you must hand-provide the few things the binary expects from a normal OS.

```dockerfile
# ---- build a fully static binary ----
FROM golang:1.23 AS build
WORKDIR /src
COPY . .
# CGO_ENABLED=0 -> no dynamic libc dependency, required for scratch
RUN CGO_ENABLED=0 GOOS=linux go build -ldflags="-s -w" -o /app ./cmd/app
# Create a passwd entry and grab CA certs to copy into scratch
RUN echo "appuser:x:10001:10001::/nonexistent:/sbin/nologin" > /passwd

# ---- assemble the scratch image ----
FROM scratch
# HTTPS needs the trust store; scratch has none
COPY --from=build /etc/ssl/certs/ca-certificates.crt /etc/ssl/certs/
# Provide a passwd entry so USER 10001 resolves and the app isn't "unknown uid"
COPY --from=build /passwd /etc/passwd
COPY --from=build /app /app
USER 10001:10001
ENTRYPOINT ["/app"]
```

What breaks on a naive `FROM scratch` + binary, and the fix for each: (1) **TLS handshakes fail** with "certificate signed by unknown authority" because there is no CA bundle — fix by copying `ca-certificates.crt`. (2) **`USER nonroot` by name fails** because there's no `/etc/passwd`/`/etc/group`; many libraries also call `getpwuid` and error on an unknown UID — fix by copying a minimal `passwd` (or use numeric `USER 10001:10001`, which always works). (3) **No `/tmp`** — apps that write temp files crash; provide it via a `--tmpfs /tmp` at runtime or a volume, since you can't `mkdir` without a shell. (4) **No timezone data** — `time.LoadLocation` fails; copy `/usr/share/zoneinfo` or compile with the `timetzdata` build tag. (5) **Debugging is impossible** — no shell to `exec` into; you attach an ephemeral debug container sharing namespaces (`docker run --pid=container:<id> nicolaka/netshoot`). The practical verdict: scratch gives the smallest, hardest-to-exploit image (often 5–15 MB, near-zero CVEs), but `distroless/static:nonroot` already bundles CA certs, `/etc/passwd`, tzdata, and a nonroot user — so most teams choose distroless to get scratch-like minimalism without re-deriving these fixes by hand.

#### Q117. [Coding] Write a script that detects and demonstrates the "container can write a file the host can't delete easily" UID-mismatch problem on a bind mount, and show the rootless/userns fix.

A classic operational pain: a container running as root writes files to a bind-mounted host directory; those files are owned by host UID 0, so an unprivileged developer on the host can't delete or edit them without `sudo`. This script reproduces it and shows the two correct fixes.

```bash
#!/usr/bin/env bash
set -euo pipefail
mkdir -p ./shared

# 1) Reproduce: container root writes a file into the bind mount
docker run --rm -v "$PWD/shared:/data" alpine \
  sh -c 'echo hi > /data/root-owned.txt'

ls -ln ./shared
# -> owner is UID 0 (root). As a normal user:  rm shared/root-owned.txt  => Permission denied

# 2) FIX A — run the container as the host user's UID/GID
docker run --rm -u "$(id -u):$(id -g)" -v "$PWD/shared:/data" alpine \
  sh -c 'echo hi > /data/user-owned.txt'
ls -ln ./shared   # -> owned by YOUR uid; you can delete it normally

# 3) FIX B — userns-remap: container root maps to a high, unprivileged host UID
#    /etc/docker/daemon.json:  { "userns-remap": "default" }
#    then container UID 0 becomes e.g. host UID 165536, NOT host root.
```

The root cause is that UIDs are just integers shared between host and container — there is no translation by default, so "root in the container" *is* host UID 0 on the filesystem, and files it creates are host-root-owned. **Fix A** (`-u $(id -u):$(id -g)`) makes the container process run as your host UID, so created files are yours; the catch is the image's internal paths may not be writable by an arbitrary UID, so this works best with `--read-only` apps or images built to tolerate a passed-in UID (use `--chown` and group-writable dirs). **Fix B**, user-namespace remapping (`userns-remap`), is the systemic fix: the daemon maps the container's UID range to a high host range from `/etc/subuid`, so container root = unprivileged host UID 165536, and a container breakout lands as a nobody, not host root. **Rootless Docker** goes further — the daemon itself runs as your user. The trade-off with userns/rootless is exactly the bind-mount ownership complexity this question highlights: volume files now appear owned by the *subordinate* UID, so you plan ownership deliberately (init containers that `chown`, or `:U` mount option in newer Docker to recursively chown a volume to the remapped owner).

#### Q118. [Coding] Implement a minimal "container" in a shell script using namespaces and chroot to demonstrate what `docker run` does under the hood.

To prove containers are "just Linux features," you can assemble a crude one from `unshare`, `chroot`, and a cgroup directory — no Docker involved. This is exactly the namespace/cgroup/pivot sequence `runc` performs, stripped to its essence.

```bash
#!/usr/bin/env bash
# mini-container.sh — run as root on Linux. Demonstrates the kernel primitives.
set -euo pipefail

ROOTFS=/tmp/minicontainer-rootfs
# 1) Build a rootfs: export a real image's filesystem to use as our root
mkdir -p "$ROOTFS"
docker export "$(docker create alpine:3.20)" | tar -C "$ROOTFS" -xf -

# 2) Create a cgroup v2 limit (cap memory at 64MB)
CG=/sys/fs/cgroup/minicontainer
mkdir -p "$CG"
echo "67108864" > "$CG/memory.max"
echo $$ > "$CG/cgroup.procs"     # move this shell (and its children) into the cgroup

# 3) unshare new namespaces, then chroot into the rootfs and run a shell.
#    --pid: new PID namespace (our shell becomes PID 1 inside)
#    --mount --uts --ipc --net: isolate those subsystems
#    --fork --mount-proc: fork so PID ns takes effect, mount a fresh /proc
unshare --pid --mount --uts --ipc --net --fork --mount-proc=$ROOTFS/proc \
  chroot "$ROOTFS" /bin/sh -c '
    hostname mini && echo "--- inside the container ---" &&
    echo "PID of this shell: $$ (should be 1)" &&
    ps aux &&        # only sees its own processes -> PID namespace works
    cat /etc/os-release | head -1
  '
echo "--- back on the host ---"
```

```bash
sudo ./mini-container.sh    # observe: PID 1 inside, isolated process table, alpine rootfs
```

Walking the mechanism maps one-to-one to `docker run`: the **rootfs** (here exported from an Alpine image) is the union of image layers Docker would mount via OverlayFS; `chroot` (Docker uses `pivot_root`, which is more secure because it fully detaches the old root) swaps the filesystem view; `unshare --pid` is the PID namespace that makes the shell PID 1 and hides host processes; `--net` gives an isolated network namespace (Docker then wires a veth pair into `docker0`); `--uts` lets us set a hostname without affecting the host; the **cgroup** `memory.max` is the resource limit Docker sets from `--memory`. What this toy omits is exactly what makes Docker production-grade: the OverlayFS layer assembly, veth/NAT networking, seccomp/AppArmor/capabilities hardening, the shim that keeps the container alive across daemon restarts, and image distribution. The interview point: there is no "container" object in the kernel — a container is a *process* with a curated set of namespaces, cgroups, and a swapped root, and Docker is the orchestration that sets all of that up consistently.

#### Q118 demonstrates the primitives; the next questions return to architecture and judgment.

#### Q119. [Theory] Two BuildKit builds of the same Dockerfile produce images with different digests. Enumerate every source of non-determinism and how to eliminate each.

Reproducibility means the same source produces the same image digest, which is the bedrock of verifiable supply chains — if rebuilding the audited source yields a different digest, you can't prove the deployed bytes match the audit. BuildKit leaks non-determinism from several places, and each has a specific mitigation.

The dominant source is **timestamps**: image and layer metadata record creation times, and files copied in carry mtimes, so two builds seconds apart differ. Eliminate by setting `SOURCE_DATE_EPOCH` (BuildKit rewrites layer and config timestamps to that fixed value) and using `--rewrite-timestamp` semantics. The second source is **floating inputs**: `FROM image:tag` resolves to whatever the tag points at *now*, `apt-get install pkg` grabs the current version, `RUN curl` fetches live content, and `pip install` without a lockfile resolves transitively each time. Mitigate by pinning `FROM` by digest, pinning package versions (or using a frozen snapshot mirror), and committing lockfiles. The third is **file ordering and metadata**: tar archive entry order, file permissions, and ownership can vary; BuildKit largely normalizes this, but `COPY` of a directory whose contents differ in mtime/uid will differ — normalize with explicit `--chmod`/`--chown` and avoid copying volatile metadata.

```dockerfile
# syntax=docker/dockerfile:1.7
FROM debian@sha256:fixed...           # pinned by digest, not a moving tag
RUN apt-get update && apt-get install -y --no-install-recommends nginx=1.26.* \
    && rm -rf /var/lib/apt/lists/*
COPY --chmod=644 ./site /var/www/html # explicit perms, no inherited metadata
```

```bash
# Set a fixed epoch (e.g., the commit time) so timestamps are deterministic
SOURCE_DATE_EPOCH=$(git log -1 --format=%ct) \
  docker buildx build --output type=image,rewrite-timestamp=true -t app:repro .
# Verify: build twice, compare digests — they should match.
```

Remaining subtle leaks: **build args that vary** (a `BUILD_ID`/timestamp ARG baked into a layer), **network-order randomness** in tools that write maps/sets unsorted, **locale/`$RANDOM`/PID-dependent** behavior in build scripts, and **parallel-stage races** if two stages write the same cache mount without `sharing=locked`. The honest expert answer is that *bit-for-bit* reproducibility is achievable but requires discipline across all of these, and even then some upstream toolchains embed nondeterminism (e.g., a compiler embedding a build path) that you fix by passing `-trimpath`-style flags. The pragmatic target most orgs adopt is **content reproducibility** — pinned inputs + `SOURCE_DATE_EPOCH` so rebuilds match — backed by SLSA provenance attestation so you can *prove* how a digest was produced even where perfect bit-reproducibility is impractical.

#### Q120. [Theory] Explain in depth how cgroup v2 CPU and memory controllers shape container behavior: `cpu.max`, `cpu.weight`, `memory.high` vs `memory.max`, PSI, and the throttling/OOM mechanics.

cgroup v2 replaced v1's per-controller hierarchies with a single unified hierarchy, which fixed the v1 footgun where CPU and memory limits lived in *separate* trees and could be assigned inconsistently. For containers this matters because the orchestrator now sets coherent limits on one cgroup, and the kernel enforces them with two distinct mechanisms — *throttling* for CPU (no work is lost, latency is added) and *killing* for memory (work is lost).

For **CPU**, `--cpus 1.5` translates to `cpu.max = "150000 100000"` (150ms of CPU per 100ms period). This is the **CFS bandwidth controller**: within each 100ms period the container runs until it consumes its quota, then is **throttled** (descheduled) until the next period. The pathology is bursty, multi-threaded apps: a service with 8 threads can burn a 150ms quota in ~19ms of wall-clock, then sit throttled for ~81ms — producing periodic latency spikes at *low average CPU* (the classic "p99 is terrible but the CPU graph looks idle" incident, Q93). `cpu.weight` (from `--cpu-shares`) is orthogonal: it sets *relative* scheduling share only under contention, not a hard cap, so it shapes fairness without throttling. The tuning lever is to raise the quota (or the CFS period) for latency-sensitive bursty workloads, or to right-size thread pools to the quota.

```bash
# Inspect a running container's actual cgroup v2 limits and throttling
CID=$(docker run -d --cpus 1.5 --memory 512m --memory-reservation 400m myorg/app)
CG=/sys/fs/cgroup/system.slice/docker-$CID.scope
cat $CG/cpu.max          # "150000 100000"  (quota period)
cat $CG/cpu.stat         # nr_throttled / throttled_usec  ← rising = CPU starvation
cat $CG/memory.max       # 536870912
cat $CG/memory.current   # live usage
cat $CG/memory.events    # 'high' and 'oom_kill' counters
cat /sys/fs/cgroup/system.slice/docker-$CID.scope/cpu.pressure  # PSI stall metric
```

For **memory**, `memory.max` (`--memory`) is the hard wall — exceeding it triggers the cgroup OOM killer, which kills a process *inside that cgroup* (usually the biggest), surfacing as exit 137 with `OOMKilled=true`. `memory.high` (`--memory-reservation` maps loosely here) is a *soft* throttle: crossing it doesn't kill, it puts the cgroup under aggressive reclaim pressure and throttles allocations, giving the app a chance to shed memory before hitting the hard cap — a far gentler degradation than a sudden kill. The decisive modern signal is **PSI (Pressure Stall Information)**: `cpu.pressure`, `memory.pressure`, and `io.pressure` report the *fraction of time tasks were stalled* waiting for that resource, which is a much better autoscaling and alerting signal than raw utilization because it directly measures contention-induced latency. The expert framing: CPU limits trade throughput for predictability via throttling (no data loss, added latency), memory limits trade nothing gracefully unless you use `memory.high` as a pressure-relief valve, and PSI is what lets you *see* both forms of starvation that average-utilization dashboards hide.

#### Q121. [Theory] Design the container platform's image lifecycle and registry GC for an org pushing 50k images/day, covering retention, garbage collection safety, and pull-through caching at scale.

At 50k pushes/day the registry becomes a capacity, cost, and reliability problem, not a convenience. The design has three pillars: a **retention policy** that decides what to keep, a **garbage-collection process** that reclaims blobs *safely* without breaking running workloads, and a **caching topology** that keeps pull latency and upstream rate limits sane across many nodes and regions.

**Retention and promotion.** Not all images are equal: CI builds (one per commit) are high-churn and mostly short-lived, while released/promoted images must be kept for audit and rollback. Tag images by *role* — `git-<sha>` for every build, and *immutable* semantic tags (`api:1.4.2`) created only on promotion (Q106). Apply tiered retention: keep the last N untagged CI builds per repo (e.g., 10) and expire by age (e.g., 14 days), but **never** auto-expire promoted/released tags or anything referenced by a deployment. Crucially, retention must be **digest-aware**: deleting a tag must not delete the underlying manifest if another tag or a running pod still references that digest — enforce by querying the orchestrator for in-use digests and adding them to a protect list before any sweep.

```
                         ┌──────── upstream (Docker Hub / vendor) ────────┐
                         │            pull-through cache (per region)      │
   build farm ──push──►  Central registry (Harbor/GHCR/ECR)               │
   (50k/day)             │  - immutability on released tags               │
                         │  - tiered retention + scheduled GC (read-only) │
                         └──────────────┬─────────────────────────────────┘
                                        │ replicate (geo)
                       ┌────────────────┼─────────────────┐
                   region A          region B           region C
                 pull-through      pull-through        pull-through
                 mirror+cache      mirror+cache        mirror+cache
                       │                │                  │
                    node pulls (warm, local, no upstream rate limit)
```

**Garbage collection safety** is the part that bites. Registry GC is a two-phase mark-and-sweep: it marks blobs referenced by manifests, then deletes unreferenced blobs. The race is that a `push` *in progress* during the sweep can reference a blob that mark didn't see, so the sweep deletes it and corrupts the new image. The safe procedure is to run GC with the registry in **read-only mode** (or use the registry's online-GC with its documented locking), schedule it in low-traffic windows, and *never* run a blob-delete sweep concurrently with pushes. Tag deletion (logical) is cheap and frequent; blob GC (physical reclamation) is the dangerous, infrequent operation that needs the read-only guard. Also account for **shared layers**: deleting one image must not remove a base layer another image still references — content-addressing makes this automatic *if* the mark phase is complete and consistent.

**Caching topology.** A single central registry can't serve a global node fleet without latency and becoming a single point of failure, and pulling from upstream public registries hits rate limits (Docker Hub's anonymous 429s). Deploy **pull-through caches/mirrors per region or per cluster** (Harbor proxy projects, a `registry:2` configured as a pull-through cache, or cloud registry replication): nodes pull from the nearby mirror, which fetches-and-caches from the central registry/upstream once. This cuts cross-region egress cost, survives upstream outages for already-cached layers, and removes the rate-limit failure mode. For the largest images (ML, data), layer on lazy pulling (eStargz/SOCI, Q63) so autoscaled nodes start before the full image lands. The throughline: at this scale the registry is critical infrastructure — design retention to bound growth, GC to never delete live content, and caching to decouple node pulls from a single chokepoint.

#### Q122. [Behavioral] Tell me about a time you led a containerization or migration effort that hit serious resistance or a major setback. How did you handle it? (STAR)

**Situation.** At a previous company, a 200-engineer org ran a sprawling monolith and a dozen services directly on long-lived VMs via Ansible. Deploys took 45 minutes, "works on my machine" failures were weekly, and onboarding a new engineer's environment took two days. I was the staff engineer asked to lead a move to containers and a managed-Kubernetes platform. The setback hit early: a pilot team containerized their service, and after rollout it began OOM-killing in production roughly hourly — something that had never happened on the VMs. Confidence cratered, two senior engineers publicly argued we should abandon the effort, and leadership asked me whether the migration was a mistake.

**Task.** I owned two things at once: fix the technical regression credibly, and rebuild organizational trust so the program survived. I deliberately treated those as separate problems, because a correct fix delivered defensively would still have lost the room.

**Action.** On the technical side I ran the systematic OOM playbook (Q24, Q120): exit code 137 with `OOMKilled=true` confirmed a cgroup memory-limit kill, and `docker stats` plus the JVM flags revealed the root cause — the JVM was an older build that ignored the cgroup limit and sized its heap to the *host's* memory, so under load it blew past the 512MB container limit. The VMs had 32GB, so the bug had been invisible there. The fix was small (set `-XX:MaxRAMPercentage` so the heap respected the limit, plus right-sized the limit from real usage data) but the *lesson* was large, so I didn't stop at the patch. On the organizational side, I did three things: I wrote a blameless post-mortem framing the incident as "containers correctly *enforced* a limit the VMs had silently let us violate" — reframing the regression as a latent bug containers *exposed*, not caused. I paired with the two skeptical seniors to build a reusable "container-ready JVM/Node base image" with the memory flags baked in, converting critics into co-authors. And I changed the rollout strategy from big-bang to a paved-road: an opt-in golden-path template (hardened base images, the run baseline from Q100, healthchecks, a CI pipeline like Q111) so teams got the hard-won fixes for free.

**Result.** The pilot service stabilized within two days and we resumed the migration with the paved road. Over the next two quarters ~40 services moved over; deploy time dropped from 45 minutes to under 5, environment onboarding went from two days to under an hour, and — the metric that won leadership back — production incidents attributable to environment drift fell to near zero because dev/CI/prod now shared one artifact. The two engineers who'd pushed to abandon it became the platform's strongest advocates, largely because I'd made them owners rather than overruling them. The durable lesson I carry: a containerization migration is as much an organizational change as a technical one, the early failures are usually *exposed* latent bugs rather than new ones, and converting your loudest skeptics into co-authors of the fix is faster and stickier than winning the argument.

#### Q123. [Practical] A multi-arch image runs fine on amd64 but crashes or behaves subtly wrong on arm64 nodes. Walk the diagnosis and the structural fixes.

Mixed-architecture fleets (Graviton/Ampere in the datacenter, Apple silicon on laptops) make "it works on my machine, crashes in prod" an *architecture* problem, not just an environment one. The first diagnostic is to confirm what actually ran: `docker image inspect --format '{{.Architecture}}'` on the node, and `docker buildx imagetools inspect <ref>` to verify the manifest list actually contains an `arm64` entry rather than the runtime falling back to emulating the amd64 image.

The most common root cause is an **emulated amd64 image running under QEMU on arm64** (or the reverse): if the manifest list is missing the arm64 variant, the container runtime may pull the amd64 image and run it via `binfmt_misc`/QEMU, which is slow and has subtle correctness gaps (some syscalls, certain atomic/SIMD instructions, and timing-sensitive code behave differently under emulation). The fix is to ensure the build actually produced a native arm64 layer set — `docker buildx build --platform linux/amd64,linux/arm64` with the build *tested* on arm64, not just produced. The second cause is **architecture-specific dependencies**: a Python wheel, npm native addon, or Go cgo dependency compiled for amd64 baked into the image, or a base image that only ships amd64 binaries. Diagnose with `file /path/to/binary` inside the arm64 container (it should report `aarch64`, not `x86-64`) and by checking that `pip`/`npm` resolved arch-appropriate artifacts at build time on the *target* platform.

```bash
# What arch is the manifest list actually offering?
docker buildx imagetools inspect ghcr.io/acme/api:1.5.0
#   linux/amd64  ... linux/arm64  ← both must be present

# On an arm64 node: is the running binary native or an x86 blob under QEMU?
docker run --rm --platform linux/arm64 ghcr.io/acme/api:1.5.0 \
  sh -c 'file /usr/local/bin/app; uname -m'   # expect aarch64

# Reproduce the arm64 build locally on amd64 (emulated) to catch build-time arch bugs
docker buildx build --platform linux/arm64 --load -t api:arm64-test .
```

The subtler, hardest-to-spot failures are **behavioral, not crash**: different floating-point rounding or `char` signedness between architectures, code that assumes x86 memory ordering and races on arm64's weaker model, or hardcoded paths/instruction-set assumptions. These pass CI on amd64 and corrupt data on arm64. The structural fixes are: (1) **build natively per arch** (native remote builders or arch-specific runners) rather than relying on QEMU emulation for anything beyond a smoke test, since emulation hides timing/ordering bugs; (2) **run the full test suite on every target architecture** in CI, not just amd64 — this is the single highest-value change, because it surfaces native-dependency and behavioral bugs before deploy; (3) **pin multi-arch base images** that genuinely ship all target arches and verify with `imagetools inspect`; and (4) for stateful or numerically sensitive workloads, add cross-arch consistency tests. The mature stance: treat each architecture as a first-class target with its own native build and test lane — a manifest list is necessary but not sufficient, because shipping an arm64 layer you never executed natively just defers the crash to production.

#### Q124. [Coding] Write a Dockerfile that builds, tests, and lints in separate stages, and show how to run just one stage with `--target`.

A single Dockerfile can encode the whole CI graph — build, test, lint — as distinct stages, so the same Dockerfile produces the slim runtime image *and* the test/lint environments, with no drift between "how CI builds" and "how prod is built." BuildKit runs independent stages concurrently and `--target` lets you stop at any stage.

```dockerfile
# syntax=docker/dockerfile:1.7
FROM golang:1.23 AS base
WORKDIR /src
COPY go.mod go.sum ./
RUN --mount=type=cache,target=/go/pkg/mod go mod download
COPY . .

# --- lint stage (independent: doesn't depend on build/test) ---
FROM base AS lint
RUN go vet ./... && \
    go run honnef.co/go/tools/cmd/staticcheck@latest ./...

# --- test stage ---
FROM base AS test
RUN --mount=type=cache,target=/root/.cache/go-build go test -race -cover ./...

# --- build stage ---
FROM base AS build
RUN CGO_ENABLED=0 go build -ldflags="-s -w" -o /out/app ./cmd/app

# --- final runtime stage (the default target = last stage) ---
FROM gcr.io/distroless/static:nonroot AS runtime
COPY --from=build /out/app /app
USER nonroot:nonroot
ENTRYPOINT ["/app"]
```

```bash
docker build --target lint  -t ci:lint  .    # run only the linter
docker build --target test  -t ci:test  .    # run only tests
docker build .                               # builds runtime (last stage) for deploy
# In CI, run lint and test in parallel jobs, then build the runtime once on green.
```

The win is a single source of truth: the test and lint environments inherit the exact `base` (same Go version, same modules) as the production build, so a passing test provably ran against the same dependencies that ship. `--target` short-circuits the graph — `--target lint` never executes the test or build stages — and because `lint` and `test` both branch off `base` independently, BuildKit runs them concurrently when you build a target that needs both. The runtime stage stays tiny (distroless, no toolchain) because the compiler and test deps live only in the build/test stages that are discarded. The one gotcha: a stage only runs if something depends on it or you target it, so a `lint` stage nothing references is silently skipped in a default build — wire it into CI explicitly with `--target`.

#### Q125. [Coding] Write a script that audits a running container's effective security posture (user, capabilities, read-only FS, privileged) and flags violations.

In a fleet, the gap between "we have a hardening policy" and "every container actually applies it" is where breaches live. This script inspects a running container against the baseline (non-root, no `--privileged`, dropped caps, read-only rootfs) and reports each deviation, suitable for a CI gate or a periodic compliance sweep.

```bash
#!/usr/bin/env bash
# audit-container.sh <container> — flag deviations from the hardening baseline
set -euo pipefail
C="$1"
fail=0
chk() { if [ "$2" = "$3" ]; then echo "  OK   $1"; else echo "  FAIL $1 (got '$2', want '$3')"; fail=1; fi; }

inspect() { docker inspect --format "$1" "$C"; }

echo "Auditing container: $C"
chk "not privileged"      "$(inspect '{{.HostConfig.Privileged}}')"        "false"
chk "read-only rootfs"    "$(inspect '{{.HostConfig.ReadonlyRootfs}}')"    "true"
chk "no host network"     "$(inspect '{{.HostConfig.NetworkMode}}')"       "default"
chk "no-new-privileges"   "$(inspect '{{index .HostConfig.SecurityOpt 0}}' 2>/dev/null || echo missing)" "no-new-privileges"

USER=$(inspect '{{.Config.User}}')
[ -n "$USER" ] && [ "$USER" != "root" ] && [ "$USER" != "0" ] \
  && echo "  OK   runs as non-root ($USER)" || { echo "  FAIL runs as root"; fail=1; }

# Capabilities: flag any added caps and confirm a broad drop
echo "  CapAdd:  $(inspect '{{.HostConfig.CapAdd}}')"
echo "  CapDrop: $(inspect '{{.HostConfig.CapDrop}}')"

# Docker-socket mount = effectively host root
if docker inspect --format '{{range .Mounts}}{{.Source}} {{end}}' "$C" | grep -q docker.sock; then
  echo "  FAIL docker.sock is mounted (= host root)"; fail=1
fi

exit $fail   # non-zero => CI gate fails
```

```bash
./audit-container.sh api    # exit 1 and prints FAIL lines on any violation
```

This codifies the baseline from Q100 into an automatable check. The high-signal flags are `Privileged=true` (disables nearly all isolation), a `docker.sock` bind mount (equivalent to host root — Q83), and `User` empty or `root`. Running it as a CI step against a freshly-launched container, or as a cron sweep across the fleet, catches drift that policy documents never will — e.g., a developer who added `--privileged` to "make it work" and never removed it. The natural next step is to move enforcement left into an admission controller (Kyverno/Gatekeeper) so non-compliant containers can't start at all, but a script like this is invaluable for auditing what's *already* running and for environments without an admission layer.

#### Q126. [Coding] Write a Compose-based blue-green deploy script for a single host behind a reverse proxy, achieving zero-downtime cutover.

On a single Docker host with no orchestrator, you can still get zero-downtime deploys with blue-green: run the new version alongside the old, health-check it, then atomically flip the reverse proxy to the new color and retire the old. This script implements the cutover.

```bash
#!/usr/bin/env bash
# deploy.sh <new-image> — blue-green on one host, fronted by an nginx/Caddy proxy
set -euo pipefail
NEW_IMAGE="$1"

# Determine current (live) and target (idle) colors
CURRENT=$(docker ps --filter "label=role=live" --format '{{.Label "color"}}' | head -1)
[ "$CURRENT" = "blue" ] && TARGET=green || TARGET=blue
echo "Current=$CURRENT  ->  Target=$TARGET"

# 1) Start the target color (not yet receiving traffic)
docker run -d --name "app-$TARGET" \
  --label "app=web" --label "color=$TARGET" \
  --network appnet "$NEW_IMAGE"

# 2) Wait until the new container is healthy before sending it any traffic
echo "Waiting for app-$TARGET to become healthy..."
for i in $(seq 1 30); do
  status=$(docker inspect --format '{{.State.Health.Status}}' "app-$TARGET")
  [ "$status" = "healthy" ] && break
  [ "$i" = "30" ] && { echo "NEW VERSION UNHEALTHY — rolling back"; docker rm -f "app-$TARGET"; exit 1; }
  sleep 2
done

# 3) Atomically flip the proxy upstream to the target, then reload (no dropped conns)
sed -i "s/app-$CURRENT/app-$TARGET/" /etc/nginx/conf.d/upstream.conf
nginx -s reload          # graceful: drains old workers, no connection drops

# 4) Mark new as live, retire old AFTER a drain window
docker update --label-add role=live "app-$TARGET" 2>/dev/null || true
sleep 10                 # let in-flight requests on the old color finish
docker rm -f "app-$CURRENT" 2>/dev/null || true
echo "Cutover to $TARGET complete."
```

The zero-downtime guarantee rests on ordering: the new color must pass its healthcheck *before* the proxy is flipped (step 2 gates step 3), and the old color is retired only *after* a drain window (step 4), so no request is ever routed to a not-ready or already-killed container. `nginx -s reload` is graceful — it starts new workers with the new config and lets old workers finish in-flight requests rather than cutting them, which is what makes the flip seamless. The rollback path is trivial and automatic: if the new color never goes healthy, the script removes it and exits non-zero with the old version still serving untouched — the cardinal blue-green virtue. The limits on one host (which an interviewer should hear you name): no protection against host failure, finite resources to run two copies at once, and the proxy itself is a single point of failure — for real HA you still want multiple hosts and an orchestrator (Q95), but this delivers genuine zero-downtime *application* updates on a single box.

#### Q127. [Practical] Your image passed CVE scanning at build time but a critical CVE was disclosed against a deployed digest a week later. Design the detect-and-remediate process.

A clean scan at build time is a point-in-time statement, not a durable guarantee — the CVE database changes daily, so an image that was clean on Monday can be critically vulnerable on Friday with *zero* changes to its bytes. The process must therefore continuously re-scan *already-deployed digests* against fresh feeds and have a fast, low-risk remediation path that doesn't require re-testing application code.

The detection half rests on the artifacts you produced at build time. Because each build emitted an **SBOM** (Q23) stored as an attestation keyed by digest, a nightly job can answer "which running digests contain the vulnerable package+version?" by querying SBOMs against the new CVE feed — *without* rebuilding or even pulling the images. Pair that with a continuous scanner (Trivy/Grype/Scout in "scan the registry" mode) pointed at the digests your orchestrator reports as currently deployed (not just `latest` tags). This is the piece teams forget: scanning the registry's newest tag tells you nothing about the 1.4.0 digest still running in three clusters.

```bash
# Nightly: enumerate digests actually running, re-scan each against today's CVE feed
kubectl get pods -A -o jsonpath='{range .items[*].status.containerStatuses[*]}{.imageID}{"\n"}{end}' \
  | sort -u > /tmp/live-digests.txt

while read -r digest; do
  trivy image --severity CRITICAL --ignore-unfixed --exit-code 1 "$digest" \
    || echo "VULNERABLE: $digest" >> /tmp/cve-hits.txt
done < /tmp/live-digests.txt

# Cross-check against stored SBOMs to find the exact affected component fast
cosign download sbom "$digest" | grep -i "log4j"   # e.g. Log4Shell triage
```

The remediation half is where digest-pinning and immutable artifacts pay off. If the fix is an OS-package or base-image bump (the common case), you **rebuild from the same pinned source with an updated base/package, producing a new digest**, run it through the standard pipeline (scan + sign + provenance), and **roll forward** — promote the new digest exactly as in normal deploys (Q106), because nothing about the application code changed, so the blast radius and test surface are minimal. The severity dictates speed: a wormable RCE like Log4Shell warrants an emergency out-of-band rebuild-and-redeploy within hours, gated by your blue-green/rolling mechanism for safe rollback; a lower-severity issue rides the next regular release. The organizational backbone is to feed the nightly findings into an admission-control policy and an SLA (e.g., "fixable CRITICAL on a running digest must be remediated within 48h"), and to maintain `cosign`/SLSA provenance so that after the emergency rebuild you can still *prove* the new running digest came from audited source. The throughline: build-time scanning prevents shipping *known* vulnerabilities; only continuous re-scanning of deployed digests plus stored SBOMs catches the ones disclosed *after* you shipped — and a build-once/promote-by-digest pipeline is what makes the roll-forward fix fast and safe.

#### Q128. [Theory] Explain the full lifecycle of a layer blob from `docker build` to a node's running filesystem: compression, content addressing, diff IDs vs digests, and how the daemon decides what to pull.

A single layer wears three different identities along its journey, and conflating them is the source of much confusion about caching, deduplication, and "why did it pull a layer I already have." Tracing one layer end-to-end clarifies the whole distribution model.

At **build** time, an instruction (say `COPY . .`) produces a set of filesystem changes captured as an uncompressed tar archive. The SHA-256 of that *uncompressed* tar is the **diffID** — this is what the image **config** lists in its `rootfs.diff_ids` array, and it's what the daemon uses to identify a layer's *content* locally (and to match against its layer cache). To store and transmit the layer, BuildKit compresses that tar (gzip, or increasingly **zstd** for faster decompression and better ratios), and the SHA-256 of the *compressed* blob is the **digest** — this is what the image **manifest** references and what the registry stores and serves. So one layer has a diffID (uncompressed content hash, used locally / in the config) and a digest (compressed blob hash, used in the manifest / on the wire); the image's overall **image ID** is in turn the digest of the config blob, and the **manifest digest** (`@sha256:...` you pin in `FROM`/deploys) is the hash of the manifest that ties config + layer digests together.

```
build: COPY . .  ──►  uncompressed tar ──SHA256──► diffID   (listed in config.rootfs.diff_ids)
                          │ gzip/zstd
                          ▼
                     compressed blob ──SHA256──► digest     (listed in manifest.layers[])
manifest (sha256:M)  ──references──►  config (sha256:C, = imageID)  ──references──►  diffIDs
                     ──references──►  layer blobs by digest
```

On **pull**, the node fetches the manifest by its digest, reads the list of layer digests, and asks itself **which it already has** — content addressing makes this a pure set-difference: any layer digest already present on disk (shared from another image) is skipped, so a 1GB image sharing a 900MB base with something already pulled transfers only ~100MB. For each missing digest the daemon downloads the compressed blob, **verifies** the bytes hash to the expected digest (tamper-evidence — a corrupted or substituted blob fails verification), decompresses it, computes the diffID to confirm it matches the config, and unpacks it as an OverlayFS lowerdir. The container's writable layer is then stacked on top (Q21). This is why **pinning by manifest digest** is the strong guarantee: it transitively fixes the config and every layer digest, so the node provably assembles the exact bytes you built — and why a re-pushed *tag* can change content while a *digest* cannot. It also explains a common surprise: changing layer *compression* (gzip→zstd) changes the digest (the compressed bytes differ) even when the diffID is identical, so the registry sees a "new" blob though the uncompressed content is the same.

## ✅ Key Takeaways

- **Containers share the host kernel** — lightweight and fast, but kernel-level isolation is weaker than VMs; use micro-VMs/gVisor for untrusted multi-tenant code.
- **Images are stacked, content-addressed, read-only layers** unioned via OverlayFS with a thin writable CoW layer per container; persist real data in **volumes**, never the writable layer.
- **Order Dockerfile instructions least-to-most volatile** and copy dependency manifests before source to maximize build-cache reuse.
- **Multi-stage builds + small/distroless bases** are the two biggest levers for size, security, and pull speed.
- **`ENTRYPOINT` = fixed command, `CMD` = default args**; always use **exec form** so your app is PID 1 and receives signals for graceful shutdown.
- **Run as non-root**, drop capabilities, use read-only rootfs, `no-new-privileges`, and never mount the Docker socket into untrusted containers (= host root).
- **Pin by digest, scan in CI, generate SBOMs, sign with cosign, attest provenance (SLSA)** — the image is a supply-chain artifact, not just a deploy blob.
- **BuildKit** unlocks cache mounts, secret mounts, multi-arch (`buildx`), and shared registry cache — learn it; it's the default builder.
- **Use exit codes and namespace-sharing debug containers** to troubleshoot; distroless images are debugged via ephemeral sidecars, not by fattening the image.

## ⚠️ Common Pitfalls

- Using **shell-form** `CMD`/`ENTRYPOINT`, so `/bin/sh` becomes PID 1 and `SIGTERM` is swallowed → containers hard-killed, requests dropped on deploy.
- Putting **`COPY . .` above** the dependency install → cache busted on every code change, slow builds.
- Cleaning up apt/yum caches in a **separate `RUN`** — the files still live in the earlier layer, so the image doesn't shrink.
- Baking **secrets into `ARG`/`ENV`** — they persist in `docker history`; use BuildKit `--mount=type=secret`.
- Expecting **`EXPOSE`** to publish a port (it's documentation only) or expecting **default-bridge** containers to resolve each other by name (no embedded DNS).
- Storing **database data on the container's writable layer** → slow CoW writes and data loss on `docker rm`.
- Running as **root** and/or with **`--privileged`** "to make it work," or disabling **seccomp** wholesale instead of allowing the specific syscall.
- Using **`latest`** tags → non-reproducible deploys and surprise upgrades; pin tags/digests.
- Ignoring **container memory limits** in JVM/Node heap sizing → OOM kills (exit 137); set `MaxRAMPercentage`/`--max-old-space-size`.
- Forgetting **`.dockerignore`** → bloated build context, leaked `.git`/`.env`, slower builds.

## 📚 Further Reading

- **Docker official docs** — Dockerfile reference, [Build best practices](https://docs.docker.com/build/building/best-practices/), and the BuildKit/`buildx` guides (the authoritative, version-current source).
- **The OCI specifications** — image-spec, runtime-spec, distribution-spec ([opencontainers.org](https://opencontainers.org)) for how images and runtimes are standardized.
- **GoogleContainerTools/distroless** and **Chainguard Images** — minimal, low-CVE base images and the rationale behind them.
- **"Container Security" by Liz Rice** (O'Reilly) — namespaces, cgroups, capabilities, seccomp, and breakout threat models explained from first principles.
- **Sigstore / cosign docs** and the **SLSA framework** ([slsa.dev](https://slsa.dev)) — image signing, provenance, and supply-chain integrity levels.
- **"Docker Deep Dive" by Nigel Poulton** — a practical, regularly updated tour of the engine, images, networking, and orchestration.
