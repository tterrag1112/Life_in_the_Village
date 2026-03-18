package tterrag1112.life_in_the_village.Lore;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import okhttp3.*;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

public class ClaudeApiClient {

    private static final String API_URL =
            "https://api.anthropic.com/v1/messages";
    private static final String MODEL =
            "claude-sonnet-4-20250514";
    private static final MediaType JSON =
            MediaType.get("application/json");

    private final OkHttpClient client =
            new OkHttpClient();
    private final Gson gson = new Gson();
    private final String apiKey;

    public ClaudeApiClient(String apiKey) {
        this.apiKey = apiKey;
    }

    /**
     * Sends a prompt async — callback fires on completion.
     * Never blocks the game thread.
     */
    public CompletableFuture<String> generateAsync(
            String prompt) {
        return CompletableFuture.supplyAsync(() -> {
            JsonObject body = new JsonObject();
            body.addProperty("model", MODEL);
            body.addProperty("max_tokens", 1024);

            JsonArray messages = new JsonArray();
            JsonObject message = new JsonObject();
            message.addProperty("role", "user");
            message.addProperty("content", prompt);
            messages.add(message);
            body.add("messages", messages);

            Request request = new Request.Builder()
                    .url(API_URL)
                    .addHeader("x-api-key", apiKey)
                    .addHeader("anthropic-version",
                            "2023-06-01")
                    .addHeader("content-type",
                            "application/json")
                    .post(RequestBody.create(
                            gson.toJson(body), JSON))
                    .build();

            try (Response response = client
                    .newCall(request).execute()) {
                if (!response.isSuccessful()
                        || response.body() == null) {
                    return "The chronicle could not "
                            + "be written at this time.";
                }

                JsonObject responseJson = gson.fromJson(
                        response.body().string(),
                        JsonObject.class);

                return responseJson
                        .getAsJsonArray("content")
                        .get(0)
                        .getAsJsonObject()
                        .get("text")
                        .getAsString();

            } catch (IOException e) {
                return "The chronicle could not "
                        + "be written at this time.";
            }
        });
    }
}