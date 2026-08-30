package dev.dashaun.testcontainer.ollama;

import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.ollama.OllamaContainer;

import static org.assertj.core.api.Assertions.assertThat;

class ImageGeneratorCommandTest {

	private final ImageGeneratorCommand command = new ImageGeneratorCommand();

	@Test
	void parsesAndTrimsModelList() {
		assertThat(ImageGeneratorCommand.parseModels("phi4-mini, nomic-embed-text")).containsExactly("phi4-mini",
				"nomic-embed-text");
		assertThat(ImageGeneratorCommand.parseModels("llama3.2:3b")).containsExactly("llama3.2:3b");
		assertThat(ImageGeneratorCommand.parseModels(" , ")).isEmpty();
	}

	@Test
	void stripsSpinnerNoiseFromPullErrors() {
		String stderr = "[?25l[1Gpulling manifest [K[?25h\n" + "Error: pull model manifest: file does not exist";
		assertThat(ImageGeneratorCommand.lastLine(stderr)).isEqualTo("Error: pull model manifest: file does not exist");
	}

	@Test
	void dockerIsReachableAfterConfigure() throws Exception {
		command.configureDockerHost();

		var info = DockerClientFactory.instance().client().infoCmd().exec();
		assertThat(info.getServerVersion()).as("Docker daemon must be reachable and return a non-empty server version")
			.isNotBlank();
	}

	@Test
	void ollamaContainerStartsAndCommits() throws Exception {
		command.configureDockerHost();

		String testTag = "test/ollama-smoke-" + System.currentTimeMillis() + ":latest";
		try (OllamaContainer ollama = new OllamaContainer("ollama/ollama:latest")) {
			ollama.start();
			ollama.commitToImage(testTag);
		}

		try {
			var image = DockerClientFactory.instance().client().inspectImageCmd(testTag).exec();
			assertThat(image.getId()).isNotBlank();
		}
		finally {
			DockerClientFactory.instance().client().removeImageCmd(testTag).withForce(true).exec();
		}
	}

}
