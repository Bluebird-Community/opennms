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
package org.opennms.netmgt.collection.commands;

import static org.junit.Assert.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.junit.MockitoJUnitRunner;
import org.opennms.netmgt.collection.api.CollectorAdaptor;
import org.opennms.netmgt.collection.api.CollectorRequestBuilder;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;

/**
 * Verifies that {@link CollectCommand#registerAdaptors(CollectorRequestBuilder)}
 * pulls every {@link CollectorAdaptor} OSGi service and registers it on the
 * request builder. Without this, ad-hoc {@code opennms:collect} bypasses
 * adaptors like {@code TokenAuthCollectorAdaptor} and {@code ${token:...}}
 * placeholders go out unresolved.
 */
@RunWith(MockitoJUnitRunner.class)
public class CollectCommandAdaptorTest {

    private CollectCommand command;
    private CollectorRequestBuilder builder;

    @Before
    public void setUp() {
        command = new CollectCommand();
        builder = mock(CollectorRequestBuilder.class);
        // Default fluent: builder.withAdaptor(any) returns the same mock so
        // the registration loop can keep chaining.
        when(builder.withAdaptor(any(CollectorAdaptor.class))).thenReturn(builder);
    }

    @Test
    public void nullBundleContextIsTolerated() {
        command.bundleContext = null;
        final CollectorRequestBuilder result = command.registerAdaptors(builder);
        assertSame(builder, result);
        verify(builder, never()).withAdaptor(any());
    }

    @Test
    public void nullServiceReferencesIsTolerated() throws Exception {
        final BundleContext ctx = mock(BundleContext.class);
        when(ctx.getServiceReferences(eq(CollectorAdaptor.class), eq((String) null))).thenReturn(null);
        command.bundleContext = ctx;

        final CollectorRequestBuilder result = command.registerAdaptors(builder);
        assertSame(builder, result);
        verify(builder, never()).withAdaptor(any());
    }

    @Test
    public void registersEachPublishedAdaptor() throws Exception {
        final BundleContext ctx = mock(BundleContext.class);
        @SuppressWarnings("unchecked")
        final ServiceReference<CollectorAdaptor> ref1 = mock(ServiceReference.class);
        @SuppressWarnings("unchecked")
        final ServiceReference<CollectorAdaptor> ref2 = mock(ServiceReference.class);

        final CollectorAdaptor adaptor1 = mock(CollectorAdaptor.class);
        final CollectorAdaptor adaptor2 = mock(CollectorAdaptor.class);

        doReturn(Arrays.asList(ref1, ref2))
                .when(ctx).getServiceReferences(eq(CollectorAdaptor.class), eq((String) null));
        when(ctx.getService(ref1)).thenReturn(adaptor1);
        when(ctx.getService(ref2)).thenReturn(adaptor2);
        command.bundleContext = ctx;

        command.registerAdaptors(builder);

        verify(builder).withAdaptor(adaptor1);
        verify(builder).withAdaptor(adaptor2);
        verify(builder, times(2)).withAdaptor(any(CollectorAdaptor.class));
    }

    @Test
    public void nullServiceFromContextIsSkipped() throws Exception {
        final BundleContext ctx = mock(BundleContext.class);
        @SuppressWarnings("unchecked")
        final ServiceReference<CollectorAdaptor> ref = mock(ServiceReference.class);
        doReturn(Arrays.asList(ref))
                .when(ctx).getServiceReferences(eq(CollectorAdaptor.class), eq((String) null));
        when(ctx.getService(ref)).thenReturn(null);
        command.bundleContext = ctx;

        command.registerAdaptors(builder);
        verify(builder, never()).withAdaptor(any());
    }
}
