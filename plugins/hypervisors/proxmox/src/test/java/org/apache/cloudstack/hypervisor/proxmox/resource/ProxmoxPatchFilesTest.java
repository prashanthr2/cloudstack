// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.
package org.apache.cloudstack.hypervisor.proxmox.resource;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

import org.apache.cloudstack.hypervisor.proxmox.client.ProxmoxApiException;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class ProxmoxPatchFilesTest {
    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private Path dir;
    private ProxmoxPatchFiles files;

    @Before
    public void setUp() throws Exception {
        dir = folder.newFolder().toPath();
        Files.write(dir.resolve("agent.zip"), "abc".getBytes(StandardCharsets.UTF_8));
        files = new ProxmoxPatchFiles(dir);
    }

    @Test
    public void theChecksumIsTheSha512InHexadecimalLowerCase() {
        // sha512("abc")
        assertEquals("ddaf35a193617abacc417349ae20413112e6fa4e89a97ea20a9eeee64b55d39a2192992a274fc1a836ba3c23a3feebbd454d4423643ce80e2a9ac94fa54ca49f",
                files.sha512("agent.zip"));
    }

    @Test
    public void theSizeAndTheBytesAreThoseOfTheFile() {
        assertEquals(3, files.size("agent.zip"));
        assertArrayEquals("abc".getBytes(StandardCharsets.UTF_8), files.bytes("agent.zip"));
    }

    @Test
    public void aChangedFileGetsANewChecksumAndAnUnchangedOneKeepsIt() throws Exception {
        String first = files.sha512("agent.zip");
        assertEquals(first, files.sha512("agent.zip"));

        Files.write(dir.resolve("agent.zip"), "abcd".getBytes(StandardCharsets.UTF_8));
        Files.setLastModifiedTime(dir.resolve("agent.zip"), FileTime.fromMillis(System.currentTimeMillis() + 5000));

        assertTrue(!first.equals(files.sha512("agent.zip")));
    }

    @Test
    public void aMissingFileIsReportedWithItsNameAndTheDirectory() {
        try {
            files.size("cloud-scripts.tgz");
            fail("expected a message");
        } catch (ProxmoxApiException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("cloud-scripts.tgz") && e.getMessage().contains(dir.toString())
                    && e.getMessage().contains("cloudstack-common"));
        }
    }
}
