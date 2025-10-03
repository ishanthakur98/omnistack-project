package com.omnistack.auth_service.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.omnistack.auth_service.dao.UserRepo;
import com.omnistack.auth_service.dto.LoginRequest;
import com.omnistack.auth_service.dto.RegisterUser;
import com.omnistack.auth_service.entity.User;
import com.omnistack.auth_service.exception.UnauthorizedException;
import com.omnistack.auth_service.exception.UserAlreadyPresentException;
import com.omnistack.auth_service.exception.UserNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
public class AuthService {

    @Value("${keycloak.base-url}") private String baseUrl;
    @Value("${keycloak.realm}") private String realm;
    @Value("${keycloak.client-id}") private String clientId;
    @Value("${keycloak.client-secret}") private String clientSecret;

    private final RestTemplate restTemplate;
    private final ObjectMapper mapper;

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);
    private final UserRepo userRepo;

    private final KeycloakAdminService keycloakAdminService;

    public AuthService(RestTemplate restTemplate, ObjectMapper mapper, UserRepo userRepo, KeycloakAdminService keycloakAdminService) {
        this.restTemplate = restTemplate;
        this.mapper = mapper;
        this.userRepo = userRepo;
        this.keycloakAdminService = keycloakAdminService;
    }

    public User register(RegisterUser req) throws Exception{
        if(userRepo.findByUsername(req.getUsername()).isPresent() ||
        userRepo.findByEmail(req.getEmail()).isPresent()){
            throw new UserAlreadyPresentException("User Already present with this username or email");
        }
        User user = new User();
        user.setUsername(req.getUsername());
        user.setEmail(req.getEmail());
        user.setFullName(req.getFullName());
        user.setRole("ROLE_USER");

        user = userRepo.save(user);

        try{
            String kcId = keycloakAdminService.createKeycloakUser(user,req.getPassword());
            user.setKeycloakId(kcId);
            userRepo.save(user);
            log.info("Created user {} with Keycloak id {}", user.getUsername(), kcId);
            return user;
        }catch (Exception e){
            userRepo.delete(user);
            log.error("Failed to create user in Keycloak", e);
            throw new Exception("Something went wrong. Please try again later!!");
        }
    }

    public Map<?,?> login(LoginRequest loginRequest) throws Exception {
        String tokenUrl = String.format("%s/realms/%s/protocol/openid-connect/token", baseUrl, realm);
        LinkedMultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "password");
        form.add("client_id", clientId);
        form.add("username", loginRequest.getUsername());
        form.add("password", loginRequest.getPassword());
        // if client is confidential use client_secret
        if (clientSecret != null && !clientSecret.isBlank()) {
            form.add("client_secret", clientSecret);
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        HttpEntity<LinkedMultiValueMap<String, String>> ent = getLinkedMultiValueMapHttpEntity(loginRequest,headers);
        try{
            ResponseEntity<String> resp = restTemplate.postForEntity(tokenUrl,ent, String.class);
            if(!resp.getStatusCode().is2xxSuccessful()){
                throw new UnauthorizedException("Invalid credentials.");
            }
            log.info("login response from keyclock for username {} is {}",loginRequest.getUsername(),resp.getBody());
            Map tokenResp = mapper.readValue(resp.getBody(), Map.class);
            Optional<User> user = userRepo.findByUsername(loginRequest.getUsername());
            if(!user.isPresent()){
                throw new UserNotFoundException("Invalid username or password.");
            }
            tokenResp.put("appUserId", user.get().getId());
            tokenResp.put("fullName",user.get().getUsername());
            return tokenResp;
        } catch (Exception e) {
            log.error("login error", e);
            throw new Exception("Something went wrong.");
        }
    }

    public Map<?,?> validate(String token) throws Exception {
        try{
            token = token.replace("Bearer ","");
            String userInfoUrl = String.format("%s/realms/%s/protocol/openid-connect/userinfo", baseUrl, realm);
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(token);
            HttpEntity<Void> ent = new HttpEntity<>(headers);
            ResponseEntity<String> resp = restTemplate.exchange(userInfoUrl, HttpMethod.GET, ent, String.class);
            if(!resp.getStatusCode().is2xxSuccessful()){
                throw new UnauthorizedException("Invalid token.");
            }
            Map tokenResp = mapper.readValue(resp.getBody(), Map.class);
            String username = tokenResp.containsKey("preferred_username") ? tokenResp.get("preferred_username").toString()
                    : (tokenResp.containsKey("email") ? tokenResp.get("email").toString() : null);

            Optional<User> profile = username != null ? userRepo.findByUsername(username) : Optional.empty();
            if(!profile.isPresent()){
                throw new UserNotFoundException("Invalid username or password.");
            }
            Map out = new HashMap();
            out.put("userinfo", tokenResp);
            profile.ifPresent(p -> out.put("profile", Map.of("id", p.getId(), "fullName", p.getFullName(), "email", p.getEmail())));
            return out;
        } catch (Exception e) {
            log.error("login error", e);
            throw new Exception("Something went wrong.");
        }
    }

    private HttpEntity<LinkedMultiValueMap<String, String>> getLinkedMultiValueMapHttpEntity(LoginRequest loginRequest,HttpHeaders headers) {
        LinkedMultiValueMap<String,String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "password");
        form.add("client_id", clientId);
        form.add("username", loginRequest.getUsername());
        form.add("password", loginRequest.getPassword());

        if(clientSecret!=null && !clientSecret.isBlank()){
            form.add("client_secret", clientSecret);
        }
        HttpEntity<LinkedMultiValueMap<String,String>> ent = new HttpEntity<>(form,headers);
        return ent;
    }
}
