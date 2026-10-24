package com.qualiscan.domain;
import jakarta.persistence.*;
@Entity @Table(name="app_users", indexes=@Index(columnList="username", unique=true))
public class AppUser {
 @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
 @Column(nullable=false,unique=true) private String username;
 @Column(nullable=false) private String passwordHash;
 @Enumerated(EnumType.STRING) @Column(nullable=false) private Role role;
 @Column(nullable=false) private boolean enabled=true;
 protected AppUser() {}
 public AppUser(String username,String passwordHash,Role role){this.username=username;this.passwordHash=passwordHash;this.role=role;}
 public Long getId(){return id;} public String getUsername(){return username;} public String getPasswordHash(){return passwordHash;} public Role getRole(){return role;} public boolean isEnabled(){return enabled;}
 public void setPasswordHash(String value){passwordHash=value;} public void setRole(Role value){role=value;} public void setEnabled(boolean value){enabled=value;}
}
