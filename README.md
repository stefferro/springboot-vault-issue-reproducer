# Spring Cloud Vault: `?prefix=` in a `vault://` config location breaks startup

Minimal reproducer for a regression in Spring Cloud Vault / Spring Vault: a contextual
`vault://` location that uses the `prefix` query parameter, as documented in the
[Spring Cloud Vault reference (ConfigData API)](https://docs.spring.io/spring-cloud-vault/reference/config-data.html),
no longer loads any secret, and the application fails at startup.

## Context

The reference documentation describes a `prefix` query parameter for contextual locations.
It keeps secrets with the same name in different paths from shadowing each other:

```yaml
spring.config.import: vault://my/path?prefix=foo., vault://my/other/path?prefix=bar.
secret: ${foo.secret}
other.secret: ${bar.secret}
```

This project does exactly that ([`application.yaml`](src/main/resources/application.yaml)):

```yaml
spring:
  config:
    import: vault://secret/my/path?prefix=foo.

app:
  secret: ${foo.secret}
```

## Affected versions

| Spring Cloud release train | spring-cloud-vault-config | spring-vault-core | Result |
|---|---|---|---|
| 2025.1.4 | 5.0.3 | 4.0.4 | ✅ works: `foo.secret` is resolved |
| 2025.1.5 | 5.0.4 | 4.0.5 | ❌ fails |
| 2026.0.0 (default in this project) | 5.1.0 | 4.1.2 | ❌ fails |

All later versions up to 2026.0.0 fail. The project itself uses Spring Boot **4.2.0** and Java 25.

## Expected behavior

The keys stored at `secret/my/path` are exposed as `foo.<key>` (here `foo.secret=my-secret-value`).

## Actual behavior

Spring Cloud Vault forwards the whole location, query string included, to Spring Vault as
the secret path. Spring Vault now validates path segments and rejects it:

```
WARN  LeaseEventPublisher$LoggingErrorListener : [RequestedSecret [path='secret/my/path?prefix=foo.', mode=ROTATE]] ...

java.lang.IllegalArgumentException: Path segment must not contain control characters: 'my/path?prefix=foo.'
	at org.springframework.vault.client.VaultClients.validateConcat(VaultClients.java:230)
	at org.springframework.vault.client.VaultClients.concatPath(VaultClients.java:192)
	at org.springframework.vault.core.util.KeyValueDelegate.getKeyValue2Path(KeyValueDelegate.java:99)
	at org.springframework.vault.core.util.KeyValueDelegate.getSecret(KeyValueDelegate.java:89)
	at org.springframework.vault.core.lease.SecretLeaseContainer.doGetSecrets(SecretLeaseContainer.java:665)
	at org.springframework.vault.core.lease.SecretLeaseContainer.doStart(SecretLeaseContainer.java:404)
	...
	at org.springframework.vault.core.env.LeaseAwareVaultPropertySource.<init>(LeaseAwareVaultPropertySource.java:164)
	at org.springframework.cloud.vault.config.VaultConfigDataLoader.createLeasingPropertySource(VaultConfigDataLoader.java:293)
	at org.springframework.cloud.vault.config.VaultConfigDataLoader.lambda$loadConfigData$1(VaultConfigDataLoader.java:166)
	at org.springframework.cloud.vault.config.VaultConfigDataLoader.createConfigData(VaultConfigDataLoader.java:235)
```

(Stack trace from 2025.1.5. With 2026.0.0 only the INFO line below is logged by default.)

The error is swallowed and only logged at INFO level:

```
INFO  LeaseAwareVaultPropertySource : Vault location [secret/my/path?prefix=foo.] not resolvable: Path segment must not contain control characters: 'my/path?prefix=foo.'
```

The `LeaseAwareVaultPropertySource` is registered but empty. The first placeholder that needs
a Vault value then fails:

```
Caused by: org.springframework.util.PlaceholderResolutionException: Could not resolve placeholder 'foo.secret' in value "${foo.secret}" <-- "${app.secret}"
```

With 2025.1.4 the path also contained `?prefix=foo.`. The older Spring Vault simply did not
reject it, and the property source exposed `[foo.secret]` as expected.

## Project layout

| File | Purpose |
|---|---|
| [`compose.yaml`](compose.yaml) | `vault`: HashiCorp Vault in dev mode (root token `root-token`, KV v2 at `secret/`).<br>`vault-provisioner`: writes `secret=my-secret-value` to `secret/my/path`, then reports healthy. |
| [`application.yaml`](src/main/resources/application.yaml) | Imports `vault://secret/my/path?prefix=foo.` and maps `app.secret` to `${foo.secret}`. |
| [`VaultDockerComposeExtension`](src/test/java/com/spf/VaultDockerComposeExtension.java) | JUnit extension that starts and stops `compose.yaml` with Spring Boot's Docker Compose support (`DockerCompose` API). |
| [`PrefixVaultReproducerApplicationTests`](src/test/java/com/spf/PrefixVaultReproducerApplicationTests.java) | Boots the application and asserts that `foo.secret` and `app.secret` equal `my-secret-value`. |

### Why the test starts Docker Compose itself

Spring Boot's Docker Compose integration (`spring-boot-docker-compose`) starts the services on
`ApplicationPreparedEvent`. That event comes **after** `spring.config.import` has been processed,
so Vault would not be reachable yet when the `vault://` location is loaded. Docker Compose
support is also skipped in tests by default (`spring.docker.compose.skip.in-tests=true`).

The extension therefore calls the same Spring Boot API (`docker compose up --detach --wait`)
in `@BeforeAll`, before the test context is created. Because `--wait` fails when a container
exits, the provisioner stays alive and its healthcheck only passes once the secret is written.

## Running

Requirements: Java 25 and a Docker-compatible engine with `docker compose` (or `docker-compose`).

Reproduce the failure (Spring Cloud 2026.0.0):

```bash
./mvnw test
```

Compare with the last working release train. Spring Cloud 2025.1.x does not officially support
Spring Boot 4.2, so the compatibility verifier must be turned off:

```bash
./mvnw test -Dspring-cloud.version=2025.1.4 -Dspring.cloud.compatibility-verifier.enabled=false
```

| Command | Result |
|---|---|
| `./mvnw test` | ❌ `Could not resolve placeholder 'foo.secret'` |
| `./mvnw test -Dspring-cloud.version=2025.1.5 -Dspring.cloud.compatibility-verifier.enabled=false` | ❌ same error |
| `./mvnw test -Dspring-cloud.version=2025.1.4 -Dspring.cloud.compatibility-verifier.enabled=false` | ✅ test passes |

To see the full stack trace on 2026.0.0, add `-Dlogging.level.org.springframework.vault=DEBUG`.

To inspect Vault manually:

```bash
docker compose up -d --wait
```

```bash
docker compose exec -e VAULT_TOKEN=root-token -e VAULT_ADDR=http://127.0.0.1:8200 vault vault kv get secret/my/path
```

```bash
docker compose down
```
