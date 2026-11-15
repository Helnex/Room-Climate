package com.example.roomclimate.models;

public class AuthRequest {
    public String name;
    public String surname;
    public String email;
    public String password;
    /** Код приглашения: сервер требует его только при регистрации из интернета. */
    public String invite_code;

    public static AuthRequest login(String email, String password) {
        AuthRequest body = new AuthRequest();
        body.email = email;
        body.password = password;
        return body;
    }

    public static AuthRequest register(String name, String surname, String email, String password) {
        AuthRequest body = new AuthRequest();
        body.name = name;
        body.surname = surname;
        body.email = email;
        body.password = password;
        return body;
    }

    public static AuthRequest register(String name, String surname, String email, String password, String inviteCode) {
        AuthRequest body = register(name, surname, email, password);
        body.invite_code = inviteCode == null || inviteCode.trim().isEmpty() ? null : inviteCode.trim();
        return body;
    }
}
