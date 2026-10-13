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
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import javax.net.ssl.SSLContext;

import org.apache.commons.lang3.StringUtils;
import org.apache.http.NameValuePair;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpDelete;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.conn.ssl.NoopHostnameVerifier;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.conn.ssl.TrustAllStrategy;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.message.BasicNameValuePair;
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
    public static final int DEFAULT_CONNECT_TIMEOUT_SECONDS = 5;
    public static final int DEFAULT_READ_TIMEOUT_SECONDS = 15;
    /** How often a running task is looked at. */
    static final long TASK_POLL_MILLIS = 1000;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final List<String> baseUrls;
    private final String authHeader;
    private final CloseableHttpClient httpClient;
    private volatile int current;

    public ProxmoxApiClient(String baseUrl, String tokenId, String tokenSecret, boolean verifyTls) {
        this(Collections.singletonList(baseUrl), tokenId, tokenSecret, verifyTls, DEFAULT_CONNECT_TIMEOUT_SECONDS, DEFAULT_READ_TIMEOUT_SECONDS);
    }

    /**
     * @param baseUrls every address the cluster can be reached on (any node answers for the whole cluster). They are
     *                 tried in turn when one cannot be reached, and the last one that worked is used first.
     * @param connectTimeoutSeconds how long to wait for a connection to one address before trying the next
     * @param readTimeoutSeconds how long to wait for an answer once connected
     */
    public ProxmoxApiClient(List<String> baseUrls, String tokenId, String tokenSecret, boolean verifyTls,
            int connectTimeoutSeconds, int readTimeoutSeconds) {
        this(baseUrls, tokenId, tokenSecret, buildHttpClient(verifyTls, connectTimeoutSeconds * 1000, readTimeoutSeconds * 1000));
    }

    ProxmoxApiClient(List<String> baseUrls, String tokenId, String tokenSecret, CloseableHttpClient httpClient) {
        List<String> cleaned = new ArrayList<>();
        for (String url : new LinkedHashSet<>(baseUrls)) {
            cleaned.add(StringUtils.removeEnd(url, "/"));
        }
        if (cleaned.isEmpty()) {
            throw new ProxmoxApiException("At least one Proxmox API address is required");
        }
        this.baseUrls = cleaned;
        this.authHeader = "PVEAPIToken=" + tokenId + "=" + tokenSecret;
        this.httpClient = httpClient;
    }

    private static CloseableHttpClient buildHttpClient(boolean verifyTls, int connectTimeoutMs, int readTimeoutMs) {
        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectTimeout(connectTimeoutMs)
                .setSocketTimeout(readTimeoutMs)
                .setConnectionRequestTimeout(readTimeoutMs)
                .build();
        try {
            SSLContext sslContext = verifyTls ? SSLContexts.createSystemDefault()
                    : SSLContexts.custom().loadTrustMaterial(null, TrustAllStrategy.INSTANCE).build();
            SSLConnectionSocketFactory socketFactory = verifyTls ? new SSLConnectionSocketFactory(sslContext)
                    : new SSLConnectionSocketFactory(sslContext, NoopHostnameVerifier.INSTANCE);
            return HttpClients.custom()
                    .setDefaultRequestConfig(requestConfig)
                    .setSSLSocketFactory(socketFactory)
                    .build();
        } catch (GeneralSecurityException e) {
            throw new ProxmoxApiException("Unable to initialise TLS for the Proxmox API client", e);
        }
    }

    /**
     * Builds the API endpoint (always https) from what the operator typed when adding the host: an address with an
     * optional port, e.g. "10.0.35.25" or "10.0.35.25:8443". The port defaults to 8006. Anything else in the URL
     * (scheme, path, query) is ignored.
     */
    public static String endpointOf(URI uri) {
        String host = uri.getHost();
        if (StringUtils.isBlank(host)) {
            throw new ProxmoxApiException("No host found in Proxmox URL: " + uri);
        }
        int port = uri.getPort() == -1 ? DEFAULT_PORT : uri.getPort();
        return "https://" + host + ":" + port;
    }

    public JsonNode get(String path) {
        return execute(api -> new HttpGet(api + path));
    }

    public JsonNode post(String path, Map<String, String> form) {
        return execute(api -> {
            HttpPost request = new HttpPost(api + path);
            request.setEntity(toFormEntity(form));
            return request;
        });
    }

    public JsonNode put(String path, Map<String, String> form) {
        return execute(api -> {
            HttpPut request = new HttpPut(api + path);
            request.setEntity(toFormEntity(form));
            return request;
        });
    }

    public JsonNode delete(String path) {
        return execute(api -> new HttpDelete(api + path));
    }

    private static UrlEncodedFormEntity toFormEntity(Map<String, String> form) {
        List<NameValuePair> pairs = new ArrayList<>();
        if (form != null) {
            form.forEach((k, v) -> pairs.add(new BasicNameValuePair(k, v)));
        }
        return new UrlEncodedFormEntity(pairs, StandardCharsets.UTF_8);
    }

    /**
     * Runs a request against the address that worked last, moving on to the next address only when the connection
     * itself fails. An HTTP error answer (401, 500 ...) means the address was reachable and is returned as such.
     */
    private JsonNode execute(Function<String, HttpRequestBase> requestFactory) {
        IOException lastFailure = null;
        String lastTarget = null;
        int count = baseUrls.size();
        int first = current;
        for (int attempt = 0; attempt < count; attempt++) {
            int index = (first + attempt) % count;
            HttpRequestBase request = requestFactory.apply(baseUrls.get(index) + "/api2/json");
            request.setHeader("Authorization", authHeader);
            request.setHeader("Accept", "application/json");
            lastTarget = request.getMethod() + " " + request.getURI().getPath();
            try (CloseableHttpResponse response = httpClient.execute(request)) {
                int status = response.getStatusLine().getStatusCode();
                String body = response.getEntity() == null ? "" : EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
                current = index;
                if (status < 200 || status >= 300) {
                    throw new ProxmoxApiException(String.format("Proxmox API %s failed: HTTP %d %s", lastTarget, status,
                            response.getStatusLine().getReasonPhrase()), status, null);
                }
                return parseData(body);
            } catch (IOException e) {
                lastFailure = e;
            }
        }
        throw new ProxmoxApiException(String.format("Unable to reach the Proxmox API for %s on any of %s: %s", lastTarget,
                baseUrls, lastFailure == null ? "no address" : lastFailure.getMessage()), lastFailure);
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

    /** A storage entry as configured for the cluster (GET /storage). Fields not set on the entry are null. */
    public static class StorageConfig {
        private final String id;
        private final String type;
        private final String server;
        private final String export;
        private final String path;
        private final String content;
        private final String options;

        public StorageConfig(String id, String type, String server, String export, String path, String content, String options) {
            this.id = id;
            this.type = type;
            this.server = server;
            this.export = export;
            this.path = path;
            this.content = content;
            this.options = options;
        }

        public String getId() {
            return id;
        }

        public String getType() {
            return type;
        }

        public String getServer() {
            return server;
        }

        public String getExport() {
            return export;
        }

        /** The local mount point on the nodes. */
        public String getPath() {
            return path;
        }

        public String getContent() {
            return content;
        }

        public String getOptions() {
            return options;
        }
    }

    /** All storage entries of the cluster. */
    public List<StorageConfig> listStorages() {
        return parseStorages(get("/storage"));
    }

    static List<StorageConfig> parseStorages(JsonNode data) {
        List<StorageConfig> storages = new ArrayList<>();
        if (data != null && data.isArray()) {
            for (JsonNode entry : data) {
                storages.add(new StorageConfig(entry.path("storage").asText(null), entry.path("type").asText(null),
                        entry.path("server").asText(null), entry.path("export").asText(null), entry.path("path").asText(null),
                        entry.path("content").asText(null), entry.path("options").asText(null)));
            }
        }
        return storages;
    }

    /** The entry with the given id, or null when there is none. */
    public StorageConfig findStorage(String id) {
        for (StorageConfig storage : listStorages()) {
            if (id.equals(storage.getId())) {
                return storage;
            }
        }
        return null;
    }

    /** Adds an NFS storage entry for the whole cluster; every node mounts the share at {@code mountPath}. */
    public void createNfsStorage(String id, String server, String export, String mountPath, String content, String options) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("storage", id);
        form.put("type", "nfs");
        form.put("server", server);
        form.put("export", export);
        form.put("content", content);
        if (StringUtils.isNotBlank(mountPath)) {
            form.put("path", mountPath);
        }
        if (StringUtils.isNotBlank(options)) {
            form.put("options", options);
        }
        post("/storage", form);
    }

    /** Removes a storage entry from the cluster configuration (the share itself is not touched). */
    public void deleteStorage(String id) {
        delete("/storage/" + id);
    }

    /** State and capacity of a storage entry on one node. */
    public static class StorageStatus {
        private final boolean active;
        private final boolean enabled;
        private final long totalBytes;
        private final long usedBytes;
        private final long availableBytes;

        public StorageStatus(boolean active, boolean enabled, long totalBytes, long usedBytes, long availableBytes) {
            this.active = active;
            this.enabled = enabled;
            this.totalBytes = totalBytes;
            this.usedBytes = usedBytes;
            this.availableBytes = availableBytes;
        }

        /** True when the node has the storage mounted and usable. */
        public boolean isActive() {
            return active;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public long getTotalBytes() {
            return totalBytes;
        }

        public long getUsedBytes() {
            return usedBytes;
        }

        public long getAvailableBytes() {
            return availableBytes;
        }
    }

    public StorageStatus getStorageStatus(String node, String id) {
        return parseStorageStatus(get("/nodes/" + node + "/storage/" + id + "/status"));
    }

    static StorageStatus parseStorageStatus(JsonNode data) {
        if (data == null || data.isNull() || data.isMissingNode()) {
            throw new ProxmoxApiException("No storage status returned");
        }
        return new StorageStatus(data.path("active").asInt(0) == 1, data.path("enabled").asInt(1) == 1, data.path("total").asLong(0),
                data.path("used").asLong(0), data.path("avail").asLong(0));
    }

    /**
     * Adds a read-only NFS entry that exposes one folder as "import" content, so that a VM can be created from the qcow2 files in it
     * ({@code import-from=<id>:import/<file>}). A volume name cannot contain a folder, hence one entry per template folder.
     *
     * @param folder the folder inside the export that holds the file, e.g. "template/tmpl/1/4"
     */
    public void createImportStorage(String id, String server, String export, String folder, String options) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("storage", id);
        form.put("type", "nfs");
        form.put("server", server);
        form.put("export", export);
        form.put("content", "import");
        form.put("content-dirs", "import=" + folder);
        if (StringUtils.isNotBlank(options)) {
            form.put("options", options);
        }
        post("/storage", form);
    }

    /** A file in the "import" content of a storage entry. */
    public static class ImportVolume {
        private final String volumeId;
        private final String format;
        private final long sizeBytes;

        public ImportVolume(String volumeId, String format, long sizeBytes) {
            this.volumeId = volumeId;
            this.format = format;
            this.sizeBytes = sizeBytes;
        }

        /** For example "cst-3f2a:import/6ddd9e96.qcow2". */
        public String getVolumeId() {
            return volumeId;
        }

        public String getFormat() {
            return format;
        }

        public long getSizeBytes() {
            return sizeBytes;
        }
    }

    public List<ImportVolume> listImportVolumes(String node, String storageId) {
        List<ImportVolume> volumes = new ArrayList<>();
        JsonNode data = get("/nodes/" + node + "/storage/" + storageId + "/content?content=import");
        if (data != null && data.isArray()) {
            for (JsonNode entry : data) {
                volumes.add(new ImportVolume(entry.path("volid").asText(null), entry.path("format").asText(null), entry.path("size").asLong(0)));
            }
        }
        return volumes;
    }

    /** The next free VM id of the cluster. Not reserved: another caller can be given the same id, so a create may have to be retried. */
    public long nextVmId() {
        JsonNode data = get("/cluster/nextid");
        if (data == null || !(data.isNumber() || data.isTextual())) {
            throw new ProxmoxApiException("Proxmox did not return a free VM id");
        }
        return data.asLong();
    }

    /** Where a VM is and what it is doing, from the cluster-wide resource list. */
    public static class VmLocation {
        private final long vmid;
        private final String name;
        private final String node;
        private final String status;

        public VmLocation(long vmid, String name, String node, String status) {
            this.vmid = vmid;
            this.name = name;
            this.node = node;
            this.status = status;
        }

        public long getVmid() {
            return vmid;
        }

        public String getName() {
            return name;
        }

        public String getNode() {
            return node;
        }

        public String getStatus() {
            return status;
        }

        public boolean isRunning() {
            return "running".equals(status);
        }
    }

    /** All QEMU VMs of the cluster (templates left out), wherever they are. */
    public List<VmLocation> listClusterVms() {
        List<VmLocation> vms = new ArrayList<>();
        JsonNode data = get("/cluster/resources?type=vm");
        if (data != null && data.isArray()) {
            for (JsonNode entry : data) {
                if (!"qemu".equals(entry.path("type").asText()) || entry.path("template").asInt(0) == 1) {
                    continue;
                }
                vms.add(new VmLocation(entry.path("vmid").asLong(), entry.path("name").asText(null), entry.path("node").asText(null),
                        entry.path("status").asText("unknown")));
            }
        }
        return vms;
    }

    /** The VM with the given id, or null. */
    public VmLocation findVm(long vmid) {
        for (VmLocation vm : listClusterVms()) {
            if (vm.getVmid() == vmid) {
                return vm;
            }
        }
        return null;
    }

    /** The VM with the given name (the CloudStack instance name), or null. */
    public VmLocation findVmByName(String name) {
        for (VmLocation vm : listClusterVms()) {
            if (name.equals(vm.getName())) {
                return vm;
            }
        }
        return null;
    }

    /** Creates a VM; returns the task id. {@code params} are the Proxmox options (name, memory, scsi0 ...). */
    public String createVm(String node, long vmid, Map<String, String> params) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("vmid", Long.toString(vmid));
        form.putAll(params);
        return taskId(post("/nodes/" + node + "/qemu", form));
    }

    /** Changes the configuration of a VM (applied at once while it is stopped). */
    public void updateVmConfig(String node, long vmid, Map<String, String> params) {
        put("/nodes/" + node + "/qemu/" + vmid + "/config", params);
    }

    /** Grows a disk to {@code size} (for example "20G"; Proxmox never shrinks a disk). */
    public void resizeDisk(String node, long vmid, String disk, String size) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("disk", disk);
        form.put("size", size);
        put("/nodes/" + node + "/qemu/" + vmid + "/resize", form);
    }

    public String startVm(String node, long vmid) {
        return taskId(post("/nodes/" + node + "/qemu/" + vmid + "/status/start", null));
    }

    /** Hard stop, like pulling the power. */
    public String stopVm(String node, long vmid) {
        return taskId(post("/nodes/" + node + "/qemu/" + vmid + "/status/stop", null));
    }

    /** Asks the guest to shut down; after {@code timeoutSeconds} the VM is stopped by force. */
    public String shutdownVm(String node, long vmid, int timeoutSeconds) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("timeout", Integer.toString(timeoutSeconds));
        form.put("forceStop", "1");
        return taskId(post("/nodes/" + node + "/qemu/" + vmid + "/status/shutdown", form));
    }

    public String rebootVm(String node, long vmid) {
        return taskId(post("/nodes/" + node + "/qemu/" + vmid + "/status/reboot", null));
    }

    /** Deletes a VM and the disks it owns (a disk owned by another VM id is kept). */
    public String destroyVm(String node, long vmid) {
        return taskId(delete("/nodes/" + node + "/qemu/" + vmid + "?purge=1"));
    }

    /** Moves a VM to another node (a stopped VM on shared storage only moves its configuration). */
    public String migrateVm(String node, long vmid, String target) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("target", target);
        return taskId(post("/nodes/" + node + "/qemu/" + vmid + "/migrate", form));
    }

    private static String taskId(JsonNode data) {
        if (data == null || !data.isTextual() || !data.asText().startsWith("UPID:")) {
            throw new ProxmoxApiException("Proxmox did not return a task id");
        }
        return data.asText();
    }

    /**
     * Waits for a Proxmox task to end. Throws when it fails, with Proxmox's own reason, or when it is still running after
     * {@code timeoutSeconds} (the task itself is not cancelled).
     */
    public void waitForTask(String upid, int timeoutSeconds) {
        String node = taskNode(upid);
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        while (true) {
            JsonNode status = get("/nodes/" + node + "/tasks/" + upid + "/status");
            if ("stopped".equals(status.path("status").asText())) {
                String exit = status.path("exitstatus").asText("");
                if (exit.equals("OK") || exit.startsWith("WARNINGS")) {
                    return;
                }
                throw new ProxmoxApiException(String.format("Proxmox task %s failed: %s%s", taskType(upid), exit, taskLogTail(node, upid)));
            }
            if (System.currentTimeMillis() >= deadline) {
                throw new ProxmoxApiException(String.format("Proxmox task %s did not finish within %d seconds", taskType(upid), timeoutSeconds));
            }
            pause(TASK_POLL_MILLIS);
        }
    }

    protected void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProxmoxApiException("Interrupted while waiting for a Proxmox task");
        }
    }

    /** "UPID:node:pid:pstart:starttime:type:id:user:" */
    static String taskNode(String upid) {
        String[] parts = upid.split(":");
        if (parts.length < 6) {
            throw new ProxmoxApiException("Not a Proxmox task id: " + upid);
        }
        return parts[1];
    }

    static String taskType(String upid) {
        String[] parts = upid.split(":");
        return parts.length > 5 ? parts[5] : upid;
    }

    private String taskLogTail(String node, String upid) {
        try {
            JsonNode log = get("/nodes/" + node + "/tasks/" + upid + "/log?limit=500");
            if (log == null || !log.isArray() || log.size() == 0) {
                return "";
            }
            List<String> lines = new ArrayList<>();
            for (JsonNode line : log) {
                lines.add(line.path("t").asText());
            }
            return " (" + String.join(" | ", lines.subList(Math.max(0, lines.size() - 5), lines.size())) + ")";
        } catch (ProxmoxApiException e) {
            return "";
        }
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
