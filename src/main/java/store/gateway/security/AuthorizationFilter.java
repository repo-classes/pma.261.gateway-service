package store.gateway.security;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;

@Component
public class AuthorizationFilter implements GlobalFilter {

    private Logger logger = LoggerFactory.getLogger(AuthorizationFilter.class);

    public static String AUTH_COOKIE_TOKEN = "__store_jwt_token";
    public static String AUTH_SERVICE_TOKEN_SOLVE = "http://auth:8080/auth/solve";

    @Autowired
    private RouterValidator routerValidator;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        logger.debug("filter: entrou no filtro de autorizacao");
        ServerHttpRequest request = exchange.getRequest();

        // verifica se a rota eh segura
        if (!routerValidator.isSecured.test(request)) {
            // se for aberta, libera
            logger.debug("filter: rota nao eh segura");
            return chain.filter(exchange);
        }

        // se for segura, verifica o cookie
        if (request.getCookies().containsKey(AUTH_COOKIE_TOKEN)) {
            logger.debug("filter: tem [" + AUTH_COOKIE_TOKEN + "] no cookie");
            String token = request.getCookies().getFirst(AUTH_COOKIE_TOKEN).getValue();
            // captura o jwt
            logger.debug(String.format(
                "filter: [Token]=[%s]",
                token
            ));
            if (null != token && token.length() > 0) {
                return requestAuthTokenSolve(exchange, chain, token.trim());
            }
        }

        logger.debug("filter: access is denied!");
        // when access is denied
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    }

    // este metodo eh responsavel por enviar o token ao Auth Microservice
    // a fim de interpretar o token, a chamada eh feita via Rest.
    private Mono<Void> requestAuthTokenSolve(ServerWebExchange exchange, GatewayFilterChain chain, String jwt) {
        logger.debug("solving jwt: " + jwt);
        return WebClient.builder()
            .defaultHeader(
                HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE
            )
            .build()
            .post()
            .uri(AUTH_SERVICE_TOKEN_SOLVE)
            .bodyValue(Map.of(
                "token", jwt)
            )
            .retrieve()
            .toEntity(Map.class)
            .flatMap(response -> {
                if (response != null && response.hasBody() && response.getBody() != null) {
                    final Map<String, String> map = response.getBody();
                    String idAccount = map.get("idAccount");
                    logger.debug("solve: id account: " + idAccount);
                    ServerWebExchange authorizated = updateRequest(exchange, idAccount, jwt);
                    return chain.filter(authorizated);
                } else {
                    throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid token");
                }
            });
    }    

    private ServerWebExchange updateRequest(ServerWebExchange exchange, String idAccount, String jwt) {
        logger.debug("original headers: " + exchange.getRequest().getHeaders().toString());
        ServerWebExchange modified = exchange.mutate()
            .request(
                exchange.getRequest()
                    .mutate()
                    .header("id-account", idAccount)
                    .header("Authorization", "Bearer " + jwt)
                    .build()
            ).build();
        logger.debug("updated headers: " + modified.getRequest().getHeaders().toString());
        return modified;
    }    

}
