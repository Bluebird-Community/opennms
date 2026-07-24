/*
 * Licensed to The OpenNMS Group, Inc (TOG) under one or more
 * contributor license agreements.  See the LICENSE.md file
 * distributed with this work for additional information
 * regarding copyright ownership.
 *
 * TOG licenses this file to You under the GNU Affero General
 * Public License Version 3 (the "License") or (at your option)
 * any later version.  You may not use this file except in
 * compliance with the License.  You may obtain a copy of the
 * License at:
 *
 *      https://www.gnu.org/licenses/agpl-3.0.txt
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied.  See the License for the specific
 * language governing permissions and limitations under the
 * License.
 */
package org.opennms.smoketest.utils;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.apache.commons.io.IOUtils;

/**
 * Rest Health client used to verify HealCheck implementations statuses
 */
public class RestHealthClient {

    private URL url;

    private Optional<String> alias;

    private Client client;

    private final static String PROBE = "/rest/health/probe";
    private final static String SUCCESS_PROBE = "Everything is awesome";
    private final static String HEALTH_KEY = "Health";

    /**
     * HealthRestclient constructor
     * @param webUrl: implementation url required to create the http requests
     * @param alias: container alias
     */
    public RestHealthClient(final URL webUrl, final Optional<String> alias){
        this.alias = alias;
        this.url = webUrl;
        // Bound the probe request: without a read timeout a health check that blocks its thread
        // server-side leaves this GET hanging forever, so the enclosing awaitility timeout can never
        // fire and the whole job runs until the CI 6h wall-clock limit. With a read timeout the probe
        // fails fast, the health wait times out normally, and the failure path can still gather a
        // thread dump from the (still-running) container.
        this.client = ClientBuilder.newBuilder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build();
    }

    private WebTarget getTargetFor(final String path){

        return alias.isPresent() ? client.target(url.toString()).path(alias.get()).path(path) : client.target(url.toString()).path(path);
    }

    public String getProbeHealthResponse(){
        // Hard, implementation-independent bound on the probe call. The JAX-RS ClientBuilder
        // connect/read timeouts are not honoured by every provider, so a health check that blocks
        // its thread server-side can otherwise leave this GET hanging forever — the enclosing
        // awaitility timeout then never fires and the job runs to the CI 6h wall-clock limit.
        // Running the request on a throwaway executor with a get(...) timeout guarantees this method
        // returns within the bound, so the health wait times out normally and the failure path can
        // still SIGQUIT the (still-running) container for a thread dump.
        final ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            return executor.submit(this::doGetProbeHealthResponse).get(45, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            return "Health probe did not respond within 45s -- a server-side HealthCheck is likely blocked.";
        } catch (Exception e) {
            return "Health probe call failed: " + e;
        } finally {
            executor.shutdownNow();
        }
    }

    private String doGetProbeHealthResponse(){
        Response response
                = getTargetFor(PROBE).request(MediaType.TEXT_PLAIN).get();
        /*
        return response.getStatus() == 200 && response.getHeaders().containsKey(HEALTH_KEY + "foo") ?
                response.getHeaders().get(HEALTH_KEY +"foo").toString() : { throw new RuntimeException("Health key not found in: " + response.toString()); return ""; };
                */

        if (response.getStatus() == 200 && response.getHeaders().containsKey(HEALTH_KEY)) {
            return response.getHeaders().get(HEALTH_KEY).toString();
        }

        try {
            return "Response status != 200 or " + HEALTH_KEY + " header not found. Dumping response.\nStatus: " + response.getStatus() + "\nHeaders: " + response.getStringHeaders() + "\n" + IOUtils.toString((InputStream)response.getEntity());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public String getProbeSuccessMessage(){return SUCCESS_PROBE;}
}
