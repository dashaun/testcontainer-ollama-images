package dev.dashaun.testcontainer.ollama;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.testcontainers.ollama.OllamaContainer;
import org.testcontainers.utility.DockerImageName;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class SmolLm2ImageTest {

	private static final String MODEL = "smollm2:135m-instruct-q4_0";

	private static final ObjectMapper JSON = new ObjectMapper();

	@Test
	@EnabledIfSystemProperty(named = "ollama.image", matches = ".+")
	void preWarmedImageListsModelAndAnswersChatRequest() throws Exception {
		String imageName = System.getProperty("ollama.image");
		DockerImageName image = DockerImageName.parse(imageName).asCompatibleSubstituteFor("ollama/ollama");

		new ImageGeneratorCommand().configureDockerHost();
		try (OllamaContainer ollama = new OllamaContainer(image)) {
			// OllamaContainer.start() waits for the server's readiness endpoint.
			ollama.start();

			HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

			JsonNode tags = getJson(client, ollama.getEndpoint() + "/api/tags");
			assertThat(tags.path("models"))
				.anySatisfy(model -> assertThat(model.path("name").asText()).isEqualTo(MODEL));

			String requestBody = JSON.createObjectNode()
				.put("model", MODEL)
				.put("stream", false)
				.set("messages",
						JSON.createArrayNode()
							.add(JSON.createObjectNode().put("role", "user").put("content", "Reply with one word.")))
				.toString();
			HttpRequest request = HttpRequest.newBuilder(URI.create(ollama.getEndpoint() + "/api/chat"))
				.timeout(Duration.ofMinutes(2))
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(requestBody))
				.build();
			HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

			assertThat(response.statusCode()).isEqualTo(200);
			assertThat(JSON.readTree(response.body()).path("message").path("content").asText()).isNotBlank();
		}
	}

	private JsonNode getJson(HttpClient client, String url) throws Exception {
		HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30)).GET().build();
		HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
		assertThat(response.statusCode()).isEqualTo(200);
		return JSON.readTree(response.body());
	}

}
