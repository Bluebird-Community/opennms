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
package org.opennms.smoketest;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;

import java.time.Duration;

import org.junit.Test;
import org.openqa.selenium.By;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.WebDriverWait;

/**
 * End-to-end check that {@code /opennms/geomap/standalone.jsp} actually
 * mounts the leaflet map AND renders it at non-zero size. Two
 * compounding regressions live in this page:
 *
 * <ol>
 *   <li>{@code map.jsp}'s inline script used
 *       {@code $('<id>').ready(handler)}, which expanded to
 *       {@code $('map').ready(handler)} -- a tag selector matching the
 *       HTML {@code <map>} element (none on the page) combined with the
 *       jQuery 3.0 removal of {@code $(selector).ready()}, so the
 *       handler never fired and {@code geomap.render()} was never
 *       called. Caught by {@link #leafletMapMounts()}.</li>
 *   <li>{@code standalone.jsp}'s {@code refresh()} subtracted
 *       {@code $("#header").outerHeight()} from the window height to
 *       size {@code #map-container}. The {@code #header} element was
 *       removed when the top navbar moved to the side menu, so
 *       {@code outerHeight()} returned {@code undefined}, the
 *       arithmetic produced {@code NaN}, and the container collapsed
 *       to zero pixels -- leaflet then mounted a zero-size map. The
 *       DOM check alone passes in that state, so caught by the
 *       size assertion in {@link #mapContainerHasNonZeroHeight()}.</li>
 * </ol>
 */
public class GeomapStandalonePageIT extends OpenNMSSeleniumIT {

    private static final int MIN_VISIBLE_HEIGHT_PX = 200;

    @Test
    public void leafletMapMounts() {
        getDriver().get(getBaseUrlInternal() + "opennms/geomap/standalone.jsp");

        // Leaflet creates .leaflet-container as the first thing inside
        // the target div on L.map(...). If geomap.render() never ran
        // (Bug #1), this element is never created.
        new WebDriverWait(getDriver(), Duration.ofSeconds(30))
                .until(d -> !d.findElements(By.cssSelector("#map .leaflet-container")).isEmpty());

        // .leaflet-tile-pane is created by L.tileLayer(...).addTo(map),
        // which only runs after the GET /api/v2/geolocation/config call
        // succeeds. Its presence proves the full init path ran.
        assertThat(
                getDriver().findElements(By.cssSelector("#map .leaflet-tile-pane")).size(),
                greaterThan(0));
    }

    @Test
    public void mapContainerHasNonZeroHeight() {
        getDriver().get(getBaseUrlInternal() + "opennms/geomap/standalone.jsp");

        // Wait for the leaflet container so we know geomap.render ran
        // before we measure size.
        new WebDriverWait(getDriver(), Duration.ofSeconds(30))
                .until(d -> !d.findElements(By.cssSelector("#map .leaflet-container")).isEmpty());

        final WebElement mapContainer = getDriver().findElement(By.id("map-container"));
        final int containerHeight = mapContainer.getRect().getHeight();
        // Bug #2 collapsed this to 0 because refresh() did
        // window.height - footer.outerHeight() - $("#header").outerHeight()
        // and $("#header") returns nothing on a page with the modern
        // side-menu chrome, so .outerHeight() was undefined, the math
        // produced NaN, and .height(NaN) is a no-op. Without #2 fixed,
        // this assertion fails with "expected greater than 200 but was 0"
        // even though leafletMapMounts() succeeds.
        assertThat(
                "Bug #2 regression: #map-container collapsed to zero height -- height-calc subtraction produced NaN",
                containerHeight,
                greaterThan(MIN_VISIBLE_HEIGHT_PX));

        // Also assert leaflet's own container inherited a usable size,
        // so a future regression that decouples #map-container from
        // its descendants gets caught too.
        final WebElement leafletContainer = getDriver().findElement(By.cssSelector("#map .leaflet-container"));
        assertThat(
                "leaflet container collapsed to zero height",
                leafletContainer.getRect().getHeight(),
                greaterThan(MIN_VISIBLE_HEIGHT_PX));
    }

    @Test
    public void rendersWithoutQueryParams() {
        // Regression: previously, hitting the page without query params
        // emitted strategy: "null" / severity: "null" into the inline
        // script. The geomap-js isUndefinedOrNull check treated "null"
        // as a real value and forwarded it to the REST endpoint, which
        // rejected it. Confirms the fix sends the JS null literal
        // (which substitutes the documented defaults) rather than the
        // string "null".
        getDriver().get(getBaseUrlInternal() + "opennms/geomap/standalone.jsp");

        new WebDriverWait(getDriver(), Duration.ofSeconds(30))
                .until(d -> !d.findElements(By.cssSelector("#map .leaflet-container")).isEmpty());

        assertThat(
                "Severity legend control should be present after a successful render",
                getDriver().findElements(By.cssSelector(".leaflet-control")).isEmpty(),
                is(false));
    }
}
