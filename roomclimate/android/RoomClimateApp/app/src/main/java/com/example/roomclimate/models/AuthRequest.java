package com.example.roomclimate.models;

public class AuthRequest {
    public String name;
    public String surname;
    public String email;
    public String password;

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
}
