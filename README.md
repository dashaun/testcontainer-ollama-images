# testcontainer-ollama-images

A Spring Shell CLI that builds pre-warmed [Ollama](https://ollama.com) Docker images with models baked in. You pick the models and the target image name, so one application produces every image you need — no repository per model. The images are published to the GitHub Container Registry so workshops and CI environments can pull a ready-to-use image instead of downloading models at runtime.

## Published images

```
ghcr.io/dashaun/testcontainer-ollama-phi4-mini:latest
ghcr.io/dashaun/testcontainer-ollama-llama3.2_3b:latest
ghcr.io/dashaun/testcontainer-ollama-smollm2-135m:latest
```

Each also carries a version tag matching the Ollama release it was built from, e.g.
`ghcr.io/dashaun/testcontainer-ollama-phi4-mini:0.32.9`. Pin to that tag when you need a
reproducible environment; `:latest` moves whenever a new Ollama release is published.

### SmolLM2 135M

| Detail | Value |
|---|---|
| Complete image name | `ghcr.io/dashaun/testcontainer-ollama-smollm2-135m` |
| Ollama version | The semantic version selected centrally by the publishing workflow; use that versioned image tag for reproducibility |
| Model | `smollm2:135m-instruct-q4_0` |
| Approximate model size | 92 MB |
| Supported architectures | One platform per published build; currently `linux/amd64` because the workflow runs on an amd64 runner |

```bash
docker pull ghcr.io/dashaun/testcontainer-ollama-smollm2-135m:<ollama-version>
```

The current builder commits a running container, so it cannot produce a multi-platform manifest in
one job. The requested `linux/arm64` variant is therefore not published yet. Publishing only the
workflow runner's native `linux/amd64` platform is explicit here rather than presenting a
single-platform image as multi-platform.

With Testcontainers 2.0.5:

```java
import org.testcontainers.ollama.OllamaContainer;
import org.testcontainers.utility.DockerImageName;

DockerImageName image = DockerImageName
    .parse("ghcr.io/dashaun/testcontainer-ollama-smollm2-135m:<ollama-version>")
    .asCompatibleSubstituteFor("ollama/ollama");

try (OllamaContainer ollama = new OllamaContainer(image)) {
    ollama.start();
    // Connect through ollama.getEndpoint().
}
```

## How it works

1. Starts an `ollama/ollama` container via [Testcontainers](https://testcontainers.com)
2. Pulls each model from `--models` into the running container
3. Commits the container as a new Docker image tagged with the Ollama version and `:latest`
4. Pushes both tags to GHCR

A GitHub Actions workflow runs daily, detects new `ollama/ollama` releases, and rebuilds every image in its matrix when a newer version is found.

## Prerequisites

- Java 17+
- Docker Desktop (Mac/Linux)
- Maven

## Running locally

```bash
mvn package -DskipTests
java -jar target/*.jar generate \
  --models phi4-mini,nomic-embed-text \
  --repository ghcr.io/dashaun/testcontainer-ollama-phi4-mini
```

### Options

| Option | Required | Description |
|---|---|---|
| `--models` | yes | Comma-separated models to pull, e.g. `llama3.2:3b,nomic-embed-text` |
| `--repository` | yes | Target image repository, e.g. `ghcr.io/dashaun/testcontainer-ollama-phi4-mini` |
| `--ollamaVersion` | no | `ollama/ollama` tag to build from; defaults to `latest` |

Two tags are always produced: `<repository>:<version>` and `<repository>:latest`.

When `--ollamaVersion` is omitted the build starts from `ollama/ollama:latest` and asks the running
container what version that actually is (`ollama --version`), tagging with the resolved number rather
than with `latest`. Note that Testcontainers reuses a locally cached `ollama/ollama:latest`, so this
can resolve to an older version than Docker Hub's newest — run `docker pull ollama/ollama:latest`
first, or pass `--ollamaVersion` explicitly, if that matters.

To push the resulting images, log in to GHCR first:

```bash
docker login ghcr.io -u <your-github-username>
# enter a GitHub personal access token with write:packages scope when prompted
docker push ghcr.io/dashaun/testcontainer-ollama-phi4-mini:0.32.9
docker push ghcr.io/dashaun/testcontainer-ollama-phi4-mini:latest
```

## Running tests

```bash
mvn test
```

The tests verify that Docker is reachable and that an Ollama container can start and commit an image successfully.
The publishing workflow also starts the newly built SmolLM2 image, checks `/api/tags`, and makes a
real non-streaming `/api/chat` request before pushing. Run that validation locally with:

```bash
mvn test -Dtest=SmolLm2ImageTest \
  -Dollama.image=ghcr.io/dashaun/testcontainer-ollama-smollm2-135m:<ollama-version>
```

## GitHub Actions

The workflow (`.github/workflows/update-image.yml`) runs at 06:00 UTC daily, once per matrix entry. For each image it:

1. Fetches the latest semver tag from `ollama/ollama` on Docker Hub
2. Compares it to the latest version published to that GHCR package
3. Skips the build if already up to date
4. Builds and pushes both the versioned and `:latest` tags if a new version is found

Adding an image means adding one matrix entry — a GHCR package name and its model list:

```yaml
- package: testcontainer-ollama-phi4-mini
  models: "phi4-mini,nomic-embed-text:latest"
```

`package` must equal the last segment of the target repository — it is used for the GHCR
up-to-date check, the `--repository` argument, and both `docker push` targets. A mismatch does
not fail the build; it just points the up-to-date check at a package nobody pushes to, so the
image rebuilds every day.

You can also trigger it manually from the Actions tab with an optional `ollama_version` input to force a specific version.

No secrets are required — authentication uses the built-in `GITHUB_TOKEN`.

### GHCR package permissions

`GITHUB_TOKEN` can only push to packages this repository is authorized for. Packages the workflow
creates itself are linked automatically and need no setup. A package that already exists under the
`dashaun` account from some *other* repository is not writable from here, and its push step fails
with:

```
denied: permission_denied: write_package
```

Fix it once, in that package's settings on github.com → **Manage Actions access** → add
`testcontainer-ollama-images` with the **Write** role. There is no REST API for this on
user-owned packages, so it cannot be scripted. `fail-fast: false` keeps such a failure from
cancelling the other images.

Newly created packages are **private** by default. Make them public from the same settings page if
they are meant to be pulled anonymously.

## Stack

| Component | Version |
|---|---|
| Spring Boot | 4.1.0 |
| Spring Shell | 4.0.3 |
| Testcontainers | 2.0.5 |
| Java | 17 (Liberica) |
