# Contributing to testcontainer-ollama-images

Thank you for contributing.

## Before You Start

- Search existing issues and pull requests before opening a new one.
- Use an issue to discuss significant features or workflow changes before investing in an implementation.
- Do not use public issues for security vulnerabilities. Follow [SECURITY.md](SECURITY.md) instead.

## Development

This project requires Java 17 or newer, Maven, and a running Docker daemon. Use `mvn`, not the
Maven Wrapper: the wrapper is configured for a private repository that is not generally reachable.

```bash
mvn clean compile
mvn clean test
```

The tests are not hermetic. They use the real Docker daemon, pull Ollama images, and commit a local
image, so expect the complete suite to take several minutes and consume disk space.

Before submitting a pull request, apply the project formatter and run the complete test suite:

```bash
mvn spring-javaformat:apply
mvn clean test
```

Keep changes focused, add tests for changed behavior, and update documentation when a change affects
users. Do not push generated container images from tests or development unless explicitly intended.

## Commits and Pull Requests

- Sign every commit with a verified signature.
- Use concise, imperative commit subjects.
- Keep history linear; rebase instead of adding merge commits.
- Submit changes through a pull request. Direct changes to `main` are not accepted.
- Ensure all required status checks pass.
