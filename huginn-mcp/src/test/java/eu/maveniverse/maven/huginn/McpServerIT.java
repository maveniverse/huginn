/*
 * Copyright (c) 2026 Maveniverse Org.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-v20.html
 */
package eu.maveniverse.maven.huginn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * The packaged runner jar, spoken to over stdio like an MCP client would: tool registration,
 * schemas, hints, results and error mapping. Runs in the integration-test phase, after packaging.
 */
@Timeout(120)
class McpServerIT {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    static Path tmp;

    static Process server;
    static BufferedWriter in;
    static BufferedReader out;
    static int nextId = 1;

    @BeforeAll
    static void start() throws IOException {
        DemoReactor reactor = new DemoReactor(tmp);
        String jar = System.getProperty("runner.jar");
        assertTrue(jar != null && Files.isRegularFile(Path.of(jar)), "runner.jar is not built: " + jar);
        ProcessBuilder pb = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Duser.home=" + System.getProperty("user.home"),
                "-jar",
                jar);
        pb.environment().put("HUGINN_PROJECT", reactor.root.toString());
        pb.redirectError(ProcessBuilder.Redirect.DISCARD); // logs go to a file; stdout is the protocol
        server = pb.start();
        in = new BufferedWriter(new OutputStreamWriter(server.getOutputStream(), StandardCharsets.UTF_8));
        out = new BufferedReader(new InputStreamReader(server.getInputStream(), StandardCharsets.UTF_8));

        JsonNode init = request(
                "initialize",
                "{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},"
                        + "\"clientInfo\":{\"name\":\"it\",\"version\":\"1\"}}");
        assertEquals("huginn-mcp", init.at("/result/serverInfo/name").asText());
        send("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
    }

    @AfterAll
    static void stop() throws InterruptedException {
        server.destroy();
        server.waitFor(10, TimeUnit.SECONDS);
    }

    private static void send(String line) throws IOException {
        in.write(line);
        in.newLine();
        in.flush();
    }

    private static JsonNode request(String method, String params) throws IOException {
        int id = nextId++;
        send("{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"" + method + "\",\"params\":" + params + "}");
        String line;
        while ((line = out.readLine()) != null) {
            JsonNode n = JSON.readTree(line);
            if (n.path("id").asInt(-1) == id) {
                return n;
            }
        }
        throw new IOException("server closed stdout while waiting for " + method);
    }

    private static JsonNode call(String tool, String arguments) throws IOException {
        JsonNode r = request("tools/call", "{\"name\":\"" + tool + "\",\"arguments\":" + arguments + "}");
        assertFalse(r.has("error"), r.toString());
        return r.get("result");
    }

    private static String text(JsonNode result) {
        return result.at("/content/0/text").asText();
    }

    @Test
    void listsTheSixReadOnlyTools() throws IOException {
        JsonNode tools = request("tools/list", "{}").at("/result/tools");
        Set<String> names = new TreeSet<>();
        for (JsonNode t : tools) {
            names.add(t.get("name").asText());
            assertTrue(t.at("/annotations/readOnlyHint").asBoolean(), t.toString());
            assertFalse(t.at("/annotations/destructiveHint").asBoolean(true), t.toString());
            assertTrue(t.at("/annotations/idempotentHint").asBoolean(), t.toString());
            // only describe works from the project's own files; the rest may reach out to repositories
            assertEquals(
                    !"describe".equals(t.get("name").asText()),
                    t.at("/annotations/openWorldHint").asBoolean(),
                    t.toString());
            assertFalse(t.get("description").asText().isBlank(), t.toString());
        }
        assertEquals(Set.of("describe", "dependencies", "why", "versions", "search", "sources"), names);
    }

    @Test
    void schemasRequireOnlyWhatTheToolCannotDefault() throws IOException {
        for (JsonNode t : request("tools/list", "{}").at("/result/tools")) {
            JsonNode required = t.at("/inputSchema/required");
            switch (t.get("name").asText()) {
                case "why" -> assertEquals("[\"artifact\"]", required.toString());
                case "search" -> assertEquals("[\"query\"]", required.toString());
                case "sources" -> assertEquals("[\"artifact\"]", required.toString());
                default -> assertTrue(required.isMissingNode() || required.isEmpty(), t.toString());
            }
        }
    }

    @Test
    void describeAnswersForTheProjectNamedInTheEnvironment() throws IOException {
        JsonNode r = call("describe", "{}");
        assertFalse(r.get("isError").asBoolean(), r.toString());
        JsonNode body = JSON.readTree(text(r));
        assertEquals(3, body.get("modules").size());
        assertEquals("1.4.0", body.at("/maven_config/user_properties/revision").asText());
    }

    @Test
    void whyAndDependenciesReturnJsonDocuments() throws IOException {
        JsonNode why = JSON.readTree(text(call("why", "{\"artifact\":\"libc\"}")));
        assertEquals(2, why.get("modules_checked").asInt());
        assertEquals(2, why.at("/found/0/paths").size()); // selected and the one that lost
        JsonNode deps = JSON.readTree(text(call("dependencies", "{\"module\":\"app\",\"maxDepth\":1}")));
        assertEquals("com.acme:core:1.4.0", deps.at("/deps/0/id").asText());
        assertTrue(deps.get("hidden_deps").asInt() > 0);
    }

    @Test
    void failuresComeBackAsToolErrorsWithAMessage() throws IOException {
        for (String arguments : new String[] {"{\"module\":\"nope\"}", "{\"module\":\"app\",\"scope\":\"bogus\"}"}) {
            JsonNode r = call("dependencies", arguments);
            assertTrue(r.get("isError").asBoolean(), r.toString());
            assertFalse(text(r).isBlank(), r.toString());
        }
        assertTrue(text(call("dependencies", "{\"module\":\"nope\"}")).contains("no module 'nope'"));
    }

    /** The only test that needs the network: it asks Maven Central itself, not the demo repository. */
    @Test
    void searchFindsAWellKnownArtifactOnCentral() throws IOException {
        for (String query : new String[] {"g:org.slf4j a:slf4j-api", "org.slf4j:slf4j-api"}) {
            JsonNode results = search(query);
            assertFalse(results.isEmpty(), query);
            assertTrue(results.toString().contains("org.slf4j:slf4j-api:"), results.toString());
        }
    }

    /** Central rejects terms separated by a bare space; free text must still get answers. */
    @Test
    void searchTakesFreeText() throws IOException {
        assertFalse(search("slf4j api").isEmpty());
    }

    /** Pages are fixed-size slices of the same result list, and 'more' tells when to ask for the next. */
    @Test
    void searchPages() throws IOException {
        JsonNode first = JSON.readTree(text(call("search", "{\"query\":\"slf4j\",\"limit\":2}")));
        JsonNode second = JSON.readTree(text(call("search", "{\"query\":\"slf4j\",\"limit\":2,\"page\":1}")));
        assertEquals(2, first.get("results").size());
        assertEquals(2, second.get("results").size());
        assertEquals(1, second.get("page").asInt());
        assertTrue(first.get("more").asBoolean(), first.toString());
        assertFalse(first.get("results").toString().equals(second.get("results").toString()));
    }

    private static JsonNode search(String query) throws IOException {
        JsonNode r = call("search", "{\"query\":\"" + query + "\",\"limit\":5}");
        assertFalse(r.get("isError").asBoolean(), r.toString());
        return JSON.readTree(text(r)).get("results");
    }
}
