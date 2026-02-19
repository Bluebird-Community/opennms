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

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opennms.smoketest.stacks.OverlayFile;

import com.google.common.collect.Lists;

public class OverlayUtilsTest {

    @TempDir
    public java.nio.file.Path temporaryFolder;

    @Test
    public void canOverlayFilesAndFolders() throws IOException {
        File source = temporaryFolder.resolve("source").toFile();
        assertTrue(source.mkdirs(), "Failed to create source directory");

        File a = new File(source, "a");
        assertThat(a.createNewFile(), equalTo(true));
        File b = new File(source, "b");
        assertThat(b.mkdirs(), equalTo(true));
        File c = new File(b, "c");
        assertThat(c.createNewFile(), equalTo(true));

        File target = temporaryFolder.resolve("target").toFile();
        assertTrue(target.mkdirs(), "Failed to create target directory");

        OverlayUtils.copyFiles(Lists.newArrayList(new OverlayFile(a.toURI().toURL(), "a"),
                new OverlayFile(b.toURI().toURL(), "b"),
                new OverlayFile(c.toURI().toURL(), "c")),
                target.toPath());

        // Verify
        assertThat(target.toPath().resolve("a").toFile().isFile(), equalTo(true));
        assertThat(target.toPath().resolve("b").toFile().isDirectory(), equalTo(true));
        assertThat(target.toPath().resolve("b").resolve("c").toFile().isFile(), equalTo(true));
        assertThat(target.toPath().resolve("c").toFile().isFile(), equalTo(true));
    }

    @Test
    public void testMergingMaps() {
        Map<String, Object> map1 = new HashMap<>();
        map1.put("base", "basevalue");

        Map<String, Object> submap1 = new HashMap<>();
        String originalKey = "submapbase";
        String originalValue = "submapvalue";
        submap1.put(originalKey, originalValue);

        String submapKey = "submap";
        map1.put(submapKey, submap1);

        Map<String, Object> newMap = new HashMap<>();
        Map<String, Object> newSubmap = new HashMap<>();
        String newKey = "newsub";
        String newValue = "newsubvalue";

        newSubmap.put(newKey, newValue);
        newMap.put(submapKey, newSubmap);

        OverlayUtils.mergeMaps(map1, newMap);

        Map<String, String> expectedMap = new HashMap<>();
        expectedMap.put(originalKey, originalValue);
        expectedMap.put(newKey, newValue);

        assertThat(map1.get(submapKey), equalTo(expectedMap));
    }
}
