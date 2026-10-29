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
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
import org.apache.http.entity.AbstractHttpEntity;
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

    /** A POST whose form may repeat a name (the "command" list of the guest agent). */
    private JsonNode postPairs(String path, List<NameValuePair> pairs) {
        return execute(api -> {
            HttpPost request = new HttpPost(api + path);
            request.setEntity(new UrlEncodedFormEntity(pairs, StandardCharsets.UTF_8));
            return request;
        });
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
        return parseData(executeBody(requestFactory));
    }

    private String executeBody(Function<String, HttpRequestBase> requestFactory) {
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
                    throw new ProxmoxApiException(String.format("Proxmox API %s failed: HTTP %d %s%s", lastTarget, status,
                            response.getStatusLine().getReasonPhrase(), errorDetail(body)), status, null);
                }
                return body;
            } catch (IOException e) {
                lastFailure = e;
            }
        }
        throw new ProxmoxApiException(String.format("Unable to reach the Proxmox API for %s on any of %s: %s", lastTarget,
                baseUrls, lastFailure == null ? "no address" : lastFailure.getMessage()), lastFailure);
    }

    /** What Proxmox says about the rejected parameters of an error answer ({"errors":{"delete":"..."}}), as " (delete: ...)", or nothing. */
    static String errorDetail(String body) {
        if (StringUtils.isBlank(body)) {
            return "";
        }
        try {
            JsonNode errors = MAPPER.readTree(body).path("errors");
            if (!errors.isObject() || errors.size() == 0) {
                return "";
            }
            List<String> parts = new ArrayList<>();
            errors.fields().forEachRemaining(entry -> parts.add(entry.getKey() + ": " + entry.getValue().asText()));
            return " (" + String.join("; ", parts) + ")";
        } catch (IOException e) {
            return "";
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

    /** The whole answer of a GET, for the few calls that carry something next to "data". */
    private JsonNode getRoot(String path) {
        String body = executeBody(api -> new HttpGet(api + path));
        if (StringUtils.isBlank(body)) {
            return MAPPER.nullNode();
        }
        try {
            return MAPPER.readTree(body);
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
     * Adds a directory entry on a path that another entry has already mounted (the NFS entry of the secondary store), exposing one folder of
     * it as "images" content, so that a VM can be created from the template files in its numbered sub-folders
     * ({@code import-from=<id>:<folder number>/<file>}). Nothing is mounted by this entry, so any number of them share the one mount; the
     * entry is offline on a node where the path is not mounted ({@code is_mountpoint}).
     *
     * @param mountPath the path the NFS entry is mounted at
     * @param folder the folder inside it, e.g. "template/tmpl/1" for the templates of account 1
     */
    public void createTemplateDirStorage(String id, String mountPath, String folder) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("storage", id);
        form.put("type", "dir");
        form.put("path", mountPath);
        form.put("content", "images");
        form.put("content-dirs", "images=" + folder);
        form.put("is_mountpoint", "yes");
        form.put("shared", "1");
        // the share is read-only: Proxmox must not try to create the folders of its content types (or the base path) in it
        form.put("create-subdirs", "0");
        form.put("create-base-path", "0");
        post("/storage", form);
    }

    /**
     * Adds the NFS entry of a secondary store: the only entry that mounts it. The store is read-only, so Proxmox is told not to create the
     * folders of the content type in it ({@code create-subdirs 0}); without that, adding the entry fails with "mkdir ... Read-only file system".
     */
    /**
     * Creates the entry that exposes one ISO folder of the secondary store's mount as {@code <id>:iso/<file>}: read-only like the mount, so Proxmox must
     * not create folders in it, and shared because the same file is seen by every node (a VM with the ISO can then move between nodes).
     */
    /**
     * Adds a plain directory entry for ISO files that live on each node's own disk: not shared, and Proxmox itself makes the directory and its
     * sub-folders ({@code template/iso}) on a node when it activates the entry there (lab P1), so nothing has to be created by hand.
     */
    public void createPatchStorage(String id, String path) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("storage", id);
        form.put("type", "dir");
        form.put("path", path);
        form.put("content", "iso");
        form.put("shared", "0");
        post("/storage", form);
    }

    /** A file of a storage entry as listed by the content API. */
    public static class ContentItem {
        private final String volid;
        private final long size;

        public ContentItem(String volid, long size) {
            this.volid = volid;
            this.size = size;
        }

        public String getVolid() {
            return volid;
        }

        public long getSize() {
            return size;
        }
    }

    /** The files of one content type ("iso") that a storage entry has on a node. */
    public List<ContentItem> listContent(String node, String storage, String content) {
        List<ContentItem> items = new ArrayList<>();
        JsonNode data = get("/nodes/" + node + "/storage/" + storage + "/content?content=" + content);
        if (data != null && data.isArray()) {
            for (JsonNode entry : data) {
                items.add(new ContentItem(entry.path("volid").asText(), entry.path("size").asLong(0)));
            }
        }
        return items;
    }

    /**
     * A multipart/form-data body for the storage upload API: the field "content" and one file, followed by zero bytes up to a multiple of
     * {@code blockBytes} (a CD shows only whole 2048-byte sectors, lab P3). It is written from the file, never held in memory, and can be
     * written again when the request has to be sent to another address.
     */
    static class UploadEntity extends AbstractHttpEntity {
        private final byte[] head;
        private final byte[] tail;
        private final Path file;
        private final long fileSize;
        private final long paddedSize;

        UploadEntity(String content, String filename, Path file, int blockBytes) throws IOException {
            String boundary = "----cloudstack" + java.util.UUID.randomUUID().toString().replace("-", "");
            this.head = ("--" + boundary + "\r\nContent-Disposition: form-data; name=\"content\"\r\n\r\n" + content + "\r\n--" + boundary
                    + "\r\nContent-Disposition: form-data; name=\"filename\"; filename=\"" + filename + "\"\r\n"
                    + "Content-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8);
            this.tail = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
            this.file = file;
            this.fileSize = Files.size(file);
            this.paddedSize = blockBytes <= 1 ? fileSize : (fileSize + blockBytes - 1) / blockBytes * blockBytes;
            setContentType("multipart/form-data; boundary=" + boundary);
        }

        long paddedSize() {
            return paddedSize;
        }

        @Override
        public boolean isRepeatable() {
            return true;
        }

        @Override
        public long getContentLength() {
            return head.length + paddedSize + tail.length;
        }

        /** Only for small bodies (tests); a request is sent with {@link #writeTo}. */
        @Override
        public InputStream getContent() throws IOException {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            writeTo(out);
            return new java.io.ByteArrayInputStream(out.toByteArray());
        }

        @Override
        public void writeTo(OutputStream out) throws IOException {
            out.write(head);
            Files.copy(file, out);
            byte[] zeros = new byte[4096];
            for (long left = paddedSize - fileSize; left > 0; left -= zeros.length) {
                out.write(zeros, 0, (int) Math.min(zeros.length, left));
            }
            out.write(tail);
            out.flush();
        }

        @Override
        public boolean isStreaming() {
            return false;
        }
    }

    /**
     * Uploads a file as ISO content of a storage entry on a node, padded with zeros to a multiple of {@code blockBytes}; returns the task id
     * (the node copies the upload to its place in a task).
     */
    public String uploadIso(String node, String storage, String filename, Path file, int blockBytes) {
        UploadEntity entity;
        try {
            entity = new UploadEntity("iso", filename, file, blockBytes);
        } catch (IOException e) {
            throw new ProxmoxApiException("Unable to read " + file + " for the upload: " + e.getMessage(), e);
        }
        return taskId(execute(api -> {
            HttpPost request = new HttpPost(api + "/nodes/" + node + "/storage/" + storage + "/upload");
            request.setEntity(entity);
            return request;
        }));
    }

    public void createIsoDirStorage(String id, String mountPath, String folder) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("storage", id);
        form.put("type", "dir");
        form.put("path", mountPath);
        form.put("content", "iso");
        form.put("content-dirs", "iso=" + folder);
        form.put("is_mountpoint", "yes");
        form.put("shared", "1");
        form.put("create-subdirs", "0");
        form.put("create-base-path", "0");
        post("/storage", form);
    }

    public void createSecondaryNfsStorage(String id, String server, String export, String mountPath, String options) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("storage", id);
        form.put("type", "nfs");
        form.put("server", server);
        form.put("export", export);
        form.put("content", "import");
        form.put("path", mountPath);
        form.put("create-subdirs", "0");
        if (StringUtils.isNotBlank(options)) {
            form.put("options", options);
        }
        post("/storage", form);
    }

    /** A console session of a VM: valid for seconds and for one connection (see docs/design-phase4-console.md). */
    public static class ConsoleSession {
        private final int port;
        private final String ticket;
        private final String password;
        private final String user;

        public ConsoleSession(int port, String ticket, String password, String user) {
            this.port = port;
            this.ticket = ticket;
            this.password = password;
            this.user = user;
        }

        /** The port the node's console proxy listens on (on the node itself). */
        public int getPort() {
            return port;
        }

        /** The credential of the websocket request. */
        public String getTicket() {
            return ticket;
        }

        /** The password of the VM's VNC server, for the viewer; null when Proxmox did not return one. */
        public String getPassword() {
            return password;
        }

        /** The user the ticket is bound to: the full "user@realm!token" for an API token. The websocket must use the same one. */
        public String getUser() {
            return user;
        }
    }

    /**
     * Opens a console session for a running VM, for a websocket (the node waits for one connection for a few seconds). The call has to go to
     * the node that holds the VM.
     */
    public ConsoleSession openConsole(String node, long vmid) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("websocket", "1");
        JsonNode data = post("/nodes/" + node + "/qemu/" + vmid + "/vncproxy", form);
        if (data == null || !data.hasNonNull("port") || !data.hasNonNull("ticket")) {
            throw new ProxmoxApiException("Proxmox did not return a console port and ticket for VM " + vmid);
        }
        return new ConsoleSession(data.path("port").asInt(), data.path("ticket").asText(), data.path("password").asText(null), data.path("user").asText(null));
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

    /** The configuration of a VM as Proxmox reports it: option name to value (for example "scsi0" to the disk option). */
    public Map<String, String> getVmConfig(String node, long vmid) {
        Map<String, String> config = new LinkedHashMap<>();
        JsonNode data = get("/nodes/" + node + "/qemu/" + vmid + "/config");
        if (data != null && data.isObject()) {
            data.fields().forEachRemaining(field -> config.put(field.getKey(), field.getValue().asText()));
        }
        return config;
    }

    /**
     * The configuration of a VM as it is in the running VM ({@code current=1}), without changes that are still pending (a drive added to a running
     * VM stays pending until it is restarted).
     */
    public Map<String, String> getRunningVmConfig(String node, long vmid) {
        Map<String, String> config = new LinkedHashMap<>();
        JsonNode data = get("/nodes/" + node + "/qemu/" + vmid + "/config?current=1");
        if (data != null && data.isObject()) {
            data.fields().forEachRemaining(field -> config.put(field.getKey(), field.getValue().asText()));
        }
        return config;
    }

    /** Changes the configuration of a VM (applied at once while it is stopped). */
    public void updateVmConfig(String node, long vmid, Map<String, String> params) {
        put("/nodes/" + node + "/qemu/" + vmid + "/config", params);
    }

    /**
     * Grows a disk to {@code size} (for example "20G"; Proxmox never shrinks a disk). Returns the task id: a refused change (a shrink) shows
     * up as a failed task, not as an error of the call.
     */
    public String resizeDisk(String node, long vmid, String disk, String size) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("disk", disk);
        form.put("size", size);
        return taskId(put("/nodes/" + node + "/qemu/" + vmid + "/resize", form));
    }

    /** What Proxmox says about a disk image of a storage entry. */
    public static class ImageInfo {
        private final long size;
        private final long used;
        private final String format;
        private final String path;

        public ImageInfo(long size, long used, String format, String path) {
            this.size = size;
            this.used = used;
            this.format = format;
            this.path = path;
        }

        public long getSize() {
            return size;
        }

        public long getUsed() {
            return used;
        }

        public String getFormat() {
            return format;
        }

        public String getPath() {
            return path;
        }
    }

    /** The attributes of a disk image ({@code volid} is "&lt;storage entry&gt;:&lt;path&gt;"), or null when the entry has no such image. */
    public ImageInfo getImageInfo(String node, String volid) {
        try {
            JsonNode data = get("/nodes/" + node + "/storage/" + storageOf(volid) + "/content/" + encodeVolume(volid));
            if (data == null || !data.isObject()) {
                return null;
            }
            return new ImageInfo(data.path("size").asLong(0), data.path("used").asLong(0), data.path("format").asText(null), data.path("path").asText(null));
        } catch (ProxmoxApiException e) {
            // a volume that does not exist is answered with an error; a refusal (401, 403) is not "no image"
            if (e.getStatusCode() == 500 || e.getStatusCode() == 404) {
                return null;
            }
            throw e;
        }
    }

    /**
     * Creates a disk image of {@code sizeGib} GiB in a storage entry for the owner {@code vmid}, named {@code filename} ("vm-&lt;vmid&gt;-&lt;anything&gt;.qcow2").
     * The owner needs no VM. Returns the volume id.
     */
    public String allocateImage(String node, String storage, long vmid, String filename, long sizeGib) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("vmid", Long.toString(vmid));
        form.put("filename", filename);
        form.put("size", sizeGib + "G");
        form.put("format", "qcow2");
        JsonNode data = post("/nodes/" + node + "/storage/" + storage + "/content", form);
        if (data == null || !data.isTextual() || data.asText().isEmpty()) {
            throw new ProxmoxApiException("Proxmox did not return the volume id of the new disk image");
        }
        return data.asText();
    }

    /** Deletes a disk image; returns the task id. Proxmox does not check whether a VM still refers to it. */
    public String deleteImage(String node, String volid) {
        return taskId(delete("/nodes/" + node + "/storage/" + storageOf(volid) + "/content/" + encodeVolume(volid)));
    }

    private static String storageOf(String volid) {
        return StringUtils.substringBefore(volid, ":");
    }

    /**
     * A task id as a URL path segment. It is used as it is (":" "@" "!" are fine) except for characters a URI does not allow: the task of a disk
     * handed to another VM is "...:qmmove:106-unused0&gt;900000002-unused0:...", and "&gt;" made the request fail (lab T4).
     */
    static String pathSafe(String upid) {
        StringBuilder safe = new StringBuilder();
        for (byte b : upid.getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
            char c = (char) (b & 0xff);
            if (b >= 0 && (Character.isLetterOrDigit(c) || ":@!._-~$&'()*+,;=".indexOf(c) >= 0)) {
                safe.append(c);
            } else {
                safe.append(String.format("%%%02X", b & 0xff));
            }
        }
        return safe.toString();
    }

    private static String encodeVolume(String volid) {
        try {
            return java.net.URLEncoder.encode(volid, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            throw new ProxmoxApiException("UTF-8 is not available", e);
        }
    }

    public String startVm(String node, long vmid) {
        return taskId(post("/nodes/" + node + "/qemu/" + vmid + "/status/start", null));
    }

    /** Hard stop, like pulling the power. */
    public String stopVm(String node, long vmid) {
        String path = "/nodes/" + node + "/qemu/" + vmid + "/status/stop";
        // A graceful shutdown that is still running (started in Proxmox, not by CloudStack, which serialises the jobs of a VM) holds the VM's lock, and a
        // stop then fails with "can't lock file ... got timeout" (lab 2026-10-21); "overrule-shutdown" aborts that shutdown first.
        Map<String, String> form = new LinkedHashMap<>();
        form.put("overrule-shutdown", "1");
        try {
            return taskId(post(path, form));
        } catch (ProxmoxApiException e) {
            if (e.getStatusCode() != 400) {
                throw e;
            }
            // Proxmox refuses the option for a VM under HA ("Not applicable for HA resources"; the stop is then only a request to the HA manager)
            return taskId(post(path, null));
        }
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

    /** Moves a running VM to another node with its memory (the disks are on shared storage). For an HA VM the call only queues the move. */
    public String migrateRunningVm(String node, long vmid, String target) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("target", target);
        form.put("online", "1");
        return taskId(post("/nodes/" + node + "/qemu/" + vmid + "/migrate", form));
    }

    /**
     * Hands a disk of one VM to another VM, which renames the volume to the target's id (lab M5: the file is renamed in place, not copied, and
     * the source may be running when the disk is an "unusedN" entry). Both VMs must be on {@code node}. Returns the task id.
     */
    public String reassignDisk(String node, long vmid, String disk, long targetVmid, String targetDisk) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("disk", disk);
        form.put("target-vmid", Long.toString(targetVmid));
        form.put("target-disk", targetDisk);
        return taskId(post("/nodes/" + node + "/qemu/" + vmid + "/move_disk", form));
    }

    /**
     * Copies a disk of a VM, running or stopped, to another storage and removes the source (lab M1). The new volume is named
     * "vm-&lt;vmid&gt;-disk-&lt;first free number&gt;" on the target, so the caller reads the configuration afterwards. Returns the task id.
     */
    public String moveDisk(String node, long vmid, String disk, String storage) {
        return moveDisk(node, vmid, disk, storage, true);
    }

    /** As above; with {@code deleteSource} false the old volume stays and the VM lists it as "unusedN". */
    public String moveDisk(String node, long vmid, String disk, String storage, boolean deleteSource) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("disk", disk);
        form.put("storage", storage);
        form.put("format", "qcow2");
        form.put("delete", deleteSource ? "1" : "0");
        return taskId(post("/nodes/" + node + "/qemu/" + vmid + "/move_disk", form));
    }

    /** The state of a VM on one node ("running", "stopped" ...), or null when that node does not have the VM (it is elsewhere or gone). */
    public String getVmStatus(String node, long vmid) {
        try {
            JsonNode data = get("/nodes/" + node + "/qemu/" + vmid + "/status/current");
            return data == null || data.path("status").isMissingNode() ? null : data.path("status").asText();
        } catch (ProxmoxApiException e) {
            if (e.getStatusCode() >= 400) {
                return null;
            }
            throw e;
        }
    }

    /** What Proxmox says about moving a VM to a node: the reasons it cannot be done (none when it can). */
    public List<String> getMigrationProblems(String node, long vmid, String target) {
        List<String> problems = new ArrayList<>();
        JsonNode data = get("/nodes/" + node + "/qemu/" + vmid + "/migrate?target=" + target);
        boolean allowed = false;
        for (JsonNode entry : data.path("allowed_nodes")) {
            allowed |= target.equals(entry.isTextual() ? entry.asText() : entry.path("node").asText());
        }
        // every node is listed in not_allowed_nodes, with an empty entry when nothing is wrong, so only a reason counts
        JsonNode reasons = data.path("not_allowed_nodes").path(target);
        List<String> why = new ArrayList<>();
        reasons.fields().forEachRemaining(field -> {
            if (field.getValue().size() > 0) {
                why.add(field.getKey() + " " + field.getValue());
            }
        });
        if (!why.isEmpty()) {
            problems.add("Proxmox does not allow node " + target + " as a target: " + String.join(", ", why));
        } else if (!allowed) {
            problems.add("Proxmox does not list node " + target + " as a possible target");
        }
        if (data.path("local_resources").size() > 0) {
            problems.add("the VM uses resources that exist only on its node: " + data.path("local_resources"));
        }
        for (JsonNode disk : data.path("local_disks")) {
            if (disk.path("shared").asInt(0) == 0 && disk.path("is_unused").asInt(0) == 0) {
                problems.add("the disk " + disk.path("volid").asText() + " is not on shared storage");
            }
        }
        return problems;
    }

    /** A bridge of a node and whether a VLAN tag can be used on it. */
    public static class NodeBridge {
        private final String name;
        private final boolean active;
        private final boolean vlanAware;

        public NodeBridge(String name, boolean active, boolean vlanAware) {
            this.name = name;
            this.active = active;
            this.vlanAware = vlanAware;
        }

        public String getName() {
            return name;
        }

        public boolean isActive() {
            return active;
        }

        public boolean isVlanAware() {
            return vlanAware;
        }
    }

    /** The bridges (Linux and Open vSwitch) that the node has. */
    public List<NodeBridge> listBridges(String node) {
        List<NodeBridge> bridges = new ArrayList<>();
        JsonNode data = get("/nodes/" + node + "/network");
        if (data != null && data.isArray()) {
            for (JsonNode entry : data) {
                String type = entry.path("type").asText();
                if ("bridge".equals(type) || "OVSBridge".equals(type)) {
                    bridges.add(new NodeBridge(entry.path("iface").asText(), entry.path("active").asInt(0) == 1,
                            "OVSBridge".equals(type) || entry.path("bridge_vlan_aware").asInt(0) == 1));
                }
            }
        }
        return bridges;
    }

    /** A network interface of a node as listed by the network API; {@code cidr} is null when it has no address. */
    public static class NetworkInterface {
        private final String name;
        private final String type;
        private final String cidr;

        public NetworkInterface(String name, String type, String cidr) {
            this.name = name;
            this.type = type;
            this.cidr = cidr;
        }

        public String getName() {
            return name;
        }

        public String getType() {
            return type;
        }

        public String getCidr() {
            return cidr;
        }

        public boolean isBridge() {
            return "bridge".equals(type) || "OVSBridge".equals(type);
        }
    }

    /** The network configuration of a node and whether it has changes that are saved but not applied yet. */
    public static class NetworkConfig {
        private final List<NetworkInterface> interfaces;
        private final boolean pendingChanges;

        public NetworkConfig(List<NetworkInterface> interfaces, boolean pendingChanges) {
            this.interfaces = interfaces;
            this.pendingChanges = pendingChanges;
        }

        public List<NetworkInterface> getInterfaces() {
            return interfaces;
        }

        public boolean hasPendingChanges() {
            return pendingChanges;
        }

        public NetworkInterface find(String name) {
            return interfaces.stream().filter(i -> i.getName().equals(name)).findFirst().orElse(null);
        }
    }

    /** The interfaces of a node, with the "changes" Proxmox reports next to them when /etc/network/interfaces.new exists. */
    public NetworkConfig getNetworkConfig(String node) {
        JsonNode root = getRoot("/nodes/" + node + "/network");
        List<NetworkInterface> interfaces = new ArrayList<>();
        JsonNode data = root.path("data");
        if (data.isArray()) {
            for (JsonNode entry : data) {
                interfaces.add(new NetworkInterface(entry.path("iface").asText(), entry.path("type").asText(), entry.path("cidr").asText(null)));
            }
        }
        return new NetworkConfig(interfaces, !root.path("changes").asText("").isEmpty());
    }

    /** Saves a Linux bridge without ports in the node's pending network configuration; {@link #applyNetworkConfig} makes it live. */
    public void createBridge(String node, String name, String cidr) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("iface", name);
        form.put("type", "bridge");
        form.put("cidr", cidr);
        form.put("autostart", "1");
        post("/nodes/" + node + "/network", form);
    }

    /** Applies the node's pending network configuration (Proxmox reloads it as a task) and waits for the task. */
    public void applyNetworkConfig(String node, int timeoutSeconds) {
        JsonNode upid = put("/nodes/" + node + "/network", null);
        if (upid != null && upid.isTextual()) {
            waitForTask(upid.asText(), timeoutSeconds);
        }
    }

    /** Throws away the node's pending network changes. */
    public void revertNetworkConfig(String node) {
        delete("/nodes/" + node + "/network");
    }

    // ---- the QEMU guest agent of a VM, through the API ----

    private static String agentPath(String node, long vmid, String call) {
        return "/nodes/" + node + "/qemu/" + vmid + "/agent/" + call;
    }

    /**
     * Whether the guest agent of the VM answers. Proxmox answers HTTP 500 ("QEMU guest agent is not running") while the guest is still
     * starting, which is "not yet", not an error; anything else (a refused privilege, a connection failure) is thrown.
     */
    public boolean agentPing(String node, long vmid) {
        try {
            post(agentPath(node, vmid, "ping"), null);
            return true;
        } catch (ProxmoxApiException e) {
            if (e.getStatusCode() == 500) {
                return false;
            }
            throw e;
        }
    }

    /** Writes a text file in the guest (Proxmox sends it encoded; the guest agent takes at most 60 KiB). */
    public void agentFileWrite(String node, long vmid, String file, String content) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("file", file);
        form.put("content", content);
        post(agentPath(node, vmid, "file-write"), form);
    }

    /** Starts a command in the guest; {@code inputData} (at most 64 KiB) is its standard input. Returns the pid for {@link #agentExecStatus}. */
    public long agentExec(String node, long vmid, List<String> command, String inputData) {
        List<NameValuePair> pairs = new ArrayList<>();
        for (String part : command) {
            pairs.add(new BasicNameValuePair("command", part));
        }
        if (inputData != null) {
            pairs.add(new BasicNameValuePair("input-data", inputData));
        }
        JsonNode data = postPairs(agentPath(node, vmid, "exec"), pairs);
        if (data == null || !data.has("pid")) {
            throw new ProxmoxApiException("Proxmox did not return a process id for the command in VM " + vmid);
        }
        return data.path("pid").asLong();
    }

    /** What a command started in the guest has done so far. */
    public static class AgentExecStatus {
        private final boolean exited;
        private final int exitCode;
        private final String out;
        private final String err;

        public AgentExecStatus(boolean exited, int exitCode, String out, String err) {
            this.exited = exited;
            this.exitCode = exitCode;
            this.out = out;
            this.err = err;
        }

        public boolean isExited() {
            return exited;
        }

        public int getExitCode() {
            return exitCode;
        }

        public String getOut() {
            return out;
        }

        public String getErr() {
            return err;
        }
    }

    public AgentExecStatus agentExecStatus(String node, long vmid, long pid) {
        JsonNode data = get(agentPath(node, vmid, "exec-status") + "?pid=" + pid);
        return new AgentExecStatus(data.path("exited").asInt(0) == 1, data.path("exitcode").asInt(-1), data.path("out-data").asText(""),
                data.path("err-data").asText(""));
    }

    /** A VM that Proxmox HA looks after. */
    public static class HaResource {
        private final String sid;
        private final String state;
        private final String comment;

        public HaResource(String sid, String state, String comment) {
            this.sid = sid;
            this.state = state;
            this.comment = comment;
        }

        public String getSid() {
            return sid;
        }

        public String getState() {
            return state;
        }

        public String getComment() {
            return comment;
        }
    }

    /** The HA resource of a VM, or null when Proxmox HA does not look after it. */
    public HaResource findHaResource(long vmid) {
        JsonNode data = get("/cluster/ha/resources");
        if (data != null && data.isArray()) {
            for (JsonNode entry : data) {
                if (("vm:" + vmid).equals(entry.path("sid").asText())) {
                    return new HaResource(entry.path("sid").asText(), entry.path("state").asText(null), entry.path("comment").asText(null));
                }
            }
        }
        return null;
    }

    /** Puts a VM under Proxmox HA (state started, Proxmox's default restart and relocate limits, no group). */
    public void createHaResource(long vmid, String comment) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("sid", "vm:" + vmid);
        form.put("state", "started");
        form.put("comment", comment);
        post("/cluster/ha/resources", form);
    }

    /** Takes a VM out of Proxmox HA; the VM itself is left as it is. */
    public void deleteHaResource(long vmid) {
        delete("/cluster/ha/resources/vm:" + vmid);
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
            JsonNode status = get("/nodes/" + node + "/tasks/" + pathSafe(upid) + "/status");
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
            JsonNode log = get("/nodes/" + node + "/tasks/" + pathSafe(upid) + "/log?limit=500");
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
