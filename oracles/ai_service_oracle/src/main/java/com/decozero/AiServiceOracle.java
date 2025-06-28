package com.decozero;

import com.google.cloud.aiplatform.v1.PredictionServiceClient;
import com.google.cloud.aiplatform.v1.PredictionServiceSettings;
import com.google.cloud.aiplatform.v1.EndpointName;
import com.google.protobuf.Value;
import com.google.protobuf.ListValue;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;

import com.decozero.secrets.SecretManager;
import org.kohsuke.github.GHApp;
import org.kohsuke.github.GHAppInstallation;
import org.kohsuke.github.GHAppInstallationToken;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GitHub;
import org.kohsuke.github.GitHubBuilder;

import spark.Spark; // Using Spark Java for the web server
import spark.Request;
import spark.Response;

import java.io.IOException;
import java.util.Collections;
import java.util.logging.Logger;
import java.util.Base64; // For Base64 encoding commit content
import java.util.Map;
import java.util.HashMap;

public class AiServiceOracle {

    private static final Logger logger = Logger.getLogger(AiServiceOracle.class.getName());
    private static final Gson gson = new Gson();

    // --- Environment Variables (configured on Cloud Run deployment) ---
    // Google Cloud Project ID
    private static final String GCP_PROJECT_ID = System.getenv("GCP_PROJECT_ID");
    // Google Cloud Region for AI Platform endpoint
    private static final String GCP_LOCATION = System.getenv("GCP_LOCATION");
    // Gemini Model ID (e.g., "gemini-1.5-flash-001" or "gemini-1.0-pro")
    private static final String GEMINI_MODEL_ID = System.getenv("GEMINI_MODEL_ID");
    // GitHub App ID for DeCo Zero
    private static final String GITHUB_APP_ID = System.getenv("GITHUB_APP_ID");
    // GitHub App Private Key (PEM format, base64 encoded for env var) - CRITICAL SECURITY NOTE
    // In production, this should be fetched from Google Cloud Secret Manager or KMS, NOT directly as an env var.
    private static final String GITHUB_APP_PRIVATE_KEY = SecretManager.getSecret("GITHUB_APP_PRIVATE_KEY");

    // NEAR API (Conceptual - Not actual NEAR Java SDK)
    // No specific NEAR env vars here as this oracle acts on HTTP trigger,
    // and NEAR interaction (listening to event) would be done by another component.
    // This oracle only *receives* event data from that component.

    private PredictionServiceClient predictionServiceClient;

    public AiServiceOracle() {
        // Initialize Gemini client
        try {
            PredictionServiceSettings predictionServiceSettings =
                PredictionServiceSettings.newBuilder()
                    .setEndpoint(GCP_LOCATION + "-aiplatform.googleapis.com:443")
                    .build();
            predictionServiceClient = PredictionServiceClient.create(predictionServiceSettings);
            logger.info("PredictionServiceClient (Gemini) initialized.");
        } catch (IOException e) {
            logger.severe("Failed to initialize PredictionServiceClient (Gemini): " + e.getMessage());
            // This is a critical error, the application should not proceed
            System.exit(1);
        }
    }

    public static void main(String[] args) {
        // Initialize the AiServiceOracle
        AiServiceOracle oracle = new AiServiceOracle();

        // Get the port from environment variable, default to 8080
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
        Spark.port(port);

        logger.info("AI Service Oracle starting on port " + port);

        // --- HTTP Endpoint to Receive Service Requests (from NEAR event listener) ---
        // This endpoint will be called by an external service that listens for
        // `ServiceRequested` events on the DeCo Zero Service Contract on NEAR.
        Spark.post("/process-deco-request", (request, response) -> oracle.handleDeCoServiceRequest(request, response));

        // Basic health check endpoint
        Spark.get("/health", (req, res) -> {
            res.status(200);
            return "OK";
        });
    }

    /**
     * Handles incoming HTTP POST requests triggered by NEAR service requests.
     * Expected JSON Payload:
     * {
     * "requester_id": "juan-deco.testnet",
     * "github_repo_url": "https://github.com/juan/mi-nueva-deco",
     * "selected_areas": ["legal", "marketing", "board"],
     * "timestamp": 1678886400000000000,
     * "transaction_id": "some_near_tx_hash_from_request_document_generation",
     * "fee_paid": "..."
     * }
     * This payload would typically come from a NEAR Indexer/Listener service.
     */
    private String handleDeCoServiceRequest(Request req, Response res) {
        logger.info("Received /process-deco-request POST request.");
        res.type("application/json");

        JsonObject payload;
        try {
            payload = gson.fromJson(req.body(), JsonObject.class);
        } catch (JsonSyntaxException e) {
            logger.severe("Invalid JSON payload: " + e.getMessage());
            res.status(400);
            return gson.toJson(Collections.singletonMap("status", "error: Invalid JSON payload"));
        }

        // --- Extract Data from Payload ---
        String requesterId = payload.get("requester_id").getAsString();
        String githubRepoUrl = payload.get("github_repo_url").getAsString();
        // Gson's getAsJsonArray().asList() is for simple types. For complex, iterate JsonElement.
        // For simplicity, assuming simple strings for now.
        String[] selectedAreas = gson.fromJson(payload.get("selected_areas"), String[].class);
        String transactionId = payload.get("transaction_id").getAsString();

        logger.info(String.format("Processing request for %s, Repo: %s, Areas: %s, Tx ID: %s",
                requesterId, githubRepoUrl, String.join(", ", selectedAreas), transactionId));

        try {
            // --- 1. Authenticate with GitHub ---
            String installationToken = getGitHubAppInstallationToken(githubRepoUrl, GITHUB_APP_ID, GITHUB_APP_PRIVATE_KEY);
            if (installationToken == null) {
                logger.severe("Failed to get GitHub App installation token.");
                res.status(500);
                return gson.toJson(Collections.singletonMap("status", "error: Could not authenticate with GitHub App"));
            }
            GitHub github = new GitHubBuilder().withAppInstallationToken(installationToken).build();
            String ownerRepo = githubRepoUrl.replace("https://github.com/", "");
            GHRepository repo = github.getRepository(ownerRepo);
            logger.info("Successfully authenticated with GitHub for repo: " + repo.getFullName());


            // --- 2. Fetch README.md Content ---
            String readmeContent = fetchReadmeContent(repo);
            if (readmeContent == null) {
                logger.severe("Failed to fetch README.md. Cannot proceed with AI generation.");
                res.status(500);
                return gson.toJson(Collections.singletonMap("status", "error: Failed to fetch README.md"));
            }
            logger.info("README.md fetched successfully. Size: " + readmeContent.length() + " chars.");


            // --- 3. Generate Documents with Gemini AI ---
            Map<String, String> generatedDocs = new HashMap<>();
            for (String area : selectedAreas) {
                logger.info("Generating document for area: " + area);
                String prompt = String.format(
                    "You are an expert AI agent tasked with drafting initial documents for a new Decentralized Autonomous Company (DeCo). " +
                    "Based on the provided README.md, generate a concise, professional, and foundational draft for the '%s' functional area. " +
                    "Focus on key elements relevant to starting such a company. The output should be in Markdown format.\n\n" +
                    "DeCo Idea (from README.md):\n%s\n\n" +
                    "Draft for %s area:",
                    area, readmeContent, area
                );
                String docContent = callGeminiApi(prompt, GEMINI_MODEL_ID);
                if (docContent != null && !docContent.trim().isEmpty()) {
                    generatedDocs.put(area, docContent);
                    logger.info("Generated document for " + area + ". Length: " + docContent.length());
                } else {
                    logger.warning("Failed to generate content for area: " + area);
                }
            }

            if (generatedDocs.isEmpty()) {
                logger.warning("No documents were successfully generated by AI.");
                res.status(500);
                return gson.toJson(Collections.singletonMap("status", "error: No documents generated by AI"));
            }


            // --- 4. Commit Generated Documents to GitHub Repo ---
            String commitMessage = "DeCo Zero AI: Initial document drafts for " + String.join(", ", selectedAreas) + " areas (Tx: " + transactionId + ")";
            for (Map.Entry<String, String> entry : generatedDocs.entrySet()) {
                String area = entry.getKey();
                String content = entry.getValue();
                String filePath = area.toUpperCase() + "/" + area.toLowerCase().replace("_", "-") + "_initial_draft.md"; // e.g., LEGAL/legal-initial-draft.md

                // Handle BOARD branch specifically
                if (area.equalsIgnoreCase("board")) {
                    String elevatorPitchContent = extractElevatorPitch(content); // Extract if AI generates multiple parts
                    String visionStatementContent = extractVisionStatement(content);
                    
                    if (elevatorPitchContent != null && !elevatorPitchContent.trim().isEmpty()) {
                        commitFileToGitHub(repo, "BOARD/elevator_pitch.md", elevatorPitchContent, "DeCo Zero AI: Initial Elevator Pitch");
                    } else {
                        logger.warning("Could not extract Elevator Pitch for BOARD. Committing full content to general board file.");
                        commitFileToGitHub(repo, filePath, content, commitMessage); // Fallback to committing full content
                    }
                    if (visionStatementContent != null && !visionStatementContent.trim().isEmpty()) {
                         commitFileToGitHub(repo, "BOARD/vision_statement.md", visionStatementContent, "DeCo Zero AI: Initial Vision Statement");
                    } else if (elevatorPitchContent == null || elevatorPitchContent.trim().isEmpty()) { // Only commit full content if neither specific piece was extracted
                        // If elevator pitch was not extracted, we already committed full content above. Avoid double commit.
                        logger.warning("Could not extract Vision Statement for BOARD. Full content already handled or will be skipped.");
                    }
                } else {
                    commitFileToGitHub(repo, filePath, content, commitMessage);
                }
            }
            
            logger.info("Documents committed to GitHub for " + githubRepoUrl);

            // --- 5. (Optional) Update NEAR Contract with Completion Status ---
            // In a more advanced system, this oracle might call a function on the
            // DeCo Zero Service Contract (Contract 1) to mark the request as complete,
            // or call a function on the individual DeCo's contract to confirm completion.
            // This is mocked for now.
            logger.info("MOCK: Notifying NEAR blockchain of service completion for Tx: " + transactionId);


            res.status(200);
            return gson.toJson(Collections.singletonMap("status", "success: Documents generated and committed."));

        } catch (Exception e) {
            logger.severe("Error processing DeCo service request: " + e.getMessage());
            res.status(500);
            return gson.toJson(Collections.singletonMap("status", "error: " + e.getMessage()));
        }
    }

    // --- Helper for fetching GitHub App Installation Token ---
    private String getGitHubAppInstallationToken(String repoUrl, String appId, String privateKey) throws IOException {
        try {
            GHApp app = new GitHubBuilder().withAppInstallationToken(privateKey).build().getApp();
            GHAppInstallation installation = app.getInstallationByRepository(repoUrl.split("/")[0], repoUrl.split("/")[1]);
            GHAppInstallationToken token = installation.createToken().create();
            return token.getToken();
        } catch (Exception e) {
            logger.severe("Error getting GitHub App installation token: " + e.getMessage());
            return null;
        }
    }

    // --- Helper for fetching README.md from GitHub ---
    private String fetchReadmeContent(GHRepository repo) throws IOException {
        return repo.getReadme().getContent();
    }

    // --- Helper for committing files to GitHub ---
    private void commitFileToGitHub(GHRepository repo, String filePath, String content, String commitMessage) throws IOException {
        repo.createContent().path(filePath).content(content).message(commitMessage).branch("main").commit();
    }

    // --- Helper to extract Elevator Pitch from generated Board document ---
    private String extractElevatorPitch(String boardDocContent) {
        // This is a simple regex-based extraction. A more robust solution might use LLM parsing or structured output.
        // Assumes a markdown section like "## Elevator Pitch"
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("##\\s*Elevator Pitch\\s*\\n(.*?)(\\n##|\\n---|$)", java.util.regex.Pattern.DOTALL);
        java.util.regex.Matcher matcher = pattern.matcher(boardDocContent);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return null;
    }

    // --- Helper to extract Vision Statement from generated Board document ---
    private String extractVisionStatement(String boardDocContent) {
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("##\\s*Vision Statement\\s*\\n(.*?)(\\n##|\\n---|$)", java.util.regex.Pattern.DOTALL);
        java.util.regex.Matcher matcher = pattern.matcher(boardDocContent);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return null;
    }
}
