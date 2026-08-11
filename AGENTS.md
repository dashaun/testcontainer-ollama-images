# AGENTS.md

This file provides guidance to coding agents when working with code in this repository.

## What this is

A single-command Spring Shell CLI whose only job is to bake Docker images: start an `ollama/ollama`
container via Testcontainers, `ollama pull` the requested models into it, then commit the running
container as `<repository>:<version>` and `<repository>:latest`. Workshops and CI pull those
pre-warmed images instead of downloading models at runtime.

Models and target repository are **command-line options**, not constants — one deployment of this app
builds every image (phi4-mini, llama3.2, …). Do not reintroduce hardcoded `MODEL`/`REPOSITORY`
fields; adding an image should mean adding a matrix entry to the workflow, not a new repo.

The CLI never pushes. It only builds and tags locally; `docker push` happens in
`.github/workflows/update-image.yml` (or manually, see README).

## Commands

```bash
mvn package -DskipTests                              # build the fat jar
java -jar target/*.jar generate \
  --models phi4-mini,nomic-embed-text \
  --repository ghcr.io/dashaun/testcontainer-ollama-phi4-mini   # --ollamaVersion optional
mvn test                                             # full test run
mvn test -Dtest=ImageGeneratorCommandTest#dockerIsReachableAfterConfigure   # single test
```

Use `mvn`, not `./mvnw` — the wrapper's `distributionUrl` points at a private Nexus
(`http://juice:8081`) that is unreachable off that network.

Tests are **not** hermetic: they talk to the real Docker daemon and `ollamaContainerStartsAndCommits`
pulls `ollama/ollama:latest` and commits an image. Expect minutes and disk churn, and expect failure
without Docker running.

## Architecture notes

**`ImageGeneratorCommand.configureDockerHost()` is the load-bearing part.** It is not boilerplate —
each of its three steps works around a specific Docker Desktop / Testcontainers interaction, and the
inline comments explain the why. Do not "clean up" the reflection:

- `wakeDockerDesktop()` — Docker Desktop's Resource Saver answers `/_ping` while the VM is still
  paused, so readiness is polled by shelling out to `curl` against `/v1.41/info` and requiring a
  non-empty `ServerVersion`.
- `cleanDockerHostProperties()` — strips `docker.client.strategy`, `DOCKER_HOST`, and `tc.host` from
  `~/.testcontainers.properties` so Testcontainers re-auto-detects. This **rewrites the user's home
  config file** as a side effect.
- `resetDockerSingletons()` — sets `api.version=1.41` (shaded docker-java defaults to 1.32, which
  Docker Desktop 4.x rejects with an empty 400) and reflectively nulls the `TestcontainersConfiguration`
  and `DockerClientFactory` singletons plus `FAIL_FAST_ALWAYS` so the new settings take effect. Order
  matters: the system property must be set before any client is created.

**Logging is off by design, and that hides shell failures.** `application.properties` sets
`logging.level.root=off`, so a logger added to the command prints nothing — user-facing progress goes
through `System.out.println`. Keep that convention, but know the trap: Spring Shell's
`NonInteractiveShellRunner.executeCommand()` catches parse errors, calls `log.error`, and returns, so
a misconfigured command produces **no output and exit code 0**. When the CLI does nothing, re-run with
`-Dlogging.level.root=INFO` (and `-Ddebug=true` for the condition-evaluation report) before assuming
the code is at fault.

**Spring Shell 4.x wiring is load-bearing and easy to break.** The 4.0 upgrade removed the legacy
`org.springframework.shell.standard` package (`@ShellComponent` / `@ShellMethod` / `@ShellOption`);
commands now use `org.springframework.shell.core.command.annotation.{Command, Option}`. Three
non-obvious rules, each of which fails silently rather than loudly:

- **Do not add `@EnableCommand`.** `SpringShellAutoConfiguration` is `@ConditionalOnMissingBean` on
  beans annotated with it, so a single `@EnableCommand` disables all shell auto-configuration — no
  `ShellRunner` is created and the app starts and exits 0 without running anything. Commands are
  picked up from ordinary `@Component` beans with `@Command` methods.
- **`spring.shell.interactive.enabled` must stay `false`.** With `true` (the default), the
  interactive runner wins and logs "Running in interactive mode, arguments will be ignored" — the
  `generate --ollamaVersion X` invocation used by the README and the workflow becomes a no-op.
- **Do not add `spring-shell-jline`.** Both `systemShellRunner` and `nonInteractiveShellRunner` are
  `@ConditionalOnMissingClass("org.springframework.shell.jline.DefaultJLineShellConfiguration")`, so
  pulling in JLine backs them off in favor of an interactive runner that does nothing without a TTY.
  This project wants the minimal, JLine-free starter.

`@Command.value` is an `@AliasFor` **`description`**, not the command name — use
`@Command(name = "generate", description = "...")`. Setting both `value` and `description` throws
`AnnotationConfigurationException` at startup.

**Image identity now lives in the workflow matrix.** Each `matrix.include` entry carries a `package`
(the GHCR package name) and a `models` string; `package` is used three ways in one job — the GHCR
up-to-date check URL, `--repository ghcr.io/dashaun/<package>`, and both `docker push` targets — so
it must equal the last segment of the repository. A mismatch doesn't fail the build; it silently
makes the up-to-date check consult a package nobody pushes to, and the image rebuilds every day.

**Two silent-failure guards exist on purpose.** `execInContainer` returns a non-zero exit code rather
than throwing, so `generate` checks it explicitly after each `ollama pull` — without that, a typo'd
model name commits an image that is simply missing the model. And when `--ollamaVersion` is omitted,
the version tag comes from `ollama --version` inside the running container, not from the string
`latest`, which would otherwise collide with the `:latest` tag. Testcontainers reuses a cached
`ollama/ollama:latest`, so the resolved version can legitimately be older than Docker Hub's newest.

**The workflow is the real driver.** It runs daily at 06:00 UTC, once per matrix entry, diffing the
newest semver tag on Docker Hub's `ollama/ollama` against the newest semver tag already in that GHCR
package and skipping the build when they match. `workflow_dispatch` with an `ollama_version` input
forces a specific build across all entries. `fail-fast: false` keeps one broken image from cancelling
the others. Auth is the built-in `GITHUB_TOKEN` — no secrets configured.

**`denied: permission_denied: write_package` is not a workflow bug.** `GITHUB_TOKEN` can only push to
GHCR packages this repo is authorized for. Packages the workflow creates are linked automatically; a
package that predates this repo — created by an older, single-model repo — is not, so its push fails
on every run while the build itself succeeds. The fix is granting that package Actions access with
the Write role in the github.com UI; there is no REST API for it on user-owned packages. Do not
"fix" this by editing the workflow. If a matrix entry cannot be granted access, drop the entry
rather than working around the denial.

## Dependency management

`pom.xml` carries a long hand-pinned `<dependencyManagement>` block (Jackson, Logback, JLine,
byte-buddy, …) that exists to override the Spring Boot parent's managed versions, driven by
`.advisor/patch-upgrade-report.json`. Those entries are deliberate overrides, not accidental
duplication — bumping the Boot parent does not make them removable without re-checking the report.

The four `org.jline:jline-*` pins are now inert: Spring Shell 4.x pulls no JLine at all (see the
shell wiring notes above), so nothing on the classpath is affected by them.

The README's Stack table lists versions and drifts from `pom.xml`; treat the pom as authoritative and
update the table when versions change.
