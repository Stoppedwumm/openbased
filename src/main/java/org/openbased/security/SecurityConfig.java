package org.openbased.security;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;

import org.openbased.common.ErrorWriter;
import org.openbased.security.oauth.JpaAuthorizationService;
import org.openbased.security.oauth.PublicClientAuthentication;
import org.openbased.security.oauth.PublicClientRefreshTokenGenerator;
import org.openbased.security.oauth.RefreshTokenReuseDetector;
import org.openbased.security.oauth.UsedRefreshTokenRepository;
import org.openbased.config.OpenBasedProperties;
import org.openbased.token.TokenService;
import org.openbased.user.User;
import org.openbased.user.UserRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.core.oidc.OidcScopes;
import org.springframework.security.oauth2.core.oidc.endpoint.OidcParameterNames;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.client.InMemoryRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.config.annotation.web.configurers.OAuth2AuthorizationServerConfigurer;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.authorization.token.DelegatingOAuth2TokenGenerator;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.JwtGenerator;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenGenerator;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.util.matcher.MediaTypeRequestMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Security configuration with three filter chains:
 * <ol>
 * <li>the OAuth2/OIDC authorization server ({@code /oauth2/*}, {@code /.well-known/*});</li>
 * <li>the stateless REST API ({@code /api/**}), authenticated with bearer tokens only;</li>
 * <li>the interactive sign-in page used during the authorization code flow.</li>
 * </ol>
 * The web UI uses the same API and tokens as every other client.
 */
@Configuration
public class SecurityConfig {

    static final String TOKEN_USE_CLAIM = "token_use";

    @Bean
    @Order(1)
    SecurityFilterChain authorizationServerChain(HttpSecurity http, OpenBasedProperties properties,
            AuthorizationServerSettings settings, RegisteredClientRepository clients,
            UsedRefreshTokenRepository usedRefreshTokens, JpaAuthorizationService authorizations) throws Exception {
        OAuth2AuthorizationServerConfigurer authorizationServer = OAuth2AuthorizationServerConfigurer.authorizationServer();
        http.securityMatcher(authorizationServer.getEndpointsMatcher())
                .with(authorizationServer, server -> server
                        // Public clients (native and TV apps) refresh and revoke tokens with only their client_id.
                        .clientAuthentication(client -> client
                                .authenticationConverters(c -> c.add(0, new PublicClientAuthentication.Converter(settings)))
                                .authenticationProviders(p -> p.add(0, new PublicClientAuthentication.Provider(clients))))
                        .tokenEndpoint(token -> token.authenticationProviders(
                                p -> p.add(0, new RefreshTokenReuseDetector(usedRefreshTokens, authorizations))))
                        .oidc(oidc -> oidc
                                .providerConfigurationEndpoint(endpoint -> endpoint.providerConfigurationCustomizer(
                                        config -> config.userInfoEndpoint(properties.getIssuer() + "/api/v1/userinfo")))))
                .authorizeHttpRequests(authorize -> authorize.anyRequest().authenticated())
                .cors(Customizer.withDefaults())
                .exceptionHandling(exceptions -> exceptions.defaultAuthenticationEntryPointFor(
                        new LoginUrlAuthenticationEntryPoint("/login"),
                        new MediaTypeRequestMatcher(MediaType.TEXT_HTML)))
                .oauth2ResourceServer(resourceServer -> resourceServer.jwt(Customizer.withDefaults()));
        return http.build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain apiChain(HttpSecurity http, TokenService tokens, UserRepository users, JwtDecoder jwtDecoder,
            ErrorWriter errors, OpenBasedProperties properties, JpaAuthorizationService authorizations) throws Exception {
        ApiErrorHandlers handlers = new ApiErrorHandlers(errors);
        ApiAuthenticationManager authenticationManager = new ApiAuthenticationManager(tokens, users, jwtDecoder,
                authorizations);
        http.securityMatcher("/api/**")
                .csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(cache -> cache.disable())
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(HttpMethod.OPTIONS, "/api/**").permitAll()
                        // Devices start linking and poll for their token before they have credentials.
                        .requestMatchers(HttpMethod.POST, "/api/v1/device-links", "/api/v1/device-links/token").permitAll()
                        .requestMatchers("/api/v1/openapi.json", "/api/v1/openapi.json/**", "/api/v1/docs",
                                "/api/v1/docs/**", "/api/v1/swagger-ui/**").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .bearerTokenResolver(new ApiBearerTokenResolver())
                        .authenticationManagerResolver(request -> authenticationManager)
                        .authenticationEntryPoint(handlers)
                        .accessDeniedHandler(handlers))
                .exceptionHandling(exceptions -> exceptions.authenticationEntryPoint(handlers).accessDeniedHandler(handlers))
                .addFilterAfter(new RateLimitFilter(properties.getRateLimit(), errors), BearerTokenAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    @Order(3)
    SecurityFilterChain signInChain(HttpSecurity http) throws Exception {
        http.authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/login", "/error", "/swagger-ui/**", "/swagger-ui.html", "/favicon.ico").permitAll()
                        // The web UI is a static client of the public API; it authenticates with OAuth2 like any other.
                        .requestMatchers("/", "/index.html", "/callback", "/app/**").permitAll()
                        .anyRequest().authenticated())
                .formLogin(Customizer.withDefaults())
                // The web UI renews tokens by loading its own /callback page in a same-origin iframe.
                .headers(headers -> headers.frameOptions(frame -> frame.sameOrigin()));
        return http.build();
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(OpenBasedProperties properties) {
        CorsConfiguration cors = new CorsConfiguration();
        cors.setAllowedOrigins(properties.getCorsAllowedOrigins());
        cors.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        cors.setAllowedHeaders(List.of("Authorization", "Content-Type", "Content-Range", "Range"));
        cors.setExposedHeaders(List.of("Content-Range", "Accept-Ranges", "Content-Length", "X-Request-Id",
                "X-RateLimit-Limit", "X-RateLimit-Remaining", "X-RateLimit-Reset", "Retry-After"));
        cors.setMaxAge(Duration.ofHours(1));
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", cors);
        source.registerCorsConfiguration("/oauth2/token", cors);
        source.registerCorsConfiguration("/.well-known/**", cors);
        source.registerCorsConfiguration("/oauth2/jwks", cors);
        return source;
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    JWKSource<SecurityContext> jwkSource(OpenBasedProperties properties) {
        RSAKey key = JwkStore.loadOrCreate(properties.getDataDir());
        return new ImmutableJWKSet<>(new JWKSet(key));
    }

    /** Validates access tokens issued by this server. ID tokens are rejected as bearer tokens. */
    @Bean
    JwtDecoder jwtDecoder(JWKSource<SecurityContext> jwkSource, OpenBasedProperties properties) {
        NimbusJwtDecoder decoder = (NimbusJwtDecoder) org.springframework.security.oauth2.server.authorization.config
                .annotation.web.configuration.OAuth2AuthorizationServerConfiguration.jwtDecoder(jwkSource);
        OAuth2TokenValidator<Jwt> accessTokensOnly = jwt -> "access".equals(jwt.getClaimAsString(TOKEN_USE_CLAIM))
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Not an access token", null));
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(properties.getIssuer()), accessTokensOnly));
        return decoder;
    }

    @Bean
    AuthorizationServerSettings authorizationServerSettings(OpenBasedProperties properties) {
        return AuthorizationServerSettings.builder().issuer(properties.getIssuer()).build();
    }

    /**
     * Access and ID tokens are JWTs; refresh tokens are also issued to public clients registered for the
     * {@code refresh_token} grant (see {@link PublicClientRefreshTokenGenerator}).
     */
    @Bean
    OAuth2TokenGenerator<?> tokenGenerator(JWKSource<SecurityContext> jwkSource,
            OAuth2TokenCustomizer<JwtEncodingContext> tokenCustomizer) {
        JwtGenerator jwtGenerator = new JwtGenerator(new NimbusJwtEncoder(jwkSource));
        jwtGenerator.setJwtCustomizer(tokenCustomizer);
        return new DelegatingOAuth2TokenGenerator(jwtGenerator, new PublicClientRefreshTokenGenerator());
    }

    @Bean
    RegisteredClientRepository registeredClientRepository(OpenBasedProperties properties, PasswordEncoder encoder) {
        List<RegisteredClient> clients = new ArrayList<>();
        for (OpenBasedProperties.Client client : properties.getClients()) {
            clients.add(toRegisteredClient(client, properties, encoder));
        }
        // Built-in clients for official apps. They are registered in code rather than application.yml because
        // an installation's own client list replaces the default list entirely.
        for (OpenBasedProperties.Client builtin : builtinClients(properties)) {
            if (clients.stream().noneMatch(c -> c.getClientId().equals(builtin.getClientId()))) {
                clients.add(toRegisteredClient(builtin, properties, encoder));
            }
        }
        if (clients.isEmpty()) {
            // A registry needs at least one client; this placeholder cannot complete any flow.
            OpenBasedProperties.Client placeholder = new OpenBasedProperties.Client();
            placeholder.setClientId("openbased-unconfigured");
            placeholder.setRedirectUris(List.of("http://127.0.0.1/unconfigured"));
            placeholder.setGrantTypes(Set.of("authorization_code"));
            clients.add(toRegisteredClient(placeholder, properties, encoder));
        }
        return new InMemoryRegisteredClientRepository(clients);
    }

    static final String ANDROID_CLIENT_ID = "openbased-android";
    static final String ANDROID_REDIRECT_URI = "org.openbased.android:/oauth2redirect";

    static List<OpenBasedProperties.Client> builtinClients(OpenBasedProperties properties) {
        List<OpenBasedProperties.Client> builtins = new ArrayList<>();
        if (properties.getBuiltinClients().isAndroid()) {
            OpenBasedProperties.Client android = new OpenBasedProperties.Client();
            android.setClientId(ANDROID_CLIENT_ID);
            android.setName("OpenBased for Android");
            // A private-use URI scheme (RFC 8252 §7.1) works for every installation, whatever its address.
            android.setRedirectUris(List.of(ANDROID_REDIRECT_URI));
            android.setGrantTypes(Set.of("authorization_code", "refresh_token"));
            builtins.add(android);
        }
        return builtins;
    }

    private static RegisteredClient toRegisteredClient(OpenBasedProperties.Client client, OpenBasedProperties properties,
            PasswordEncoder encoder) {
        Set<String> scopes = client.getScopes() == null || client.getScopes().isEmpty() ? Scopes.ALL : client.getScopes();
        RegisteredClient.Builder builder = RegisteredClient.withId(UUID.nameUUIDFromBytes(client.getClientId().getBytes()).toString())
                .clientId(client.getClientId())
                .clientName(client.getName() != null ? client.getName() : client.getClientId())
                .scopes(s -> s.addAll(scopes))
                .clientSettings(ClientSettings.builder()
                        .requireProofKey(client.isPublicClient())
                        .requireAuthorizationConsent(client.isRequireConsent())
                        .build())
                .tokenSettings(TokenSettings.builder()
                        .accessTokenTimeToLive(properties.getTokens().getAccessTokenTtl())
                        .refreshTokenTimeToLive(properties.getTokens().getRefreshTokenTtl())
                        .authorizationCodeTimeToLive(Duration.ofMinutes(5))
                        .reuseRefreshTokens(false)
                        .build());
        if (client.isPublicClient()) {
            builder.clientAuthenticationMethod(ClientAuthenticationMethod.NONE);
        } else {
            builder.clientSecret(encoder.encode(client.getClientSecret()))
                    .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                    .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST);
        }
        for (String grant : client.getGrantTypes()) {
            builder.authorizationGrantType(new AuthorizationGrantType(grant));
        }
        client.getRedirectUris().forEach(builder::redirectUri);
        client.getPostLogoutRedirectUris().forEach(builder::postLogoutRedirectUri);
        return builder.build();
    }

    /**
     * Sets {@code sub} to the user ID, narrows the token's scopes to the user's permissions and marks
     * access tokens so they cannot be confused with ID tokens.
     */
    @Bean
    OAuth2TokenCustomizer<JwtEncodingContext> tokenCustomizer(UserRepository users, OpenBasedProperties properties) {
        return context -> {
            String clientId = context.getRegisteredClient().getClientId();
            User user;
            if (AuthorizationGrantType.CLIENT_CREDENTIALS.equals(context.getAuthorizationGrantType())) {
                user = properties.client(clientId).map(OpenBasedProperties.Client::getServiceUser)
                        .flatMap(users::findByUsername).orElse(null);
            } else {
                user = users.findByUsername(context.getPrincipal().getName()).orElse(null);
            }
            context.getClaims().subject(user != null ? user.getId() : "client:" + clientId);

            if (OAuth2TokenType.ACCESS_TOKEN.equals(context.getTokenType())) {
                context.getClaims().claim(TOKEN_USE_CLAIM, "access");
                context.getClaims().claim("client_id", clientId);
                if (user != null) {
                    Set<String> granted = new LinkedHashSet<>(context.getAuthorizedScopes());
                    granted.retainAll(user.permissions());
                    context.getClaims().claim("scope", granted);
                }
            } else if (OidcParameterNames.ID_TOKEN.equals(context.getTokenType().getValue()) && user != null) {
                Set<String> scopes = context.getAuthorizedScopes();
                if (scopes.contains(OidcScopes.PROFILE)) {
                    context.getClaims().claim("name", user.getDisplayName());
                    context.getClaims().claim("preferred_username", user.getUsername());
                }
                if (scopes.contains(OidcScopes.EMAIL) && user.getEmail() != null) {
                    context.getClaims().claim("email", user.getEmail());
                }
            }
        };
    }
}
