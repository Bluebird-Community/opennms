/*
 * This file is part of BlueBirdOps(tm).
 *
 * BlueBirdOps is Copyright (C) 2026 BlueBirdOps Contributors.
 *
 * Portions Copyright (C) 2002-2026 The OpenNMS Group, Inc.
 *
 * See the LICENSE.md file distributed with this work for additional
 * information regarding copyright ownership.
 *
 * BlueBirdOps is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at your
 * option) any later version.
 *
 * BlueBirdOps is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License
 * for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with BlueBirdOps. If not, see <https://www.gnu.org/licenses/>.
 */
package org.opennms.smoketest;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.FixMethodOrder;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runners.MethodSorters;
import org.opennms.netmgt.model.monitoringLocations.OnmsMonitoringLocation;
import org.openqa.selenium.By;
import org.openqa.selenium.StaleElementReferenceException;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Year;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.ws.rs.core.Response;

@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class AdminPageIT extends OpenNMSSeleniumIT {
    private static final Logger LOG = LoggerFactory.getLogger(AdminPageIT.class);

    private final List<String[]> m_adminPageEntries = new ArrayList<>();

    private final String[][] m_adminPageEntriesAll = new String[][] {
        // OpenNMS System
        new String[] { "System Configuration", "//span[text()='OpenNMS Configuration']" },
        new String[] { "Configure Users, Groups and On-Call Roles", "//span[text()='Users and Groups']" },

        // Provisioning
        new String[] { "Manage Provisioning Requisitions", "//h4[contains(text(), 'Requisitions (')]" },
        new String[] { "Import and Export Asset Information", "//span[text()='Import and Export Assets']" },
        new String[] { "Manage Surveillance Categories", "//span[text()='Surveillance Categories']" },
        new String[] { "Configure Discovery", "//span[text()='General Settings']" },
        new String[] { "Run Single Discovery Scan", "//span[text()='Exclude Ranges']" },
        new String[] { "Configure SNMP Community Names by IP Address", "//h2[text()='Manage SNMP Configuration']" },
        new String[] { "Manually Add an Interface", "//span[text()='Enter IP Address']" },
        new String[] { "Delete Nodes", "//span[text()='Delete Nodes']" },
        new String[] { "Configure External Requisitions", "//h1[contains(text(), 'Configuration')]" },
        new String[] { "Configure Geocoder Service", "//div/nav/ol/li[text()='Geocoder Configuration']" },
        new String[] { "Secure Credentials Vault", "//*[text()='Add Credentials']" },

        // Flow Management
        new String[] { "Manage Flow Classification", "//div/nav/ol/li[text()='Flow Classification']" },

        // Event Management
        new String[] { "Manually Send an Event", "//span[text()='Send Event to OpenNMS']" },
        new String[] { "Configure Notifications", "//span[text()='Configure Notifications']" },
        new String[] { "Manage Event Configurations", "//div[@id='app']//div[@class='event-config']//div[@class='heading']/h1[text()='Manage Event Configurations']" },

        // Service Monitoring
        new String[] { "Configure Scheduled Outages", "//div[@id='app']//div[@class='card-title' and text()='Scheduled Outages']" },
        new String[] { "Manage and Unmanage Interfaces and Services", "//span[text()='Manage and Unmanage Interfaces and Services']" },

        // Performance Measurement
        new String[] { "Manage SNMP Data Collection Config", "//div[@id='app']//h1[text()='Manage SNMP Data Collection Sources']" },
        new String[] { "Configure SNMP Data Collection per Interface", "//span[text()='Manage SNMP Data Collection per Interface']" },
        new String[] { "Configure Thresholds", "//div[@id='app']//h1[text()='Threshold Configuration']" },

        // Distributed Monitoring
        // both links open the same /ui (Vue) page on different tabs; the xpath requires the title and the selected tab
        new String[] { "Manage Monitoring Locations", "//div[@id='app'][.//h1[@class='page-title' and normalize-space(text())='Manage Minions and Locations']]//*[@data-test='tab-locations' and @aria-selected='true']" },
        new String[] { "Manage Applications", "//div[@id='app']//h1[@class='page-title' and normalize-space(text())='Manage Applications']" },
        new String[] { "Manage Minions", "//div[@id='app'][.//h1[@class='page-title' and normalize-space(text())='Manage Minions and Locations']]//*[@data-test='tab-minions' and @aria-selected='true']" },

        // Additional Tools
        new String[] { "Configure Grafana Endpoints (Reports only)", "//div/ul/li/a[contains(text(),'Grafana Endpoints')]" },
        new String[] { "Instrumentation Log Reader", "//span[text()='Filtering']" },
        new String[] { "Usage Statistics Sharing", "//div[contains(@class, 'card')]//span[text()='Usage Statistics Sharing']" },
        new String[] { "Product Update Enrollment", "//div[contains(@class, 'admin-product-update-enrollment-form-wrapper')]" }
    };

    private void initAdminPageEntries() {
        // Determine actual links displayed on the Admin page based on some configuration properties
        if (m_adminPageEntries.isEmpty()) {
            for (final String[] entry : m_adminPageEntriesAll) {
                m_adminPageEntries.add(entry);
            }
        }
    }

    @Before
    public void setUp() throws Exception {
        initAdminPageEntries();
        adminPage();
    }

    @Test
    public void testAllTextIsPresent() throws Exception {
        assertEquals(9, countElementsMatchingCss("div.card-header"));
        findElementByXpath("//span[text()='OpenNMS System']");
        findElementByXpath("//span[text()='Provisioning']");
        findElementByXpath("//span[text()='Flow Management']");
        findElementByXpath("//span[text()='Event Management']");
        findElementByXpath("//span[text()='Service Monitoring']");
        findElementByXpath("//span[text()='Performance Measurement']");
        findElementByXpath("//span[text()='Distributed Monitoring']");
        findElementByXpath("//span[text()='Additional Tools']");
        findElementByXpath("//span[text()='Descriptions']");
    }

    @Test
    public void testAllLinks() throws Exception {
        LOG.debug("In testAllLinks");

        adminPage();
        findElementById("content");
        findElementByXpath("//div[contains(@class,'card-body')]");

        final int count = countElementsMatchingCss("div.card-body > ul > li > a");
        assertEquals("We expect " + m_adminPageEntries.size() + " link entries on the admin page.", m_adminPageEntries.size(), count);

        for (final String[] entry : m_adminPageEntries) {
            LOG.debug("Looking for link element: '{}', page with xpath: {}", entry[0], entry[1]);

            adminPage();

            WebElement linkElement = findElementByLink(entry[0]);
            assertNotNull("Did not find link element", linkElement);

            scrollToElement(linkElement);
            linkElement.click();

            waitForElement(By.xpath(entry[1]));
        }
    }

    @Test
    @Ignore
    public void testCopyrightYear() {
        LOG.info("Starting testCopyrightYear");
        login();
        adminPage();

        WebElement footerElem = findElementByXpath("//div[@id='content']/footer[@id='footer']");

        assertNotNull("Did not find footer", footerElem);
        String footer = footerElem.getText();

        Year thisYear = Year.now();
        Pattern pattern = Pattern.compile("\\d{4}-" + thisYear, Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(footer);
        boolean matchFound = matcher.find();

        assertTrue("Is the year in the footer equal to current? - ", matchFound);
    }

    @Test
    public void testNMS16443() {
        // this is a malicious string that should be properly escaped
        final String evilString = "foobar'\"><script>alert(document.domain)</script>";

        // the location editor refuses these characters, so the location is created over REST
        final Response response = stack.opennms().getRestClient().addMonitoringLocation(new OnmsMonitoringLocation(evilString, "foobar"));
        assertEquals(Response.Status.Family.SUCCESSFUL, response.getStatusInfo().getFamily());

        // the Monitoring Locations tab must show the name as text; a script that ran would raise an alert and fail the wait
        getDriver().get(getBaseUrlInternal() + "opennms/ui/index.html#/distributed-monitoring?tab=locations");
        wait.until(driver -> {
            try {
                return driver.findElements(By.cssSelector("[data-test='locations-table'] td")).stream()
                        .anyMatch(cell -> evilString.equals(cell.getText().trim()));
            } catch (final StaleElementReferenceException e) {
                // the table re-renders as its data loads; poll again
                return false;
            }
        });

        // create an application on Manage Applications; saving it opens its editor
        getDriver().get(getBaseUrlInternal() + "opennms/ui/index.html#/admin/applications");
        wait.until(ExpectedConditions.elementToBeClickable(By.cssSelector("[data-test='add-application-button']"))).click();
        wait.until(ExpectedConditions.visibilityOfElementLocated(By.cssSelector("[data-test='application-name-input']"))).sendKeys("foobar");
        getDriver().findElement(By.cssSelector("[data-test='application-create-dialog'] [data-test='save-button']")).click();

        // the editor offers the location as a perspective; it must be listed as text, not run
        wait.until(ExpectedConditions.elementToBeClickable(By.cssSelector("[data-test='perspective-locations']"))).click();
        wait.until(driver -> {
            try {
                return driver.findElements(By.cssSelector(".p-multiselect-option")).stream()
                        .anyMatch(option -> evilString.equals(option.getText().trim()));
            } catch (final StaleElementReferenceException e) {
                return false;
            }
        });
    }
}
