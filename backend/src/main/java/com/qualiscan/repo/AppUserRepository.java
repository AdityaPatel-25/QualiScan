package com.qualiscan.repo;
import com.qualiscan.domain.AppUser; import java.util.Optional; import org.springframework.data.jpa.repository.JpaRepository;
public interface AppUserRepository extends JpaRepository<AppUser,Long>{Optional<AppUser> findByUsername(String username);}
