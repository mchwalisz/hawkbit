/**
 * Copyright (c) 2015 Bosch Software Innovations GmbH and others.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.eclipse.hawkbit.security;

import org.eclipse.hawkbit.dmf.json.model.TenantSecruityToken;
import org.eclipse.hawkbit.repository.SystemManagement;
import org.eclipse.hawkbit.tenancy.TenantAware;
import org.eclipse.hawkbit.tenancy.configuration.TenantConfigurationKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * An pre-authenticated processing filter which extracts the principal from a
 * request URI and the credential from a request header in a the
 * {@link TenantSecruityToken}.
 *
 *
 *
 */
public class ControllerPreAuthenticatedSecurityHeaderFilter extends AbstractControllerAuthenticationFilter {

    private static final Logger LOGGER = LoggerFactory.getLogger(ControllerPreAuthenticatedSecurityHeaderFilter.class);
    private static final Logger LOG_SECURITY_AUTH = LoggerFactory.getLogger("server-security.authentication");

    private final GetSecurityAuthorityNameTenantRunner sslIssuerNameConfigTenantRunner = new GetSecurityAuthorityNameTenantRunner();
    // Example Headers with Cert Information
    // Clientip: 217.24.201.180
    // X-Forwarded-Proto: https
    // X-Ssl-Client-Cn: my.name
    // X-Ssl-Client-Dn:
    // CN=my.name,CN=O,CN=R,CN=DE,CN=BOSCH,CN=pki,DC=bosch,DC=com
    // X-Ssl-Client-Hash: 7f:87:cb:b5:9c:e0:c5:0a:1a:a6:57:69:0f:ca:0a:95
    // X-Ssl-Client-Notafter: Dec 18 08:02:45 2017 GMT
    // X-Ssl-Client-Notbefore: Dec 18 07:32:45 2014 GMT
    // X-Ssl-Client-Verify: ok
    // X-Ssl-Issuer: CN=Bosch-CA1-DE,CN=PKI,DC=Bosch,DC=com
    // X-Ssl-Issuer-Dn-1: CN=Bosch-CA-DE,CN=PKI,DC=Bosch,DC=com
    // X-Ssl-Issuer-Hash-1: ae:11:f5:6a:0a:e8:74:50:81:0e:0c:37:ec:c5:22:fc
    private final String caCommonNameHeader;
    // the X-Ssl-Issuer-Hash basic header header which contains the x509
    // fingerprint hash, this
    // header exists multiple in the request for all trusted chain.
    private final String sslIssuerHashBasicHeader;

    /**
     * Constructor.
     *
     * @param caCommonNameHeader
     *            the http-header which holds the common-name of the certificate
     * @param caAuthorityNameHeader
     *            the http-header which holds the ca-authority name of the
     *            certificate
     * @param systemManagement
     *            the system management service to retrieve configuration
     *            properties to check if the header authentication is enabled
     *            for this tenant
     * @param tenantAware
     *            the tenant aware service to get configuration for the specific
     *            tenant
     */
    public ControllerPreAuthenticatedSecurityHeaderFilter(final String caCommonNameHeader,
            final String caAuthorityNameHeader, final SystemManagement systemManagement,
            final TenantAware tenantAware) {
        super(systemManagement, tenantAware);
        this.caCommonNameHeader = caCommonNameHeader;
        this.sslIssuerHashBasicHeader = caAuthorityNameHeader;
    }

    @Override
    public HeaderAuthentication getPreAuthenticatedPrincipal(final TenantSecruityToken secruityToken) {
        // retrieve the common name header and the authority name header from
        // the http request and
        // combine them together
        final String commonNameValue = secruityToken.getHeader(caCommonNameHeader);
        final String knownSslIssuerConfigurationValue = tenantAware.runAsTenant(secruityToken.getTenant(),
                sslIssuerNameConfigTenantRunner);
        final String sslIssuerHashValue = getIssuerHashHeader(secruityToken, knownSslIssuerConfigurationValue);
        if (commonNameValue != null && LOGGER.isTraceEnabled()) {
            LOGGER.trace("Found commonNameHeader {}={}, using as credentials", caCommonNameHeader, commonNameValue);
        }
        if (sslIssuerHashValue != null && LOGGER.isTraceEnabled()) {
            LOGGER.trace("Found sslIssuerHash ****, using as credentials for tenant {}", secruityToken.getTenant());
        }

        if (commonNameValue != null && sslIssuerHashValue != null) {
            return new HeaderAuthentication(commonNameValue, sslIssuerHashValue);
        }
        return null;
    }

    @Override
    public HeaderAuthentication getPreAuthenticatedCredentials(final TenantSecruityToken secruityToken) {
        final String authorityNameConfigurationValue = tenantAware.runAsTenant(secruityToken.getTenant(),
                sslIssuerNameConfigTenantRunner);
        String controllerId = secruityToken.getControllerId();
        // in case of legacy download artifact, the controller ID is not in the
        // URL path, so then
        // we just use the common name header
        if (controllerId == null || "anonymous".equals(controllerId)) {
            controllerId = secruityToken.getHeader(caCommonNameHeader);
        }

        return new HeaderAuthentication(controllerId, authorityNameConfigurationValue);
    }

    /**
     * Iterates over the {@link #sslIssuerHashBasicHeader} basic header
     * {@code X-Ssl-Issuer-Hash-%d} and try to finds the same hash as known.
     * It's ok if we find the the hash in any the trusted CA chain to accept
     * this request for this tenant.
     */
    private String getIssuerHashHeader(final TenantSecruityToken secruityToken, final String knownIssuerHash) {
        // iterate over the headers until we get a null header.
        int iHeader = 1;
        String foundHash;
        while ((foundHash = secruityToken.getHeader(String.format(sslIssuerHashBasicHeader, iHeader))) != null) {
            if (foundHash.equals(knownIssuerHash)) {
                if (LOGGER.isTraceEnabled()) {
                    LOGGER.trace("Found matching ssl issuer hash at position {}", iHeader);
                }
                return foundHash;
            }
            iHeader++;
        }
        LOG_SECURITY_AUTH.debug(
                "Certifacte request but no matching hash found in headers {} for common name {} in request",
                sslIssuerHashBasicHeader, secruityToken.getHeader(caCommonNameHeader));
        return null;
    }

    @Override
    protected TenantConfigurationKey getTenantConfigurationKey() {
        return TenantConfigurationKey.AUTHENTICATION_MODE_HEADER_ENABLED;
    }

    private final class GetSecurityAuthorityNameTenantRunner implements TenantAware.TenantRunner<String> {
        @Override
        public String run() {
            return systemManagement.getConfigurationValue(
                    TenantConfigurationKey.AUTHENTICATION_MODE_HEADER_AUTHORITY_NAME, String.class);
        }
    }
}
