package io.groundedrag.config;

import io.groundedrag.security.ApiKeyAuthenticationFilter;
import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain api(HttpSecurity http, RagProperties props) throws Exception {
        http
                // Stateless API authenticated by header: no cookies, so CSRF does not apply.
                .csrf(c -> c.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(new ApiKeyAuthenticationFilter(props.security().apiKeys()),
                        UsernamePasswordAuthenticationFilter.class)
                .authorizeHttpRequests(a -> a
                        // The request was authorised on its original dispatch; when an SSE stream
                        // completes, Spring re-dispatches it as ASYNC (and ERROR on failures). Without
                        // this the API-key filter (skipped on async dispatch) leaves it anonymous and
                        // the stream is cut off mid-response.
                        .dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll()
                        .requestMatchers("/", "/index.html", "/app.js", "/app.css", "/favicon.svg").permitAll()
                        .requestMatchers("/actuator/health/**", "/actuator/info", "/error").permitAll()
                        .requestMatchers("/actuator/**").hasRole("API")
                        .anyRequest().hasRole("API"))
                .exceptionHandling(e -> e.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .headers(h -> h.contentSecurityPolicy(csp -> csp.policyDirectives(
                        "default-src 'self'; style-src 'self'; script-src 'self'; connect-src 'self'")));
        return http.build();
    }
}
