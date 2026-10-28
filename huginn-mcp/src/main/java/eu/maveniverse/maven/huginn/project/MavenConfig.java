/*
 * Copyright (c) 2026 Maveniverse Org.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-v20.html
 */
package eu.maveniverse.maven.huginn.project;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The parts of {@code .mvn/maven.config} that change what a build sees: user properties
 * ({@code -D}, used for CI-friendly versions such as {@code ${revision}}), profiles ({@code -P}),
 * offline mode and settings files ({@code -s}, {@code -gs}; null when not given, as written in the
 * file). Everything else in the file is irrelevant to model building.
 */
public record MavenConfig(
        Map<String, String> userProperties,
        List<String> activeProfiles,
        List<String> inactiveProfiles,
        boolean offline,
        String userSettings,
        String globalSettings) {

    public static final MavenConfig EMPTY = new MavenConfig(Map.of(), List.of(), List.of(), false, null, null);

    public static MavenConfig read(Path root) {
        Path file = root.resolve(".mvn").resolve("maven.config");
        if (!Files.isRegularFile(file)) {
            return EMPTY;
        }
        try {
            return parse(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            return EMPTY;
        }
    }

    static MavenConfig parse(String content) {
        Map<String, String> props = new LinkedHashMap<>();
        List<String> active = new ArrayList<>();
        List<String> inactive = new ArrayList<>();
        boolean offline = false;
        String userSettings = null;
        String globalSettings = null;
        List<String> args = tokenize(content);
        for (int i = 0; i < args.size(); i++) {
            String a = args.get(i);
            if (a.startsWith("-D") || a.startsWith("--define")) {
                String kv = a.startsWith("-D")
                        ? a.substring(2)
                        : a.substring("--define".length()).replaceFirst("^=", "");
                if (kv.isEmpty() && i + 1 < args.size()) {
                    kv = args.get(++i);
                }
                int eq = kv.indexOf('=');
                if (eq > 0) {
                    props.put(kv.substring(0, eq), kv.substring(eq + 1));
                } else if (!kv.isEmpty()) {
                    props.put(kv, "true");
                }
            } else if (a.equals("-P") || a.equals("--activate-profiles")) {
                if (i + 1 < args.size()) {
                    profiles(args.get(++i), active, inactive);
                }
            } else if (a.startsWith("-P")) {
                profiles(a.substring(2), active, inactive);
            } else if (a.startsWith("--activate-profiles=")) {
                profiles(a.substring("--activate-profiles=".length()), active, inactive);
            } else if (a.equals("-o") || a.equals("--offline")) {
                offline = true;
            } else if ((a.equals("-s") || a.equals("--settings")) && i + 1 < args.size()) {
                userSettings = args.get(++i);
            } else if (a.startsWith("--settings=")) {
                userSettings = a.substring("--settings=".length());
            } else if ((a.equals("-gs") || a.equals("--global-settings")) && i + 1 < args.size()) {
                globalSettings = args.get(++i);
            } else if (a.startsWith("--global-settings=")) {
                globalSettings = a.substring("--global-settings=".length());
            }
        }
        return new MavenConfig(
                Collections.unmodifiableMap(props),
                List.copyOf(active),
                List.copyOf(inactive),
                offline,
                userSettings,
                globalSettings);
    }

    private static void profiles(String spec, List<String> active, List<String> inactive) {
        for (String p : spec.split(",")) {
            String id = p.trim();
            boolean off = id.startsWith("!") || id.startsWith("-");
            if (off || id.startsWith("+")) {
                id = id.substring(1);
            }
            if (id.startsWith("?")) {
                id = id.substring(1); // optional: no error when missing, otherwise the same
            }
            if (!id.isEmpty()) {
                (off ? inactive : active).add(id);
            }
        }
    }

    /** Whitespace-separated, with single or double quotes grouping, as Maven reads the file. */
    private static List<String> tokenize(String content) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        char quote = 0;
        boolean inToken = false;
        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                } else {
                    cur.append(c);
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
                inToken = true;
            } else if (Character.isWhitespace(c)) {
                if (inToken) {
                    out.add(cur.toString());
                    cur.setLength(0);
                    inToken = false;
                }
            } else {
                cur.append(c);
                inToken = true;
            }
        }
        if (inToken) {
            out.add(cur.toString());
        }
        return out;
    }
}
