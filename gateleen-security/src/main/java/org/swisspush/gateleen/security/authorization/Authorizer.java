package org.swisspush.gateleen.security.authorization;

import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.eventbus.EventBus;
import io.vertx.core.eventbus.Message;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.swisspush.gateleen.core.http.UriBuilder;
import org.swisspush.gateleen.core.logging.LoggableResource;
import org.swisspush.gateleen.core.logging.RequestLogger;
import org.swisspush.gateleen.core.storage.ResourceStorage;
import org.swisspush.gateleen.core.util.ResponseStatusCodeLogUtil;
import org.swisspush.gateleen.core.util.RoleExtractor;
import org.swisspush.gateleen.core.util.StatusCode;
import org.swisspush.gateleen.validation.ValidationException;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @author https://github.com/lbovet [Laurent Bovet]
 */
public class Authorizer implements LoggableResource {

    private static final String UPDATE_ADDRESS = "gateleen.authorization-updated";

    private Pattern userUriPattern;

    private String aclKey = "acls";

    private String anonymousRole = "everyone";

    private RoleMapper roleMapper;
    private RoleAuthorizer roleAuthorizer;

    private PatternHolder aclUriPattern;
    private PatternHolder roleMapperUriPattern;

    private Vertx vertx;
    private EventBus eb;

    private boolean logACLChanges = false;
    private ResourceStorage storage;
    private RoleExtractor roleExtractor;

    public static final Logger log = LoggerFactory.getLogger(Authorizer.class);

    public Authorizer(Vertx vertx, final ResourceStorage storage, String securityRoot, String rolePattern) {
        this.vertx = vertx;
        this.storage = storage;
        String aclRoot = UriBuilder.concatUriSegments(securityRoot, aclKey, "/");
        this.aclUriPattern = new PatternHolder(Pattern.compile("^" + aclRoot + "(?<role>.+)$"));
        this.userUriPattern = Pattern.compile(securityRoot + "user(\\?.*)?");
        this.roleMapperUriPattern = new PatternHolder(Pattern.compile("^" + UriBuilder.concatUriSegments(securityRoot, RoleMapper.ROLEMAPPER)));
        this.roleExtractor = new RoleExtractor(rolePattern);
        this.roleMapper = new RoleMapper(storage, securityRoot);
        this.roleAuthorizer = new RoleAuthorizer(storage, securityRoot, rolePattern, roleMapper);
        eb = vertx.eventBus();

        // Receive update notifications
        eb.consumer(UPDATE_ADDRESS, (Handler<Message<String>>) role -> updateAllConfigs());
    }

    @Override
    public void enableResourceLogging(boolean resourceLoggingEnabled) {
        this.logACLChanges = resourceLoggingEnabled;
    }


    public Future<Boolean> authorize(final HttpServerRequest request) {
        Future<Boolean> future = Future.future();

        handleUserUriRequest(request, future);

        if (!future.isComplete()) {
            roleAuthorizer.handleIsAuthorized(request, future);
        }

        if (!future.isComplete()) {
            handleConfigurationUriRequest(request, future, aclUriPattern, roleAuthorizer);
        }

        if (!future.isComplete()) {
            handleConfigurationUriRequest(request, future, roleMapperUriPattern, roleMapper);
        }

        if (!future.isComplete()) {
            future.complete(Boolean.TRUE);
        }

        return future;
    }

    public void authorize(final HttpServerRequest request, final Handler<Void> handler) {
        Future<Boolean> future = Future.future();

        handleUserUriRequest(request, future);

        if (!future.isComplete()) {
            roleAuthorizer.handleIsAuthorized(request, future);
        }

        if (!future.isComplete()) {
            handleConfigurationUriRequest(request, future, aclUriPattern, roleAuthorizer);
        }

        if (!future.isComplete()) {
            handleConfigurationUriRequest(request, future, roleMapperUriPattern, roleMapper);
        }

        if (!future.isComplete()) {
            handler.handle(null);
        }
    }

    private void handleUserUriRequest(final HttpServerRequest request, Future<Boolean> future) {
        if (userUriPattern.matcher(request.uri()).matches()) {
            if (HttpMethod.GET == request.method()) {
                String userId = request.headers().get("x-rp-usr");
                JsonObject user = new JsonObject();
                request.response().headers().set("Content-Type", "application/json");
                String userName = request.headers().get("cas_name");
                if (userName != null) {
                    userId = userName;
                }
                user.put("userId", userId);
                Set<String> roles = roleExtractor.extractRoles(request);
                if (roles != null) {
                    roles.add(anonymousRole);
                    user.put("roles", new JsonArray(new ArrayList<>(roles)));
                }
                ResponseStatusCodeLogUtil.info(request, StatusCode.OK, Authorizer.class);
                request.response().end(user.toString());
            } else {
                ResponseStatusCodeLogUtil.info(request, StatusCode.METHOD_NOT_ALLOWED, Authorizer.class);
                request.response().setStatusCode(StatusCode.METHOD_NOT_ALLOWED.getStatusCode());
                request.response().setStatusMessage(StatusCode.METHOD_NOT_ALLOWED.getStatusMessage());
                request.response().end();
            }
            future.complete(Boolean.FALSE);
        }
    }

    /**
     * Common handler for uri requests with PatternHolder and a class implementing the AuthorisationResource Interface
     *
     * @param request       The original request
     * @param future        The future with the result feeded
     * @param patternHolder The pattern with the Configuration Resource to be used for Configuration reload
     * @param checker       The checker Object (implementing ConfigurationResource interface) to be used to validate the configuration
     */
    private void handleConfigurationUriRequest(final HttpServerRequest request, Future<Boolean> future, PatternHolder patternHolder, ConfigurationResource checker) {
        // Intercept configuration
        final Matcher aclMatcher = patternHolder.getPattern().matcher(request.uri());
        if (aclMatcher.matches()) {
            if (HttpMethod.PUT == request.method()) {
                request.bodyHandler(buffer -> {
                    try {
                        checker.checkConfigResource(buffer);
                    } catch (ValidationException validationException) {
                        log.warn("Could not parse acl: " + validationException.toString());
                        ResponseStatusCodeLogUtil.info(request, StatusCode.BAD_REQUEST, Authorizer.class);
                        request.response().setStatusCode(StatusCode.BAD_REQUEST.getStatusCode());
                        request.response().setStatusMessage(StatusCode.BAD_REQUEST.getStatusMessage() + " " + validationException.getMessage());
                        if (validationException.getValidationDetails() != null) {
                            request.response().headers().add("content-type", "application/json");
                            request.response().end(validationException.getValidationDetails().encode());
                        } else {
                            request.response().end(validationException.getMessage());
                        }
                        return;
                    }
                    storage.put(request.uri(), buffer, status -> {
                        if (status == StatusCode.OK.getStatusCode()) {
                            if (logACLChanges) {
                                RequestLogger.logRequest(vertx.eventBus(), request, status, buffer);
                            }
                            scheduleUpdate();
                        } else {
                            request.response().setStatusCode(status);
                        }
                        ResponseStatusCodeLogUtil.info(request, StatusCode.fromCode(status), Authorizer.class);
                        request.response().end();
                    });
                });
                future.complete(Boolean.FALSE);
            } else if (HttpMethod.DELETE == request.method()) {
                storage.delete(request.uri(), status -> {
                    if (status == StatusCode.OK.getStatusCode()) {
                        eb.publish(UPDATE_ADDRESS, "*");
                    } else {
                        log.warn("Could not delete '" + (request.uri() == null ? "<null>" : request.uri()) + "'. Error code is '" + (status == null ? "<null>" : status) + "'.");
                        request.response().setStatusCode(status);
                    }
                    ResponseStatusCodeLogUtil.info(request, StatusCode.fromCode(status), Authorizer.class);
                    request.response().end();
                });
                future.complete(Boolean.FALSE);
            }
        }
    }


    private void updateAllConfigs() {
        roleAuthorizer.configUpdate();
        roleMapper.configUpdate();
    }

    private long updateTimerId = -1;

    private void scheduleUpdate() {
        vertx.cancelTimer(updateTimerId);
        updateTimerId = vertx.setTimer(3000, id -> eb.publish(UPDATE_ADDRESS, "*"));
    }

}
