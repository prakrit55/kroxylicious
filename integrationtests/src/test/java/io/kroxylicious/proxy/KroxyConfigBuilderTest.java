/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.kroxylicious.proxy;

import java.io.IOException;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class KroxyConfigBuilderTest {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper(new YAMLFactory());

    @Test
    public void testBareConfig() throws IOException {
        ObjectNode deserializedConfig = serializeAndDeserialize(new KroxyConfigBuilder("localhost:9192"));
        ObjectNode proxyObj = assertObjectField(deserializedConfig, "proxy");
        assertTextField(proxyObj, "address", "localhost:9192");
    }

    @Test
    public void testSslConfig() throws IOException {
        ObjectNode deserializedConfig = serializeAndDeserialize(new KroxyConfigBuilder("localhost:9192")
                .withKeyStoreConfig("file", "pass"));
        ObjectNode proxyObj = assertObjectField(deserializedConfig, "proxy");
        assertTextField(proxyObj, "address", "localhost:9192");
        assertTextField(proxyObj, "keyStoreFile", "file");
        assertTextField(proxyObj, "keyPassword", "pass");
    }

    @Test
    public void testClusterConfig() throws IOException {
        ObjectNode deserializedConfig = serializeAndDeserialize(new KroxyConfigBuilder("localhost:9192")
                .withDefaultCluster("localhost:9092"));
        ObjectNode clusterObj = assertObjectField(deserializedConfig, "clusters");
        ObjectNode demoObj = assertObjectField(clusterObj, "demo");
        assertTextField(demoObj, "bootstrap_servers", "localhost:9092");
    }

    @Test
    public void testTypeOnlyFilter() throws IOException {
        ObjectNode deserializedConfig = serializeAndDeserialize(new KroxyConfigBuilder("localhost:9192")
                .addFilter("FilterType"));
        ArrayNode filters = assertArrayField(deserializedConfig, "filters", 1);
        ObjectNode filterObj = assertOnlyElementIsObject(filters);
        assertTextField(filterObj, "type", "FilterType");
    }

    @Test
    public void testFilterWithSingleParam() throws IOException {
        ObjectNode deserializedConfig = serializeAndDeserialize(new KroxyConfigBuilder("localhost:9192")
                .addFilter("FilterType", "a", "b"));
        ArrayNode filters = assertArrayField(deserializedConfig, "filters", 1);
        ObjectNode filterObj = assertOnlyElementIsObject(filters);
        assertTextField(filterObj, "type", "FilterType");
        ObjectNode filterConfig = assertObjectField(filterObj, "config");
        assertTextField(filterConfig, "a", "b");
    }

    @Test
    public void testPrometheusEndpointConfig() throws IOException {
        ObjectNode deserializedConfig = serializeAndDeserialize(new KroxyConfigBuilder("localhost:9192")
                .withPrometheusEndpoint());
        ObjectNode adminHttp = assertObjectField(deserializedConfig, "adminHttp");
        ObjectNode endpoints = assertObjectField(adminHttp, "endpoints");
        ObjectNode prometheus = assertObjectField(endpoints, "prometheus");
        assertTrue(prometheus.isEmpty(), "expect prometheus endpoint to have an empty object serialized");
    }

    private static ObjectNode serializeAndDeserialize(KroxyConfigBuilder builder) throws IOException {
        String config = builder.build();
        return OBJECT_MAPPER.reader().readValue(config, ObjectNode.class);
    }

    private static ObjectNode assertObjectField(ObjectNode o, String fieldName) {
        assertTrue(o.has(fieldName), "config should have a " + fieldName + " field");
        JsonNode field = o.get(fieldName);
        assertTrue(field.isObject(), fieldName + " should be an object");
        return (ObjectNode) field;
    }

    private static ArrayNode assertArrayField(ObjectNode o, String fieldName, int size) {
        assertTrue(o.has(fieldName), "config should have a " + fieldName + " field");
        JsonNode field = o.get(fieldName);
        assertTrue(field.isArray(), fieldName + " should be an array");
        ArrayNode array = (ArrayNode) field;
        assertEquals(size, array.size(), fieldName + " was unexpected size");
        return array;
    }

    private static ObjectNode assertOnlyElementIsObject(ArrayNode o) {
        assertEquals(1, o.size(), "array had more than one element");
        JsonNode jsonNode = o.get(0);
        assertTrue(jsonNode.isObject(), "only array element was not an object");
        return (ObjectNode) jsonNode;
    }

    private static void assertTextField(ObjectNode object, String fieldName, String expected) {
        assertTrue(object.has(fieldName), "expect " + fieldName + " to be defined");
        JsonNode field = object.get(fieldName);
        assertTrue(field.isTextual(), fieldName + " should be text");
        assertEquals(expected, field.asText(), "expect " + fieldName + " to be set");
    }

}
