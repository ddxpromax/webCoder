package io.github.ddxpromax.webcoder.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ddxpromax.webcoder.agent.JsonFileStore;
import io.github.ddxpromax.webcoder.agent.MajoWorkingDirectory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AuthControllerTests {
    @TempDir
    Path workingDir;

    @Test
    void registerLoginVerifyAndRevokeFollowMajoApi() throws Exception {
        AuthStore store = new AuthStore(
            new MajoWorkingDirectory(workingDir.toString()),
            new JsonFileStore(new ObjectMapper()),
            true
        );
        var mvc = MockMvcBuilders.standaloneSetup(
            new AuthController(store)
        ).addInterceptors(new AuthInterceptor(store)).build();

        mvc.perform(get("/api/auth/status"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.enabled").value(true))
            .andExpect(jsonPath("$.has_users").value(false));

        String registration = mvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"username":"owner","password":"private-password"}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.username").value("owner"))
            .andReturn().getResponse().getContentAsString();
        String initialToken = new ObjectMapper().readTree(registration)
            .get("token").asText();
        assertThat(initialToken).isNotBlank();
        assertThat(Files.readString(workingDir.resolve("auth.json")))
            .doesNotContain("private-password");

        mvc.perform(get("/api/auth/status"))
            .andExpect(jsonPath("$.has_users").value(true));
        mvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"username":"other","password":"pass"}
                    """))
            .andExpect(status().isConflict());
        mvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"username":"owner","password":"wrong"}
                    """))
            .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/verify")
                .header("Authorization", "Bearer " + initialToken))
            .andExpect(jsonPath("$.valid").value(true));

        String login = mvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"username":"owner","password":"private-password"}
                    """))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        String secondToken = new ObjectMapper().readTree(login)
            .get("token").asText();

        mvc.perform(post("/api/auth/revoke-token")
                .header("Authorization", "Bearer " + secondToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + initialToken + "\"}"))
            .andExpect(status().isOk());
        mvc.perform(get("/api/auth/verify")
                .header("Authorization", "Bearer " + initialToken))
            .andExpect(jsonPath("$.valid").value(false));

        mvc.perform(post("/api/auth/update-profile")
                .header("Authorization", "Bearer " + secondToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"current_password":"private-password",
                     "new_username":"new-owner",
                     "new_password":"new-private-password"}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.username").value("new-owner"));
        mvc.perform(get("/api/auth/verify")
                .header("Authorization", "Bearer " + secondToken))
            .andExpect(jsonPath("$.valid").value(false));
    }

    @Test
    void expiredTokensAreRejected() {
        ObjectMapper mapper = new ObjectMapper();
        JsonFileStore files = new JsonFileStore(mapper);
        files.writeAtomic(workingDir.resolve("auth.json"), Map.of(
            "username", "owner",
            "tokens", Map.of(
                "expired", Instant.now().getEpochSecond() - 1
            )
        ));
        AuthStore store = new AuthStore(
            new MajoWorkingDirectory(workingDir.toString()),
            files,
            true
        );
        assertThat(store.validToken("expired")).isFalse();
    }
}
