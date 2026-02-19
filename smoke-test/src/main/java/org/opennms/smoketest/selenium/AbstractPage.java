package org.opennms.smoketest.selenium;

import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.ExpectedCondition;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

public class AbstractPage {
    protected static final Duration SHORT_WAIT = Duration.ofSeconds(5);
    protected static final Duration LONG_WAIT = Duration.ofSeconds(10);

    protected final AbstractOpenNMSSeleniumHelper testCase;

    public AbstractPage(AbstractOpenNMSSeleniumHelper testCase) {
        this.testCase = Objects.requireNonNull(testCase);
    }

    protected WebDriver getDriver() {
        return testCase.getDriver();
    }

    protected void get(String path) {
        final String fullURL = testCase.buildUrlInternal(path);
        getDriver().get(fullURL);
    }

    protected List<WebElement> findElements(By by) {
        try {
            testCase.setImplicitWait(LONG_WAIT);
            return getDriver().findElements(by);
        } finally {
            testCase.setImplicitWait();
        }
    }

    protected WebElement findElement(By by) {
        try {
            testCase.setImplicitWait(SHORT_WAIT);
            return getDriver().findElement(by);
        } finally {
            testCase.setImplicitWait();
        }
    }

    protected WebElement findElementByName(final String name) {
        try {
            testCase.setImplicitWait(SHORT_WAIT);
            return testCase.findElementByName(name);
        } finally {
            testCase.setImplicitWait();
        }
    }

    protected WebElement findElementByXpath(final String xpath) {
        try {
            testCase.setImplicitWait(SHORT_WAIT);
            return testCase.findElementByXpath(xpath);
        } finally {
            testCase.setImplicitWait();
        }
    }

    protected WebElement clickElement(final By by) {
        try {
            testCase.setImplicitWait(SHORT_WAIT);
            return testCase.clickElement(by);
        } finally {
            testCase.setImplicitWait();
        }
    }

    protected WebElement enterText(final By by, final String text) {
        return testCase.enterText(by, text);
    }

    protected void waitUntil(ExpectedCondition<Boolean> condition) {
        try {
            testCase.setImplicitWait(LONG_WAIT);
            new WebDriverWait(getDriver(), LONG_WAIT).until(condition);
        } finally {
            testCase.setImplicitWait();
        }
    }

    protected ExpectedCondition<Boolean> pageContainsText(String text) {
        return testCase.pageContainsText(text);
    }
}
