package org.apereo.cas.support.saml.web.idp.profile;

import net.shibboleth.utilities.java.support.xml.ParserPool;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.apereo.cas.CasProtocolConstants;
import org.apereo.cas.authentication.AuthenticationSystemSupport;
import org.apereo.cas.authentication.principal.ServiceFactory;
import org.apereo.cas.authentication.principal.WebApplicationService;
import org.apereo.cas.services.ServicesManager;
import org.apereo.cas.support.saml.OpenSamlConfigBean;
import org.apereo.cas.support.saml.SamlIdPConstants;
import org.apereo.cas.support.saml.SamlProtocolConstants;
import org.apereo.cas.support.saml.services.idp.metadata.cache.SamlRegisteredServiceCachingMetadataResolver;
import org.apereo.cas.support.saml.web.idp.profile.builders.SamlProfileObjectBuilder;
import org.apereo.cas.support.saml.web.idp.profile.builders.enc.SamlObjectSigner;
import org.jasig.cas.client.util.CommonUtils;
import org.jasig.cas.client.validation.Assertion;
import org.jasig.cas.client.validation.Cas30ServiceTicketValidator;
import org.opensaml.messaging.context.MessageContext;
import org.opensaml.saml.common.SAMLObject;
import org.opensaml.saml.common.binding.SAMLBindingSupport;
import org.opensaml.saml.saml2.core.AuthnRequest;
import org.opensaml.saml.saml2.core.Response;
import org.springframework.web.bind.annotation.GetMapping;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Set;

/**
 * This is {@link SSOPostProfileCallbackHandlerController}, which handles
 * the profile callback request to build the final saml response.
 *
 * @author Misagh Moayyed
 * @since 5.0.0
 */
public class SSOPostProfileCallbackHandlerController extends AbstractSamlProfileHandlerController {

    /**
     * Instantiates a new idp-sso post saml profile handler controller.
     *
     * @param samlObjectSigner                             the saml object signer
     * @param parserPool                                   the parser pool
     * @param authenticationSystemSupport                  the authentication system support
     * @param servicesManager                              the services manager
     * @param webApplicationServiceFactory                 the web application service factory
     * @param samlRegisteredServiceCachingMetadataResolver the saml registered service caching metadata resolver
     * @param configBean                                   the config bean
     * @param responseBuilder                              the response builder
     * @param authenticationContextClassMappings           the authentication context class mappings
     * @param serverPrefix                                 the server prefix
     * @param serverName                                   the server name
     * @param authenticationContextRequestParameter        the authentication context request parameter
     * @param loginUrl                                     the login url
     * @param logoutUrl                                    the logout url
     * @param forceSignedLogoutRequests                    the force signed logout requests
     * @param singleLogoutCallbacksDisabled                the single logout callbacks disabled
     */
    public SSOPostProfileCallbackHandlerController(final SamlObjectSigner samlObjectSigner,
                                                   final ParserPool parserPool,
                                                   final AuthenticationSystemSupport authenticationSystemSupport,
                                                   final ServicesManager servicesManager,
                                                   final ServiceFactory<WebApplicationService> webApplicationServiceFactory,
                                                   final SamlRegisteredServiceCachingMetadataResolver samlRegisteredServiceCachingMetadataResolver,
                                                   final OpenSamlConfigBean configBean,
                                                   final SamlProfileObjectBuilder<Response> responseBuilder,
                                                   final Set<String> authenticationContextClassMappings,
                                                   final String serverPrefix,
                                                   final String serverName,
                                                   final String authenticationContextRequestParameter,
                                                   final String loginUrl,
                                                   final String logoutUrl,
                                                   final boolean forceSignedLogoutRequests,
                                                   final boolean singleLogoutCallbacksDisabled) {
        super(samlObjectSigner,
                parserPool,
                authenticationSystemSupport,
                servicesManager,
                webApplicationServiceFactory,
                samlRegisteredServiceCachingMetadataResolver,
                configBean,
                responseBuilder,
                authenticationContextClassMappings,
                serverPrefix,
                serverName,
                authenticationContextRequestParameter,
                loginUrl,
                logoutUrl,
                forceSignedLogoutRequests,
                singleLogoutCallbacksDisabled);
    }

    /**
     * Handle callback profile request.
     *
     * @param response the response
     * @param request  the request
     * @throws Exception the exception
     */
    @GetMapping(path = SamlIdPConstants.ENDPOINT_SAML2_SSO_PROFILE_POST_CALLBACK)
    protected void handleCallbackProfileRequest(final HttpServletResponse response, final HttpServletRequest request) throws Exception {

        logger.info("Received SAML callback profile request [{}]", request.getRequestURI());
        final AuthnRequest authnRequest = retrieveSamlAuthenticationRequestFromHttpRequest(request);
        if (authnRequest == null) {
            logger.error("Can not validate the request because the original Authn request can not be found.");
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            return;
        }

        final String ticket = CommonUtils.safeGetParameter(request, CasProtocolConstants.PARAMETER_TICKET);
        if (StringUtils.isBlank(ticket)) {
            logger.error("Can not validate the request because no [{}] is provided via the request",
                    CasProtocolConstants.PARAMETER_TICKET);
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            return;
        }

        final Pair<AuthnRequest, MessageContext> authenticationContext = buildAuthenticationContextPair(request, authnRequest);
        final Assertion assertion = validateRequestAndBuildCasAssertion(response, request, authenticationContext);
        buildSamlResponse(response, request, authenticationContext, assertion);
    }

    /**
     * Build authentication context pair pair.
     *
     * @param request      the request
     * @param authnRequest the authn request
     * @return the pair
     */
    protected Pair<AuthnRequest, MessageContext> buildAuthenticationContextPair(final HttpServletRequest request,
                                                                                final AuthnRequest authnRequest) {
        final MessageContext<SAMLObject> messageContext = bindRelayStateParameter(request);
        return Pair.of(authnRequest, messageContext);
    }

    private MessageContext<SAMLObject> bindRelayStateParameter(final HttpServletRequest request) {
        final MessageContext<SAMLObject> messageContext = new MessageContext<>();
        final String relayState = request.getParameter(SamlProtocolConstants.PARAMETER_SAML_RELAY_STATE);
        logger.debug("RelayState is [{}]", relayState);
        SAMLBindingSupport.setRelayState(messageContext, relayState);
        return messageContext;
    }

    private Assertion validateRequestAndBuildCasAssertion(final HttpServletResponse response,
                                                          final HttpServletRequest request,
                                                          final Pair<AuthnRequest, MessageContext> pair) throws Exception {
        final AuthnRequest authnRequest = pair.getKey();
        final String ticket = CommonUtils.safeGetParameter(request, CasProtocolConstants.PARAMETER_TICKET);
        final Cas30ServiceTicketValidator validator = new Cas30ServiceTicketValidator(this.serverPrefix);
        validator.setRenew(authnRequest.isForceAuthn());
        final String serviceUrl = constructServiceUrl(request, response, pair);
        logger.debug("Created service url for validation: [{}]", serviceUrl);
        final Assertion assertion = validator.validate(ticket, serviceUrl);
        logCasValidationAssertion(assertion);
        return assertion;
    }



}
