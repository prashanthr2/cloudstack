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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.cloudstack.hypervisor.proxmox.client.ProxmoxApiClient;
import org.apache.cloudstack.hypervisor.proxmox.ssh.ExecResult;
import org.apache.cloudstack.hypervisor.proxmox.ssh.ProxmoxSsh;
import org.apache.cloudstack.hypervisor.proxmox.ssh.ProxmoxSshException;
import org.apache.cloudstack.hypervisor.proxmox.ssh.ScriptedSshLink;
import org.apache.cloudstack.hypervisor.proxmox.ssh.SshTarget;
import org.apache.cloudstack.storage.command.CopyCmdAnswer;
import org.apache.cloudstack.storage.to.PrimaryDataStoreTO;
import org.apache.cloudstack.storage.to.TemplateObjectTO;
import org.apache.cloudstack.storage.to.VolumeObjectTO;
import org.junit.Before;
import org.junit.Test;

import com.cloud.agent.api.to.NfsTO;
import com.cloud.storage.DataStoreRole;
import com.cloud.storage.Storage;

/** Step 4 of design-phase11: a template on the secondary store made from a volume. */
public class ProxmoxTemplatesFromVolumeTest {
    private static final String POOL_UUID = "7fd5565c-6c1e-3032-81ec-d404812915ab";
    private static final String TEMPLATE_UUID = "3f8302d1-fa8e-4606-8aae-0e225e2e81cd";
    private static final String FOLDER = "template/tmpl/2/301";
    private static final String VOLUME_PATH = "104/vm-104-disk-0.qcow2";
    private static final String FILE = "/mnt/" + POOL_UUID + "/images/" + VOLUME_PATH;

    private ScriptedSshLink link;
    private ProxmoxTemplates templates;
    private final Map<String, ExecResult> replies = new LinkedHashMap<>();
    private String info = "{\"format\": \"qcow2\", \"virtual-size\": 10737418240, \"backing-filename\": \"/mnt/x/base.qcow2\"}";
    private int logins;

    @Before
    public void setUp() {
        link = new ScriptedSshLink();
        link.answers = command -> {
            for (Map.Entry<String, ExecResult> reply : replies.entrySet()) {
                if (command.contains(reply.getKey())) {
                    return reply.getValue();
                }
            }
            if (command.contains("qemu-img info")) {
                return new ExecResult(0, info, "");
            }
            if (command.contains("stat -c %s")) {
                return new ExecResult(0, "1234567\n", "");
            }
            return new ExecResult(0, "", "");
        };
        templates = new ProxmoxTemplates(() -> mock(ProxmoxApiClient.class), "pve1", () -> {
            logins++;
            return ProxmoxSsh.open(new SshTarget("10.0.0.1", 22, "root", null, "pw", null), () -> link);
        }, new ProxmoxSecondaryMounts(() -> ProxmoxSecondaryMounts.DEFAULT_OPTIONS), () -> 600, millis -> { });
    }

    private static VolumeObjectTO volume(String path) {
        PrimaryDataStoreTO pool = mock(PrimaryDataStoreTO.class);
        when(pool.getUuid()).thenReturn(POOL_UUID);
        VolumeObjectTO volume = mock(VolumeObjectTO.class);
        when(volume.getDataStore()).thenReturn(pool);
        when(volume.getPath()).thenReturn(path);
        return volume;
    }

    private static TemplateObjectTO destination(String folder, String uuid) {
        NfsTO store = new NfsTO("nfs://10.0.32.4/acs/secondary", DataStoreRole.Image);
        store.setNfsVersion("3");
        TemplateObjectTO template = mock(TemplateObjectTO.class);
        when(template.getDataStore()).thenReturn(store);
        when(template.getPath()).thenReturn(folder);
        when(template.getUuid()).thenReturn(uuid);
        when(template.getId()).thenReturn(301L);
        when(template.getName()).thenReturn("301-2-abc");
        return template;
    }

    private boolean ran(String text) {
        return !link.commandsWith(text).isEmpty();
    }

    private static void refused(CopyCmdAnswer answer, String... words) {
        assertFalse(answer.getDetails(), answer.getResult());
        for (String word : words) {
            assertTrue(answer.getDetails(), answer.getDetails().contains(word));
        }
    }

    @Test
    public void aVolumeIsConvertedIntoTheTemplateFolderUnderTheTemplateUuidAndTheAnswerHasPathAndSizes() {
        CopyCmdAnswer answer = templates.createFromVolume(volume(VOLUME_PATH), destination("/" + FOLDER, TEMPLATE_UUID), 3600);

        assertTrue(answer.getDetails(), answer.getResult());
        TemplateObjectTO made = (TemplateObjectTO) answer.getNewData();
        assertEquals(FOLDER + "/" + TEMPLATE_UUID + ".qcow2", made.getPath());
        assertEquals(10737418240L, (long) made.getSize());
        assertEquals(1234567L, (long) made.getPhysicalSize());
        assertEquals(Storage.ImageFormat.QCOW2, made.getFormat());
        String mount = link.commandsWith("mount -t nfs").get(0);
        assertTrue(mount, mount.contains("'rw,vers=3,soft,timeo=30,retrans=2,nosharecache'"));
        String directory = mount.substring(mount.indexOf('\'') + 1, mount.indexOf('\'', mount.indexOf('\'') + 1));
        String convert = link.commandsWith("qemu-img convert").get(0);
        assertEquals("qemu-img convert -O qcow2 '" + FILE + "' '" + directory + "/" + FOLDER + "/" + TEMPLATE_UUID + ".qcow2.tmp'", convert);
        assertTrue(ran("mv -f '" + directory + "/" + FOLDER + "/" + TEMPLATE_UUID + ".qcow2.tmp' '" + directory + "/" + FOLDER + "/" + TEMPLATE_UUID + ".qcow2'"));
        assertTrue(link.commands.toString(), link.commands.get(link.commands.size() - 1).contains("umount"));
        assertEquals(1, logins);
    }

    @Test
    public void theStoreIsMountedForTheOneCommandAndTheOrderIsMountMkdirConvertMoveSizePropertiesUnmount() {
        templates.createFromVolume(volume(VOLUME_PATH), destination(FOLDER, TEMPLATE_UUID), 3600);

        int mount = indexOf("mount -t nfs");
        int mkdir = indexOf("mkdir -p '/mnt/css-tmp-" + "" , FOLDER);
        int convert = indexOf("qemu-img convert");
        int move = indexOf("mv -f");
        int stat = indexOf("stat -c %s");
        int properties = indexOf("cat > '/mnt/css-tmp-");
        int unmount = indexOf("umount '/mnt/css-tmp-");
        assertTrue(link.commands.toString(), mount < convert && mkdir < convert && convert < move && move < stat && stat < properties && properties < unmount);
    }

    private int indexOf(String text) {
        return indexOf(text, "");
    }

    private int indexOf(String text, String also) {
        for (int i = 0; i < link.commands.size(); i++) {
            if (link.commands.get(i).contains(text) && link.commands.get(i).contains(also)) {
                return i;
            }
        }
        return Integer.MAX_VALUE;
    }

    @Test
    public void thePropertiesFileIsTheOneCloudStackWritesAndNamesTheFileAndTheSizes() {
        templates.createFromVolume(volume(VOLUME_PATH), destination(FOLDER, TEMPLATE_UUID), 3600);

        String properties = new String(link.lastInput, StandardCharsets.UTF_8);
        assertTrue(properties, properties.contains("id=301"));
        assertTrue(properties, properties.contains("uniquename=301-2-abc"));
        assertTrue(properties, properties.contains("filename=" + TEMPLATE_UUID + ".qcow2"));
        assertTrue(properties, properties.contains("qcow2.filename=" + TEMPLATE_UUID + ".qcow2"));
        assertTrue(properties, properties.contains("qcow2.virtualsize=10737418240") && properties.contains("virtualsize=10737418240"));
        assertTrue(properties, properties.contains("qcow2.size=1234567") && properties.contains("size=1234567"));
        assertTrue(properties, properties.contains("qcow2=true"));
    }

    @Test
    public void aVolumeWithABackingFileIsReadThroughItAndACopyThatNeedsNoneIsNotRefused() {
        assertTrue(templates.createFromVolume(volume(VOLUME_PATH), destination(FOLDER, TEMPLATE_UUID), 3600).getResult());
        info = "{\"format\": \"qcow2\", \"virtual-size\": 1073741824}";
        assertTrue(templates.createFromVolume(volume(VOLUME_PATH), destination(FOLDER, TEMPLATE_UUID), 3600).getResult());
    }

    @Test
    public void aDataVolumeOfAHolderIsAcceptedLikeAnyOther() {
        String holder = "900000002/vm-900000002-6ddd9e96-1804-4324-b39a-a04a2c5e127e.qcow2";

        assertTrue(templates.createFromVolume(volume(holder), destination(FOLDER, TEMPLATE_UUID), 3600).getResult());
        assertTrue(ran("'/mnt/" + POOL_UUID + "/images/" + holder + "'"));
    }

    @Test
    public void aTemplateWithoutAUuidGetsARandomOneAndStillHasAFileName() {
        CopyCmdAnswer answer = templates.createFromVolume(volume(VOLUME_PATH), destination(FOLDER, null), 3600);

        assertTrue(answer.getDetails(), answer.getResult());
        assertTrue(((TemplateObjectTO) answer.getNewData()).getPath(), ((TemplateObjectTO) answer.getNewData()).getPath().matches(FOLDER + "/[0-9a-f-]{36}\\.qcow2"));
    }

    // ---- refusals, before anything is touched ----

    @Test
    public void whatIsNotAVolumeOnPrimaryToNfsOrIsNotAProxmoxDiskIsRefusedBeforeAnySshLogin() {
        refused(templates.createFromVolume(volume("104/base-104-disk-0.qcow2"), destination(FOLDER, TEMPLATE_UUID), 3600), "not a Proxmox qcow2 disk");
        refused(templates.createFromVolume(volume("x; reboot"), destination(FOLDER, TEMPLATE_UUID), 3600), "not a Proxmox qcow2 disk");
        refused(templates.createFromVolume(volume(null), destination(FOLDER, TEMPLATE_UUID), 3600), "not a Proxmox qcow2 disk");
        refused(templates.createFromVolume(volume(VOLUME_PATH), destination("../etc", TEMPLATE_UUID), 3600), "must not contain");
        refused(templates.createFromVolume(volume(VOLUME_PATH), destination("a b/c", TEMPLATE_UUID), 3600), "relative path");
        TemplateObjectTO notNfs = destination(FOLDER, TEMPLATE_UUID);
        when(notNfs.getDataStore()).thenReturn(mock(PrimaryDataStoreTO.class));
        refused(templates.createFromVolume(volume(VOLUME_PATH), notNfs, 3600), "NFS secondary store");
        VolumeObjectTO encrypted = volume(VOLUME_PATH);
        when(encrypted.requiresEncryption()).thenReturn(true);
        refused(templates.createFromVolume(encrypted, destination(FOLDER, TEMPLATE_UUID), 3600), "encrypted");
        assertEquals(0, logins);
    }

    // ---- failures leave nothing ----

    @Test
    public void aMissingVolumeFileFailsBeforeAnythingIsWrittenAndTheStoreIsUnmounted() {
        replies.put("test -f '" + FILE + "'", new ExecResult(1, "", ""));

        refused(templates.createFromVolume(volume(VOLUME_PATH), destination(FOLDER, TEMPLATE_UUID), 3600), "does not exist on the primary storage");

        assertFalse(ran("qemu-img convert"));
        assertFalse(ran("/" + FOLDER + "'"));
        assertTrue(ran("umount '/mnt/css-tmp-"));
    }

    @Test
    public void aFailedConvertRemovesTheTemporaryFileAndTheFolderItMade() {
        replies.put("test -d", new ExecResult(1, "", ""));
        replies.put("qemu-img convert", new ExecResult(1, "", "No space left on device"));

        refused(templates.createFromVolume(volume(VOLUME_PATH), destination(FOLDER, TEMPLATE_UUID), 3600), "No space left on device");

        String cleanup = link.commandsWith("rm -f").get(0);
        assertTrue(cleanup, cleanup.contains(TEMPLATE_UUID + ".qcow2.tmp") && cleanup.contains("template.properties") && cleanup.contains("rmdir"));
        assertTrue(ran("umount"));
    }

    @Test
    public void aFolderThatExistedIsKept() {
        replies.put("qemu-img convert", new ExecResult(1, "", "boom"));

        templates.createFromVolume(volume(VOLUME_PATH), destination(FOLDER, TEMPLATE_UUID), 3600);

        assertFalse(link.commandsWith("rm -f").get(0).contains("rmdir"));
    }

    @Test
    public void aMountThatFailsFailsTheCommandWithTheReasonAndConvertsNothing() {
        replies.put("mount -t nfs", new ExecResult(124, "", ""));

        refused(templates.createFromVolume(volume(VOLUME_PATH), destination(FOLDER, TEMPLATE_UUID), 3600), "Could not mount the secondary store");

        assertFalse(ran("qemu-img convert"));
    }

    @Test
    public void withoutAnSshLoginTheCommandFailsWithTheReason() {
        ProxmoxTemplates without = new ProxmoxTemplates(() -> mock(ProxmoxApiClient.class), "pve1", () -> {
            throw new ProxmoxSshException("no SSH login");
        }, new ProxmoxSecondaryMounts(() -> ProxmoxSecondaryMounts.DEFAULT_OPTIONS), () -> 600, millis -> { });

        refused(without.createFromVolume(volume(VOLUME_PATH), destination(FOLDER, TEMPLATE_UUID), 3600), "no SSH login");
    }
}
