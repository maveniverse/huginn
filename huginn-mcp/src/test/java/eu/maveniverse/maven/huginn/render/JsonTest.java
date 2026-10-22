/*
 * Copyright (c) 2026 Maveniverse Org.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-v20.html
 */
package eu.maveniverse.maven.huginn.render;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JsonTest {

    @Test
    void escapesAndOmitsEmpty() {
        String json = Json.write(Json.obj()
                .with("s", "a\"b\\c\nd\u0001")
                .with("n", 3)
                .with("b", true)
                .with("none", null)
                .with("empty", List.of())
                .with("emptyMap", Map.of())
                .with("list", List.of("x", 1)));
        assertEquals("{\"s\":\"a\\\"b\\\\c\\nd\\u0001\",\"n\":3,\"b\":true,\"list\":[\"x\",1]}", json);
    }
}
