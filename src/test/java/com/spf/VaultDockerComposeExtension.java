package com.spf;

import java.io.File;
import java.time.Duration;
import java.util.Set;

import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

import org.springframework.boot.docker.compose.core.DockerCompose;
import org.springframework.boot.docker.compose.core.DockerComposeFile;
import org.springframework.boot.logging.LogLevel;

/**
 * Starts {@code compose.yaml} with Spring Boot's own Docker Compose support before the
 * Spring test context is created, and stops it afterwards.
 * The same Spring Boot API is used ({@code docker compose up --detach --wait}), it is only
 * triggered earlier.
 */
class VaultDockerComposeExtension implements BeforeAllCallback, AfterAllCallback {

	private static final DockerCompose dockerCompose = DockerCompose
		.get(DockerComposeFile.of(new File("compose.yaml")), null, Set.of());

	@Override
	public void beforeAll(ExtensionContext context) {
		dockerCompose.up(LogLevel.INFO);
	}

	@Override
	public void afterAll(ExtensionContext context) {
		dockerCompose.down(Duration.ofSeconds(10));
	}

}
