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
package org.opennms.netmgt.dnsresolver.netty;

import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.opennms.core.test.dns.JUnitDNSServerExecutionListener;
import org.opennms.core.test.dns.annotations.DNSEntry;
import org.opennms.core.test.dns.annotations.DNSZone;
import org.opennms.core.test.dns.annotations.JUnitDNSServer;
import org.opennms.core.utils.InetAddressUtils;
import org.opennms.netmgt.events.api.EventForwarder;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

import com.codahale.metrics.MetricRegistry;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.netty.resolver.dns.DnsNameResolverTimeoutException;

@RunWith(SpringJUnit4ClassRunner.class)
@ContextConfiguration(locations={"classpath:/META-INF/opennms/emptyContext.xml"})
@TestExecutionListeners(JUnitDNSServerExecutionListener.class)
@JUnitDNSServer(port=NettyDnsResolverTest.DNS_SERVER_PORT, zones={
        @DNSZone(name = "test.bbo.local.", entries = {
                @DNSEntry(hostname = "rnd", data = "192.0.2.53"),
        }),
        @DNSZone(name = "in-addr.arpa.", entries = {
                @DNSEntry(hostname = "53.2.0.192", type = "PTR", data = "rnd.test.bbo.local.")
        }),
        @DNSZone(name = "ip6.arpa.", entries = {
                @DNSEntry(hostname = "1.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.d.a.a.d.c.9.4.1.c.7.d.f", type = "PTR", data = "secret.test.bbo.local.")
        })
})
public class NettyDnsResolverTest {

    protected static final int DNS_SERVER_PORT = 9153;

    private NettyDnsResolver dnsResolver;

    @Before
    public void setUp() {
        EventForwarder eventForwarder = mock(EventForwarder.class);
        dnsResolver = new NettyDnsResolver(eventForwarder, new MetricRegistry());
        dnsResolver.setNameservers(String.format("%s:%d", InetAddressUtils.getLocalHostName(), DNS_SERVER_PORT));
        dnsResolver.init();
    }

    @After
    public void destroy() {
        dnsResolver.destroy();
    }

    @Test
    public void canDoLookups() throws UnknownHostException, ExecutionException, InterruptedException {
        // Our DNS server knows about this one
        assertThat(dnsResolver.lookup("rnd.test.bbo.local").get().get(), equalTo(InetAddress.getByName("192.0.2.53")));
        // Our DNS server does not know about this one
        assertThat(dnsResolver.lookup("private.test.bbo.local").get(), equalTo(Optional.empty()));
    }

    @Test
    public void canCacheLookups() throws UnknownHostException, ExecutionException, InterruptedException {
        // Cache should start empty
        assertThat(dnsResolver.getCache().getSize(), equalTo(0L));

        // Query for a known host
        assertThat(dnsResolver.lookup("rnd.test.bbo.local").get().get(), equalTo(InetAddress.getByName("192.0.2.53")));

        // There should be 1 cached record now
        assertThat(dnsResolver.getCache().getSize(), equalTo(1L));

        // Cache hit
        assertThat(dnsResolver.lookup("rnd.test.bbo.local").get().get(), equalTo(InetAddress.getByName("192.0.2.53")));

        // Our DNS server does not know about this one
        assertThat(dnsResolver.lookup("private.test.bbo.local").get(), equalTo(Optional.empty()));

        // There should be at least 2 cached records now (there can be more that 2 if the system is configured with search domains)
        assertThat(dnsResolver.getCache().getSize(), greaterThanOrEqualTo(2L));

        // Cache hit
        assertThat(dnsResolver.lookup("private.test.bbo.local").get(), equalTo(Optional.empty()));
    }

    @Test
    public void canDoReverseLookups() throws UnknownHostException, ExecutionException, InterruptedException {
        // Our DNS server knows about these
        assertThat(dnsResolver.reverseLookup(InetAddress.getByName("192.0.2.53")).get().get(), equalTo("rnd.test.bbo.local"));
        assertThat(dnsResolver.reverseLookup(InetAddress.getByName("fd7c:149c:daad:0000:0000:0000:0000:0001")).get().get(), equalTo("secret.test.bbo.local"));
        // But not about these
        assertThat(dnsResolver.reverseLookup(InetAddress.getByName("1.1.1.1")).get(), equalTo(Optional.empty()));
        assertThat(dnsResolver.reverseLookup(InetAddressUtils.addr("2606:4700:4700::1111")).get(), equalTo(Optional.empty()));
    }

    @Test
    public void canCacheReverseLookups() throws UnknownHostException, ExecutionException, InterruptedException {
        // Cache should start empty
        assertThat(dnsResolver.getCache().getSize(), equalTo(0L));

        // Query for a known host
        assertThat(dnsResolver.reverseLookup(InetAddress.getByName("192.0.2.53")).get().get(), equalTo("rnd.test.bbo.local"));

        // There should be 1 cached record now
        assertThat(dnsResolver.getCache().getSize(), equalTo(1L));

        // Cache hit
        assertThat(dnsResolver.reverseLookup(InetAddress.getByName("192.0.2.53")).get().get(), equalTo("rnd.test.bbo.local"));

        // Now query for an unknown host
        assertThat(dnsResolver.reverseLookup(InetAddress.getByName("192.0.2.254")).get(), equalTo(Optional.empty()));

        // There should be 2 cached record now
        assertThat(dnsResolver.getCache().getSize(), equalTo(2L));

        // Cache hit
        assertThat(dnsResolver.reverseLookup(InetAddress.getByName("192.0.2.254")).get(), equalTo(Optional.empty()));
    }

    @Test
    public void canTriggerTimeoutException() throws InterruptedException {
        // Reinitialize the resolver using a non-routable address as the target - we want the queries to fail due to timeouts
        dnsResolver.destroy();
        dnsResolver.setNameservers(InetAddressUtils.str(InetAddressUtils.UNPINGABLE_ADDRESS));
        dnsResolver.init();

        try {
            dnsResolver.lookup("rnd.test.bbo.local").get();
            fail("Expected a DnsNameResolverTimeoutException to be thrown");
        } catch (ExecutionException e) {
            assertThat(e.getCause(), is(instanceOf(DnsNameResolverTimeoutException.class)));
        }

        try {
            dnsResolver.reverseLookup(InetAddressUtils.addr("fe80::")).get();
            fail("Expected a DnsNameResolverTimeoutException to be thrown");
        } catch (ExecutionException e) {
            assertThat(e.getCause(), is(instanceOf(DnsNameResolverTimeoutException.class)));
        }
    }

    @Test
    public void canTriggerOpenCircuit() throws InterruptedException, TimeoutException {
        // Reinitialize the resolver using a non-routable address as the target - we want the queries to fail due to timeouts
        dnsResolver.destroy();
        dnsResolver.setNameservers(InetAddressUtils.str(InetAddressUtils.UNPINGABLE_ADDRESS));
        dnsResolver.init();

        // Now trigger enough requests to open the circuit breaker
        final int N = 2 * dnsResolver.getCircuitBreaker().getCircuitBreakerConfig().getRingBufferSizeInClosedState();
        final CompletableFuture futures[] = new CompletableFuture[N];
        for (int i = 0; i < N; i++) {
            futures[i] = dnsResolver.reverseLookup(InetAddressUtils.addr("fe80::"));
        }

        // Wait for the requests to complete
        try {
            CompletableFuture.allOf(futures)
                    // This should not take longer than the query timeout
                    .get(2 * dnsResolver.getQueryTimeoutMillis(), TimeUnit.MILLISECONDS);
            fail("Expected an ExecutionException to be thrown");
        } catch (ExecutionException e) {
            // pass
        }

        // The circuit breaker should be in a open state now and start rejecting calls
        try {
            dnsResolver.reverseLookup(InetAddressUtils.addr("fe80::")).get();
            fail("Expected an CallNotPermittedException to be thrown");
        } catch (ExecutionException e) {
            assertThat(e.getCause(), is(instanceOf(CallNotPermittedException.class)));
        }
    }

    @Test
    public void canParseNameserversFromString() {
        assertThat(NettyDnsResolver.toSocketAddresses("8.8.8.8 "),
                contains(new InetSocketAddress("8.8.8.8", 53)));
        assertThat(NettyDnsResolver.toSocketAddresses("8.8.8.8:53 ,1.1.1.1, 1.1.2.2:1153 "),
                contains(new InetSocketAddress("8.8.8.8", 53),
                        new InetSocketAddress("1.1.1.1", 53),
                        new InetSocketAddress("1.1.2.2", 1153)));
        assertThat(NettyDnsResolver.toSocketAddresses("[::1], [::1]:5353 ,1.1.1.1:54 "),
                contains(new InetSocketAddress("::1", 53),
                        new InetSocketAddress("::1", 5353),
                        new InetSocketAddress("1.1.1.1", 54)));
    }
}
