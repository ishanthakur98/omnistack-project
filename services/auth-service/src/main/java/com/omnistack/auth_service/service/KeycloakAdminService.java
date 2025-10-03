package com.omnistack.auth_service.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.omnistack.auth_service.entity.User;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.util.*;


@Service
public class KeycloakAdminService {

    private static final Logger log = LoggerFactory.getLogger(KeycloakAdminService.class);
    private final RestTemplate rest;
    private final ObjectMapper mapper;

    @Value("${keycloak.base-url}") private String baseUrl;
    @Value("${keycloak.realm}") private String realm;
    @Value("${keycloak.client-id}") private String clientId;
    @Value("${keycloak.client-secret}") private String clientSecret;

    private String adminAccessToken;
    private long tokenExpiryMillis = 0L;

    public KeycloakAdminService(RestTemplate rest, ObjectMapper mapper) {
        this.rest = rest;
        this.mapper = mapper;
    }

    // Obtain admin token using client_credentials (service account client)
    private void ensureAdminToken() {
        if (adminAccessToken != null && System.currentTimeMillis() < tokenExpiryMillis - 60_000) {
            log.info("adminAccessToken is null !!!");
            return;
        }
        String tokenUrl = String.format("%s/realms/%s/protocol/openid-connect/token", baseUrl, realm);
        LinkedMultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", clientId);
        form.add("client_secret", clientSecret);

        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        HttpEntity<LinkedMultiValueMap<String,String>> request = new HttpEntity<>(form, h);

        ResponseEntity<String> resp = rest.postForEntity(tokenUrl, request, String.class);
        if (resp.getStatusCode().is2xxSuccessful()) {
            try {
                log.info("adminAccessToken Response From Keyclock {}",resp.getBody());
                JsonNode node = mapper.readTree(resp.getBody());
                adminAccessToken = node.get("access_token").asText();
                long exp = node.get("expires_in").asLong() * 1000L;
                tokenExpiryMillis = System.currentTimeMillis() + exp;
            } catch (Exception ex) {
                throw new RuntimeException("Failed parsing Keycloak token response", ex);
            }
        } else {
            throw new RuntimeException("Failed to obtain Keycloak admin token: " + resp.getStatusCode());
        }
    }

    public Optional<String> findUserIdByUsername(String username) {
        ensureAdminToken();
        String url = String.format("%s/admin/realms/%s/users?username=%s", baseUrl, realm, username);
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(adminAccessToken);
        HttpEntity<Void> ent = new HttpEntity<>(headers);
        ResponseEntity<String> resp = rest.exchange(url, HttpMethod.GET, ent, String.class);
        if (!resp.getStatusCode().is2xxSuccessful()) return Optional.empty();
        try {
            log.info("findUserIdByUsername Response from keyclock {}",resp.getBody());
            JsonNode arr = mapper.readTree(resp.getBody());
            if (arr.isArray() && arr.size() > 0) {
                return Optional.of(arr.get(0).get("id").asText());
            }
        } catch (Exception e) { }
        return Optional.empty();
    }

    public String createKeycloakUser(User profile, String password) {
        ensureAdminToken();
        String url = String.format("%s/admin/realms/%s/users", baseUrl, realm);

        // --- Create User Payload ---
        Map<String, Object> payload = new HashMap<>();
        payload.put("username", profile.getUsername());
        payload.put("email", profile.getEmail());
        payload.put("enabled", true);
        payload.put("emailVerified", true);

        // credentials
        Map<String, Object> cred = new HashMap<>();
        cred.put("type", "password");
        cred.put("value", password);
        cred.put("temporary", false);
        payload.put("credentials", List.of(cred));

        log.info("create user req {}", payload);

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(adminAccessToken);
        headers.setContentType(MediaType.APPLICATION_JSON);

        HttpEntity<Map<String, Object>> ent = new HttpEntity<>(payload, headers);
        ResponseEntity<String> resp = rest.postForEntity(url, ent, String.class);

        if (resp.getStatusCode().is2xxSuccessful() || resp.getStatusCode() == HttpStatus.CREATED) {
            log.info("createKeycloakUser Response from Keycloak {}", resp.getBody());

            // get userId
            String userId = null;
            List<String> loc = resp.getHeaders().get("Location");
            if (loc != null && !loc.isEmpty()) {
                String location = loc.get(0);
                userId = location.substring(location.lastIndexOf('/') + 1);
            } else {
                userId = findUserIdByUsername(profile.getUsername())
                        .orElseThrow(() -> new RuntimeException("Failed to find user after creation"));
            }

            // --- Clear required actions ---
            String updateUrl = String.format("%s/admin/realms/%s/users/%s", baseUrl, realm, userId);

            Map<String, Object> updatePayload = new HashMap<>();
            updatePayload.put("requiredActions", List.of()); // ✅ remove required actions

            HttpEntity<Map<String, Object>> updateEntity = new HttpEntity<>(updatePayload, headers);
            rest.exchange(updateUrl, HttpMethod.PUT, updateEntity, Void.class);

            log.info("User {} created and required actions cleared", userId);

            return userId;
        } else {
            throw new RuntimeException("Keycloak user creation failed: "
                    + resp.getStatusCode() + " body:" + resp.getBody());
        }
    }

}
