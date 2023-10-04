package org.djar.football.tests;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.CREATED;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.Charset;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.djar.football.model.event.Event;
import org.djar.football.model.view.MatchScore;
import org.djar.football.model.view.PlayerCards;
import org.djar.football.model.view.PlayerGoals;
import org.djar.football.model.view.TeamRanking;
import org.djar.football.model.view.TopPlayers;
import org.djar.football.stream.JsonPojoSerde;
import org.djar.football.tests.utils.DockerCompose;
import org.djar.football.tests.utils.WebSocket;
import org.djar.football.util.Topics;
import org.postgresql.Driver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.util.StreamUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

public class FootballEcosystem {

    private static final Logger logger = LoggerFactory.getLogger(FootballEcosystem.class);

    private long eventTimeout = 10000;
    private long restTimeout = 10000;
    private long startupTimeout = 180000;

    private DockerCompose dockerCompose;
    private RestTemplate rest;
    private JdbcTemplate postgres;
    private WebSocket webSocket;

    private final Properties consumerProps = new Properties();

    private boolean started;

    public long getEventTimeout() {
        return eventTimeout;
    }

    public void setEventTimeout(long eventTimeout) {
        this.eventTimeout = eventTimeout;
    }

    public long getRestTimeout() {
        return restTimeout;
    }

    public void setRestTimeout(long restTimeout) {
        this.restTimeout = restTimeout;
    }

    public long getStartupTimeout() {
        return startupTimeout;
    }

    public void setStartupTimeout(long startupTimeout) {
        this.startupTimeout = startupTimeout;
    }

    public void start() {
        dockerCompose = new DockerCompose()
            .addHealthCheck("http://football-match:18081/actuator/health", "\\{\"status\":\"UP\"\\}")
            .addHealthCheck("http://football-player:18082/actuator/health", "\\{\"status\":\"UP\"\\}")
            .addHealthCheck("http://football-view-basic:18083/actuator/health", "\\{\"status\":\"UP\"\\}")
            .addHealthCheck("http://football-view-top:18084/actuator/health", "\\{\"status\":\"UP\"\\}")
            .addHealthCheck("http://football-ui:18080/actuator/health", "\\{\"status\":\"UP\"\\}")
            // create the connector asap, probably before the other services
            // to avoid the problem of missing records - probably lost in the snapshot
            .addHealthCheck("http://connect:8083/connectors", "\\[.*\\]", this::connectorCreated);

        rest = new RestTemplate(new HttpComponentsClientHttpRequestFactory());

        webSocket = new WebSocket("ws://football-ui:18080/dashboard");
        webSocket.subscribe("/topic/MatchScore", MatchScore.class);
        webSocket.subscribe("/topic/TeamRanking", TeamRanking.class);
        webSocket.subscribe("/topic/PlayerGoals", PlayerGoals.class);
        webSocket.subscribe("/topic/PlayerCards", PlayerCards.class);
        webSocket.subscribe("/topic/TopPlayers", TopPlayers.class);

        consumerProps.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, "kafka:9092");
        consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        consumerProps.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, 20000);
        consumerProps.put(ConsumerConfig.GROUP_ID_CONFIG, getClass().getName());
        consumerProps.put(ConsumerConfig.CLIENT_ID_CONFIG, getClass().getName());

//        dockerCompose.up();
//        dockerCompose.waitUntilServicesAreAvailable(startupTimeout, MILLISECONDS);

//        webSocket.connect();

        postgres = new JdbcTemplate(new SimpleDriverDataSource(new Driver(),
                "jdbc:postgresql://postgres:5432/postgres", "postgres", "postgres"));

        started = true;
    }

    private void connectorCreated() {
        // create database and table
        postgres = new JdbcTemplate(new SimpleDriverDataSource(new Driver(),
            "jdbc:postgresql://postgres:5432/postgres", "postgres", "postgres"));
        createPlayersTable();
        createConnector("http://connect:8083/connectors/", "football-connector.json");
        sleep(2000); // some time to init the connector
    }

    public boolean isStarted() {
        return started;
    }

    public void shutdown() {
        dockerCompose.down();
        webSocket.disconnect();
        started = false;
    }

    public HttpStatus command(String url, HttpMethod method, String json, HttpStatus retryStatus) {
        logger.trace(json);
        HttpStatus currentStatus;
        long endTime = System.currentTimeMillis() + restTimeout;

        do {
            currentStatus = command(url, method, json);

            if (!currentStatus.equals(retryStatus)) {
                return currentStatus;
            }
            logger.trace("Retry status received ({}), trying again...", retryStatus);
            sleep(500);
        } while (System.currentTimeMillis() < endTime);

        throw new AssertionError("Response timeout, last status: " + currentStatus);
    }

    public HttpStatus command(String url, HttpMethod method, String json) {
        logger.trace(json);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        try {
            return rest.exchange(url, method, new HttpEntity<>(json, headers), String.class).getStatusCode();
        } catch (HttpClientErrorException e) {
            return e.getStatusCode();
        }
    }

    public <T> T query(String url, Class<T> responseType, int expectedResultCount) throws IOException {
        long timeout = System.currentTimeMillis() + restTimeout;
        int resultSize = -1;

        do {
            var response = rest.getForEntity(url, String.class);
            logger.trace(response.getBody());
            T result = new ObjectMapper().readerFor(responseType).readValue(response.getBody());
            resultSize = ((Object[]) result).length;

            if (resultSize == expectedResultCount) {
                return result;
            }
            logger.trace(resultSize + " items received, trying again...");
            sleep(500);
        } while (System.currentTimeMillis() > timeout);

        throw new AssertionError("Expected items: " + expectedResultCount + ", actual: " + resultSize);
    }

    public <T extends Event> T waitForEvent(Class<T> type) {
        return waitForEvents(type, 1).get(0);
    }

    public <T extends Event> List<T> waitForEvents(Class<T> type, int expectedEventCount) {
        var consumer = new KafkaConsumer<String, T>(consumerProps, new StringDeserializer(),
                new JsonPojoSerde<T>(type));

        try {
            String topic = Topics.eventTopicName(type);
            consumer.subscribe(Collections.singletonList(topic));
            var found = new ArrayList<T>(expectedEventCount);
            long timeout = eventTimeout;
            long endTime = System.currentTimeMillis() + timeout;

            do {
                for (ConsumerRecord<String, T> record : consumer.poll(timeout)) {
                    found.add(record.value());
                }
                timeout = endTime - System.currentTimeMillis();
            } while (found.size() < expectedEventCount && timeout > 0);

            if (found.size() < expectedEventCount) {
                throw new RuntimeException("The expected number of waitForEvents in topic " + topic + " should be: "
                    + expectedEventCount + ", but found: " + found);
            }
            if (found.size() > expectedEventCount) {
                logger.warn("Some redundant waitForEvents have been found in topic {}: {}", topic, found);
            }
            return found;
        } finally {
            consumer.close();
        }
    }

    public <T> T waitForWebSocketEvent(Class<T> type) {
        Object event = webSocket.readLast(type, eventTimeout, MILLISECONDS);

        if (event == null) {
            throw new AssertionError("The expected WebSocket event " + type.getSimpleName() + " was not found");
        }
        if (!type.isInstance(event)) {
            throw new RuntimeException("The expected WebSocket event is " + type.getSimpleName()
                + ", but found: " + event.getClass());
        }
        return (T)event;
    }

    public <T> List<T> waitForWebSocketEvent(Class<T> type, int expectedEventCount) {
        var events = webSocket.readAll(type, expectedEventCount, eventTimeout, TimeUnit.MILLISECONDS);

        if (events.size() < expectedEventCount) {
            throw new RuntimeException("The expected number of WebSocket waitForEvents " + type
                + " should be: " + expectedEventCount + ", but found: " + events);
        }
        return events;
    }

    public void createConnector(String connectorRestApiUrl, String request) {
        String json;

        try {
            json = StreamUtils.copyToString(
                getClass().getClassLoader().getResourceAsStream(request), Charset.defaultCharset());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        try {
            ResponseEntity<String> response = rest.exchange(connectorRestApiUrl, POST, new HttpEntity<>(json, headers), String.class);
            HttpStatus status = response.getStatusCode();

            if (status != CREATED) {
                throw new RuntimeException("Unable to create Kafka connector, HTTP status: " + status);
            }
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() != CONFLICT) {
                throw new RuntimeException("Unable to create Kafka connector, HTTP status: " + e.getStatusCode());
            }
            logger.warn("Connector already exists - response: {}", e.getMessage());
        }
    }

    public void createPlayersTable() {
        postgres.execute("CREATE TABLE IF NOT EXISTS players"
                + "(id bigint PRIMARY KEY, name varchar(50) NOT NULL, created timestamp NOT NULL)");
    }

    public void insertPlayer(Integer playerId, String name) {
        postgres.update("INSERT INTO players VALUES (?, ?, ?) ON CONFLICT DO NOTHING",
                playerId, name, new Timestamp(System.currentTimeMillis()));
    }

    public void executeSql(String sql) {
        postgres.update(sql);
    }

    private void sleep(long time) {
        try {
            Thread.sleep(time);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupted();
            throw new RuntimeException(e);
        }
    }
}
