/*
 * Copyright (c) 2026 Maveniverse Org.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-v20.html
 */
package eu.maveniverse.maven.huginn.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.graph.DefaultDependencyNode;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.graph.DependencyNode;
import org.eclipse.aether.util.graph.manager.DependencyManagerUtils;
import org.eclipse.aether.util.graph.transformer.ConflictResolver;
import org.junit.jupiter.api.Test;

class GraphsTest {

    private static DefaultDependencyNode node(String coords, String scope) {
        DefaultDependencyNode n = new DefaultDependencyNode(new Dependency(new DefaultArtifact(coords), scope));
        n.setChildren(new ArrayList<>());
        return n;
    }

    private static void add(DependencyNode parent, DependencyNode child) {
        parent.getChildren().add(child);
    }

    /**
     * module -> x:1 -> y:1.0 (selected)
     * module -> z:1 -> y:0.9 (lost to 1.0)
     * module -> junit:4 (test) -> hamcrest:1.3
     */
    private static DependencyNode graph() {
        DefaultDependencyNode root = new DefaultDependencyNode(new DefaultArtifact("com.acme:app:jar:1.0"));
        root.setChildren(new ArrayList<>());
        DefaultDependencyNode x = node("org.x:x:1", "compile");
        DefaultDependencyNode y = node("org.y:y:1.0", "compile");
        y.setData(DependencyManagerUtils.NODE_DATA_PREMANAGED_VERSION, "0.8");
        y.setManagedBits(DependencyNode.MANAGED_VERSION); // Resolver only reports premanaged data with this bit
        DefaultDependencyNode z = node("org.z:z:1", "compile");
        DefaultDependencyNode yOld = node("org.y:y:0.9", "compile");
        yOld.setData(ConflictResolver.NODE_DATA_WINNER, y);
        DefaultDependencyNode junit = node("junit:junit:4.13.2", "test");
        DefaultDependencyNode hamcrest = node("org.hamcrest:hamcrest-core:1.3", "test");
        add(root, x);
        add(x, y);
        add(root, z);
        add(z, yOld);
        add(root, junit);
        add(junit, hamcrest);
        return root;
    }

    @Test
    void treeKeepsWinnersAndReportsConflicts() {
        Graphs.Tree t = Graphs.tree(graph(), 0);
        assertEquals(5, t.count); // x, y, z, junit, hamcrest; the losing y is not a node
        String json = Json.write(t.children);
        assertTrue(json.contains("{\"id\":\"org.y:y:1.0\",\"managed_from\":\"0.8\"}"), json);
        assertTrue(json.contains("{\"id\":\"junit:junit:4.13.2\",\"scope\":\"test\""), json);
        assertFalse(json.contains("0.9"), json);
        Map<String, Object> conflict = t.conflicts.get("org.y:y");
        assertEquals("1.0", conflict.get("selected"));
        assertEquals("[{\"version\":\"0.9\",\"by\":\"org.z:z:1\"}]", Json.write(conflict.get("requested")));
    }

    @Test
    void depthLimitCountsHiddenNodes() {
        Graphs.Tree t = Graphs.tree(graph(), 1);
        assertEquals(2, t.truncated); // y under x, hamcrest under junit
        assertTrue(Json.write(t.children).contains("\"hidden_deps\":1"));
    }

    @Test
    void pathsIncludeLosingRequests() {
        List<Map<String, Object>> paths = Graphs.paths(graph(), Graphs.matcher("org.y:y"), 10);
        assertEquals(2, paths.size());
        assertEquals(
                "{\"path\":[\"org.x:x:1 (compile)\",\"org.y:y:1.0 (compile)\"],\"status\":\"selected\"}",
                Json.write(paths.get(0)));
        assertEquals("omitted: conflict, 1.0 selected", paths.get(1).get("status"));
    }

    @Test
    void matcherAcceptsBareWordsAndWildcards() {
        DefaultArtifact a = new DefaultArtifact("com.google.guava:guava:33.0.0-jre");
        assertTrue(Graphs.matcher("guava").test(a));
        assertTrue(Graphs.matcher("com.google.*").test(a));
        assertTrue(Graphs.matcher("com.google.guava:guava").test(a));
        assertTrue(Graphs.matcher("*:guava:33.*").test(a));
        assertFalse(Graphs.matcher("com.google.guava:guava:32.*").test(a));
        assertFalse(Graphs.matcher("guav").test(a));
    }
}
