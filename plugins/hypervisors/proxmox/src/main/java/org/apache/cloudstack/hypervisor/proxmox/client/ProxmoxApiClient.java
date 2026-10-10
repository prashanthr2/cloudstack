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
package org.apache.cloudstack.hypervisor.proxmox.client;

import java.io.Closeable;
import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.net.ssl.SSLContext;

import org.apache.commons.lang3.StringUtils;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.conn.ssl.NoopHostnameVerifier;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.conn.ssl.TrustAllStrategy;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.ssl.SSLContexts;
import org.apache.http.util.EntityUtils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Minimal client for the Proxmox VE REST API, authenticated with an API token
 * ("user@realm!tokenid" + secret).
 */
public class ProxmoxApiClient implements Closeable {
    public static final int DEFAULT_PORT = 8006;
    public static final String VERIFY_TLS_PARAM = "verifyTls";
    private static final int DEFAULT_TIMEOUT_MS = 15000;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String baseUrl;
    private final String authHeader;
    private final CloseableHttpClient httpClient;

    public ProxmoxApiClient(String baseUrl, String tokenId, String tokenSecret, boolean verifyTls) {
        this(baseUrl, tokenId, tokenSecret, verifyTls, DEFAULT_TIMEOUT_MS);
    }

    public ProxmoxApiClient(String baseUrl, String tokenId, String tokenSecret, boolean verifyTls, int timeoutMs) {
        this.baseUrl = StringUtils.removeEnd(baseUrl, "/");
        this.authHeader = "PVEAPIToken=" + tokenId + "=" + tokenSecret;
        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectTimeout(timeoutMs)
                .setSocketTimeout(timeoutMs)
                .setConnectionRequestTimeout(timeoutMs)
                .build();
        try {
            SSLContext sslContext = verifyTls ? SSLContexts.createSystemDefault()
                    : SSLContexts.custom().loadTrustMaterial(null, TrustAllStrategy.INSTANCE).build();
            SSLConnectionSocketFactory socketFactory = verifyTls ? new SSLConnectionSocketFactory(sslContext)
                    : new SSLConnectionSocketFactory(sslContext, NoopHostnameVerifier.INSTANCE);
            this.httpClient = HttpClients.custom()
                    .setDefaultRequestConfig(requestConfig)
                    .setSSLSocketFactory(socketFactory)
                    .build();
        } catch (GeneralSecurityException e) {
            throw new ProxmoxApiException("Unable to initialise TLS for the Proxmox API client", e);
        }
    }

    /**
     * Builds the API endpoint (always https) from the URL the operator supplied when adding the host,
     * e.g. "https://10.0.35.25:8006" or "10.0.35.25". The port defaults to 8006.
     */
    public static String endpointOf(URI uri) {
        String host = uri.getHost();
        if (StringUtils.isBlank(host)) {
            throw new ProxmoxApiException("No host found in Proxmox URL: " + uri);
        }
        int port = uri.getPort() == -1 ? DEFAULT_PORT : uri.getPort();
        return "https://" + host + ":" + port;
    }

    /**
     * TLS verification is on unless the URL carries verifyTls=false (as query parameter, or - because the
     * management server URL-encodes everything after the first '/' - inside the path).
     */
    public static boolean isTlsVerificationEnabled(URI uri) {
        String candidates = StringUtils.defaultString(uri.getRawQuery()) + "&"
                + URLDecoder.decode(StringUtils.defaultString(uri.getRawPath()), StandardCharsets.UTF_8);
        for (String token : candidates.split("[?&]")) {
            if (token.equalsIgnoreCase(VERIFY_TLS_PARAM + "=false")) {
                return false;
            }
        }
        return true;
    }

    public JsonNode get(String path) {
        return execute(new org.apache.http.client.methods.HttpGet(baseUrl + "/api2/json" + path));
    }

    public JsonNode post(String path, Map<String, String> form) {
        org.apache.http.client.methods.HttpPost request = new org.apache.http.client.methods.HttpPost(baseUrl + "/api2/json" + path);
        request.setEntity(toFormEntity(form));
        return execute(request);
    }

    public JsonNode put(String path, Map<String, String> form) {
        org.apache.http.client.methods.HttpPut request = new org.apache.http.client.methods.HttpPut(baseUrl + "/api2/json" + path);
        request.setEntity(toFormEntity(form));
        return execute(request);
    }

    public JsonNode delete(String path) {
        return execute(new org.apache.http.client.methods.HttpDelete(baseUrl + "/api2/json" + path));
    }

    private static org.apache.http.client.entity.UrlEncodedFormEntity toFormEntity(Map<String, String> form) {
        List<org.apache.http.NameValuePair> pairs = new ArrayList<>();
        if (form != null) {
            form.forEach((k, v) -> pairs.add(new org.apache.http.message.BasicNameValuePair(k, v)));
        }
        return new org.apache.http.client.entity.UrlEncodedFormEntity(pairs, StandardCharsets.UTF_8);
    }

    private JsonNode execute(HttpRequestBase request) {
        request.setHeader("Authorization", authHeader);
        request.setHeader("Accept", "application/json");
        try (CloseableHttpResponse response = httpClient.execute(request)) {
            int status = response.getStatusLine().getStatusCode();
            String body = response.getEntity() == null ? "" : EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
            if (status < 200 || status >= 300) {
                throw new ProxmoxApiException(String.format("Proxmox API %s %s failed: HTTP %d %s", request.getMethod(),
                        request.getURI().getPath(), status, response.getStatusLine().getReasonPhrase()), status, null);
            }
            return parseData(body);
        } catch (IOException e) {
            throw new ProxmoxApiException(String.format("Unable to reach the Proxmox API for %s %s: %s", request.getMethod(),
                    request.getURI().getPath(), e.getMessage()), e);
        }
    }

    static JsonNode parseData(String body) {
        if (StringUtils.isBlank(body)) {
            return MAPPER.nullNode();
        }
        try {
            JsonNode root = MAPPER.readTree(body);
            JsonNode data = root.get("data");
            return data == null ? MAPPER.nullNode() : data;
        } catch (IOException e) {
            throw new ProxmoxApiException("Unable to parse the Proxmox API response", e);
        }
    }

    public String getVersion() {
        JsonNode data = get("/version");
        return data.path("version").asText(null);
    }

    /** A node of the Proxmox cluster as seen through the API. */
    public static class NodeInfo {
        private final String name;
        private final String ip;
        private final boolean online;

        public NodeInfo(String name, String ip, boolean online) {
            this.name = name;
            this.ip = ip;
            this.online = online;
        }

        public String getName() {
            return name;
        }

        public String getIp() {
            return ip;
        }

        public boolean isOnline() {
            return online;
        }
    }

    /** Lists the cluster members. A standalone Proxmox node is returned as a single entry. */
    public List<NodeInfo> getClusterNodes(String fallbackIp) {
        List<NodeInfo> nodes = new ArrayList<>();
        JsonNode status = get("/cluster/status");
        if (status != null && status.isArray()) {
            for (JsonNode entry : status) {
                if ("node".equals(entry.path("type").asText())) {
                    nodes.add(new NodeInfo(entry.path("name").asText(), entry.path("ip").asText(null),
                            entry.path("online").asInt(0) == 1));
                }
            }
        }
        if (nodes.isEmpty()) {
            JsonNode list = get("/nodes");
            if (list != null && list.isArray()) {
                for (JsonNode entry : list) {
                    nodes.add(new NodeInfo(entry.path("node").asText(), fallbackIp, "online".equals(entry.path("status").asText())));
                }
            }
        }
        return nodes;
    }

    /** Name of the Proxmox cluster, or an empty string for a standalone node. */
    public String getClusterName() {
        JsonNode status = get("/cluster/status");
        if (status != null && status.isArray()) {
            for (JsonNode entry : status) {
                if ("cluster".equals(entry.path("type").asText())) {
                    return entry.path("name").asText("");
                }
            }
        }
        return "";
    }

    /** Hardware and utilisation figures of one node. */
    public static class NodeStatus {
        private final int cpus;
        private final int sockets;
        private final long cpuMhz;
        private final long memoryTotalBytes;
        private final long memoryUsedBytes;
        private final long memoryFreeBytes;
        private final double cpuUtilization;
        private final String pveVersion;

        public NodeStatus(int cpus, int sockets, long cpuMhz, long memoryTotalBytes, long memoryUsedBytes,
                long memoryFreeBytes, double cpuUtilization, String pveVersion) {
            this.cpus = cpus;
            this.sockets = sockets;
            this.cpuMhz = cpuMhz;
            this.memoryTotalBytes = memoryTotalBytes;
            this.memoryUsedBytes = memoryUsedBytes;
            this.memoryFreeBytes = memoryFreeBytes;
            this.cpuUtilization = cpuUtilization;
            this.pveVersion = pveVersion;
        }

        public int getCpus() {
            return cpus;
        }

        public int getSockets() {
            return sockets;
        }

        public long getCpuMhz() {
            return cpuMhz;
        }

        public long getMemoryTotalBytes() {
            return memoryTotalBytes;
        }

        public long getMemoryUsedBytes() {
            return memoryUsedBytes;
        }

        public long getMemoryFreeBytes() {
            return memoryFreeBytes;
        }

        /** Fraction in the range 0..1. */
        public double getCpuUtilization() {
            return cpuUtilization;
        }

        public String getPveVersion() {
            return pveVersion;
        }
    }

    /**
     * Proxmox reports "pve-manager/8.4.21/2606ac850d46da29"; only the version part is wanted. The host table keeps
     * the hypervisor version in a varchar(32), so the result is also capped to that length.
     */
    static String shortVersion(String pveVersion) {
        if (StringUtils.isBlank(pveVersion)) {
            return null;
        }
        String[] parts = pveVersion.split("/");
        String version = parts.length >= 2 ? parts[1] : parts[0];
        return StringUtils.left(version, 32);
    }

    public NodeStatus getNodeStatus(String node) {
        JsonNode data = get("/nodes/" + node + "/status");
        if (data == null || data.isNull() || data.isMissingNode()) {
            throw new ProxmoxApiException("No status returned for Proxmox node " + node);
        }
        JsonNode cpuInfo = data.path("cpuinfo");
        JsonNode memory = data.path("memory");
        double mhz = 0;
        try {
            mhz = Double.parseDouble(cpuInfo.path("mhz").asText("0"));
        } catch (NumberFormatException e) {
            mhz = 0;
        }
        return new NodeStatus(cpuInfo.path("cpus").asInt(0), cpuInfo.path("sockets").asInt(1), (long) mhz,
                memory.path("total").asLong(0), memory.path("used").asLong(0), memory.path("free").asLong(0),
                data.path("cpu").asDouble(0), shortVersion(data.path("pveversion").asText(null)));
    }

    /** A QEMU virtual machine on a node. */
    public static class VmInfo {
        private final long vmid;
        private final String name;
        private final String status;

        public VmInfo(long vmid, String name, String status) {
            this.vmid = vmid;
            this.name = name;
            this.status = status;
        }

        public long getVmid() {
            return vmid;
        }

        public String getName() {
            return name;
        }

        public String getStatus() {
            return status;
        }

        public boolean isRunning() {
            return "running".equals(status);
        }
    }

    /** Lists the (non-template) QEMU VMs currently placed on the given node. */
    public List<VmInfo> listVms(String node) {
        List<VmInfo> vms = new ArrayList<>();
        JsonNode data = get("/nodes/" + node + "/qemu");
        if (data != null && data.isArray()) {
            for (JsonNode entry : data) {
                if (entry.path("template").asInt(0) == 1) {
                    continue;
                }
                vms.add(new VmInfo(entry.path("vmid").asLong(), entry.path("name").asText(null), entry.path("status").asText("unknown")));
            }
        }
        return vms;
    }

    @Override
    public void close() {
        try {
            httpClient.close();
        } catch (IOException e) {
            // nothing useful to do
        }
    }
}
