package com.aio.platform.config;

import com.aio.platform.security.ProfileOidcUserService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.oidc.web.logout.OidcClientInitiatedLogoutSuccessHandler;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, ProfileOidcUserService userService,
                                           ClientRegistrationRepository clients) throws Exception {
        var oidcLogout = new OidcClientInitiatedLogoutSuccessHandler(clients);
        oidcLogout.setPostLogoutRedirectUri("{baseUrl}/login");
        http
            .authorizeHttpRequests(a -> a
                .requestMatchers("/api/webhooks/**", "/actuator/health/**", "/actuator/health").permitAll()
                .requestMatchers("/api/me").hasAnyRole("AIO_ADMIN", "AIO_BUSINESS", "AIO_COMMUNITY")
                .requestMatchers("/api/users", "/api/clusters").hasRole("AIO_ADMIN")
                .requestMatchers(HttpMethod.GET, "/api/projects", "/api/metrics").hasAnyRole("AIO_ADMIN", "AIO_BUSINESS", "AIO_COMMUNITY")
                .requestMatchers("/api/**").hasAnyRole("AIO_ADMIN", "AIO_BUSINESS")
                .anyRequest().permitAll()) // static SPA assets + client-side routes
            .oauth2Login(o -> o
                .loginPage("/login")
                .userInfoEndpoint(u -> u.oidcUserService(userService))
                .defaultSuccessUrl("/", true)
                .failureUrl("/login?error"))
            .logout(l -> l.logoutUrl("/logout").logoutSuccessHandler(oidcLogout))
            .exceptionHandling(e -> e.defaultAuthenticationEntryPointFor(
                new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED), new AntPathRequestMatcher("/api/**")))
            .csrf(c -> c
                .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
                .ignoringRequestMatchers("/api/webhooks/**")) // protected by HMAC signature instead
            .addFilterAfter(new CsrfCookieFilter(), BasicAuthenticationFilter.class);
        return http.build();
    }

    /** Forces the deferred CSRF token to load so the XSRF-TOKEN cookie is always sent to the SPA. */
    static final class CsrfCookieFilter extends OncePerRequestFilter {
        @Override
        protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
                throws ServletException, IOException {
            CsrfToken token = (CsrfToken) req.getAttribute(CsrfToken.class.getName());
            if (token != null) token.getToken();
            chain.doFilter(req, res);
        }
    }
}
