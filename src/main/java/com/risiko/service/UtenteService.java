package com.risiko.service;

import com.risiko.model.Utente;
import com.risiko.repository.UtenteRepository;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class UtenteService implements UserDetailsService {

    private final UtenteRepository repo;
    private final PasswordEncoder encoder;

    public UtenteService(UtenteRepository repo, PasswordEncoder encoder) {
        this.repo = repo;
        this.encoder = encoder;
    }

    /** Usato da Spring Security per l'autenticazione */
    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        Utente u = repo.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("Utente non trovato: " + username));
        return User.builder()
                .username(u.getUsername())
                .password(u.getPassword())
                .roles("USER")
                .build();
    }

    /** Registra un nuovo utente */
    public Utente registra(String username, String password, String email, String avatar) {
        if (repo.existsByUsername(username)) {
            throw new RuntimeException("Username già in uso");
        }
        if (email != null && !email.isBlank() && repo.existsByEmail(email)) {
            throw new RuntimeException("Email già in uso");
        }
        return repo.save(new Utente(
                username,
                encoder.encode(password),
                email,
                avatar
        ));
    }

    /** Cerca utente per username (lancia eccezione se non trovato) */
    public Utente findByUsername(String username) {
        return repo.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("Utente non trovato: " + username));
    }
}
