/*
 * This file is part of BlueBirdOps(tm).
 *
 * BlueBirdOps is Copyright (C) 2025 BlueBirdOps Contributors.
 *
 * Portions Copyright (C) 2002-2025 The OpenNMS Group, Inc.
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

import com.google.common.base.Strings;
import org.junit.Before;
import org.junit.FixMethodOrder;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runners.MethodSorters;
import org.openqa.selenium.By;
import org.openqa.selenium.WebElement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Year;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class AdminPageIT extends OpenNMSSeleniumIT {
    private static final Logger LOG = LoggerFactory.getLogger(AdminPageIT.class);

    private final List<String[]> m_adminPageEntries = new ArrayList<>();

    private final String[][] m_adminPageEntriesAll = new String[][] {
        // OpenNMS System
        new String[] { "System Configuration", "//span[text()='OpenNMS Configuration']" },
        new String[] { "Configure Users, Groups and On-Call Roles", "//span[text()='Users and Groups']" },
        new String[] { "Connect to Zenith", "//span[text()='Zenith Connect']" },

        // Provisioning
        new String[] { "Manage Provisioning Requisitions", "//h4[contains(text(), 'Requisitions (')]" },
        new String[] { "Import and Export Asset Information", "//span[text()='Import and Export Assets']" },
        new String[] { "Manage Surveillance Categories", "//span[text()='Surveillance Categories']" },
        new String[] { "Configure Discovery", "//span[text()='General Settings']" },
        new String[] { "Run Single Discovery Scan", "//span[text()='Exclude Ranges']" },
        new String[] { "Configure SNMP Community Names by IP Address", "//span[text()='SNMP Config Lookup']" },
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
        new String[] { "Customize Event Configurations", "//div[@id='content']/iframe[@name='mib-compiler-events']" },

        // Service Monitoring
        new String[] { "Configure Scheduled Outages", "//form//input[@value='New Name']" },
        new String[] { "Manage and Unmanage Interfaces and Services", "//span[text()='Manage and Unmanage Interfaces and Services']" },
        new String[] { "Manage Business Services", "//div[@id='content']/iframe[@name='bsm-admin-page']" },

        // Performance Measurement
        new String[] { "Configure SNMP Collections and Data Collection Groups", "//div[@id='content']/iframe[@name='mib-compiler-snmp']" },
        new String[] { "Configure SNMP Data Collection per Interface", "//span[text()='Manage SNMP Data Collection per Interface']" },
        new String[] { "Configure Thresholds", "//span[text()='Threshold Configuration']" },

        // Distributed Monitoring
        new String[] { "Manage Monitoring Locations", "//div[contains(@class,'card')]/table//tr//a[text()='Location Name']" },
        new String[] { "Manage Applications", "//span[text()='Applications']" },
        new String[] { "Manage Minions", "//div[contains(@class,'card')]/table//th/a[text()='Location']" },

        // Additional Tools
        new String[] { "Configure Grafana Endpoints (Reports only)", "//div/ul/li/a[contains(text(),'Grafana Endpoints')]" },
        new String[] { "Instrumentation Log Reader", "//span[text()='Filtering']" },
        new String[] { "SNMP MIB Compiler", "//div[@id='content']/iframe[@name='mib-compiler']" },
        new String[] { "Ops Board Configuration", "//div[@id='content']/iframe[@name='wallboard-config']" },
        new String[] { "Surveillance Views Configuration", "//div[@id='content']/iframe[@name='surveillance-views-config']" },
        new String[] { "JMX Configuration Generator", "//div[@id='content']/iframe[@name='jmx-config-ui']" },
        new String[] { "Usage Statistics Sharing", "//div[contains(@class, 'card')]//span[text()='Usage Statistics Sharing']" },
        new String[] { "Product Update Enrollment", "//div[contains(@class, 'admin-product-update-enrollment-form-wrapper')]" }
    };

    private void initAdminPageEntries() {
        // Determine actual links displayed on the Admin page based on some configuration properties
        if (m_adminPageEntries.isEmpty()) {
            boolean displayZenithConnect = Strings.nullToEmpty(System.getProperty("opennms.zenithConnect.enabled")).equals("true");

            for (final String[] entry : m_adminPageEntriesAll) {
                // omit this link if Zenith Connect is disabled
                if (!displayZenithConnect && entry[0] != null && entry[0].equals("Connect to Zenith")) {
                    continue;
                }

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

        // navigate to locations page
        getDriver().get(getBaseUrlInternal() + "opennms/locations/index.jsp");
        // add a new location
        findElementByXpath("//button[text()='Add a new location']").click();
        // wait for modal dialog to appear
        waitUntil(pageContainsText("Add a new Monitoring Location"));
        // enter the evil string...
        enterText(By.xpath("//*[@id=\"addLocationModal\"]/div/div/div/form/div[1]/input"), evilString);
        // ...and something in the other mandatory field
        enterText(By.xpath("//*[@id=\"addLocationModal\"]/div/div/div/form/div[2]/input"), "foobar");
        // hit the submit button
        findElementByXpath("//*[@id=\"addLocationModal\"]/div/div/div/form/button").click();
        // wait till the modal dialog is closed
        waitForClose(By.cssSelector(".modal-dialog"));

        // navigate to the applications page
        driver.get(getBaseUrlInternal() + "opennms/admin/applications.htm");
        // enter an application name...
        enterText(By.name("newApplicationName"), "foobar");
        // ...and create it by hitting the button
        findElementByName("newApplicationSubmit").click();
        // now click the edit link...
        findElementByXpath("//*[@id=\"content\"]/table/tbody/tr[2]/td[2]/a").click();
        // ...and check for the given text.
        // This will fail if the text is not properly escaped, as a user dialogue will appear.
        waitUntil(pageContainsText("Edit application foobar"));
    }
}
