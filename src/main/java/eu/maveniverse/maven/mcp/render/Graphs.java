/*
 * Copyright (c) 2026 Maveniverse Org.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-v20.html
 */
package eu.maveniverse.maven.mcp.render;

import eu.maveniverse.maven.mcp.project.Models;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.graph.DependencyNode;
import org.eclipse.aether.util.graph.manager.DependencyManagerUtils;
import org.eclipse.aether.util.graph.transformer.ConflictResolver;

/**
 * Renders a verbosely collected dependency graph (conflict losers kept as leaves) for agents:
 * the tree holds only what actually ends up on the classpath, and conflicts are reported
 * separately, which is where version questions are answered.
 */
public final class Graphs {

    private Graphs() {}

    // ---- tree ---------------------------------------------------------------------------------

    public static final class Tree {
        public final List<Map<String, Object>> children = new ArrayList<>();
        public final Map<String, Map<String, Object>> conflicts = new LinkedHashMap<>();
        public int count;
        public int truncated;
    }

    /** @param maxDepth 0 for unlimited; deeper nodes are counted, not listed */
    public static Tree tree(DependencyNode root, int maxDepth) {
        Tree t = new Tree();
        Set<DependencyNode> onPath = Collections.newSetFromMap(new IdentityHashMap<>());
        onPath.add(root);
        for (DependencyNode child : root.getChildren()) {
            Map<String, Object> n = node(child, root, 1, maxDepth, t, onPath);
            if (n != null) {
                t.children.add(n);
            }
        }
        return t;
    }

    private static Map<String, Object> node(
            DependencyNode node, DependencyNode parent, int depth, int maxDepth, Tree t, Set<DependencyNode> onPath) {
        Artifact a = node.getArtifact();
        if (a == null) {
            return null;
        }
        DependencyNode winner = winner(node);
        if (winner != null) {
            recordConflict(node, winner, parent, t);
            return null;
        }
        t.count++;
        Json.Obj o = Json.obj().with("id", Models.id(a));
        if (node.getDependency() != null) {
            String scope = node.getDependency().getScope();
            if (!scope.isEmpty() && !"compile".equals(scope)) {
                o.with("scope", scope);
            }
            if (node.getDependency().isOptional()) {
                o.with("optional", true);
            }
        }
        String premanaged = DependencyManagerUtils.getPremanagedVersion(node);
        if (premanaged != null && !premanaged.equals(a.getVersion())) {
            o.with("managed_from", premanaged);
        }
        if (!onPath.add(node)) {
            return o.with("cycle", true);
        }
        try {
            if (maxDepth > 0 && depth >= maxDepth) {
                int hidden = countWinners(node, Collections.newSetFromMap(new IdentityHashMap<>()));
                if (hidden > 0) {
                    o.with("hidden_deps", hidden);
                    t.truncated += hidden;
                }
                return o;
            }
            List<Map<String, Object>> deps = new ArrayList<>();
            for (DependencyNode c : node.getChildren()) {
                Map<String, Object> n = node(c, node, depth + 1, maxDepth, t, onPath);
                if (n != null) {
                    deps.add(n);
                }
            }
            return o.with("deps", deps);
        } finally {
            onPath.remove(node);
        }
    }

    private static int countWinners(DependencyNode node, Set<DependencyNode> seen) {
        int n = 0;
        for (DependencyNode c : node.getChildren()) {
            if (c.getArtifact() != null && winner(c) == null && seen.add(c)) {
                n += 1 + countWinners(c, seen);
            }
        }
        return n;
    }

    private static void recordConflict(DependencyNode loser, DependencyNode winner, DependencyNode parent, Tree t) {
        Artifact l = loser.getArtifact();
        Artifact w = winner.getArtifact();
        if (w == null || Objects.equals(l.getVersion(), w.getVersion())) {
            return; // same version seen twice: a duplicate, not a conflict
        }
        String key =
                l.getGroupId() + ":" + l.getArtifactId() + (l.getClassifier().isEmpty() ? "" : ":" + l.getClassifier());
        Map<String, Object> c = t.conflicts.computeIfAbsent(
                key,
                k -> Json.obj()
                        .with("artifact", k)
                        .with("selected", w.getVersion())
                        .with("requested", new ArrayList<>()));
        @SuppressWarnings("unchecked")
        List<Object> requested = (List<Object>) c.get("requested");
        Json.Obj r = Json.obj().with("version", l.getVersion());
        if (parent != null && parent.getArtifact() != null) {
            r.with("by", Models.id(parent.getArtifact()));
        }
        if (!requested.contains(r) && requested.size() < 10) {
            requested.add(r);
        }
    }

    static DependencyNode winner(DependencyNode node) {
        Object w = node.getData().get(ConflictResolver.NODE_DATA_WINNER);
        return w instanceof DependencyNode d ? d : null;
    }

    // ---- paths ("why") --------------------------------------------------------------------------

    /**
     * Every path from the root to a node matching {@code matcher}, including requests that lost
     * a conflict (marked), so "why is X here" and "who wants the old X" share one answer.
     */
    public static List<Map<String, Object>> paths(DependencyNode root, Predicate<Artifact> matcher, int maxPaths) {
        List<Map<String, Object>> out = new ArrayList<>();
        List<DependencyNode> stack = new ArrayList<>();
        Set<DependencyNode> onPath = Collections.newSetFromMap(new IdentityHashMap<>());
        walk(root, matcher, stack, onPath, out, maxPaths);
        return out;
    }

    private static void walk(
            DependencyNode node,
            Predicate<Artifact> matcher,
            List<DependencyNode> stack,
            Set<DependencyNode> onPath,
            List<Map<String, Object>> out,
            int maxPaths) {
        if (out.size() >= maxPaths || !onPath.add(node)) {
            return;
        }
        stack.add(node);
        try {
            if (stack.size() > 1 && node.getArtifact() != null && matcher.test(node.getArtifact())) {
                List<String> path = new ArrayList<>();
                for (int i = 1; i < stack.size(); i++) {
                    path.add(label(stack.get(i)));
                }
                Json.Obj p = Json.obj().with("path", path);
                DependencyNode w = winner(node);
                if (w == null) {
                    p.with("status", "selected");
                } else if (w.getArtifact() != null
                        && !Objects.equals(
                                w.getArtifact().getVersion(), node.getArtifact().getVersion())) {
                    p.with("status", "omitted: conflict, " + w.getArtifact().getVersion() + " selected");
                } else {
                    p.with("status", "omitted: duplicate");
                }
                out.add(p);
            }
            if (winner(node) == null) {
                for (DependencyNode c : node.getChildren()) {
                    walk(c, matcher, stack, onPath, out, maxPaths);
                }
            }
        } finally {
            stack.remove(stack.size() - 1);
            onPath.remove(node);
        }
    }

    private static String label(DependencyNode n) {
        String id = Models.id(n.getArtifact());
        String scope = n.getDependency() != null ? n.getDependency().getScope() : "";
        return scope.isEmpty() ? id : id + " (" + scope + ")";
    }

    // ---- matching ---------------------------------------------------------------------------------

    /**
     * {@code groupId:artifactId[:version]} with {@code *} wildcards; a pattern without a colon
     * matches either the groupId or the artifactId.
     */
    public static Predicate<Artifact> matcher(String pattern) {
        String p = pattern.trim();
        String[] parts = p.split(":", -1);
        if (parts.length == 1) {
            return a -> wildcard(p, a.getArtifactId()) || wildcard(p, a.getGroupId());
        }
        return a -> wildcard(parts[0], a.getGroupId())
                && wildcard(parts[1], a.getArtifactId())
                && (parts.length < 3 || wildcard(parts[parts.length - 1], a.getVersion()));
    }

    static boolean wildcard(String pattern, String value) {
        if (pattern.isEmpty() || "*".equals(pattern)) {
            return true;
        }
        if (!pattern.contains("*")) {
            return pattern.equals(value);
        }
        String regex = ("\\Q" + pattern + "\\E").replace("*", "\\E.*\\Q");
        return value.matches(regex);
    }
}
