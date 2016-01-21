/**
 * Copyright (c) 2015 Bosch Software Innovations GmbH and others.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.eclipse.hawkbit.security;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.hawkbit.util.IpUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;

/**
 * Filter for protection against denial of service attacks. It reduces the
 * maximum number of request per seconds which can be separately configured for
 * read (GET) and write (PUT/POST/DELETE) requests. requests
 *
 *
 *
 *
 */
public class DosFilter extends OncePerRequestFilter {

    private static final Logger LOG = LoggerFactory.getLogger(DosFilter.class);
    private static final Logger LOG_DOS = LoggerFactory.getLogger("server-security.dos");
    private static final Logger LOG_BLACKLIST = LoggerFactory.getLogger("server-security.blacklist");

    private final Pattern ipAdressBlacklist;

    private final Cache<String, AtomicInteger> readCountCache = CacheBuilder.newBuilder()
            .expireAfterAccess(1, TimeUnit.SECONDS).build();

    private final Cache<String, AtomicInteger> writeCountCache = CacheBuilder.newBuilder()
            .expireAfterAccess(1, TimeUnit.SECONDS).build();

    private final Integer maxRead;
    private final Integer maxWrite;

    private final Pattern whitelist;

    private final String forwardHeader;

    /**
     * Filter constructor including configuration.
     *
     * @param maxRead
     *            Maximum number of allowed REST read/GET requests per second
     *            per client
     * @param maxWrite
     *            Maximum number of allowed REST write/(PUT/POST/etc.) requests
     *            per second per client
     * @param ipDosWhiteListPattern
     *            {@link Pattern} with with white list of peer IP addresses for
     *            DOS filter
     * @param ipBlackListPattern
     *            {@link Pattern} with black listed IP addresses
     * @param forwardHeader
     *            the header containing the forwarded IP address e.g.
     *            {@code x-forwarded-for}
     */
    public DosFilter(final Integer maxRead, final Integer maxWrite, final String ipDosWhiteListPattern,
            final String ipBlackListPattern, final String forwardHeader) {
        super();
        this.maxRead = maxRead;
        this.maxWrite = maxWrite;
        this.forwardHeader = forwardHeader;

        if (ipBlackListPattern != null && !ipBlackListPattern.isEmpty()) {
            ipAdressBlacklist = Pattern.compile(ipBlackListPattern);
        } else {
            ipAdressBlacklist = null;
        }

        if (ipDosWhiteListPattern != null && !ipDosWhiteListPattern.isEmpty()) {
            whitelist = Pattern.compile(ipDosWhiteListPattern);
        } else {
            whitelist = null;
        }
    }

    /*
     * (non-Javadoc)
     *
     * @see
     * org.springframework.web.filter.OncePerRequestFilter#doFilterInternal(
     * javax.servlet.http. HttpServletRequest,
     * javax.servlet.http.HttpServletResponse, javax.servlet.FilterChain)
     */
    @Override
    protected void doFilterInternal(final HttpServletRequest request, final HttpServletResponse response,
            final FilterChain filterChain) throws ServletException, IOException {

        boolean processChain;

        final String ip = IpUtil.getClientIpFromRequest(request, forwardHeader).getHost();
        if (checkIpFails(ip)) {
            processChain = handleMissingIpAddress(response);
        } else {
            processChain = checkAgainstBlacklist(response, ip);

            if (processChain && (whitelist == null || !whitelist.matcher(ip).find())) {
                // read request
                if (HttpMethod.valueOf(request.getMethod()) == HttpMethod.GET) {
                    processChain = handleReadRequest(response, ip);
                }
                // write request
                else {
                    processChain = handleWriteRequest(response, ip);
                }
            }
        }

        if (processChain) {
            filterChain.doFilter(request, response);
        }

    }

    /**
     * @return false if the given ip address is on the blacklist and further
     *         processing of the request if forbidden
     */
    private boolean checkAgainstBlacklist(final HttpServletResponse response, final String ip) {
        if (ipAdressBlacklist != null && ipAdressBlacklist.matcher(ip).find()) {
            LOG_BLACKLIST.info("Blacklisted client ({}) tries to access the server!", ip);
            response.setStatus(HttpStatus.FORBIDDEN.value());
            return false;
        }
        return true;
    }

    private static boolean checkIpFails(final String ip) {
        return ip == null || ip.length() == 0 || "unknown".equalsIgnoreCase(ip);
    }

    private static boolean handleMissingIpAddress(final HttpServletResponse response) {
        boolean processChain;
        LOG.error("Failed to get peer IP adress");
        response.setStatus(HttpStatus.INTERNAL_SERVER_ERROR.value());
        processChain = false;
        return processChain;
    }

    private boolean handleWriteRequest(final HttpServletResponse response, final String ip) {
        boolean processChain = true;
        final AtomicInteger count = writeCountCache.getIfPresent(ip);

        if (count == null) {
            writeCountCache.put(ip, new AtomicInteger());
        } else if (count.getAndIncrement() > maxWrite) {
            LOG_DOS.info("Registered DOS attack! Client {} is above configured WRITE request threshold ({})!", ip,
                    maxWrite);
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            processChain = false;
        }

        return processChain;
    }

    private boolean handleReadRequest(final HttpServletResponse response, final String ip) {
        boolean processChain = true;
        final AtomicInteger count = readCountCache.getIfPresent(ip);

        if (count == null) {
            readCountCache.put(ip, new AtomicInteger());
        } else if (count.getAndIncrement() > maxRead) {
            LOG_DOS.info("Registered DOS attack! Client {} is above configured READ request threshold ({})!", ip,
                    maxRead);
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            processChain = false;
        }

        return processChain;
    }
}
