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

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.cloudstack.hypervisor.proxmox.client.ProxmoxApiException;

/**
 * The files that patch a system VM, as the management server has them (the same ones the KVM host copies into a VM over SSH):
 * {@code agent.zip} (the Java agent of the secondary storage VM and the console proxy VM, about 110 MiB), {@code cloud-scripts.tgz} and
 * {@code patch-sysvms.sh}. They come with the package {@code cloudstack-common}, in {@code /usr/share/cloudstack-common/vms/}.
 */
public class ProxmoxPatchFiles {
    public static final String AGENT_ZIP = "agent.zip";
    public static final String SCRIPTS = "cloud-scripts.tgz";
    public static final String PATCH_SCRIPT = "patch-sysvms.sh";
    static final String DEFAULT_DIRECTORY = "/usr/share/cloudstack-common/vms/";

    private final Path directory;
    private final Map<String, Hashed> hashes = new ConcurrentHashMap<>();

    /** A file's SHA-512, kept until the file changes (the agent is 110 MiB, so it is not read at every start). */
    private static final class Hashed {
        private final long size;
        private final long modified;
        private final String sha512;

        private Hashed(long size, long modified, String sha512) {
            this.size = size;
            this.modified = modified;
            this.sha512 = sha512;
        }
    }

    public ProxmoxPatchFiles(Path directory) {
        this.directory = directory;
    }

    private static volatile ProxmoxPatchFiles located;

    /**
     * Where the files are on this management server: on the class path (a source build) or in the package directory. One instance serves
     * every host, so the checksums that it keeps are computed once.
     */
    public static ProxmoxPatchFiles locate() {
        ProxmoxPatchFiles files = located;
        if (files == null) {
            files = find();
            located = files;
        }
        return files;
    }

    private static ProxmoxPatchFiles find() {
        URL url = ProxmoxPatchFiles.class.getClassLoader().getResource("vms/" + SCRIPTS);
        if (url != null && "file".equals(url.getProtocol())) {
            try {
                return new ProxmoxPatchFiles(Paths.get(url.toURI()).getParent());
            } catch (java.net.URISyntaxException e) {
                // fall through to the package directory
            }
        }
        return new ProxmoxPatchFiles(Paths.get(DEFAULT_DIRECTORY));
    }

    /** The file, or a message that names the directory when it is not there. */
    public Path path(String name) {
        Path file = directory.resolve(name);
        if (!Files.isRegularFile(file)) {
            throw new ProxmoxApiException(String.format("The system VM patch file %s was not found in %s on the management server "
                    + "(it comes with the package cloudstack-common)", name, directory));
        }
        return file;
    }

    public long size(String name) {
        try {
            return Files.size(path(name));
        } catch (IOException e) {
            throw new ProxmoxApiException("Unable to read " + path(name) + ": " + e.getMessage(), e);
        }
    }

    public byte[] bytes(String name) {
        try {
            return Files.readAllBytes(path(name));
        } catch (IOException e) {
            throw new ProxmoxApiException("Unable to read " + path(name) + ": " + e.getMessage(), e);
        }
    }

    /** The SHA-512 of the file in hexadecimal lower case, the form the system VM keeps as its script signature. */
    public String sha512(String name) {
        Path file = path(name);
        try {
            long size = Files.size(file);
            long modified = Files.getLastModifiedTime(file).toMillis();
            Hashed known = hashes.get(name);
            if (known != null && known.size == size && known.modified == modified) {
                return known.sha512;
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-512");
            try (InputStream in = Files.newInputStream(file)) {
                byte[] buffer = new byte[1 << 16];
                for (int read = in.read(buffer); read >= 0; read = in.read(buffer)) {
                    digest.update(buffer, 0, read);
                }
            }
            StringBuilder hex = new StringBuilder();
            for (byte b : digest.digest()) {
                hex.append(String.format("%02x", b));
            }
            hashes.put(name, new Hashed(size, modified, hex.toString()));
            return hex.toString();
        } catch (IOException | NoSuchAlgorithmException e) {
            throw new ProxmoxApiException("Unable to read " + file + ": " + e.getMessage(), e);
        }
    }
}
