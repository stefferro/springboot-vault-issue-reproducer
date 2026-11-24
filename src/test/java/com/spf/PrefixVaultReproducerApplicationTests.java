package com.spf;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Starts Vault + the provisioner (compose.yaml), boots the application which imports
 * {@code vault://secret/my/path?prefix=foo.} and checks that the provisioned secret is
 * exposed under the prefixed property name.
 * <p>
 * Passes with Spring Cloud 2025.1.4, fails at startup with 2025.1.5 up to 2026.0.0.
 */
@ExtendWith(VaultDockerComposeExtension.class)
@SpringBootTest
class PrefixVaultReproducerApplicationTests {

	@Autowired
	Environment environment;

	@Value("${app.secret}")
	String appSecret;

	@Test
	void secretIsImportedFromVaultWithPrefix() {
		assertThat(environment.getProperty("foo.secret")).isEqualTo("my-secret-value");
		assertThat(appSecret).isEqualTo("my-secret-value");
		// The un-prefixed key must not be exposed.
		assertThat(environment.containsProperty("secret")).isFalse();
	}

}
