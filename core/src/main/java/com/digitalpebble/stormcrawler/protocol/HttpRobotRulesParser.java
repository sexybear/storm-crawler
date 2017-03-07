/**
 * Licensed to DigitalPebble Ltd under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * DigitalPebble licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.digitalpebble.stormcrawler.protocol;

import java.net.URL;
import java.util.Locale;

import org.apache.commons.lang.StringUtils;
import org.apache.storm.Config;

import com.digitalpebble.stormcrawler.Metadata;
import com.digitalpebble.stormcrawler.util.ConfUtils;
import com.google.common.cache.Cache;

import crawlercommons.robots.BaseRobotRules;

/**
 * This class is used for parsing robots for urls belonging to HTTP protocol. It
 * extends the generic {@link RobotRulesParser} class and contains Http protocol
 * specific implementation for obtaining the robots file.
 */
public class HttpRobotRulesParser extends RobotRulesParser {

    protected boolean allowForbidden = false;

    HttpRobotRulesParser() {
    }

    public HttpRobotRulesParser(Config conf) {
        setConf(conf);
    }

    @Override
    public void setConf(Config conf) {
        super.setConf(conf);
        allowForbidden = ConfUtils.getBoolean(conf, "http.robots.403.allow",
                true);
    }

    /**
     * Compose unique key to store and access robot rules in cache for given URL
     */
    protected static String getCacheKey(URL url) {
        String protocol = url.getProtocol().toLowerCase(Locale.ROOT);
        String host = url.getHost().toLowerCase(Locale.ROOT);

        int port = url.getPort();
        if (port == -1) {
            port = url.getDefaultPort();
        }
        /*
         * Robot rules apply only to host, protocol, and port where robots.txt
         * is hosted (cf. NUTCH-1752). Consequently
         */
        String cacheKey = protocol + ":" + host + ":" + port;
        return cacheKey;
    }

    /**
     * Get the rules from robots.txt which applies for the given {@code url}.
     * Robot rules are cached for a unique combination of host, protocol, and
     * port. If no rules are found in the cache, a HTTP request is send to fetch
     * {{protocol://host:port/robots.txt}}. The robots.txt is then parsed and
     * the rules are cached to avoid re-fetching and re-parsing it again.
     * 
     * @param http
     *            The {@link Protocol} object
     * @param url
     *            URL robots.txt applies to
     * 
     * @return {@link BaseRobotRules} holding the rules from robots.txt
     */
    @Override
    public RobotRules getRobotRulesSet(Protocol http, URL url) {

        String cacheKey = getCacheKey(url);

        // check in the error cache first
        RobotRules cachedRules = ERRORCACHE.getIfPresent(cacheKey);
        if (cachedRules != null) {
            return cachedRules;
        }

        // now try the proper cache
        cachedRules = CACHE.getIfPresent(cacheKey);
        if (cachedRules != null) {
            return cachedRules;
        }

        BaseRobotRules robotRules = null;

        boolean cacheRule = true;
        URL redir = null;
        LOG.debug("Cache miss {} for {}", cacheKey, url);
        try {
            ProtocolResponse response = http.getProtocolOutput(new URL(url,
                    "/robots.txt").toString(), Metadata.empty);
            int code = response.getStatusCode();
            // try one level of redirection ?
            if (code == 301 || code == 302 || code == 307 || code == 308) {
                String redirection = response.getMetadata().getFirstValue(
                        HttpHeaders.LOCATION);
                if (StringUtils.isNotBlank(redirection)) {
                    if (!redirection.startsWith("http")) {
                        // RFC says it should be absolute, but apparently it
                        // isn't
                        redir = new URL(url, redirection);
                    } else {
                        redir = new URL(redirection);
                    }
                    response = http.getProtocolOutput(redir.toString(),
                            Metadata.empty);
                    code = response.getStatusCode();
                }
            }
            if (code == 200) // found rules: parse them
            {
                String ct = response.getMetadata().getFirstValue(
                        HttpHeaders.CONTENT_TYPE);
                robotRules = parseRules(url.toString(), response.getContent(),
                        ct, agentNames);
            } else if ((code == 403) && (!allowForbidden)) {
                robotRules = FORBID_ALL_RULES; // use forbid all
            } else if (code >= 500) {
                cacheRule = false;
                robotRules = EMPTY_RULES;
            } else
                robotRules = EMPTY_RULES; // use default rules
        } catch (Throwable t) {
            LOG.info("Couldn't get robots.txt for {} : {}", url, t.toString());
            cacheRule = false;
            robotRules = EMPTY_RULES;
        }

        Cache<String, RobotRules> cacheToUse = CACHE;
        String cacheName = "success";
        if (!cacheRule) {
            cacheToUse = ERRORCACHE;
            cacheName = "error";
        }

        LOG.debug("Caching robots for {} under key {} in cache {}", url,
                cacheKey, cacheName);
        cacheToUse.put(cacheKey, new RobotRules(robotRules, true));
        if (redir != null && !redir.getHost().equalsIgnoreCase(url.getHost())) {
            // cache also for the redirected host
            String keyredir = getCacheKey(redir);
            LOG.debug("Caching robots for {} under key {} in cache {}", redir,
                    keyredir, cacheName);
            cacheToUse.put(keyredir, new RobotRules(robotRules, true));
        }

        return new RobotRules(robotRules, false);
    }
}
