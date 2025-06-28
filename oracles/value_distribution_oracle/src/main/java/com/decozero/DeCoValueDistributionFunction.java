package com.decozero;

import com.decozero.secrets.SecretManager;
import com.google.cloud.functions.HttpFunction;
import com.google.cloud.functions.HttpRequest;
import com.google.cloud.functions.HttpResponse;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.opencsv.CSVReader;
import com.opencsv.exceptions.CsvValidationException;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GitHub;
import org.kohsuke.github.GitHubBuilder;

// --- Data Models for CSV and GitHub Payload ---

// Represents a row in the values.csv file
class ContributionRule {
    String contributionType;
    String role;
    double baseValue;
    double memberReactionMultiplier;
    double nonMemberReactionMultiplier;

    // Constructor to parse a CSV row
    public ContributionRule(String[] csvLine) {
        this.contributionType = csvLine[0].trim();
        this.role = csvLine[1].trim();
        this.baseValue = Double.parseDouble(csvLine[2].trim());
        this.memberReactionMultiplier = Double.parseDouble(csvLine[3].trim());
        this.nonMemberReactionMultiplier = Double.parseDouble(csvLine[4].trim());
    }
}

// --- Main Cloud Function Class ---
public class DeCoValueDistributionFunction implements HttpFunction {

    private static final Logger logger = Logger.getLogger(DeCoValueDistributionFunction.class.getName());
    private static final Gson gson = new Gson();

    // Environment variable for GitHub Webhook Secret (for verification)
    private static final String GITHUB_WEBHOOK_SECRET = SecretManager.getSecret("GITHUB_WEBHOOK_SECRET");
    // Environment variable for the NEAR Individual DeCo Contract's Account ID
    // This oracle instance will be deployed for a specific DeCo, so it knows its contract ID.
    private static final String NEAR_DECO_CONTRACT_ID = System.getenv("NEAR_DECO_CONTRACT_ID");
    // Environment variable for the NEAR private key of the oracle's account to sign transactions
    // In a real scenario, this should be handled with KMS or similar secure methods, not raw env var.
    private static final String NEAR_ORACLE_PRIVATE_KEY = SecretManager.getSecret("NEAR_ORACLE_PRIVATE_KEY");


    @Override
    public void service(HttpRequest request, HttpResponse response) throws Exception {
        logger.info("Received request.");

        // --- 1. GitHub Webhook Validation ---
        // (Highly Recommended for Production)
        // For simplicity, this basic example skips full signature verification.
        // In production, you'd verify the 'X-Hub-Signature-256' header.
        String eventType = request.getFirstHeader("X-GitHub-Event").orElse("");
        if (eventType.isEmpty()) {
            response.setStatusCode(400);
            response.getWriter().write("Missing X-GitHub-Event header.");
            logger.warning("Missing X-GitHub-Event header.");
            return;
        }

        // --- 2. Parse GitHub Webhook Payload ---
        String requestBody = request.getReader().lines().collect(Collectors.joining(System.lineSeparator()));
        JsonObject payload;
        try {
            payload = gson.fromJson(requestBody, JsonObject.class);
            logger.info("Parsed GitHub payload. Event Type: " + eventType);
        } catch (Exception e) {
            response.setStatusCode(400);
            response.getWriter().write("Invalid JSON payload.");
            logger.severe("Error parsing JSON payload: " + e.getMessage());
            return;
        }

        // Extract repository URL and contributor from payload
        String repoUrl = "";
        String contributorUsername = "";
        String contributionType = "";
        String gitRef = ""; // For push events
        String prUrl = ""; // For pull_request events
        String issueUrl = ""; // For issue_comment events
        String reactionType = ""; // For reaction events (if GitHub webhook for reactions is granular enough)

        // Determine relevant data based on event type
        switch (eventType) {
            case "push":
                // For push events, this signifies a commit
                if (payload.has("repository") && payload.get("repository").isJsonObject()) {
                    repoUrl = payload.getAsJsonObject("repository").get("html_url").getAsString();
                }
                if (payload.has("pusher") && payload.get("pusher").isJsonObject()) {
                    contributorUsername = payload.getAsJsonObject("pusher").get("name").getAsString();
                }
                contributionType = "commit"; // Each push contains commits
                gitRef = payload.get("ref").getAsString(); // e.g., "refs/heads/main"
                logger.info("Push event detected for repo: " + repoUrl + ", Pusher: " + contributorUsername);
                // In a real scenario, you would iterate through 'commits' array in payload
                // and process each commit individually. For simplicity, we treat a push as 'a commit'.
                break;
            case "pull_request":
                // PR opened, closed, merged etc.
                if (payload.has("pull_request") && payload.get("pull_request").isJsonObject()) {
                    JsonObject pr = payload.getAsJsonObject("pull_request");
                    repoUrl = pr.getAsJsonObject("base").getAsJsonObject("repo").get("html_url").getAsString();
                    contributorUsername = pr.getAsJsonObject("user").get("login").getAsString();
                    prUrl = pr.get("html_url").getAsString();
                    String action = payload.get("action").getAsString();

                    if ("closed".equals(action) && pr.get("merged").getAsBoolean()) {
                        contributionType = "pull_request_merged";
                        logger.info("Pull Request MERGED event for repo: " + repoUrl + ", PR by: " + contributorUsername);
                    } else {
                        // For other PR actions (opened, reopened, etc.), might have different rules
                        logger.info("Pull Request event (action: " + action + ") for repo: " + repoUrl + ", PR by: " + contributorUsername);
                        response.setStatusCode(200); // Acknowledge but don't process for tokens if not merged
                        response.getWriter().write("PR action not configured for token distribution: " + action);
                        return;
                    }
                }
                break;
            case "issues":
                 // Issue opened, closed, reopened
                if (payload.has("issue") && payload.get("issue").isJsonObject()) {
                    JsonObject issue = payload.getAsJsonObject("issue");
                    repoUrl = issue.getAsJsonObject("repository").get("html_url").getAsString();
                    contributorUsername = issue.getAsJsonObject("user").get("login").getAsString();
                    issueUrl = issue.get("html_url").getAsString();
                    String action = payload.get("action").getAsString();
                    // For simplicity, we'll map 'opened' to 'issue_report_critical' or 'minor' based on keywords/labels
                    // A more robust system would involve AI analysis or manual labeling.
                    if ("opened".equals(action)) {
                         String title = issue.get("title").getAsString();
                         // Simple heuristic: if title contains "bug", "error", "critical", categorize as critical
                         if (title.toLowerCase().contains("bug") || title.toLowerCase().contains("error") || title.toLowerCase().contains("critical")) {
                             contributionType = "issue_report_critical";
                         } else {
                             contributionType = "issue_report_minor";
                         }
                         logger.info("Issue opened for repo: " + repoUrl + ", Issue by: " + contributorUsername + ", Type: " + contributionType);
                    } else {
                        logger.info("Issue event (action: " + action + ") for repo: " + repoUrl + ", Issue by: " + contributorUsername);
                        response.setStatusCode(200);
                        response.getWriter().write("Issue action not configured for token distribution: " + action);
                        return;
                    }
                }
                break;
            case "issue_comment":
                // Comment on an issue or PR
                if (payload.has("comment") && payload.has("issue")) { // Check for issue comments, PR comments also come as issue_comment sometimes
                    JsonObject comment = payload.getAsJsonObject("comment");
                    JsonObject issueOrPull = payload.getAsJsonObject("issue"); // Could be issue or pull_request object
                    
                    repoUrl = issueOrPull.getAsJsonObject("repository").get("html_url").getAsString();
                    contributorUsername = comment.getAsJsonObject("user").get("login").getAsString();
                    
                    String commentBody = comment.get("body").getAsString();
                    // This is a very simplistic way to detect a 'suggestion'
                    if (commentBody.toLowerCase().contains("suggestion") || commentBody.toLowerCase().contains("idea:")) {
                        contributionType = "suggestion";
                        logger.info("Suggestion detected in comment for repo: " + repoUrl + ", Comment by: " + contributorUsername);
                    } else {
                        logger.info("Comment event detected, but not classified as suggestion for repo: " + repoUrl + ", Comment by: " + contributorUsername);
                        response.setStatusCode(200);
                        response.getWriter().write("Comment not classified for token distribution.");
                        return;
                    }
                }
                break;
            // Reactions are tricky. GitHub webhooks for reactions are often on IssueCommentEvent, PullRequestReviewCommentEvent, etc.
            // A direct 'reaction' event type is less common for specific reactions like thumbs up/heart on arbitrary content.
            // You might need to infer reactions from issue_comment or pull_request_review_comment events
            // or use specific GitHub API polling for reactions if webhooks don't provide sufficient granularity.
            // For this initial draft, we'll focus on primary contribution types.
            default:
                response.setStatusCode(200); // Acknowledge but don't process unknown events
                response.getWriter().write("Unhandled GitHub event type: " + eventType);
                logger.info("Unhandled GitHub event type: " + eventType);
                return;
        }

        if (repoUrl.isEmpty() || contributorUsername.isEmpty() || contributionType.isEmpty()) {
            response.setStatusCode(400);
            response.getWriter().write("Could not extract essential data from webhook payload.");
            logger.warning("Could not extract essential data from webhook payload for event: " + eventType);
            return;
        }

        // --- 3. Fetch values.csv from the specific DeCo's repository ---
        Map<String, ContributionRule> rules = new HashMap<>();
        try {
            GitHub github = new GitHubBuilder().withOAuthToken(SecretManager.getSecret("GITHUB_TOKEN")).build();
            GHRepository repo = github.getRepository(repoUrl.replace("https://github.com/", ""));
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(repo.getFileContent("values.csv").read(), StandardCharsets.UTF_8));
                 CSVReader csvReader = new CSVReader(reader)) {
                String[] nextLine;
                // Skip header and comment lines starting with '#'
                while ((nextLine = csvReader.readNext()) != null) {
                    if (nextLine.length > 0 && !nextLine[0].trim().startsWith("#")) {
                        try {
                            ContributionRule rule = new ContributionRule(nextLine);
                            rules.put(rule.contributionType + "_" + rule.role, rule);
                        } catch (NumberFormatException e) {
                            logger.warning("Skipping malformed CSV line (number format error): " + String.join(",", nextLine));
                        }
                    }
                }
            }
        } catch (IOException | CsvValidationException e) {
            logger.severe("Error fetching or parsing values.csv from " + repoUrl + ": " + e.getMessage());
            response.setStatusCode(500);
            response.getWriter().write("Error fetching or parsing values.csv.");
            return;
        }

        if (rules.isEmpty()) {
            response.setStatusCode(500);
            response.getWriter().write("values.csv loaded, but no valid rules found. Check CSV format.");
            logger.severe("values.csv loaded, but no valid rules found.");
            return;
        }
        logger.info("Values.csv loaded. Rules count: " + rules.size());

        // --- 4. Determine Contributor Role (Simplified for this example) ---
        // In a real scenario, you'd fetch roles from GitHub API (repo collaborators)
        // For simplicity, we'll use a placeholder logic for roles based on username.
        // Or you might need to extend the webhook payload with roles if DeCo Cero can fetch this.
        String contributorRole = "contributor"; // Default role
        if (contributorUsername.equals("maintainer_username_placeholder")) { // Replace with actual maintainer logic
            contributorRole = "maintainer";
        }
        // If not found in rules for specific role, try a general contributor rule
        ContributionRule rule = rules.get(contributionType + "_" + contributorRole);
        if (rule == null) {
             logger.warning(String.format("No specific rule found for type '%s' and role '%s'. Attempting general 'contributor' rule.", contributionType, contributorRole));
             rule = rules.get(contributionType + "_contributor"); // Fallback to general contributor rule
             if (rule == null) {
                 logger.warning(String.format("No rule found for contribution type '%s' at all. Skipping token minting.", contributionType));
                 response.setStatusCode(200);
                 response.getWriter().write(String.format("No rule found for '%s'. No tokens minted.", contributionType));
                 return;
             }
        }
       
        // --- 5. Calculate Tokens to Mint (Simplified) ---
        // This calculation does NOT include reactions yet for simplicity,
        // as GitHub webhooks for reactions are more complex to get granularly.
        // It's a base value per contribution.
        double tokensToMint = rule.baseValue;
        logger.info(String.format("Calculated %f tokens for %s (%s) of type %s.",
                tokensToMint, contributorUsername, contributorRole, contributionType));

        // --- 6. Call Individual DeCo Smart Contract (NEAR) to Mint Tokens ---
        // THIS IS A MOCK IMPLEMENTATION.
        // In a real scenario, you would use a NEAR SDK (e.g., near-api-js for Node.js,
        // or a Rust/Python client that interacts with the blockchain) to sign and send a transaction
        // to the `mint` function of the specific DeCo's contract (NEAR_DECO_CONTRACT_ID).

        logger.info(String.format("MOCK: Attempting to mint %f tokens to %s on DeCo contract %s...",
                tokensToMint, contributorUsername, NEAR_DECO_CONTRACT_ID));
        
        // Example of what a real NEAR SDK call might look like conceptually (not actual Java SDK code):
        /*
        try {
            // Initialize NEAR connection
            Near near = new Near(new JsonRpcProvider("[https://rpc.testnet.near.org](https://rpc.testnet.near.org)"), new KeyPair(NEAR_ORACLE_PRIVATE_KEY));
            Account oracleAccount = near.account("oracle_account_id_on_near"); // The oracle's account
            Contract contract = new Contract(oracleAccount, NEAR_DECO_CONTRACT_ID);

            // Call the 'mint' function on the DeCo's contract
            contract.call("mint", new JsonObject() {{
                addProperty("account_id", contributorUsername);
                addProperty("amount", (long) (tokensToMint * Math.pow(10, contract.getDecimals()))); // Convert to yoctoTokens
            }});
            logger.info("Successfully MINTED tokens on NEAR blockchain (MOCK transaction)!");

        } catch (Exception e) {
            logger.severe("Error minting tokens on NEAR blockchain (MOCK): " + e.getMessage());
            response.setStatusCode(500);
            response.getWriter().write("Failed to mint tokens on blockchain.");
            return;
        }
        */
        
        // Simulate success
        response.setStatusCode(200);
        response.getWriter().write("Tokens calculation initiated for " + contributorUsername + ". Check blockchain for minting status.");
        logger.info("Function execution complete for event " + eventType + " from " + repoUrl);
    }
}