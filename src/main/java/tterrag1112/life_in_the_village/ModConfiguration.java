package tterrag1112.life_in_the_village.Api;

import java.io.*;
import java.nio.file.*;
import java.util.Properties;

public class ModConfiguration {

    private static final Path CONFIG_PATH =
            Path.of("config", "life_in_the_village.properties");

    private static String apiKey = null;

    public static void load() {
        if (!Files.exists(CONFIG_PATH)) {
            // Create template config
            try {
                Files.createDirectories(
                        CONFIG_PATH.getParent());
                Properties props = new Properties();
                props.setProperty("anthropic_api_key", "");
                props.setProperty(
                        "# Get your key at",
                        "https://console.anthropic.com");
                try (OutputStream out = Files
                        .newOutputStream(CONFIG_PATH)) {
                    props.store(out,
                            "Life in the Village config");
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
            return;
        }

        try (InputStream in = Files
                .newInputStream(CONFIG_PATH)) {
            Properties props = new Properties();
            props.load(in);
            apiKey = props.getProperty(
                    "anthropic_api_key", "").trim();
            if (apiKey.isEmpty()) apiKey = null;
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static String getApiKey() {
        return apiKey;
    }
}