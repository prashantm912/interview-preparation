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
