package dev.dashaun.testcontainer.ollama;

import org.springframework.shell.core.command.annotation.Command;
import org.springframework.shell.core.command.annotation.Option;
import org.springframework.stereotype.Component;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.dockerclient.DockerClientProviderStrategy;
import org.testcontainers.ollama.OllamaContainer;
import org.testcontainers.utility.TestcontainersConfiguration;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class ImageGeneratorCommand {

	// "ollama version is 0.32.9" — the number is what we tag with.
	private static final Pattern VERSION = Pattern.compile("(\\d+\\.\\d+\\.\\d+)");

	@Command(name = "generate", description = "Pull models into an Ollama container and commit it as a tagged image")
	public void generate(
			@Option(longName = "models", required = true,
					description = "Comma-separated models to pull, e.g. phi4-mini,nomic-embed-text") String models,
			@Option(longName = "repository", required = true,
					description = "Target image repository, e.g. ghcr.io/dashaun/testcontainer-ollama-phi4-mini") String repository,
			@Option(longName = "ollamaVersion", required = false,
					description = "ollama/ollama tag to build from; defaults to latest") String ollamaVersion)
			throws Exception {

		List<String> modelList = parseModels(models);
		if (modelList.isEmpty()) {
			throw new IllegalArgumentException("--models must name at least one model");
		}

		configureDockerHost();

		String baseImage = "ollama/ollama:"
				+ (ollamaVersion == null || ollamaVersion.isBlank() ? "latest" : ollamaVersion);
		String latestTag = repository + ":latest";
		String versionTag;

		System.out.println("Starting OllamaContainer: " + baseImage);
		try (OllamaContainer ollama = new OllamaContainer(baseImage)) {
			ollama.start();

			// Without an explicit version the base tag is "latest", which would collide
			// with the
			// latest tag we push. Ask the running engine what it actually is and tag with
			// that.
			if (ollamaVersion == null || ollamaVersion.isBlank()) {
				String resolved = resolveOllamaVersion(ollama);
				System.out.println("Resolved ollama version: " + resolved);
				versionTag = repository + ":" + resolved;
			}
			else {
				versionTag = repository + ":" + ollamaVersion;
			}

			for (String model : modelList) {
				System.out.println("Pulling model: " + model);
				var result = ollama.execInContainer("ollama", "pull", model);
				// execInContainer never throws on a non-zero exit, so an unknown model
				// would
				// otherwise be committed as a silently empty image.
				if (result.getExitCode() != 0) {
					throw new IllegalStateException("ollama pull " + model + " failed (exit " + result.getExitCode()
							+ "): " + lastLine(result.getStderr()));
				}
			}

			System.out.println("Committing image as: " + versionTag);
			ollama.commitToImage(versionTag);
		}

		System.out.println("Tagging as: " + latestTag);
		DockerClientFactory.instance().client().tagImageCmd(versionTag, repository, "latest").exec();

		System.out.println("Done. Images ready to push:");
		System.out.println("  " + versionTag);
		System.out.println("  " + latestTag);
	}

	static List<String> parseModels(String models) {
		return Arrays.stream(models.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
	}

	// `ollama pull` draws a progress spinner, so its stderr is mostly ANSI escapes; the
	// real
	// message ("Error: pull model manifest: file does not exist") is the last line.
	static String lastLine(String output) {
		String cleaned = output.replaceAll("\\[[0-9;?]*[a-zA-Z]", "").replace("\r", "\n");
		return Arrays.stream(cleaned.split("\n"))
			.map(String::trim)
			.filter(s -> !s.isEmpty())
			.reduce((first, second) -> second)
			.orElse("no output");
	}

	private String resolveOllamaVersion(OllamaContainer ollama) throws Exception {
		var result = ollama.execInContainer("ollama", "--version");
		String output = (result.getStdout() + result.getStderr()).trim();
		Matcher matcher = VERSION.matcher(output);
		if (result.getExitCode() != 0 || !matcher.find()) {
			throw new IllegalStateException("Could not resolve the ollama version from the container " + "(exit "
					+ result.getExitCode() + "): " + output + " — pass --ollamaVersion explicitly");
		}
		return matcher.group(1);
	}

	void configureDockerHost() {
		wakeDockerDesktop();
		cleanDockerHostProperties();
		resetDockerSingletons();
	}

	void wakeDockerDesktop() {
		// Docker Desktop Resource Saver pauses the VM; the proxy socket accepts
		// connections and responds
		// to /_ping immediately, but /info returns empty 400 until the VM resumes. Poll
		// /info for a
		// non-empty ServerVersion to confirm the engine itself is ready.
		Path sock = resolveDockerSocket();
		System.out.println("Waiting for Docker engine to be ready...");
		long deadline = System.currentTimeMillis() + 60_000;
		while (System.currentTimeMillis() < deadline) {
			try {
				var proc = new ProcessBuilder("curl", "--silent", "--unix-socket", sock.toString(),
						"http://localhost/v1.41/info")
					.redirectErrorStream(true)
					.start();
				String output = new String(proc.getInputStream().readAllBytes()).trim();
				proc.waitFor();
				if (output.contains("\"ServerVersion\":\"") && !output.contains("\"ServerVersion\":\"\"")) {
					System.out.println("Docker engine ready.");
					return;
				}
			}
			catch (Exception e) {
				System.err.println("docker info check failed: " + e.getMessage());
			}
			try {
				Thread.sleep(2_000);
			}
			catch (InterruptedException ie) {
				Thread.currentThread().interrupt();
				return;
			}
		}
		System.err.println("Warning: Docker engine did not become ready within 60 seconds");
	}

	private void cleanDockerHostProperties() {
		// Remove stale strategy overrides so Testcontainers auto-detects via DOCKER_HOST
		// env var.
		try {
			Path propsPath = Path.of(System.getProperty("user.home"), ".testcontainers.properties");
			Properties props = new Properties();
			if (Files.exists(propsPath)) {
				try (InputStream in = Files.newInputStream(propsPath)) {
					props.load(in);
				}
			}
			props.remove("docker.client.strategy");
			props.remove("DOCKER_HOST");
			props.remove("tc.host");
			try (OutputStream out = Files.newOutputStream(propsPath)) {
				props.store(out, "Modified by testcontainer-ollama-images");
			}
		}
		catch (Exception e) {
			System.err.println("Warning: could not clean docker host properties: " + e.getMessage());
		}
	}

	private void resetDockerSingletons() {
		// Docker Desktop 4.x requires API >= 1.40; Testcontainers' shaded docker-java
		// defaults
		// to 1.32 and gets an empty 400 from Docker Desktop's proxy for any version below
		// 1.40.
		// The shaded DefaultDockerClientConfig reads the API version from the system
		// property "api.version".
		System.setProperty("api.version", "1.41");
		try {
			var tcField = TestcontainersConfiguration.class.getDeclaredField("instance");
			tcField.setAccessible(true);
			((AtomicReference<?>) tcField.get(null)).set(null);

			var dcfField = DockerClientFactory.class.getDeclaredField("instance");
			dcfField.setAccessible(true);
			dcfField.set(null, null);

			var failFast = DockerClientProviderStrategy.class.getDeclaredField("FAIL_FAST_ALWAYS");
			failFast.setAccessible(true);
			((AtomicBoolean) failFast.get(null)).set(false);
		}
		catch (Exception e) {
			System.err.println("Warning: could not reset Docker singletons: " + e.getMessage());
		}
	}

	private Path resolveDockerSocket() {
		String dockerHost = System.getenv("DOCKER_HOST");
		if (dockerHost != null && dockerHost.startsWith("unix://")) {
			return Path.of(dockerHost.substring("unix://".length()));
		}
		Path macSocket = Path.of(System.getProperty("user.home"), ".docker", "run", "docker.sock");
		return Files.exists(macSocket) ? macSocket : Path.of("/var/run/docker.sock");
	}

}
