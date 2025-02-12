/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.kroxylicious.proxy;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.config.SslConfigs;
import org.apache.kafka.common.security.auth.SecurityProtocol;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import io.micrometer.core.instrument.Metrics;
import io.netty.handler.codec.http.HttpResponseStatus;

import io.kroxylicious.proxy.config.ConfigParser;
import io.kroxylicious.proxy.config.Configuration;
import io.kroxylicious.proxy.internal.filter.ByteBufferTransformation;
import io.kroxylicious.proxy.testkafkacluster.KafkaClusterConfig;
import io.kroxylicious.proxy.testkafkacluster.KafkaClusterFactory;
import io.kroxylicious.proxy.testkafkacluster.KeytoolCertificateGenerator;

import static java.net.http.HttpResponse.BodyHandlers.ofString;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class KrpcFilterIT {

    private static final String TOPIC_1 = "my-test-topic";
    private static final String TOPIC_2 = "other-test-topic";
    private static final String PLAINTEXT = "Hello, world!";
    private static final byte[] TOPIC_1_CIPHERTEXT = { (byte) 0x3d, (byte) 0x5a, (byte) 0x61, (byte) 0x61, (byte) 0x64, (byte) 0x21, (byte) 0x15, (byte) 0x6c,
            (byte) 0x64, (byte) 0x67, (byte) 0x61, (byte) 0x59, (byte) 0x16 };
    private static final byte[] TOPIC_2_CIPHERTEXT = { (byte) 0xffffffa7, (byte) 0xffffffc4, (byte) 0xffffffcb, (byte) 0xffffffcb, (byte) 0xffffffce, (byte) 0xffffff8b,
            (byte) 0x7f, (byte) 0xffffffd6, (byte) 0xffffffce, (byte) 0xffffffd1, (byte) 0xffffffcb, (byte) 0xffffffc3, (byte) 0xffffff80 };

    private TestInfo testInfo;

    @BeforeEach
    public void beforeEach(TestInfo testInfo) {
        this.testInfo = testInfo;
    }

    @BeforeAll
    public static void checkReversibleEncryption() {
        // The precise details of the cipher don't matter
        // What matters is that it the ciphertext key depends on the topic name
        // and that decode() is the inverse of encode()
        assertArrayEquals(TOPIC_1_CIPHERTEXT, encode(TOPIC_1, ByteBuffer.wrap(PLAINTEXT.getBytes(StandardCharsets.UTF_8))).array());
        assertEquals(PLAINTEXT, new String(decode(TOPIC_1, ByteBuffer.wrap(TOPIC_1_CIPHERTEXT)).array(), StandardCharsets.UTF_8));
        assertArrayEquals(TOPIC_2_CIPHERTEXT, encode(TOPIC_2, ByteBuffer.wrap(PLAINTEXT.getBytes(StandardCharsets.UTF_8))).array());
        assertEquals(PLAINTEXT, new String(decode(TOPIC_2, ByteBuffer.wrap(TOPIC_2_CIPHERTEXT)).array(), StandardCharsets.UTF_8));
    }

    public static class TestEncoder implements ByteBufferTransformation {

        @Override
        public ByteBuffer transform(String topicName, ByteBuffer in) {
            return encode(topicName, in);
        }
    }

    private static ByteBuffer encode(String topicName, ByteBuffer in) {
        var out = ByteBuffer.allocate(in.limit());
        byte rot = (byte) (topicName.hashCode() % Byte.MAX_VALUE);
        for (int index = 0; index < in.limit(); index++) {
            byte b = in.get(index);
            byte rotated = (byte) (b + rot);
            out.put(index, rotated);
        }
        return out;
    }

    public static class TestDecoder implements ByteBufferTransformation {

        @Override
        public ByteBuffer transform(String topicName, ByteBuffer in) {
            return decode(topicName, in);
        }
    }

    private static ByteBuffer decode(String topicName, ByteBuffer in) {
        var out = ByteBuffer.allocate(in.limit());
        out.limit(in.limit());
        byte rot = (byte) -(topicName.hashCode() % Byte.MAX_VALUE);
        for (int index = 0; index < in.limit(); index++) {
            byte b = in.get(index);
            byte rotated = (byte) (b + rot);
            out.put(index, rotated);
        }
        return out;
    }

    @Test
    public void shouldPassThroughRecordUnchanged() throws Exception {
        String proxyAddress = "localhost:9192";

        try (var cluster = KafkaClusterFactory.create(KafkaClusterConfig.builder().testInfo(testInfo).build())) {
            cluster.start();

            String bootstrapServers = cluster.getBootstrapServers();
            try (var admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers))) {
                admin.createTopics(List.of(new NewTopic(TOPIC_1, 1, (short) 1))).all().get();
            }
            String config = baseConfigBuilder(proxyAddress, bootstrapServers).build();

            var proxy = startProxy(config);

            try (var producer = new KafkaProducer<String, String>(Map.of(
                    ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, proxyAddress,
                    ProducerConfig.CLIENT_ID_CONFIG, "shouldPassThroughRecordUnchanged",
                    ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                    ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                    ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 3_600_000))) {
                producer.send(new ProducerRecord<>(TOPIC_1, "my-key", "Hello, world!")).get();
            }

            try (var consumer = new KafkaConsumer<String, String>(Map.of(
                    ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, proxyAddress,
                    ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                    ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                    ConsumerConfig.GROUP_ID_CONFIG, "my-group-id",
                    ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"))) {
                consumer.subscribe(Set.of(TOPIC_1));
                var records = consumer.poll(Duration.ofSeconds(10));
                consumer.close();
                assertEquals(1, records.count());
                assertEquals("Hello, world!", records.iterator().next().value());
            }

            // shutdown the proxy
            proxy.shutdown();

        }

    }

    @Test
    public void shouldOfferPrometheusMetricsScrapeEndpoint() throws Exception {
        String proxyAddress = "localhost:9192";

        try (var cluster = KafkaClusterFactory.create(KafkaClusterConfig.builder().testInfo(testInfo).build())) {
            cluster.start();

            String config = baseConfigBuilder(proxyAddress, cluster.getBootstrapServers())
                    .withPrometheusEndpoint().build();

            var proxy = startProxy(config);

            String counter_name = "test_metric_" + Math.abs(new Random().nextLong()) + "_total";
            Metrics.counter(counter_name).increment();
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:9193/metrics")).GET().build();
            HttpResponse<String> response = HttpClient.newHttpClient().send(request, ofString());
            assertTrue(response.body().contains(counter_name + " 1.0"));
            HttpRequest notFoundReq = HttpRequest.newBuilder(URI.create("http://localhost:9193/nonexistant")).GET().build();
            HttpResponse<String> notFoundResp = HttpClient.newHttpClient().send(notFoundReq, ofString());
            assertEquals(notFoundResp.statusCode(), HttpResponseStatus.NOT_FOUND.code());
            // shutdown the proxy
            proxy.shutdown();

        }

    }

    @Test
    public void shouldModifyProduceMessage() throws Exception {
        String proxyAddress = "localhost:9192";
        try (var cluster = KafkaClusterFactory.create(KafkaClusterConfig.builder().testInfo(testInfo).build())) {
            cluster.start();
            try (var admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, cluster.getBootstrapServers()))) {
                admin.createTopics(List.of(
                        new NewTopic(TOPIC_1, 1, (short) 1),
                        new NewTopic(TOPIC_2, 1, (short) 1))).all().get();
            }

            String config = baseConfigBuilder(proxyAddress, cluster.getBootstrapServers())
                    .addFilter("ProduceRequestTransformation", "transformation", TestEncoder.class.getName())
                    .build();

            var proxy = startProxy(config);
            try {
                try (var producer = new KafkaProducer<String, String>(Map.of(
                        ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, proxyAddress,
                        ProducerConfig.CLIENT_ID_CONFIG, "shouldModifyProduceMessage",
                        ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                        ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                        ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 3_600_000))) {
                    producer.send(new ProducerRecord<>(TOPIC_1, "my-key", PLAINTEXT)).get();
                    producer.send(new ProducerRecord<>(TOPIC_2, "my-key", PLAINTEXT)).get();
                    producer.flush();
                }

                ConsumerRecords<String, byte[]> records1;
                ConsumerRecords<String, byte[]> records2;
                try (var consumer = new KafkaConsumer<String, byte[]>(Map.of(
                        ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, proxyAddress,
                        ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                        ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class,
                        ConsumerConfig.GROUP_ID_CONFIG, "my-group-id",
                        ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"))) {
                    consumer.subscribe(Set.of(TOPIC_1));
                    records1 = consumer.poll(Duration.ofSeconds(10));
                    consumer.subscribe(Set.of(TOPIC_2));
                    records2 = consumer.poll(Duration.ofSeconds(10));
                }

                assertEquals(1, records1.count());
                assertArrayEquals(TOPIC_1_CIPHERTEXT, records1.iterator().next().value());
                assertEquals(1, records2.count());
                assertArrayEquals(TOPIC_2_CIPHERTEXT, records2.iterator().next().value());

            }
            finally {
                // shutdown the proxy
                proxy.shutdown();
            }

        }
    }

    @Test
    public void shouldModifyFetchMessage() throws Exception {
        String proxyAddress = "localhost:9192";
        try (var cluster = KafkaClusterFactory.create(KafkaClusterConfig.builder().testInfo(testInfo).build())) {
            cluster.start();
            try (var admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, cluster.getBootstrapServers()))) {
                admin.createTopics(List.of(
                        new NewTopic(TOPIC_1, 1, (short) 1),
                        new NewTopic(TOPIC_2, 1, (short) 1))).all().get();
            }

            String config = baseConfigBuilder(proxyAddress, cluster.getBootstrapServers())
                    .addFilter("FetchResponseTransformation", "transformation", TestDecoder.class.getName())
                    .build();

            var proxy = startProxy(config);

            try {

                try (var producer = new KafkaProducer<String, byte[]>(Map.of(
                        ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, proxyAddress,
                        ProducerConfig.CLIENT_ID_CONFIG, "shouldModifyFetchMessage",
                        ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                        ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class,
                        ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 3_600_000))) {

                    producer.send(new ProducerRecord<>(TOPIC_1, "my-key", TOPIC_1_CIPHERTEXT)).get();
                    producer.send(new ProducerRecord<>(TOPIC_2, "my-key", TOPIC_2_CIPHERTEXT)).get();
                }

                ConsumerRecords<String, String> records1;
                ConsumerRecords<String, String> records2;
                try (var consumer = new KafkaConsumer<String, String>(Map.of(
                        ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, proxyAddress,
                        ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                        ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                        ConsumerConfig.GROUP_ID_CONFIG, "my-group-id",
                        ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"))) {
                    consumer.subscribe(Set.of(TOPIC_1));

                    records1 = consumer.poll(Duration.ofSeconds(100));

                    consumer.subscribe(Set.of(TOPIC_2));

                    records2 = consumer.poll(Duration.ofSeconds(100));
                }
                assertEquals(1, records1.count());
                assertEquals(1, records2.count());
                assertEquals(List.of(PLAINTEXT, PLAINTEXT),
                        List.of(records1.iterator().next().value(),
                                records2.iterator().next().value()));

            }
            finally {
                // shutdown the proxy
                proxy.shutdown();
            }

        }

    }

    @Test
    public void proxySslClusterPlain() throws Exception {
        String proxyAddress = "localhost:9192";

        var certificateGenerator = new KeytoolCertificateGenerator();
        certificateGenerator.generateSelfSignedCertificateEntry("test@redhat.com", "localhost", "KI", "RedHat", null, null, "US");

        try (var cluster = KafkaClusterFactory.create(KafkaClusterConfig.builder().testInfo(testInfo).build())) {
            cluster.start();

            String config = baseConfigBuilder(proxyAddress, cluster.getBootstrapServers())
                    .withKeyStoreConfig(certificateGenerator.getCertLocation(), certificateGenerator.getPassword())
                    .build();

            var proxy = startProxy(config);
            try {
                try (var admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, proxyAddress,
                        CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, SecurityProtocol.SSL.name,
                        SslConfigs.SSL_TRUSTSTORE_LOCATION_CONFIG, certificateGenerator.getCertLocation(),
                        SslConfigs.SSL_TRUSTSTORE_PASSWORD_CONFIG, certificateGenerator.getPassword()))) {
                    // do some work to ensure connection is opened
                    admin.createTopics(List.of(new NewTopic(TOPIC_1, 1, (short) 1))).all().get();
                    var connectionsMetric = admin.metrics().entrySet().stream().filter(metricNameEntry -> "connections".equals(metricNameEntry.getKey().name()))
                            .findFirst();
                    assertTrue(connectionsMetric.isPresent());
                    var protocol = connectionsMetric.get().getKey().tags().get("protocol");
                    assertThat(protocol).startsWith("TLS");
                }
            }
            finally {
                // shutdown the proxy
                proxy.shutdown();
            }
        }
    }

    private static KroxyConfigBuilder baseConfigBuilder(String proxyAddress, String bootstrapServers) {
        return new KroxyConfigBuilder(proxyAddress)
                .withDefaultCluster(bootstrapServers)
                .addFilter("ApiVersions")
                .addFilter("BrokerAddress");
    }

    private KafkaProxy startProxy(String config) throws InterruptedException {
        Configuration proxyConfig = new ConfigParser().parseConfiguration(config);

        KafkaProxy kafkaProxy = new KafkaProxy(proxyConfig);
        kafkaProxy.startup();

        return kafkaProxy;
    }

}
