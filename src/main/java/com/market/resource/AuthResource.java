package com.market.resource;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Path("/api/auth")
@Produces(MediaType.APPLICATION_JSON)
@ApplicationScoped
public class AuthResource {

    private static final String  USERNAME  = "admin";
    private static final Duration TOKEN_TTL = Duration.ofHours(8);

    @ConfigProperty(name = "app.auth.password", defaultValue = "scalping2026")
    String password;

    private final ConcurrentHashMap<String, Instant> tokens = new ConcurrentHashMap<>();

    @POST
    @Path("/login")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response login(LoginRequest req) {
        if (req == null || !USERNAME.equals(req.username) || !password.equals(req.password)) {
            return Response.status(Response.Status.UNAUTHORIZED)
                .entity(Map.of("error", "Identifiants incorrects"))
                .build();
        }
        tokens.entrySet().removeIf(e -> e.getValue().isBefore(Instant.now()));
        String token = UUID.randomUUID().toString();
        tokens.put(token, Instant.now().plus(TOKEN_TTL));
        return Response.ok(Map.of("token", token)).build();
    }

    @GET
    @Path("/check")
    public Response check(@HeaderParam("Authorization") String auth) {
        if (isValid(auth)) {
            return Response.ok(Map.of("valid", true)).build();
        }
        return Response.status(Response.Status.UNAUTHORIZED)
            .entity(Map.of("valid", false))
            .build();
    }

    @POST
    @Path("/logout")
    public Response logout(@HeaderParam("Authorization") String auth) {
        String token = extractToken(auth);
        if (token != null) tokens.remove(token);
        return Response.ok(Map.of("ok", true)).build();
    }

    private boolean isValid(String auth) {
        String token = extractToken(auth);
        if (token == null) return false;
        Instant exp = tokens.get(token);
        return exp != null && exp.isAfter(Instant.now());
    }

    private String extractToken(String auth) {
        if (auth != null && auth.startsWith("Bearer ")) return auth.substring(7).trim();
        return null;
    }

    public static class LoginRequest {
        public String username;
        public String password;
    }
}
