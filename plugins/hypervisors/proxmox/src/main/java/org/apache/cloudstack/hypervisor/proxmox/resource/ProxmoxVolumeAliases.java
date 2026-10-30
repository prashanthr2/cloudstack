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

import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.cloudstack.hypervisor.proxmox.client.ProxmoxApiException;
import org.apache.commons.lang3.StringUtils;

/**
 * The aliases of the volumes that the plugin renamed without core being able to hear about it (design-phase9-storage-migration.md, section 10).
 * Core keeps the path it was given when the volume was made or last moved; Proxmox renames a disk whenever it changes owner, so after the plugin hands
 * a volume to the holder VM at a moment when no answer can carry the new path (the instance was stopped when the volume was detached), the stored
 * path names a file that is gone. The alias says where it went: "old volume id" to "new volume id", kept as lines in the description of the account's
 * holder VM, below a marker, after the text that explains the VM to an administrator. A volume id is "&lt;pool entry&gt;:&lt;vmid&gt;/&lt;file&gt;"; a rename
 * never changes the storage, so a line is "&lt;pool entry&gt;|&lt;old path&gt;|&lt;new path&gt;".
 */
final class ProxmoxVolumeAliases {
    static final String MARKER = "--- CloudStack volume aliases (kept by the plugin, do not edit) ---";
    /** A Proxmox description is at most 8192 characters; this leaves room for the text above the marker. */
    static final int MAX_LENGTH = 8000;
    private static final int MAX_HOPS = 10;

    private ProxmoxVolumeAliases() {
    }

    /** The aliases in a description: old volume id to new volume id, in the order they were made. */
    static Map<String, String> parse(String description) {
        Map<String, String> aliases = new LinkedHashMap<>();
        if (description == null) {
            return aliases;
        }
        int marker = description.indexOf(MARKER);
        if (marker < 0) {
            return aliases;
        }
        for (String line : description.substring(marker + MARKER.length()).split("\n")) {
            String[] parts = line.trim().split("\\|");
            if (parts.length == 3 && !parts[0].isEmpty() && !parts[1].isEmpty() && !parts[2].isEmpty()) {
                aliases.put(parts[0] + ":" + parts[1], parts[0] + ":" + parts[2]);
            }
        }
        return aliases;
    }

    /** The text of a description above the marker (what an administrator reads). */
    static String textOf(String description) {
        if (description == null) {
            return "";
        }
        int marker = description.indexOf(MARKER);
        return (marker < 0 ? description : description.substring(0, marker)).replaceAll("\\s+$", "");
    }

    /** The description with these aliases: the text, then the marker and one line per alias; no marker when there are none. */
    static String render(String description, Map<String, String> aliases) {
        StringBuilder text = new StringBuilder(textOf(description));
        if (!aliases.isEmpty()) {
            text.append('\n').append(MARKER);
            for (Map.Entry<String, String> alias : aliases.entrySet()) {
                String pool = StringUtils.substringBefore(alias.getKey(), ":");
                text.append('\n').append(pool).append('|').append(StringUtils.substringAfter(alias.getKey(), ":")).append('|')
                        .append(StringUtils.substringAfter(alias.getValue(), ":"));
            }
        }
        if (text.length() > MAX_LENGTH) {
            throw new ProxmoxApiException("The holder VM cannot list more renamed volumes (its description is full at " + MAX_LENGTH + " characters)");
        }
        return text.toString();
    }

    /** Whether one more alias of this size still fits, checked before anything is renamed. */
    static boolean hasRoomFor(String description, Map<String, String> aliases, String oldVolumeId, String expectedNewVolumeId) {
        Map<String, String> more = new LinkedHashMap<>(aliases);
        more.put(oldVolumeId, expectedNewVolumeId);
        try {
            render(description, more);
            return true;
        } catch (ProxmoxApiException e) {
            return false;
        }
    }

    /** The volume id that {@code volumeId} stands for now: follows the aliases (a volume can be renamed more than once). */
    static String resolve(Map<String, String> aliases, String volumeId) {
        String current = volumeId;
        for (int hop = 0; hop < MAX_HOPS; hop++) {
            String next = aliases.get(current);
            if (next == null) {
                return current;
            }
            current = next;
        }
        return current;
    }

    /**
     * Records that {@code oldVolumeId} is now {@code newVolumeId}. Entries that pointed at the old id are changed to the new one, so a lookup is
     * always one step. Two rules keep an alias from naming a disk that is not the volume's:
     * <ul>
     * <li>an entry that starts at the new id is dropped: the id was free (an earlier volume moved away from it) and now holds this volume;</li>
     * <li>when some entry pointed at the old id, that entry is what CloudStack holds (a path that names the holder), so the old id was only a
     * place on the way and is not kept as a key: Proxmox gives the same name (vm-9204-disk-1) to a later disk.</li>
     * </ul>
     */
    static Map<String, String> record(Map<String, String> aliases, String oldVolumeId, String newVolumeId) {
        Map<String, String> result = new LinkedHashMap<>();
        boolean pointedAtOld = false;
        for (Map.Entry<String, String> alias : aliases.entrySet()) {
            if (alias.getKey().equals(newVolumeId)) {
                continue;
            }
            if (alias.getValue().equals(oldVolumeId)) {
                pointedAtOld = true;
                result.put(alias.getKey(), newVolumeId);
            } else {
                result.put(alias.getKey(), alias.getValue());
            }
        }
        if (pointedAtOld) {
            result.remove(oldVolumeId);
        } else {
            result.put(oldVolumeId, newVolumeId);
        }
        return result;
    }

    /** The aliases without those that lead to {@code volumeId} (the volume was deleted) or start at it (core stores the new path now). */
    static Map<String, String> forget(Map<String, String> aliases, String volumeId) {
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, String> alias : aliases.entrySet()) {
            if (!alias.getKey().equals(volumeId) && !alias.getValue().equals(volumeId)) {
                result.put(alias.getKey(), alias.getValue());
            }
        }
        return result;
    }
}
