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
package org.opennms.reporting.availability.render;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Callable;

import javax.xml.XMLConstants;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;

import org.apache.commons.io.IOUtils;
import org.opennms.core.logging.Logging;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;

/**
 * HTMLReportRenderer will transform its input XML into HTML using the
 * supplied XSLT resource.
 *
 * @author <a href="mailto:jonathan@opennms.org">Jonathan Sartin</a>
 */
public class HTMLReportRenderer implements ReportRenderer {
    private static final Logger LOG = LoggerFactory.getLogger(HTMLReportRenderer.class);

    private static final String LOG4J_CATEGORY = "reports";

    private String m_outputFileName;

    private String m_inputFileName;

    private Resource m_xsltResource;

    private String m_baseDir;

    /**
     * <p>Constructor for HTMLReportRenderer.</p>
     */
    public HTMLReportRenderer() {
    }

    /**
     * <p>render</p>
     *
     * @throws org.opennms.reporting.availability.render.ReportRenderException if any.
     */
    @Override
    public void render() throws ReportRenderException {
        render(m_inputFileName, m_outputFileName, m_xsltResource);
    }

    /** {@inheritDoc} */
    @Override
    public byte[] render(final String inputFileName, final Resource xsltResource) throws ReportRenderException {
        try {
            return Logging.withPrefix(LOG4J_CATEGORY, new Callable<byte[]>() {
                @Override public byte[] call() throws Exception {
                    LOG.debug("Rendering {} with XSL File {} to byte array", inputFileName, xsltResource.getDescription());

                    ByteArrayOutputStream outputStream = null;
                    try {
                        outputStream = new ByteArrayOutputStream();
                        render(inputFileName, outputStream, xsltResource);
                        return outputStream.toByteArray();
                    } finally {
                        IOUtils.closeQuietly(outputStream);
                    }
                }
            });
        } catch (final Exception e) {
            if (e instanceof ReportRenderException) throw (ReportRenderException)e;
            throw new ReportRenderException("Failed to render " + inputFileName, e);
        }
    }

    /** {@inheritDoc} */
    @Override
    public void render(final String inputFileName, final OutputStream outputStream, final Resource xsltResource) throws ReportRenderException {
        try {
            Logging.withPrefix(LOG4J_CATEGORY, new Callable<Void>() {
                @Override public Void call() throws Exception {
                    LOG.debug("Rendering {} with XSL File {} to OutputStream", inputFileName, xsltResource.getDescription());

                    FileInputStream in = null, xslt = null;
                    try {
                        in = new FileInputStream(xsltResource.getFile());
                        Reader xsl = new InputStreamReader(in, StandardCharsets.UTF_8);
                        xslt = new FileInputStream(inputFileName);
                        Reader xml = new InputStreamReader(xslt, StandardCharsets.UTF_8);

                        render(xml, outputStream, xsl);
                    } finally {
                        IOUtils.closeQuietly(in);
                        IOUtils.closeQuietly(xslt);
                    }
                    return null;
                }
            });
        } catch (final Exception e) {
            if (e instanceof ReportRenderException) throw (ReportRenderException)e;
            throw new ReportRenderException("Failed to render " + inputFileName, e);
        }

    }

    /** {@inheritDoc} */
    @Override
    public void render(final InputStream inputStream, final OutputStream outputStream, final Resource xsltResource) throws ReportRenderException {
        try {
            Logging.withPrefix(LOG4J_CATEGORY, new Callable<Void>() {
                @Override public Void call() throws Exception {
                    LOG.debug("Rendering InputStream with XSL File {} to OutputStream", xsltResource.getDescription());

                    FileInputStream xslt = null;
                    Reader xsl = null;
                    Reader xml = null;
                    try {
                        xslt = new FileInputStream(xsltResource.getFile());
                        xsl = new InputStreamReader(xslt, StandardCharsets.UTF_8);
                        xml = new InputStreamReader(inputStream, StandardCharsets.UTF_8);
                        render(xml, outputStream, xsl);
                        return null;
                    } finally {
                        IOUtils.closeQuietly(xml);
                        IOUtils.closeQuietly(xsl);
                        IOUtils.closeQuietly(xslt);
                    }
                }
            });
        } catch (final Exception e) {
            if (e instanceof ReportRenderException) throw (ReportRenderException)e;
            throw new ReportRenderException(e);
        }
    }

    /** {@inheritDoc} */
    @Override
    public void render(final String inputFileName, final String outputFileName, final Resource xsltResource) throws ReportRenderException {
        try {
            Logging.withPrefix(LOG4J_CATEGORY, new Callable<Void>() {
                @Override public Void call() throws Exception {

                    LOG.debug("Rendering {} with XSL File {} to {} with base directory of {}", m_baseDir, inputFileName, xsltResource.getDescription(), outputFileName);

                    FileInputStream in = null, xslt = null;
                    FileOutputStream out = null;
                    Reader xsl = null, xml = null;
                    try {

                        xslt = new FileInputStream(xsltResource.getFile());
                        xsl = new InputStreamReader(xslt, StandardCharsets.UTF_8);
                        in = new FileInputStream(m_baseDir + "/" + inputFileName);
                        xml = new InputStreamReader(in, StandardCharsets.UTF_8);

                        out = new FileOutputStream(new File(m_baseDir + "/" + outputFileName));

                        render(xml, out, xsl);
                        return null;
                    } finally {
                        IOUtils.closeQuietly(xml);
                        IOUtils.closeQuietly(xsl);
                        IOUtils.closeQuietly(in);
                        IOUtils.closeQuietly(out);
                        IOUtils.closeQuietly(xslt);
                    }
                }
            });
        } catch (final Exception e) {
            if (e instanceof ReportRenderException) throw (ReportRenderException)e;
            throw new ReportRenderException(e);
        }
    }

    /**
     * <p>render</p>
     *
     * @param in a {@link java.io.Reader} object.
     * @param out a {@link java.io.OutputStream} object.
     * @param xslt a {@link java.io.Reader} object.
     * @throws org.opennms.reporting.availability.render.ReportRenderException if any.
     */
    public void render(final Reader in, final OutputStream out, final Reader xslt) throws ReportRenderException {
        try {
            TransformerFactory tfact = TransformerFactory.newInstance();

            // see NMS-16414
            tfact.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);

            Transformer transformer = tfact.newTransformer(new StreamSource(xslt));
            transformer.setOutputProperty(OutputKeys.ENCODING, StandardCharsets.UTF_8.name());
            transformer.transform(new StreamSource(in), new StreamResult(out));
        } catch (final Exception e) {
            if (e instanceof ReportRenderException) throw (ReportRenderException)e;
            throw new ReportRenderException(e);
        }
    }

    /** {@inheritDoc} */
    @Override
    public void setXsltResource(Resource xsltResource) {
        this.m_xsltResource = xsltResource;
    }

    /** {@inheritDoc} */
    @Override
    public void setOutputFileName(String outputFileName) {
        this.m_outputFileName = outputFileName;
    }

    /**
     * <p>getOutputFileName</p>
     *
     * @return a {@link java.lang.String} object.
     */
    @Override
    public String getOutputFileName() {
        return m_outputFileName;
    }

    /** {@inheritDoc} */
    @Override
    public void setInputFileName(String inputFileName) {
        this.m_inputFileName = inputFileName;
    }

    /** {@inheritDoc} */
    @Override
    public void setBaseDir(String baseDir) {
        this.m_baseDir = baseDir;
    }

    /**
     * <p>getBaseDir</p>
     *
     * @return a {@link java.lang.String} object.
     */
    @Override
    public String getBaseDir() {
        return m_baseDir;
    }
}
