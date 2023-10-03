
### Fire fb-event.match-started

```bash
curl -v -X POST -H "Content-Type: application/json" \
    http://localhost:18081/command/matches \
    -d '{"id":"1","reqTimestamp":"2015-08-05T18:45:00","seasonId":"Championship 2015/2016","matchDate":"2015-08-07T18:45:00","homeClubId":"Brighton & Hove Albion","awayClubId":"Nottingham Forest"}'
```

### Create Debezium connector for PostgreSQL

```bash
curl -v -X POST -H "Content-Type: application/json" \
    http://localhost:8083/connectors \
    -d '{"name": "football-connector","config": {"connector.class": "io.debezium.connector.postgresql.PostgresConnector","tasks.max": "1","database.hostname": "postgres","database.port": "5432","database.user": "postgres","database.password": "postgres","database.dbname" : "postgres","database.server.name": "fb-connect","table.whitelist": "public.players","topic.prefix": "fb-connect","database.history.kafka.bootstrap.servers": "kafka:9092","database.history.kafka.topic": "fb-connect.schema-changes","snapshot.mode": "never"}}'
```
