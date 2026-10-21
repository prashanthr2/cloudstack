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

import java.util.regex.Pattern;

import org.apache.cloudstack.hypervisor.proxmox.client.ProxmoxApiClient;
import org.apache.cloudstack.hypervisor.proxmox.client.ProxmoxApiException;

import com.cloud.utils.net.NetUtils;

/**
 * The host-only bridge that the control NIC of a system VM or virtual router is attached to (the counterpart of
 * {@code cloud0} on a KVM host). It has no ports and carries the link-local gateway address, so that an administrator can
 * log in to the node and reach the system VM. When a host is added the bridge is looked for; if it is missing it is
 * created and applied through the API (which needs the privilege {@code Sys.Modify} on the node), and if that cannot be
 * done the caller gets a message that says what to do.
 */
public final class ProxmoxControlBridge {

    public enum Outcome {
        /** The bridge was there and is left alone. */
        EXISTS,
        /** The bridge was there but its address is not the link-local gateway; left alone. */
        EXISTS_WITH_OTHER_ADDRESS,
        /** The bridge was missing and has been created and applied. */
        CREATED
    }

    /** Linux interface names have at most 15 characters. */
    private static final Pattern VALID_NAME = Pattern.compile("[a-zA-Z][a-zA-Z0-9_]{1,14}");
    private static final int FORBIDDEN = 403;

    private ProxmoxControlBridge() {
    }

    /** The address the node has on the bridge: the link-local gateway with the prefix of the link-local network. */
    public static String expectedCidr() {
        return NetUtils.getLinkLocalGateway() + "/" + NetUtils.getLinkLocalCIDR().split("/")[1];
    }

    /**
     * Makes sure that {@code node} has the bridge. Nothing is changed when it exists. Throws a {@link ProxmoxApiException} whose
     * message can be shown to the user when it is missing and cannot be created.
     *
     * @param tokenId the API token id ("user@realm!name"), used in the commands of the message
     */
    public static Outcome ensure(ProxmoxApiClient client, String node, String bridge, String tokenId, int applyTimeoutSeconds) {
        if (!VALID_NAME.matcher(bridge).matches()) {
            throw new ProxmoxApiException(String.format("The setting proxmox.control.bridge is '%s', which is not a usable bridge name "
                    + "(a letter followed by up to 14 letters, digits or underscores).", bridge));
        }

        ProxmoxApiClient.NetworkConfig config;
        try {
            config = client.getNetworkConfig(node);
        } catch (ProxmoxApiException e) {
            if (e.getStatusCode() == FORBIDDEN) {
                throw new ProxmoxApiException(String.format("The API token is not allowed to read the network configuration of Proxmox node %s "
                        + "(it needs Sys.Audit on /nodes/%s, which the role PVEAuditor has), so CloudStack cannot check for the control "
                        + "bridge '%s'. See the Proxmox plugin requirements.", node, node, bridge), e);
            }
            throw new ProxmoxApiException(String.format("Unable to read the network configuration of Proxmox node %s to look for the "
                    + "control bridge '%s': %s", node, bridge, e.getMessage()), e);
        }

        ProxmoxApiClient.NetworkInterface existing = config.find(bridge);
        if (existing != null) {
            if (!existing.isBridge()) {
                throw new ProxmoxApiException(String.format("Proxmox node %s has an interface named '%s' that is not a bridge. Choose another "
                        + "name in the setting proxmox.control.bridge, or remove or rename that interface.", node, bridge));
            }
            return expectedCidr().equals(existing.getCidr()) ? Outcome.EXISTS : Outcome.EXISTS_WITH_OTHER_ADDRESS;
        }

        if (config.hasPendingChanges()) {
            throw new ProxmoxApiException(String.format("Proxmox node %s has pending network changes (/etc/network/interfaces.new), so CloudStack "
                    + "will not apply the network configuration to create the control bridge '%s'. Apply or discard the pending changes in "
                    + "the Proxmox web interface, or create the bridge by hand as described in the Proxmox plugin requirements (\"The "
                    + "link-local control bridge\"), and add the host again.", node, bridge));
        }

        try {
            client.createBridge(node, bridge, expectedCidr());
        } catch (ProxmoxApiException e) {
            throw failed("create", node, bridge, tokenId, e);
        }
        try {
            client.applyNetworkConfig(node, applyTimeoutSeconds);
        } catch (ProxmoxApiException e) {
            boolean reverted = revert(client, node);
            ProxmoxApiException failure = failed("apply", node, bridge, tokenId, e);
            throw new ProxmoxApiException(failure.getMessage() + (reverted ? " The pending change was discarded again."
                    : " The pending change could not be discarded: check /etc/network/interfaces.new on the node."),
                    failure.getStatusCode(), e);
        }
        return Outcome.CREATED;
    }

    private static boolean revert(ProxmoxApiClient client, String node) {
        try {
            client.revertNetworkConfig(node);
            return true;
        } catch (ProxmoxApiException e) {
            return false;
        }
    }

    private static ProxmoxApiException failed(String step, String node, String bridge, String tokenId, ProxmoxApiException cause) {
        if (cause.getStatusCode() == FORBIDDEN) {
            String user = tokenId.contains("!") ? tokenId.substring(0, tokenId.indexOf('!')) : tokenId;
            return new ProxmoxApiException(String.format("Proxmox node %s has no control bridge '%s' and the API token is not allowed to create "
                    + "it (it needs the privilege Sys.Modify on /nodes/%s). Either grant it: pveum role add CloudStackNet --privs Sys.Modify, "
                    + "then pveum acl modify /nodes/%s --users %s --tokens '%s' --roles CloudStackNet --propagate 0 (the user and the token "
                    + "both need it), or create the bridge '%s' by hand on every node as described in the Proxmox plugin requirements "
                    + "(\"The link-local control bridge\"), and add the host again.", node, bridge, node, node, user, tokenId, bridge),
                    FORBIDDEN, cause);
        }
        return new ProxmoxApiException(String.format("Proxmox did not %s the control bridge '%s' on node %s: %s. You can also create the bridge "
                + "by hand as described in the Proxmox plugin requirements (\"The link-local control bridge\") and add the host again.",
                step, bridge, node, cause.getMessage()), cause.getStatusCode(), cause);
    }
}
