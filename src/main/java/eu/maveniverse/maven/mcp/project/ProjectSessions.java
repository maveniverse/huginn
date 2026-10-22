/*
 * Copyright (c) 2026 Maveniverse Org.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-v20.html
 */
package eu.maveniverse.maven.mcp.project;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Loaded reactors keyed by root. Loading is the expensive part (every effective model), so a
 * session is reused until one of its POMs changes on disk, then rebuilt on the next call.
 */
public final class ProjectSessions implements AutoCloseable {

    private final Map<Path, ProjectSession> sessions = new HashMap<>();

    /** The session for the reactor containing {@code path} (a directory or a POM file). */
    public synchronized ProjectSession forPath(Path path) throws IOException {
        Path root = ReactorScanner.findRoot(path);
        ProjectSession s = sessions.get(root);
        if (s != null && s.isStale()) {
            sessions.remove(root);
            s.close();
            s = null;
        }
        if (s == null) {
            s = ProjectSession.open(root);
            sessions.put(root, s);
        }
        return s;
    }

    @Override
    public synchronized void close() {
        for (ProjectSession s : sessions.values()) {
            try {
                s.close();
            } catch (RuntimeException ignored) {
                // closing the rest matters more
            }
        }
        sessions.clear();
    }
}
