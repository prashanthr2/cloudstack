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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The network interfaces of a running router as its Proxmox configuration lists them ({@code net0}, {@code net1} ...), and the number of each
 * one inside the guest. As on KVM (where the number is the position of the interface in the domain), the guest names the interfaces
 * {@code eth0}, {@code eth1} ... in the order of the configuration (lab S5 and S6: the order of the PCI slots, a hot-plugged interface comes last).
 */
final class ProxmoxRouterNics {
    private static final Pattern NET_KEY = Pattern.compile("net(\\d+)");
    private static final Pattern MAC = Pattern.compile("^[^=,]+=([0-9A-Fa-f:]{17})");

    /** One interface: its configuration key number, its MAC address in lower case, and the VLAN tag or null. */
    static final class Nic {
        private final int index;
        private final String mac;
        private final String tag;

        Nic(int index, String mac, String tag) {
            this.index = index;
            this.mac = mac;
            this.tag = tag;
        }

        int getIndex() {
            return index;
        }

        String getMac() {
            return mac;
        }

        String getTag() {
            return tag;
        }
    }

    private ProxmoxRouterNics() {
    }

    /** The interfaces in the order of their numbers; a {@code netN} entry without a MAC address is skipped. */
    static List<Nic> parse(Map<String, String> config) {
        TreeMap<Integer, Nic> sorted = new TreeMap<>();
        for (Map.Entry<String, String> entry : config.entrySet()) {
            Matcher key = NET_KEY.matcher(entry.getKey());
            if (!key.matches() || entry.getValue() == null) {
                continue;
            }
            Matcher mac = MAC.matcher(entry.getValue());
            if (!mac.find()) {
                continue;
            }
            String tag = null;
            for (String part : entry.getValue().split(",")) {
                if (part.startsWith("tag=")) {
                    tag = part.substring(4);
                }
            }
            int index = Integer.parseInt(key.group(1));
            sorted.put(index, new Nic(index, mac.group(1).toLowerCase(Locale.ROOT), tag));
        }
        return new ArrayList<>(sorted.values());
    }

    /** The number of the interface with this MAC address inside the guest (its position), or null when the router has none. */
    static Integer numberOf(List<Nic> nics, String mac) {
        if (mac == null) {
            return null;
        }
        String wanted = mac.toLowerCase(Locale.ROOT);
        for (int position = 0; position < nics.size(); position++) {
            if (nics.get(position).getMac().equals(wanted)) {
                return position;
            }
        }
        return null;
    }

    /** The key number for an interface that is added: one more than the highest in use. */
    static int nextIndex(List<Nic> nics) {
        return nics.isEmpty() ? 0 : nics.get(nics.size() - 1).getIndex() + 1;
    }

    /** The key number of the interface at this position. */
    static int indexAt(List<Nic> nics, int position) {
        return nics.get(position).getIndex();
    }
}
