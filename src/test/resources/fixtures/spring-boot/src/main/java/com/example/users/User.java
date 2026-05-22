package com.example.users;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;

@Entity
public class User {
    @Id
    @GeneratedValue
    private Long id;
    private String email;

    public Long getId() { return id; }
    public String getEmail() { return email; }
}
