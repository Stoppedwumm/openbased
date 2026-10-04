package org.openbased.config;

import java.util.LinkedHashMap;
import java.util.Map;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.OAuthFlow;
import io.swagger.v3.oas.models.security.OAuthFlows;
import io.swagger.v3.oas.models.security.Scopes;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The OpenAPI document is generated from the controllers and DTOs; this only adds global metadata. */
@Configuration
public class OpenApiConfig {

    @Bean
    OpenAPI openBasedOpenApi(OpenBasedProperties properties) {
        Scopes scopes = new Scopes();
        Map<String, String> descriptions = new LinkedHashMap<>();
        org.openbased.security.Scopes.ALL.forEach(s -> descriptions.put(s, s));
        scopes.putAll(descriptions);
        String issuer = properties.getIssuer();
        return new OpenAPI()
                .info(new Info().title("OpenBased API").version("v1")
                        .description("REST API for authentication, users, libraries, media, metadata, streaming, "
                                + "uploads, playback and plugins. The OpenBased web UI uses this same API."))
                .addServersItem(new Server().url(issuer))
                .components(new Components()
                        .addSecuritySchemes("bearer", new SecurityScheme().type(SecurityScheme.Type.HTTP)
                                .scheme("bearer").description("OAuth2 access token or ob_pat_ personal access token"))
                        .addSecuritySchemes("oauth2", new SecurityScheme().type(SecurityScheme.Type.OAUTH2)
                                .flows(new OAuthFlows()
                                        .authorizationCode(new OAuthFlow()
                                                .authorizationUrl(issuer + "/oauth2/authorize")
                                                .tokenUrl(issuer + "/oauth2/token")
                                                .refreshUrl(issuer + "/oauth2/token")
                                                .scopes(scopes))
                                        .clientCredentials(new OAuthFlow()
                                                .tokenUrl(issuer + "/oauth2/token")
                                                .scopes(scopes)))))
                .addSecurityItem(new SecurityRequirement().addList("bearer"))
                .addSecurityItem(new SecurityRequirement().addList("oauth2"));
    }
}
